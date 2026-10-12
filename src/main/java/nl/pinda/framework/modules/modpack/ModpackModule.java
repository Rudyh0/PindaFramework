package nl.pinda.framework.modules.modpack;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.modpack.ModpackSettings.Mode;
import nl.pinda.framework.modules.modpack.ModpackSettings.Required;
import nl.pinda.framework.modules.ranks.Rank;
import nl.pinda.framework.modules.ranks.RankModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Modpack-lock: spelers moeten bepaalde client-mods hebben (bijv. Simple Voice Chat).
 *
 * <p>De server kan zelf niet zien welke mods iemand heeft. Daarvoor is er de PindaCraft-mod
 * (map {@code client/} in de repo), die in de modpack zit en op verzoek de modlijst stuurt.
 *
 * <ul>
 *   <li><b>uit</b>: iedereen mag spelen; de lijsten worden wel bijgehouden (handig om te kiezen wat verplicht wordt);</li>
 *   <li><b>soft</b>: wie mods mist, krijgt een melding (tekst in te stellen);</li>
 *   <li><b>hard</b>: wie mods mist, komt er niet in.</li>
 * </ul>
 *
 * <p>De lijst wordt al tijdens het configureren opgevraagd (vóór je de wereld in gaat), zodat
 * de hardlock spelers tegenhoudt voordat ze joinen. Lukt dat niet, dan gebeurt het direct na
 * het joinen; de speler staat dan even stil tot de lijst binnen is.
 */
public final class ModpackModule extends PindaModule implements Listener, PluginMessageListener {

    public static final String REQUEST_CHANNEL = "pindacraft:request";
    public static final String REPORT_CHANNEL = "pindacraft:mods";
    /** De mod-ID van de PindaCraft-mod zelf. */
    public static final String COMPANION = "pindacraft";
    public static final String COMPANION_NAME = "PindaCraft";

    public static final String USE = "pinda.modpack.use";
    public static final String OTHERS = "pinda.modpack.others";
    public static final String BYPASS = "pinda.modpack.bypass";

    public static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_modpack_reports (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        brand TEXT,
                        mods TEXT NOT NULL,
                        updated INTEGER NOT NULL
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_modpack_reports_updated ON pinda_modpack_reports (updated)"
            )
    );

    /** Zo vaak per verbinding nemen we een lijst aan (tegen spam). */
    private static final int MAX_REPORTS = 6;
    /** Mods van spelers uit deze periode komen in de suggesties van het paneel. */
    private static final long SEEN_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_STORED = 200_000;
    private static final byte[] REQUEST = ("pindacraft " + 1 + "\n").getBytes(StandardCharsets.UTF_8);

    /** Hoe het ervoor staat met een speler. */
    public enum Status {
        /** De lijst is (nog) niet binnen. */
        CHECKING,
        /** Alle verplichte mods. */
        OK,
        /** Wel de PindaCraft-mod, maar er ontbreekt iets. */
        MISSING,
        /** Geen PindaCraft-mod: we weten niet wat hij heeft. */
        NO_MOD
    }

    /** Hoe het ervoor staat met een online speler (voor het paneel en /modpack). */
    public record PlayerState(UUID uuid, String name, Status status, boolean exempt, String brand,
                              ClientReport report, List<Required> missing, long joined) {
    }

    /** Het laatst bekende verslag van een speler, uit de database. */
    public record Stored(UUID uuid, String name, String brand, ClientReport report, long updated) {
    }

    /** Een mod die bij spelers voorkwam (voor de suggesties in het paneel). */
    public record SeenMod(String id, String name, String version, int players, long lastSeen) {
    }

    private static final class Seen {
        final String id;
        volatile String name;
        volatile String version;
        volatile long last;
        final Set<UUID> players = ConcurrentHashMap.newKeySet();

        Seen(String id) {
            this.id = id;
        }
    }

    private static final class Session {
        final UUID uuid;
        final String name;
        final long joined;
        /** Tot wanneer we op de lijst wachten (0 = niet: bijv. een vanilla client). */
        long deadline;
        boolean exempt;
        /** De lock heeft al gedaan wat hij moest doen (melding of kick), of hoeft niets te doen. */
        boolean handled;
        long lastReminder;

        Session(UUID uuid, String name, long joined) {
            this.uuid = uuid;
            this.name = name;
            this.joined = joined;
        }
    }

    private volatile ModpackSettings settings;
    private final ClientReport.Assembler assembler = new ClientReport.Assembler();
    private final Map<UUID, ClientReport> reports = new ConcurrentHashMap<>();
    private final Map<UUID, String> brands = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> received = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> rankBypass = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<ClientReport>> waiting = new ConcurrentHashMap<>();
    private final Set<UUID> askedInConfig = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();
    /** Online spelers; alleen op de hoofdthread. */
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final AtomicInteger configAnswers = new AtomicInteger();
    private final AtomicInteger configMisses = new AtomicInteger();
    private volatile boolean configWarned;

    public ModpackModule(PindaFramework plugin) {
        super(plugin, "modpack");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("modpack", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de tabel van de modpack-lock niet aanmaken", e);
        }
        loadSettings();
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, REQUEST_CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, REPORT_CHANNEL, this);
        listen(this);
        command(new ModpackCommand(plugin, this));
        repeat(this::tick, 20L, 10L);
        loadSeen();
        // Na /pinda reload: wie al online is, krijgt geen melding of kick, maar wel een status.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            start(player, false);
        }
    }

    @Override
    protected void onDisable() {
        Messenger messenger = plugin.getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin, REPORT_CHANNEL, this);
        messenger.unregisterOutgoingPluginChannel(plugin, REQUEST_CHANNEL);
        waiting.values().forEach(answer -> answer.complete(null));
        waiting.clear();
        frozen.clear();
        sessions.clear();
    }

    @Override
    protected void onReload() {
        loadSettings();
        if (settings.mode() != Mode.HARD) {
            frozen.clear();
        }
        // Nieuwe regels gelden vanaf de volgende keer joinen; wie online is, blijft.
        for (Session session : sessions.values()) {
            Player player = plugin.getServer().getPlayer(session.uuid);
            if (player != null) {
                session.exempt = isExempt(player);
            }
        }
    }

    private void loadSettings() {
        settings = ModpackSettings.read(config());
    }

    public ModpackSettings settings() {
        return settings;
    }

    // ============================================================ wat ontbreekt er

    /** De verplichte mods die niet in de lijst staan. Zonder lijst: de PindaCraft-mod en alles wat verplicht is. */
    public List<Required> missing(ClientReport report) {
        List<Required> required = settings.required();
        List<Required> missing = new ArrayList<>();
        if (report == null) {
            missing.add(new Required(COMPANION, COMPANION_NAME));
            missing.addAll(required);
            return missing;
        }
        for (Required mod : required) {
            if (!report.has(mod.id())) {
                missing.add(mod);
            }
        }
        return missing;
    }

    private boolean exempt(UUID uuid) {
        return settings.exempt().containsKey(uuid) || Boolean.TRUE.equals(rankBypass.get(uuid));
    }

    private boolean isExempt(Player player) {
        return player.hasPermission(BYPASS) || settings.exempt().containsKey(player.getUniqueId());
    }

    private static boolean isVanilla(String brand) {
        return brand != null && brand.equalsIgnoreCase("vanilla");
    }

    /** Antwoordt de PindaCraft-mod ooit tijdens het configureren? Zo niet, dan niet meer laten wachten. */
    private boolean configBroken() {
        return configAnswers.get() == 0 && configMisses.get() >= 3;
    }

    /** Voor het paneel: of de controle vóór het joinen werkt ("ja", "nee" of nog "onbekend"). */
    public String configPhase() {
        if (configAnswers.get() > 0) {
            return "works";
        }
        return configBroken() ? "broken" : "unknown";
    }

    // ============================================================ vóór het joinen (configureren)

    @EventHandler
    public void onInitialConfigure(PlayerConnectionInitialConfigureEvent event) {
        UUID uuid = event.getConnection().getProfile().getId();
        if (uuid != null) {
            forget(uuid); // een nieuwe verbinding: alles van de vorige keer weg
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        ModpackSettings current = settings;
        if (current.mode() == Mode.OFF) {
            return;
        }
        PlayerConfigurationConnection connection = event.getConnection();
        PlayerProfile profile = connection.getProfile();
        UUID uuid = profile.getId();
        if (uuid == null || !connection.isConnected()) {
            return;
        }
        String brand = connection.getClientBrandName();
        if (brand != null) {
            brands.put(uuid, brand);
        }
        boolean hard = current.mode() == Mode.HARD;
        if (hard && !current.exempt().containsKey(uuid)) {
            rankBypass.put(uuid, lookupRankBypass(uuid));
        }
        if (reports.containsKey(uuid) || isVanilla(brand) || configBroken()) {
            return;
        }
        CompletableFuture<ClientReport> answer = new CompletableFuture<>();
        waiting.put(uuid, answer);
        try {
            connection.sendPluginMessage(plugin, REQUEST_CHANNEL, REQUEST);
            askedInConfig.add(uuid);
            if (hard && !exempt(uuid)) {
                // De mod antwoordt meteen; zonder mod komt er niets en gaan we na de wachttijd verder.
                answer.get(current.timeoutSeconds(), TimeUnit.SECONDS);
            }
        } catch (TimeoutException e) {
            // Geen antwoord: geen PindaCraft-mod (of de server kan nu nog niets sturen).
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Kon de modlijst van " + profile.getName() + " niet opvragen", e);
        } finally {
            waiting.remove(uuid, answer);
        }
    }

    /** Heeft de rang van deze (nog niet gejoinde) speler pinda.modpack.bypass? */
    private boolean lookupRankBypass(UUID uuid) {
        RankModule ranks = plugin.modules().get(RankModule.class);
        if (ranks == null || !ranks.isEnabled()) {
            return false;
        }
        try {
            Rank rank = ranks.service().rankOf(uuid).get(2, TimeUnit.SECONDS);
            if (rank == null) {
                return false;
            }
            Map<String, Boolean> permissions = plugin.getServer().getScheduler()
                    .callSyncMethod(plugin, () -> ranks.service().resolve(rank)).get(2, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(permissions.get(BYPASS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return false;
        }
    }

    /** Hardlock: tegenhouden voordat de speler de wereld in gaat, als we zeker weten dat er iets ontbreekt. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onValidate(PlayerConnectionValidateLoginEvent event) {
        if (!(event.getConnection() instanceof PlayerConfigurationConnection connection) || !event.isAllowed()) {
            return;
        }
        ModpackSettings current = settings;
        PlayerProfile profile = connection.getProfile();
        UUID uuid = profile.getId();
        if (current.mode() != Mode.HARD || uuid == null || exempt(uuid) || plugin.getServer().getPlayer(uuid) != null) {
            return; // (online = opnieuw configureren: die mag blijven)
        }
        ClientReport report = reports.get(uuid);
        boolean certain;
        if (report != null) {
            certain = !missing(report).isEmpty();
        } else {
            // Zonder lijst alleen tegenhouden als we zeker weten dat er geen PindaCraft-mod is: een vanilla
            // client, of geen antwoord terwijl de mod bij anderen wel antwoordt tijdens het configureren.
            certain = isVanilla(brands.get(uuid)) || askedInConfig.contains(uuid) && configAnswers.get() > 0;
        }
        if (certain) {
            String name = profile.getName() == null ? "?" : profile.getName();
            event.kickMessage(kickMessage(name, report));
            plugin.getLogger().info(name + " tegengehouden door de modpack-lock (mist: " + labels(missing(report)) + ")");
        }
    }

    // ============================================================ de lijst ontvangen

    /** Tijdens het configureren (en, afhankelijk van de serverversie, ook tijdens het spelen). */
    @Override
    public void onPluginMessageReceived(String channel, PlayerConnection connection, byte[] message) {
        if (connection instanceof PlayerConfigurationConnection config) {
            PlayerProfile profile = config.getProfile();
            if (profile.getId() != null) {
                receive(channel, profile.getId(), profile.getName(), message, true);
            }
        }
        // Spelende spelers: zie de methode hieronder (Paper roept die daarnaast ook aan).
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        receive(channel, player.getUniqueId(), player.getName(), message, false);
    }

    private void receive(String channel, UUID uuid, String name, byte[] message, boolean configuring) {
        if (!REPORT_CHANNEL.equals(channel) || !isEnabled()) {
            return;
        }
        ClientReport report = assembler.accept(uuid, message, System.currentTimeMillis());
        if (report == null || received.merge(uuid, 1, Integer::sum) > MAX_REPORTS) {
            return;
        }
        reports.put(uuid, report);
        CompletableFuture<ClientReport> answer = waiting.get(uuid);
        if (answer != null) {
            answer.complete(report);
        }
        if (configuring) {
            askedInConfig.remove(uuid);
            configAnswers.incrementAndGet();
        } else if (askedInConfig.remove(uuid) && configMisses.incrementAndGet() >= 3 && configBroken() && !configWarned) {
            configWarned = true;
            plugin.getLogger().info("Modpack-lock: de PindaCraft-mod antwoordt pas na het joinen. Spelers worden "
                    + "daarom direct na het joinen gecontroleerd (ze staan even stil tot hun modlijst binnen is).");
        }
        remember(uuid, report);
        save(uuid, name == null ? "?" : name, brands.get(uuid), report);
        if (!configuring) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Session session = sessions.get(uuid);
                Player player = plugin.getServer().getPlayer(uuid);
                if (session != null && player != null) {
                    settle(player, session);
                }
            });
        }
    }

    // ============================================================ na het joinen

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer(), true);
    }

    /** @param fresh net gejoind (anders: al online toen de module startte; dan geen melding of kick) */
    private void start(Player player, boolean fresh) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        String brand = player.getClientBrandName();
        if (brand != null) {
            brands.put(uuid, brand);
        }
        Session session = new Session(uuid, player.getName(), now);
        session.exempt = isExempt(player);
        session.handled = !fresh;
        sessions.put(uuid, session);
        if (!reports.containsKey(uuid) && !isVanilla(brand)) {
            session.deadline = now + settings.timeoutSeconds() * 1000L;
            request(player);
        }
        if (fresh && settings.mode() == Mode.HARD && !session.exempt && status(session, now) == Status.CHECKING) {
            frozen.add(uuid); // even stilstaan tot de lijst binnen is
        }
        // Een tik later: niet kicken of berichten sturen midden in het joinen.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && sessions.get(uuid) == session) {
                settle(player, session);
            }
        });
    }

    /** Vraagt de lijst op, als de client het kanaal al heeft aangemeld (anders bij het aanmelden). */
    private void request(Player player) {
        if (player.getListeningPluginChannels().contains(REQUEST_CHANNEL)) {
            player.sendPluginMessage(plugin, REQUEST_CHANNEL, REQUEST);
        }
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (REQUEST_CHANNEL.equals(event.getChannel()) && !reports.containsKey(event.getPlayer().getUniqueId())) {
            event.getPlayer().sendPluginMessage(plugin, REQUEST_CHANNEL, REQUEST);
        }
    }

    private Status status(Session session, long now) {
        ClientReport report = reports.get(session.uuid);
        if (report != null) {
            return missing(report).isEmpty() ? Status.OK : Status.MISSING;
        }
        return session.deadline > now ? Status.CHECKING : Status.NO_MOD;
    }

    /** Doet wat de lock moet doen zodra bekend is hoe het ervoor staat (melding of kick). */
    private void settle(Player player, Session session) {
        long now = System.currentTimeMillis();
        Status status = status(session, now);
        if (status == Status.CHECKING) {
            return;
        }
        frozen.remove(session.uuid);
        if (session.handled) {
            return;
        }
        session.handled = true;
        ModpackSettings current = settings;
        if (session.exempt || current.mode() == Mode.OFF || status == Status.OK) {
            return;
        }
        ClientReport report = reports.get(session.uuid);
        if (current.mode() == Mode.HARD) {
            plugin.getLogger().info(player.getName() + " gekickt door de modpack-lock (mist: " + labels(missing(report)) + ")");
            player.kick(kickMessage(player.getName(), report));
            return;
        }
        session.lastReminder = now;
        // Even wachten, zodat de melding niet tussen de joinberichten verdwijnt.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && isEnabled()) {
                sendSoftMessage(player);
            }
        }, 40L);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        ModpackSettings current = settings;
        for (Session session : new ArrayList<>(sessions.values())) {
            Player player = plugin.getServer().getPlayer(session.uuid);
            if (player == null) {
                continue;
            }
            if (!session.handled) {
                if (status(session, now) != Status.CHECKING) {
                    settle(player, session);
                } else if (frozen.contains(session.uuid)) {
                    plugin.lang().send(player, "modpack.checking-bar");
                }
                continue;
            }
            if (current.mode() == Mode.SOFT && current.remindMinutes() > 0 && !session.exempt
                    && now - session.lastReminder >= current.remindMinutes() * 60_000L) {
                Status status = status(session, now);
                if (status == Status.MISSING || status == Status.NO_MOD) {
                    session.lastReminder = now;
                    sendSoftMessage(player);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        sessions.remove(uuid);
        forget(uuid);
    }

    private void forget(UUID uuid) {
        reports.remove(uuid);
        brands.remove(uuid);
        received.remove(uuid);
        rankBypass.remove(uuid);
        askedInConfig.remove(uuid);
        frozen.remove(uuid);
        assembler.forget(uuid);
    }

    // ============================================================ meldingen

    private void sendSoftMessage(Player player) {
        String raw = settings.softMessage();
        if (!raw.isBlank()) {
            player.sendMessage(render(raw, player.getName(), reports.get(player.getUniqueId()), true));
        }
    }

    public Component kickMessage(String name, ClientReport report) {
        String raw = settings.kickMessage();
        return render(raw.isBlank() ? "<error>Je hebt de modpack van deze server nodig.<newline><text><url>" : raw, name, report, false);
    }

    /** Een melding met &lt;player&gt;, &lt;missing&gt;, &lt;count&gt; en &lt;url&gt; ingevuld. */
    public Component render(String raw, String name, ClientReport report, boolean clickable) {
        List<Required> missing = missing(report);
        return plugin.lang().parse(raw, Text.p("player", name), Text.p("missing", labels(missing)),
                Text.p("count", missing.size()), Text.c("url", link(clickable)));
    }

    /** De modpack-link als component (in de chat aanklikbaar). */
    public Component link(boolean clickable) {
        String url = settings.url();
        if (url.isEmpty()) {
            String none = plugin.lang().raw(plugin.lang().defaultLanguage(), "modpack.no-url-text");
            return Component.text(none == null ? "-" : none);
        }
        Component text = Component.text(url);
        return clickable ? text.decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.openUrl(url)) : text;
    }

    public static String labels(List<Required> mods) {
        List<String> names = new ArrayList<>();
        for (Required mod : mods) {
            names.add(mod.label());
        }
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    // ============================================================ stilstaan tijdens de controle (hardlock)

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onMove(PlayerMoveEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId()) && event.hasChangedPosition()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            plugin.lang().send(event.getPlayer(), "modpack.checking");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && frozen.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ============================================================ voor het paneel en /modpack

    /** Hoe het ervoor staat met een online speler (hoofdthread). Null als hij niet online is. */
    public PlayerState state(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return null;
        }
        ClientReport report = reports.get(session.uuid);
        Status status = status(session, System.currentTimeMillis());
        return new PlayerState(session.uuid, player.getName(), status, session.exempt, brands.get(session.uuid), report,
                status == Status.CHECKING ? List.of() : missing(report), session.joined);
    }

    /** Alle online spelers (hoofdthread). */
    public List<PlayerState> online() {
        List<PlayerState> states = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            PlayerState state = state(player);
            if (state != null) {
                states.add(state);
            }
        }
        states.sort(Comparator.comparing(state -> state.name().toLowerCase(Locale.ROOT)));
        return states;
    }

    /** De mods die bij spelers voorkwamen (de afgelopen 30 dagen), meest gebruikte eerst. */
    public List<SeenMod> seenMods() {
        long since = System.currentTimeMillis() - SEEN_MILLIS;
        List<SeenMod> list = new ArrayList<>();
        for (Seen mod : seen.values()) {
            if (mod.last >= since) {
                list.add(new SeenMod(mod.id, mod.name, mod.version, mod.players.size(), mod.last));
            }
        }
        list.sort(Comparator.comparingInt((SeenMod mod) -> -mod.players()).thenComparing(SeenMod::id));
        return list;
    }

    private void remember(UUID uuid, ClientReport report) {
        for (ClientReport.Mod mod : report.topLevel()) {
            Seen entry = seen.computeIfAbsent(mod.id(), Seen::new);
            entry.name = mod.name();
            if (report.time() >= entry.last) {
                entry.version = mod.version();
                entry.last = report.time();
            }
            entry.players.add(uuid);
        }
    }

    // ============================================================ database

    private void save(UUID uuid, String name, String brand, ClientReport report) {
        String text = report.serialize();
        if (text.length() > MAX_STORED) {
            text = text.substring(0, text.lastIndexOf('\n', MAX_STORED) + 1);
        }
        String mods = text;
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pinda_modpack_reports (uuid, name, brand, mods, updated) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, brand = excluded.brand, mods = excluded.mods, updated = excluded.updated""")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setString(3, brand);
                statement.setString(4, mods);
                statement.setLong(5, report.time());
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kon de modlijst van " + name + " niet opslaan", error);
            return null;
        });
    }

    private void loadSeen() {
        long since = System.currentTimeMillis() - SEEN_MILLIS;
        plugin.database().query(connection -> {
            List<Map.Entry<UUID, ClientReport>> found = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, mods, updated FROM pinda_modpack_reports WHERE updated >= ?")) {
                statement.setLong(1, since);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        try {
                            UUID uuid = UUID.fromString(result.getString(1));
                            found.add(Map.entry(uuid, ClientReport.parse(List.of(result.getString(2).split("\n")), result.getLong(3))));
                        } catch (IllegalArgumentException ignored) {
                            // kapotte regel: overslaan
                        }
                    }
                }
            }
            return found;
        }).thenAccept(found -> found.forEach(entry -> remember(entry.getKey(), entry.getValue())))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Kon de bekende mods niet laden", error);
                    return null;
                });
    }

    /** Het laatst bekende verslag van een (eventueel offline) speler. */
    public CompletableFuture<Stored> stored(UUID uuid) {
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, brand, mods, updated FROM pinda_modpack_reports WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    long updated = result.getLong(4);
                    return new Stored(uuid, result.getString(1), result.getString(2),
                            ClientReport.parse(List.of(result.getString(3).split("\n")), updated), updated);
                }
            }
        });
    }
}
