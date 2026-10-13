package nl.pinda.framework.modules.duel;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.economy.EconomyModule;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.itemlimits.ItemLimitModule;
import nl.pinda.framework.modules.spawn.SpawnModule;
import nl.pinda.framework.modules.staff.StaffModule;
import nl.pinda.framework.modules.utility.UtilityModule;
import nl.pinda.framework.player.PlayerManager;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * 1v1-gevechten: spelers dagen elkaar uit, gaan samen naar de arena, zetten geld en items in en
 * vechten tot de dood (zonder echt dood te gaan: niemand verliest iets behalve zijn inzet). De
 * winnaar krijgt alles. Daarna kan het nog een keer (rematch). Anderen kunnen komen kijken.
 *
 * <p>Buiten een gevecht is PvP uit (instelbaar): wie iemand slaat, krijgt een melding met een
 * knop om diegene uit te dagen.
 */
public final class DuelModule extends PindaModule {

    public static final String USE = "pinda.duel.use";
    public static final String SPECTATE = "pinda.duel.spectate";
    public static final String ADMIN = "pinda.duel.admin";
    /** Toch bouwen in de beschermde arena (zonder WorldGuard; met WorldGuard geldt de bypass van WorldGuard). */
    public static final String BUILD = "pinda.duel.build";
    private static final String FORCED_PVP_KEY = "duel-forced-pvp";

    /** Een openstaande uitdaging. */
    record Invite(UUID from, String fromName, UUID to, String toName, long created, BukkitTask expiry) {
    }

    private DuelStore store;
    private volatile DuelSettings settings;
    private Session session;
    /** Uitdagingen, per uitdager (elke speler heeft er hooguit één openstaan). */
    private final Map<UUID, Invite> invites = new HashMap<>();
    /** Wanneer iemand voor het laatst de melding kreeg dat PvP niet mag. */
    private final Map<UUID, Long> nudged = new HashMap<>();
    /** Teleports die wij zelf doen (die mogen altijd; /back onthoudt ze niet). */
    private final Map<UUID, Integer> moving = new HashMap<>();
    /** Wat spelers nog terug moeten krijgen (na uitloggen of een crash), tot ze weer online komen. */
    private final Map<UUID, DuelStore.Pending> recovery = new HashMap<>();
    /** Wie dood is als het gevecht stopt: waar hij moet respawnen en wat hij dan nog krijgt. */
    private final Map<UUID, Respawn> respawns = new HashMap<>();
    /** In welke wereld wij PvP tijdelijk aanzetten (de gamerule stond uit). */
    private String forcedPvp;
    private boolean stopping;
    /** Het beschermde gebied (met de hoogte van de wereld), of null. */
    private volatile Area guarded;
    /** Beschermt WorldGuard het gebied (de regio pinda_arena)? Dan doet {@link ArenaGuard} niets. */
    private volatile boolean viaWorldGuard;
    /** Een fout met WorldGuard maar één keer in het log. */
    private boolean worldGuardFailed;
    /** De eerste hoek van /setarena hoek1, per beheerder. */
    private final Map<UUID, Location> corners = new HashMap<>();

    record Respawn(Location location, List<ItemStack> items) {
    }

    public DuelModule(PindaFramework plugin) {
        super(plugin, "duel");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("duel", DuelStore.MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de 1v1-tabellen niet aanmaken", e);
        }
        stopping = false;
        store = new DuelStore(plugin);
        settings = DuelSettings.read(config());
        restorePvp();
        loadRecovery();
        listen(new DuelListener(plugin, this));
        listen(new ArenaGuard(plugin, this));
        refreshProtection();
        command(new DuelCommand(plugin, this));
        command(new SetArenaCommand(plugin, this));
    }

    @Override
    protected void onDisable() {
        stopping = true;
        if (session != null) {
            Session running = session;
            running.cancel("stop");
        }
        session = null;
        for (Invite invite : invites.values()) {
            invite.expiry().cancel();
        }
        invites.clear();
        nudged.clear();
        moving.clear();
        respawns.clear();
        recovery.clear();
        corners.clear();
        releasePvp();
        // De WorldGuard-regio blijft gewoon staan: de arena blijft beschermd.
        guarded = null;
        viaWorldGuard = false;
    }

    @Override
    protected void onReload() {
        settings = DuelSettings.read(config());
        refreshProtection();
    }

    // ============================================================ toegang

    PindaFramework plugin() {
        return plugin;
    }

    public DuelSettings settings() {
        return settings;
    }

    public DuelStore store() {
        return store;
    }

    EconomyService economy() {
        EconomyModule economy = plugin.modules().get(EconomyModule.class);
        return economy == null || !economy.isEnabled() ? null : economy.service();
    }

    Session session() {
        return session;
    }

    /** Doet deze speler mee aan het gevecht (of kijkt hij)? Voor andere modules, zoals /back. */
    public boolean involved(Player player) {
        Session current = session;
        return current != null && current.involves(player.getUniqueId());
    }

    /** Is dit een teleport van de 1v1 zelf (naar de arena of terug)? */
    public boolean ownTeleport(Player player) {
        return moving.containsKey(player.getUniqueId());
    }

    /** Een bericht in de taal van de speler. */
    void lang(Player player, String key, TagResolver... resolvers) {
        plugin.lang().send(player, key, resolvers);
    }

    Component component(Player player, String key, TagResolver... resolvers) {
        return plugin.lang().component(player, key, resolvers);
    }

    // ============================================================ arena

    /** Zet een plek van de arena (1, 2 of spectate) op deze locatie. */
    public void setArena(String which, Location location) {
        Spot.of(location).write(config().createSection("arena." + which));
        saveConfig();
        settings = DuelSettings.read(config());
        refreshProtection();
    }

    /** Haalt een plek van de arena weg (spawn1, spawn2, spectate of area). */
    public void clearArena(String which) {
        config().set("arena." + which, null);
        saveConfig();
        settings = DuelSettings.read(config());
        refreshProtection();
    }

    /** Zet het beschermde gebied (ook de grens tijdens een gevecht). */
    public void setArea(Area area) {
        area.write(config().createSection("arena.area"));
        saveConfig();
        settings = DuelSettings.read(config());
        refreshProtection();
    }

    // ============================================================ bescherming

    /** Het gebied dat het framework zelf beschermt: null als de bescherming uit staat of WorldGuard het doet. */
    Area guardedByFramework() {
        return viaWorldGuard ? null : guarded;
    }

    /** Het beschermde gebied (met of zonder WorldGuard), of null. */
    public Area guarded() {
        return guarded;
    }

    /** Beschermt WorldGuard de arena (de regio pinda_arena)? */
    public boolean viaWorldGuard() {
        return viaWorldGuard;
    }

    /** Staat WorldGuard op de server (en aan)? */
    public boolean worldGuardPresent() {
        return plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard");
    }

    boolean worldEditPresent() {
        return plugin.getServer().getPluginManager().isPluginEnabled("WorldEdit");
    }

    /** De hoogte van een wereld (laagste en hoogste blok), voor een gebied zonder eigen hoogte. */
    private static Area fullHeight(Area area, World world) {
        return area.withHeight(world.getMinHeight(), world.getMaxHeight() - 1);
    }

    /**
     * Rekent het beschermde gebied opnieuw uit en zet het in WorldGuard (als dat er is). Aanroepen
     * na elke wijziging aan de arena of de instellingen (hoofdthread).
     */
    void refreshProtection() {
        DuelSettings current = settings;
        Area area = null;
        World world = null;
        if (current.protect()) {
            Area base = current.area() != null ? current.area() : current.autoArea();
            world = base == null ? null : plugin.getServer().getWorld(base.world());
            if (world != null) {
                area = current.area() != null ? base : fullHeight(base, world);
            }
        }
        boolean worldGuard = false;
        if (worldGuardPresent()) {
            try {
                for (World each : plugin.getServer().getWorlds()) {
                    if (area == null || !each.equals(world)) {
                        WorldGuardHook.remove(each);
                    }
                }
                if (area != null) {
                    worldGuard = WorldGuardHook.sync(world, area);
                }
                World saved = world;
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        for (World each : plugin.getServer().getWorlds()) {
                            WorldGuardHook.save(each);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "1v1: kon de WorldGuard-regio niet opslaan"
                                + (saved == null ? "" : " (" + saved.getName() + ")"), e);
                    }
                });
                worldGuardFailed = false;
            } catch (Exception | LinkageError e) {
                worldGuard = false;
                if (!worldGuardFailed) {
                    worldGuardFailed = true;
                    plugin.getLogger().log(Level.WARNING, "1v1: WorldGuard gaf een fout; PindaFramework beschermt de arena nu zelf.", e);
                }
            }
        }
        guarded = area;
        viaWorldGuard = worldGuard;
    }

    /** /setarena gebied: de selectie met de WorldEdit-bijl. Null als er geen (hele) selectie is. */
    Area worldEditSelection(Player player) {
        try {
            return WorldEditSelection.of(player);
        } catch (Exception | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "1v1: kon de WorldEdit-selectie niet lezen", e);
            return null;
        }
    }

    /** Een selectie van maar een paar blokken hoog (bijv. alleen de vloer) wordt de hele hoogte van de wereld. */
    Area tallEnough(Area area, World world) {
        return area.sizeY() < 4 ? fullHeight(area, world) : area;
    }

    void corner(Player player, Location location) {
        corners.put(player.getUniqueId(), location.clone());
    }

    Location corner(Player player) {
        return corners.get(player.getUniqueId());
    }

    void clearCorner(Player player) {
        corners.remove(player.getUniqueId());
    }

    // ============================================================ uitdagen

    /** Waarom deze speler nu geen 1v1 kan doen (een taalsleutel), of null als het kan. */
    String blocker(Player player) {
        if (player.isDead()) {
            return "duel.cannot.dead";
        }
        StaffModule staff = plugin.modules().get(StaffModule.class);
        if (staff != null && staff.isEnabled() && staff.isVanished(player)) {
            return "duel.cannot.vanished";
        }
        UtilityModule utility = plugin.modules().get(UtilityModule.class);
        if (utility != null && utility.isEnabled() && utility.isGod(player)) {
            return "duel.cannot.god";
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            return "duel.cannot.spectator";
        }
        return null;
    }

    void challenge(Player from, Player to) {
        DuelSettings current = settings;
        if (!current.arenaReady()) {
            fail(from, from.hasPermission(ADMIN) ? "duel.no-arena-admin" : "duel.no-arena");
            return;
        }
        if (from.getUniqueId().equals(to.getUniqueId())) {
            fail(from, "duel.self");
            return;
        }
        if (session != null) {
            if (session.involves(from.getUniqueId())) {
                fail(from, session.isWatcher(from.getUniqueId()) ? "duel.busy-watching" : "duel.busy-self");
            } else {
                fail(from, "duel.arena-busy", watchButton(from));
            }
            return;
        }
        String reason = blocker(from);
        if (reason != null) {
            fail(from, reason);
            return;
        }
        boolean ignored = plugin.players().get(to).isIgnoring(from.getUniqueId()) && !from.hasPermission(PlayerManager.IGNORE_EXEMPT);
        if (ignored || !to.hasPermission(USE) || blocker(to) != null) {
            fail(from, "duel.unavailable", Text.p("player", to.getName()));
            return;
        }
        // Daagde de ander jou al uit? Dan is dit gewoon "ja".
        Invite theirs = invites.get(to.getUniqueId());
        if (theirs != null && theirs.to().equals(from.getUniqueId())) {
            accept(from, to.getName());
            return;
        }
        Invite existing = invites.get(from.getUniqueId());
        if (existing != null && existing.to().equals(to.getUniqueId())) {
            fail(from, "duel.invite.already", Text.p("player", to.getName()));
            return;
        }
        if (existing != null) {
            removeInvite(existing);
            Player old = plugin.getServer().getPlayer(existing.to());
            if (old != null) {
                lang(old, "duel.invite.withdrawn-target", Text.p("player", from.getName()));
            }
        }
        int seconds = current.inviteSeconds();
        UUID fromId = from.getUniqueId();
        Invite[] holder = new Invite[1];
        BukkitTask expiry = plugin.getServer().getScheduler().runTaskLater(plugin, () -> expire(holder[0]), seconds * 20L);
        Invite invite = new Invite(fromId, from.getName(), to.getUniqueId(), to.getName(), System.currentTimeMillis(), expiry);
        holder[0] = invite;
        invites.put(fromId, invite);

        TagResolver withdraw = Placeholder.styling("withdraw", ClickEvent.runCommand("/duel intrekken"),
                HoverEvent.showText(component(from, "duel.invite.hover-withdraw")));
        lang(from, "duel.invite.sent", Text.p("player", to.getName()), Text.p("seconds", seconds), withdraw);
        plugin.theme().play(from, "click");
        TagResolver accept = Placeholder.styling("accept", ClickEvent.runCommand("/duel accepteer " + from.getName()),
                HoverEvent.showText(component(to, "duel.invite.hover-accept")));
        TagResolver deny = Placeholder.styling("deny", ClickEvent.runCommand("/duel weiger " + from.getName()),
                HoverEvent.showText(component(to, "duel.invite.hover-deny")));
        lang(to, "duel.invite.received", Text.p("player", from.getName()), Text.p("seconds", seconds), accept, deny);
        plugin.theme().play(to, "request");
    }

    void accept(Player to, String fromName) {
        Invite invite = incoming(to, fromName);
        if (invite == null) {
            noInvite(to, fromName);
            return;
        }
        removeInvite(invite);
        Player from = plugin.getServer().getPlayer(invite.from());
        if (from == null) {
            fail(to, "duel.invite.gone", Text.p("player", invite.fromName()));
            return;
        }
        if (!settings.arenaReady()) {
            fail(to, "duel.no-arena");
            return;
        }
        if (session != null) {
            fail(to, "duel.arena-busy", watchButton(to));
            lang(from, "duel.invite.busy-sender", Text.p("player", to.getName()));
            return;
        }
        String reason = blocker(to);
        if (reason != null) {
            fail(to, reason);
            return;
        }
        if (blocker(from) != null) {
            fail(to, "duel.unavailable", Text.p("player", from.getName()));
            return;
        }
        // Alle andere uitdagingen van en aan deze twee vervallen.
        for (Invite other : List.copyOf(invites.values())) {
            if (involvesEither(other, from, to)) {
                removeInvite(other);
            }
        }
        lang(from, "duel.invite.accepted-sender", Text.p("player", to.getName()));
        lang(to, "duel.invite.accepted-target", Text.p("player", from.getName()));
        Session created = new Session(this, from, to);
        session = created;
        if (!created.start()) {
            session = null;
            fail(to, "duel.no-arena");
            fail(from, "duel.no-arena");
        }
    }

    private static boolean involvesEither(Invite invite, Player a, Player b) {
        UUID first = a.getUniqueId();
        UUID second = b.getUniqueId();
        return invite.from().equals(first) || invite.to().equals(first) || invite.from().equals(second) || invite.to().equals(second);
    }

    void deny(Player to, String fromName) {
        Invite invite = incoming(to, fromName);
        if (invite == null) {
            noInvite(to, fromName);
            return;
        }
        removeInvite(invite);
        lang(to, "duel.invite.denied-target", Text.p("player", invite.fromName()));
        Player from = plugin.getServer().getPlayer(invite.from());
        if (from != null) {
            lang(from, "duel.invite.denied-sender", Text.p("player", to.getName()));
            plugin.theme().play(from, "error");
        }
    }

    void withdraw(Player from) {
        Invite invite = invites.get(from.getUniqueId());
        if (invite == null) {
            fail(from, "duel.invite.no-outgoing");
            return;
        }
        removeInvite(invite);
        lang(from, "duel.invite.withdrawn-sender", Text.p("player", invite.toName()));
        Player to = plugin.getServer().getPlayer(invite.to());
        if (to != null) {
            lang(to, "duel.invite.withdrawn-target", Text.p("player", from.getName()));
        }
    }

    /** Namen van wie deze speler uitdaagden (voor tab-aanvulling). */
    List<String> incomingNames(Player to) {
        List<String> names = new ArrayList<>();
        for (Invite invite : invites.values()) {
            if (invite.to().equals(to.getUniqueId())) {
                names.add(invite.fromName());
            }
        }
        return names;
    }

    private Invite incoming(Player to, String fromName) {
        Invite newest = null;
        for (Invite invite : invites.values()) {
            if (!invite.to().equals(to.getUniqueId())) {
                continue;
            }
            if (fromName != null) {
                if (invite.fromName().equalsIgnoreCase(fromName)) {
                    return invite;
                }
            } else if (newest == null || invite.created() > newest.created()) {
                newest = invite;
            }
        }
        return newest;
    }

    private void noInvite(Player to, String fromName) {
        if (fromName == null) {
            fail(to, "duel.invite.none");
        } else {
            fail(to, "duel.invite.none-from", Text.p("player", fromName));
        }
    }

    private void expire(Invite invite) {
        if (invite == null || !invites.remove(invite.from(), invite)) {
            return;
        }
        Player from = plugin.getServer().getPlayer(invite.from());
        Player to = plugin.getServer().getPlayer(invite.to());
        if (from != null) {
            lang(from, "duel.invite.expired-sender", Text.p("player", invite.toName()));
        }
        if (to != null) {
            lang(to, "duel.invite.expired-target", Text.p("player", invite.fromName()));
        }
    }

    private void removeInvite(Invite invite) {
        invites.remove(invite.from(), invite);
        invite.expiry().cancel();
    }

    /** Een speler logt uit: zijn uitdagingen vervallen. */
    void dropInvites(Player player) {
        UUID uuid = player.getUniqueId();
        Iterator<Invite> iterator = invites.values().iterator();
        while (iterator.hasNext()) {
            Invite invite = iterator.next();
            if (invite.from().equals(uuid) || invite.to().equals(uuid)) {
                invite.expiry().cancel();
                iterator.remove();
                Player other = plugin.getServer().getPlayer(invite.from().equals(uuid) ? invite.to() : invite.from());
                if (other != null) {
                    lang(other, "duel.invite.gone", Text.p("player", player.getName()));
                }
            }
        }
        nudged.remove(uuid);
    }

    private void fail(Player player, String key, TagResolver... resolvers) {
        lang(player, key, resolvers);
        plugin.theme().play(player, "error");
    }

    TagResolver watchButton(Player player) {
        return Placeholder.styling("watch", ClickEvent.runCommand("/duel kijken"),
                HoverEvent.showText(component(player, "duel.broadcast.watch-hover")));
    }

    // ============================================================ PvP buiten een gevecht

    /** De melding als iemand een speler slaat buiten een gevecht (niet te vaak). */
    void nudge(Player attacker, Player victim) {
        DuelSettings current = settings;
        if (!current.blockPvp() || current.pvpMessage().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = nudged.get(attacker.getUniqueId());
        if (last != null && now - last < current.pvpCooldownSeconds() * 1000L) {
            return;
        }
        nudged.put(attacker.getUniqueId(), now);
        TagResolver challenge = attacker.hasPermission(USE)
                ? Placeholder.styling("challenge", ClickEvent.runCommand("/duel " + victim.getName()),
                        HoverEvent.showText(component(attacker, "duel.pvp-hover", Text.p("player", victim.getName()))))
                : Placeholder.styling("challenge");
        attacker.sendMessage(plugin.lang().parse(current.pvpMessage(), Text.p("player", victim.getName()), challenge));
        plugin.theme().play(attacker, "error");
    }

    /**
     * Mag de een de ander raken? Ja als ze tegen elkaar vechten. Nee als een van beiden met een
     * gevecht bezig is (of kijkt), of als PvP buiten gevechten uit staat.
     *
     * @return null = mag, anders: "silent" (stil tegenhouden) of "nudge" (tegenhouden met melding)
     */
    String pvp(Player attacker, Player victim) {
        Session current = session;
        if (current != null) {
            if (current.fighting(attacker, victim)) {
                return null;
            }
            if (current.involves(attacker.getUniqueId()) || current.involves(victim.getUniqueId())) {
                return "silent";
            }
        }
        if (settings.blockPvp()) {
            return "nudge";
        }
        if (forcedPvp != null && forcedPvp.equals(victim.getWorld().getName())) {
            return "silent"; // wij zetten PvP hier alleen aan voor het gevecht
        }
        return null;
    }

    /** Staat PvP uit in de wereld van de arena? Dan zetten wij hem tijdelijk aan (alleen het gevecht telt). */
    void forcePvp(Location arena) {
        if (arena == null || arena.getWorld() == null || forcedPvp != null) {
            return;
        }
        World world = arena.getWorld();
        Boolean pvp = world.getGameRuleValue(GameRules.PVP);
        if (Boolean.FALSE.equals(pvp)) {
            world.setGameRule(GameRules.PVP, true);
            forcedPvp = world.getName();
            plugin.serverData().set(FORCED_PVP_KEY, forcedPvp);
        }
    }

    /** PvP weer uit, als wij hem aanzetten. */
    void releasePvp() {
        if (forcedPvp == null) {
            return;
        }
        World world = plugin.getServer().getWorld(forcedPvp);
        if (world != null) {
            world.setGameRule(GameRules.PVP, false);
        }
        forcedPvp = null;
        plugin.serverData().remove(FORCED_PVP_KEY);
    }

    /** Na een crash midden in een gevecht: PvP weer uit zoals het was. */
    private void restorePvp() {
        String world = plugin.serverData().get(FORCED_PVP_KEY);
        if (world == null || world.isBlank()) {
            return;
        }
        forcedPvp = world;
        releasePvp();
    }

    // ============================================================ het gevecht

    /** Het gevecht is klaar en iedereen is terug. */
    void ended(Session ended) {
        if (session == ended) {
            session = null;
        }
        releasePvp();
    }

    void watch(Player player) {
        Session current = session;
        if (current == null || current.phase == Session.Phase.ENDED) {
            fail(player, "duel.watch.none");
            return;
        }
        if (current.isDuelist(player.getUniqueId())) {
            fail(player, "duel.watch.duelist");
            return;
        }
        if (current.isWatcher(player.getUniqueId())) {
            fail(player, "duel.watch.already");
            return;
        }
        Spot spot = settings.spectate();
        Location location = spot == null ? null : spot.location(plugin.getServer());
        if (location == null) {
            fail(player, player.hasPermission(ADMIN) ? "duel.watch.no-spot-admin" : "duel.watch.no-spot");
            return;
        }
        if (player.isDead()) {
            fail(player, "duel.cannot.dead");
            return;
        }
        current.watch(player, location);
    }

    void leave(Player player) {
        Session current = session;
        if (current == null || !current.isWatcher(player.getUniqueId())) {
            fail(player, "duel.watch.not-watching");
            return;
        }
        current.unwatch(player, true);
        lang(player, "duel.watch.left");
    }

    /** Breekt het lopende gevecht af (beheerder): iedereen krijgt zijn inzet terug. */
    public boolean abort(String by) {
        Session current = session;
        if (current == null) {
            return false;
        }
        for (Session.Side side : new Session.Side[] {current.one, current.two}) {
            Player player = current.player(side);
            if (player != null) {
                lang(player, "duel.aborted", Text.p("player", by));
                plugin.theme().play(player, "error");
            }
        }
        current.cancel("admin");
        return true;
    }

    // ============================================================ teleporteren

    /**
     * Teleporteert een speler voor de 1v1 (mag altijd, /back onthoudt het niet). Klaar op de
     * hoofdthread, met true als het gelukt is.
     */
    CompletableFuture<Boolean> move(Player player, Location target) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (target == null || target.getWorld() == null) {
            result.complete(false);
            return result;
        }
        moving.merge(uuid, 1, Integer::sum);
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        if (stopping || !plugin.isEnabled()) {
            // Bij het stoppen van de server wacht niemand meer op een async teleport.
            boolean ok;
            try {
                ok = player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            } finally {
                doneMoving(uuid);
            }
            result.complete(ok);
            return result;
        }
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, error) -> {
            Runnable done = () -> {
                doneMoving(uuid);
                if (error != null) {
                    plugin.getLogger().log(Level.WARNING, "1v1: kon " + player.getName() + " niet teleporteren", error);
                }
                result.complete(error == null && Boolean.TRUE.equals(ok));
            };
            if (plugin.getServer().isPrimaryThread()) {
                done.run();
            } else if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, done);
            } else {
                done.run();
            }
        });
        return result;
    }

    private void doneMoving(UUID uuid) {
        moving.computeIfPresent(uuid, (key, count) -> count <= 1 ? null : count - 1);
    }

    /** Waar iemand heen gaat als zijn oude plek er niet meer is (wereld weg). */
    private Location fallback() {
        SpawnModule spawn = plugin.modules().get(SpawnModule.class);
        if (spawn != null && spawn.isEnabled()) {
            return spawn.spawn();
        }
        return plugin.getServer().getWorlds().getFirst().getSpawnLocation();
    }

    /**
     * Zet een speler terug naar zijn oude plek, met items die hij nog krijgt (wat niet past valt
     * bij zijn voeten, alleen voor hem op te pakken). Daarna hoeft er niets meer terug.
     */
    void sendBack(Player player, Spot spot, List<ItemStack> items, Runnable after) {
        UUID uuid = player.getUniqueId();
        Location target = spot == null ? null : spot.location(plugin.getServer());
        if (target == null) {
            target = fallback();
        }
        List<ItemStack> left = give(player, items);
        player.saveData();
        store.delete(uuid);
        recovery.remove(uuid);
        if (player.isDead()) {
            respawns.put(uuid, new Respawn(target, left));
            if (after != null) {
                after.run();
            }
            return;
        }
        move(player, target).thenAccept(ok -> {
            for (ItemStack item : left) {
                Session.dropFor(player, item);
            }
            if (!left.isEmpty()) {
                lang(player, "duel.items-dropped");
            }
            if (after != null) {
                after.run();
            }
        });
    }

    /** Stopt items in de inventory; geeft terug wat niet paste. */
    private static List<ItemStack> give(Player player, Collection<ItemStack> items) {
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) {
                left.addAll(player.getInventory().addItem(item.clone()).values());
            }
        }
        return left;
    }

    /** Waar deze speler moet respawnen (na een echte dood in de arena), of null. */
    Respawn takeRespawn(Player player) {
        return respawns.remove(player.getUniqueId());
    }

    // ============================================================ terugzetten na uitloggen of een crash

    /** Onthoudt wat deze speler nog terug moet krijgen, tot hij weer joint. */
    void remember(DuelStore.Pending pending) {
        if (pending.isEmpty()) {
            store.delete(pending.uuid());
            recovery.remove(pending.uuid());
            return;
        }
        store.save(pending);
        recovery.put(pending.uuid(), pending);
    }

    private void loadRecovery() {
        Map<UUID, DuelStore.RawPending> raw;
        try {
            raw = store.loadPending().join();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "1v1: kon niet lezen wie er nog iets terug moet krijgen", e);
            return;
        }
        for (DuelStore.RawPending entry : raw.values()) {
            try {
                recovery.put(entry.uuid(), entry.toPending());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "1v1: de inzet van " + entry.name() + " kon niet worden gelezen", e);
            }
        }
        if (!recovery.isEmpty()) {
            plugin.getLogger().info("1v1: " + recovery.size() + " speler(s) krijgen bij het joinen hun inzet terug of gaan terug naar hun plek.");
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            recover(player);
        }
    }

    /** Joint iemand die nog iets terug moet krijgen? Dan nu (even later, na de spawn-teleport). */
    void recover(Player player) {
        DuelStore.Pending pending = recovery.get(player.getUniqueId());
        if (pending == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || recovery.get(player.getUniqueId()) != pending) {
                return;
            }
            recovery.remove(player.getUniqueId());
            apply(player, pending);
        }, 10L);
    }

    private void apply(Player player, DuelStore.Pending pending) {
        if (pending.gamemode() != null) {
            try {
                GameMode mode = GameMode.valueOf(pending.gamemode().toUpperCase(Locale.ROOT));
                if (player.getGameMode() != mode) {
                    player.setGameMode(mode);
                }
            } catch (IllegalArgumentException ignored) {
                // Onbekende spelmodus: laten zoals het is.
            }
        }
        boolean stake = pending.money() > 0 || !pending.items().isEmpty();
        Spot spot = pending.location();
        if (spot == null) {
            List<ItemStack> left = give(player, pending.items());
            player.saveData();
            store.delete(player.getUniqueId());
            for (ItemStack item : left) {
                Session.dropFor(player, item);
            }
        } else {
            sendBack(player, spot, pending.items(), null);
        }
        if (pending.money() > 0) {
            EconomyService economy = economy();
            if (economy == null) {
                plugin.getLogger().warning("1v1: " + pending.money() + " centen voor " + player.getName()
                        + " kunnen niet terug (economy staat uit). Ze blijven bewaard.");
                store.save(new DuelStore.Pending(pending.uuid(), pending.name(), pending.role(), null, pending.money(), List.of(), null));
            } else {
                economy.give(player.getUniqueId(), pending.money(), false, "duel-refund", "1v1 afgebroken");
            }
        }
        lang(player, stake ? "duel.recovered-stake" : "duel.recovered");
    }

    // ============================================================ items

    /** Mag dit item worden ingezet? Niet als het in de lijst staat, en nooit met een item-limiet. */
    boolean mayStake(ItemStack item) {
        if (settings.blockedItems().contains(item.getType())) {
            return false;
        }
        ItemLimitModule limits = plugin.modules().get(ItemLimitModule.class);
        return limits == null || !limits.isEnabled() || !limits.involves(item);
    }

    /** Geluid en een wolkje op de plek van de verliezer (die gaat niet echt dood). */
    void deathEffect(Player player) {
        Location location = player.getLocation().add(0, 1, 0);
        player.getWorld().spawnParticle(Particle.CLOUD, location, 25, 0.3, 0.5, 0.3, 0.02);
        player.getWorld().playSound(Sound.sound(Key.key("entity.player.death"), Sound.Source.PLAYER, 1f, 1f),
                location.getX(), location.getY(), location.getZ());
    }

    // ============================================================ voor het paneel

    /** Hoe het er nu voor staat (hoofdthread). */
    public Map<String, Object> status() {
        Session current = session;
        if (current == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        EconomyService economy = economy();
        List<Map<String, Object>> sides = new ArrayList<>();
        for (Session.Side side : new Session.Side[] {current.one, current.two}) {
            Player player = current.player(side);
            Map<String, Object> entry = new HashMap<>();
            entry.put("uuid", side.uuid.toString());
            entry.put("name", side.name);
            entry.put("online", player != null);
            entry.put("health", player == null ? null : Math.round(player.getHealth() * 10) / 10.0);
            entry.put("money", side.money);
            entry.put("moneyText", economy == null ? null : economy.format(side.money));
            entry.put("items", side.itemCount());
            entry.put("ready", side.ready);
            entry.put("wins", side.wins);
            entry.put("rematch", side.rematch);
            sides.add(entry);
        }
        List<String> watching = new ArrayList<>();
        for (Session.Watcher watcher : current.watchers.values()) {
            watching.add(watcher.name());
        }
        result.put("phase", current.phase.name().toLowerCase(Locale.ROOT));
        result.put("round", current.round);
        result.put("since", current.phaseStarted);
        result.put("fightStarted", current.phase == Session.Phase.FIGHT ? current.fightStarted : null);
        result.put("players", sides);
        result.put("watchers", watching);
        return result;
    }

    /** Hoeveel uitdagingen er openstaan. */
    public int openInvites() {
        return invites.size();
    }
}
