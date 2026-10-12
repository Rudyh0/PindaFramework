package nl.pinda.framework.modules.bossbar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.integration.Placeholders;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.leaderboards.Board;
import nl.pinda.framework.modules.leaderboards.LeaderboardService;
import nl.pinda.framework.modules.leaderboards.LeaderboardsModule;
import nl.pinda.framework.player.PlayerSetting;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Bossbars bovenin het scherm, naar het voorbeeld van AnimaBossBar: zoveel als je wilt, met
 * wisselende teksten (frames), kleuren, een balk die leegloopt, volloopt, heen en weer gaat of
 * een waarde volgt, bewegende effecten over de tekst, en wisselingen waarin bossbars elkaar
 * afwisselen. Per bossbar in te stellen voor welke werelden en permissies. Alles in te stellen
 * in het paneel (Bossbars) of in modules/bossbar.yml. Spelers zetten ze uit in /instellingen.
 */
public final class BossBarModule extends PindaModule implements Listener {

    public static final String SETTING = "bossbar";
    public static final String ADMIN = "pinda.bossbar.admin";
    public static final String TOGGLE = "pinda.bossbar.toggle";

    /** Om de hoeveel ticks alles wordt bijgewerkt (10 keer per seconde). */
    static final long PERIOD = 2L;
    /** Om de hoeveel ticks placeholders en tags opnieuw worden ingevuld. */
    private static final long REFRESH = 20L;
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private Map<String, BarDefinition> bars = Map.of();
    private List<Rotation> rotations = List.of();
    private List<String> disabledWorlds = List.of();
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private final Map<String, Component> topCache = new HashMap<>();
    /** Teksten die voor iedereen (per taal) hetzelfde zijn: één keer opmaken, niet per speler. */
    private final Map<String, Shared> shared = new HashMap<>();
    private long topUpdated = -1;
    private long now;

    public BossBarModule(PindaFramework plugin) {
        super(plugin, "bossbar");
    }

    @Override
    protected void onEnable() {
        load();
        registerSetting();
        listen(this);
        command(new BossBarCommand(plugin, this));
        repeat(this::tick, 1L, PERIOD);
    }

    @Override
    protected void onReload() {
        load();
        registerSetting();
        for (Viewer viewer : viewers.values()) {
            for (Slot slot : viewer.slots.values()) {
                slot.barId = null;
            }
            viewer.temps.removeIf(temp -> !bars.containsKey(temp.bar()));
        }
        topCache.clear();
        shared.clear();
    }

    @Override
    protected void onDisable() {
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) {
                for (Slot slot : entry.getValue().slots.values()) {
                    player.hideBossBar(slot.bar);
                }
            }
        }
        viewers.clear();
        topCache.clear();
        shared.clear();
        plugin.settings().unregister(SETTING);
    }

    private void registerSetting() {
        plugin.settings().register(new PlayerSetting(SETTING, config().getBoolean("default-enabled", true),
                Material.DRAGON_HEAD, config().getBoolean("show-in-setup", true)));
    }

    private void load() {
        Map<String, BarDefinition> loaded = new LinkedHashMap<>();
        ConfigurationSection section = config().getConfigurationSection("bars");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection bar = section.getConfigurationSection(key);
                String id = key.toLowerCase(Locale.ROOT);
                if (bar == null || !BarDefinition.ID.matcher(id).matches()) {
                    plugin.getLogger().warning("bossbar.yml: ongeldige bossbar '" + key + "' (alleen letters, cijfers, - en _)");
                    continue;
                }
                loaded.putIfAbsent(id, BarDefinition.parse(id, bar));
            }
        }
        List<Rotation> loadedRotations = new ArrayList<>();
        for (Map<?, ?> map : config().getMapList("rotations")) {
            Rotation rotation = Rotation.parse(map);
            for (String id : rotation.bars()) {
                if (!loaded.containsKey(id)) {
                    plugin.getLogger().warning("bossbar.yml: wisseling '" + rotation.name() + "' noemt bossbar '" + id + "', maar die bestaat niet.");
                }
            }
            loadedRotations.add(rotation);
        }
        bars = loaded;
        rotations = List.copyOf(loadedRotations);
        disabledWorlds = config().getStringList("disabled-worlds");
    }

    // ============================================================ voor het paneel en het commando

    public Map<String, BarDefinition> bars() {
        return bars;
    }

    public List<Rotation> rotations() {
        return rotations;
    }

    /** Hoe ver de wisselingen voor iedereen nu zijn (in ticks), zodat het paneel gelijk loopt. */
    public long clock() {
        return now;
    }

    /**
     * Toont een bossbar los aan deze spelers, bovenop de wisselingen (bijvoorbeeld voor een
     * event). Seconden 0 = zo lang als de bossbar zelf duurt (of tot /bossbars hide).
     */
    public int show(String id, Collection<? extends Player> targets, int seconds) {
        BarDefinition bar = bars.get(id);
        if (bar == null) {
            return 0;
        }
        long length = seconds > 0 ? seconds * 20L : bar.durationTicks();
        long end = length > 0 ? now + length : Long.MAX_VALUE;
        int count = 0;
        for (Player player : targets) {
            Viewer viewer = viewer(player);
            viewer.temps.removeIf(temp -> temp.bar().equals(id));
            viewer.temps.add(new TempShow(id, now, end));
            count++;
        }
        return count;
    }

    /** Haalt los getoonde bossbars weg (id null = allemaal). */
    public int hide(String id, Collection<? extends Player> targets) {
        int count = 0;
        for (Player player : targets) {
            Viewer viewer = viewers.get(player.getUniqueId());
            if (viewer != null && viewer.temps.removeIf(temp -> id == null || temp.bar().equals(id))) {
                count++;
            }
        }
        return count;
    }

    /**
     * De tekst van een bossbar voor een speler. Voor het voorbeeld in het paneel: {@code player}
     * null (niet online) en {@code as} de speler van wie de gegevens getoond worden.
     */
    public Component render(Player player, OfflinePlayer as, String raw, String code) {
        try {
            String text = as == null ? raw : Placeholders.apply(as, raw);
            return plugin.lang().parse(text, tags(player, as, code));
        } catch (RuntimeException e) {
            // Een fout in de opmaak mag de rest van de bossbars niet stilzetten.
            return Component.text(raw);
        }
    }

    /** Een effect over een al opgemaakte tekst (voor het voorbeeld in het paneel). */
    public static Component effect(Component text, BarDefinition bar, long ticks, long frameTicks) {
        return TextEffects.apply(text, bar.effect(), bar.effectColors(), bar.effectSpeed(), ticks, frameTicks);
    }

    // ============================================================ elke paar ticks

    private static final class Viewer {
        final long joined;
        final Map<String, Slot> slots = new LinkedHashMap<>();
        final List<TempShow> temps = new ArrayList<>();

        Viewer(long joined) {
            this.joined = joined;
        }
    }

    private static final class Slot {
        final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        String barId;
        int frame = -1;
        long refreshed;
        Component text = Component.empty();
        /** Wat de speler nu ziet: Adventure stuurt bij elke name() een pakketje, ook als hij gelijk is. */
        Component sent;
        float valueProgress;
    }

    private record Shared(Component text, long at) {
    }

    /** Staat er iets in dat per speler anders is? Zo niet, dan is de tekst voor iedereen gelijk. */
    static boolean personal(String raw) {
        return raw.indexOf('%') >= 0 || raw.contains("<player") || raw.contains("<own") || raw.contains("<world") || raw.contains("<ping");
    }

    private record TempShow(String bar, long start, long end) {
    }

    private Viewer viewer(Player player) {
        // Wie al online was toen de module startte, joinde "lang geleden" (geen welkomstbalk na /pinda reload).
        return viewers.computeIfAbsent(player.getUniqueId(), uuid -> new Viewer(now - 1_000_000_000L));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        viewers.put(event.getPlayer().getUniqueId(), new Viewer(now));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        viewers.remove(event.getPlayer().getUniqueId());
    }

    private void tick() {
        now += PERIOD;
        LeaderboardService service = leaderboards();
        if (service != null && service.updated() != topUpdated) {
            topCache.clear();
            topUpdated = service.updated();
        }
        if (shared.size() > 2000) {
            shared.clear();
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Viewer viewer = viewer(player);
            Set<String> used = new HashSet<>();
            boolean allowedWorld = !contains(disabledWorlds, player.getWorld().getName());
            if (allowedWorld && plugin.settings().isEnabled(player, SETTING)) {
                for (int index = 0; index < rotations.size(); index++) {
                    Rotation rotation = rotations.get(index);
                    if (!rotation.enabled()) {
                        continue;
                    }
                    Rotation.Placement placement = rotation.at(rotation.onJoin() ? now - viewer.joined : now, bars, bar -> visible(player, bar));
                    if (placement != null) {
                        String key = "r" + index;
                        update(player, viewer, key, placement);
                        used.add(key);
                    }
                }
            }
            // Los getoonde bossbars (een event): ook als iemand de bossbar zelf uit heeft staan
            // (maar niet in de werelden zonder bossbars).
            for (Iterator<TempShow> iterator = viewer.temps.iterator(); iterator.hasNext(); ) {
                TempShow temp = iterator.next();
                BarDefinition bar = bars.get(temp.bar());
                if (bar == null || now >= temp.end()) {
                    iterator.remove();
                    continue;
                }
                if (allowedWorld && visible(player, bar)) {
                    String key = "t:" + temp.bar();
                    long length = temp.end() == Long.MAX_VALUE ? 0 : temp.end() - temp.start();
                    update(player, viewer, key, new Rotation.Placement(bar, now - temp.start(), length, 0));
                    used.add(key);
                }
            }
            for (Iterator<Map.Entry<String, Slot>> iterator = viewer.slots.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<String, Slot> entry = iterator.next();
                if (!used.contains(entry.getKey())) {
                    player.hideBossBar(entry.getValue().bar);
                    iterator.remove();
                }
            }
        }
    }

    private void update(Player player, Viewer viewer, String key, Rotation.Placement placement) {
        BarDefinition bar = placement.bar();
        Slot slot = viewer.slots.get(key);
        boolean created = slot == null;
        if (created) {
            slot = new Slot();
            viewer.slots.put(key, slot);
        }
        long local = placement.local();
        int frame = (int) ((local / bar.frameTicks()) % bar.frames().size());
        if (!bar.id().equals(slot.barId) || frame != slot.frame || now - slot.refreshed >= REFRESH) {
            String code = plugin.lang().languageOf(player);
            String raw = bar.frames().get(frame);
            slot.barId = bar.id();
            slot.frame = frame;
            slot.refreshed = now;
            if (personal(raw)) {
                slot.text = render(player, player, raw, code);
            } else {
                String cacheKey = bar.id() + "|" + frame + "|" + code;
                Shared cached = shared.get(cacheKey);
                if (cached == null || now - cached.at() >= REFRESH) {
                    cached = new Shared(render(player, player, raw, code), now);
                    shared.put(cacheKey, cached);
                }
                slot.text = cached.text();
            }
            if (bar.progress() == ProgressMode.VALUE) {
                double max = number(player, bar.progressMax());
                slot.valueProgress = max > 0 ? (float) (number(player, bar.progressValue()) / max) : 0f;
            }
        }
        Component name = bar.effect() == TextEffect.NONE ? slot.text
                : TextEffects.apply(slot.text, bar.effect(), bar.effectColors(), bar.effectSpeed(), local, local % bar.frameTicks());
        if (!name.equals(slot.sent)) {
            slot.bar.name(name);
            slot.sent = name;
        }
        // In stapjes van 2%: de client laat de balk zelf vloeiend bewegen, en zo gaan er minder pakketjes heen.
        float progress = BarMath.progress(bar, local, placement.length(), slot.valueProgress);
        slot.bar.progress(Math.round(progress * 50) / 50f);
        slot.bar.color(bar.color());
        slot.bar.overlay(bar.overlay());
        if (created) {
            player.showBossBar(slot.bar);
        }
    }

    private static boolean visible(Player player, BarDefinition bar) {
        if (!bar.permission().isEmpty() && !player.hasPermission(bar.permission())) {
            return false;
        }
        String world = player.getWorld().getName().toLowerCase(Locale.ROOT);
        if (!bar.worlds().isEmpty() && !bar.worlds().contains(world)) {
            return false;
        }
        return !bar.disabledWorlds().contains(world);
    }

    private static boolean contains(List<String> names, String value) {
        for (String name : names) {
            if (name.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    // ============================================================ tags en getallen

    /**
     * De tags die in een bossbar werken: &lt;player&gt;, &lt;online&gt;, &lt;max&gt;, &lt;world&gt;, &lt;tps&gt;,
     * &lt;ping&gt;, &lt;top:lijst:plekken&gt; (de top van een toplijst op één regel), &lt;board:lijst&gt;
     * (de naam van een toplijst) en &lt;own:lijst&gt; (je eigen plek).
     */
    private TagResolver tags(Player player, OfflinePlayer as, String code) {
        int online = plugin.getServer().getOnlinePlayers().size();
        String name = player != null ? player.getName() : as != null && as.getName() != null ? as.getName() : "Rudyh0";
        return TagResolver.resolver(
                Text.p("player", name),
                Text.p("online", online),
                Text.p("max", plugin.getServer().getMaxPlayers()),
                Text.p("world", player == null ? "world" : player.getWorld().getName()),
                Text.p("tps", LeaderboardService.number(Math.round(Math.min(20.0, plugin.getServer().getTPS()[0]) * 10) / 10.0, code)),
                Text.p("ping", player == null ? 25 : player.getPing()),
                TagResolver.resolver("top", (args, context) -> Tag.selfClosingInserting(top(code, board(args), places(args)))),
                TagResolver.resolver("board", (args, context) -> {
                    Board board = board(args);
                    LeaderboardService service = leaderboards();
                    return Tag.selfClosingInserting(Component.text(board == null || service == null ? "?" : service.name(board, code)));
                }),
                TagResolver.resolver("own", (args, context) -> {
                    Board board = board(args);
                    LeaderboardService service = leaderboards();
                    int position = board == null || service == null || as == null ? 0 : service.ranking(board).position(as.getUniqueId());
                    return Tag.selfClosingInserting(Component.text(position == 0 ? "-" : "#" + position));
                }));
    }

    private static Board board(ArgumentQueue args) {
        return args.hasNext() ? Board.find(args.pop().value()) : Board.MONEY;
    }

    private static int places(ArgumentQueue args) {
        int places = args.hasNext() ? args.pop().asInt().orElse(3) : 3;
        return Math.max(1, Math.min(10, places));
    }

    /** De top van een toplijst op één regel: "1. Rudyh0 12,3K · 2. Lisa 8K · 3. …". */
    private Component top(String code, Board board, int places) {
        LeaderboardService service = leaderboards();
        if (service == null || board == null) {
            return Component.empty();
        }
        return topCache.computeIfAbsent(code + ':' + board.id() + ':' + places, key -> {
            LanguageManager lang = plugin.lang();
            List<LeaderboardService.Entry> entries = service.ranking(board).top(places);
            if (entries.isEmpty()) {
                return lang.component(code, "bossbar.top-empty");
            }
            Component separator = lang.component(code, "bossbar.top-separator");
            Component line = Component.empty();
            for (int index = 0; index < entries.size(); index++) {
                LeaderboardService.Entry entry = entries.get(index);
                if (index > 0) {
                    line = line.append(separator);
                }
                line = line.append(lang.component(code, "bossbar.top-entry", Text.p("position", index + 1),
                        Text.p("player", entry.name()), Text.p("value", service.compact(board, entry.value(), code))));
            }
            return line;
        });
    }

    /** Een getal uit een tekst met tags of placeholders (voor de balk bij 'value'). */
    private double number(Player player, String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String text = raw.replace("<online>", String.valueOf(plugin.getServer().getOnlinePlayers().size()))
                .replace("<max>", String.valueOf(plugin.getServer().getMaxPlayers()))
                .replace("<tps>", String.format(Locale.ROOT, "%.2f", Math.min(20.0, plugin.getServer().getTPS()[0])))
                .replace("<ping>", String.valueOf(player.getPing()));
        text = MINI_MESSAGE.stripTags(Placeholders.apply(player, text));
        return BarMath.parseNumber(text);
    }

    private LeaderboardService leaderboards() {
        LeaderboardsModule module = plugin.modules().get(LeaderboardsModule.class);
        return module != null && module.isEnabled() ? module.service() : null;
    }
}
