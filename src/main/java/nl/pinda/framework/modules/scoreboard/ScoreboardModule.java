package nl.pinda.framework.modules.scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Het scoreboard rechts in beeld: wisselt om de paar seconden tussen de toplijsten, met je
 * eigen plek onderaan. Spelers kunnen het uitzetten in /instellingen.
 */
public final class ScoreboardModule extends PindaModule implements Listener {

    public static final String SETTING = "scoreboard";

    private final Map<UUID, Sidebar> sidebars = new HashMap<>();
    /** Spelers bij wie een andere plugin het scoreboard heeft overgenomen: daar blijven we af. */
    private final Set<UUID> yielded = new HashSet<>();

    public ScoreboardModule(PindaFramework plugin) {
        super(plugin, "scoreboard");
    }

    @Override
    protected void onEnable() {
        registerSetting();
        listen(this);
        repeat(this::tick, 20L, 20L);
    }

    @Override
    protected void onDisable() {
        for (UUID uuid : new ArrayList<>(sidebars.keySet())) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null) {
                remove(player);
            }
        }
        sidebars.clear();
        yielded.clear();
        plugin.settings().unregister(SETTING);
    }

    @Override
    protected void onReload() {
        cache.clear();
        registerSetting();
        tick();
    }

    private void registerSetting() {
        plugin.settings().register(new PlayerSetting(SETTING, config().getBoolean("default-enabled", true),
                Material.PAINTING, config().getBoolean("show-in-setup", true)));
    }

    // ============================================================ instellingen

    /** De toplijsten op het scoreboard, in volgorde (alleen die ook echt bestaan). */
    public List<Board> boards() {
        LeaderboardService service = leaderboards();
        List<Board> boards = new ArrayList<>();
        if (service == null) {
            return boards;
        }
        List<Board> available = service.boards();
        for (String id : config().getStringList("boards")) {
            Board board = Board.find(id);
            if (board != null && available.contains(board) && !boards.contains(board)) {
                boards.add(board);
            }
        }
        return boards;
    }

    /** Welke toplijst er nu op het scoreboard staat (voor iedereen dezelfde). */
    public Board current() {
        List<Board> boards = boards();
        if (boards.isEmpty()) {
            return null;
        }
        long seconds = Math.max(3, config().getLong("switch-seconds", 10));
        return boards.get((int) ((System.currentTimeMillis() / 1000L / seconds) % boards.size()));
    }

    private int places() {
        return Math.max(1, Math.min(10, config().getInt("places", 10)));
    }

    private boolean wants(Player player) {
        if (!plugin.settings().isEnabled(player, SETTING)) {
            return false;
        }
        for (String world : config().getStringList("disabled-worlds")) {
            if (world.equalsIgnoreCase(player.getWorld().getName())) {
                return false;
            }
        }
        return true;
    }

    private LeaderboardService leaderboards() {
        LeaderboardsModule module = plugin.modules().get(LeaderboardsModule.class);
        return module != null && module.isEnabled() ? module.service() : null;
    }

    // ============================================================ bijwerken

    /**
     * Wat voor iedereen hetzelfde is (per toplijst en taal): de titel, de regels van de top en de
     * opgemaakte waarden. Zo hoeft er per speler per seconde bijna niets opnieuw opgemaakt te worden.
     */
    private static final class Shared {
        final List<SidebarLine> entries = new ArrayList<>();
        final List<UUID> uuids = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final Map<Integer, Component> selfLeft = new HashMap<>();
        final Map<Integer, SidebarLine> ownLines = new HashMap<>();
        Component title;
        String titleRaw;
        Component footer;
        String footerRaw;
    }

    private record CacheKey(Board board, String code) {
    }

    private final Map<CacheKey, Shared> cache = new HashMap<>();
    private long cachedUpdated = -1;
    private int ticks;

    private void tick() {
        LeaderboardService service = leaderboards();
        Board board = current();
        if (service != null && service.updated() != cachedUpdated) {
            cache.clear();
            cachedUpdated = service.updated();
        }
        boolean syncTeams = config().getBoolean("sync-teams", true) && ticks++ % 5 == 0;
        Scoreboard main = plugin.getServer().getScoreboardManager().getMainScoreboard();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (service == null || board == null || !wants(player)) {
                remove(player);
                continue;
            }
            if (yielded.contains(uuid)) {
                continue;
            }
            Sidebar sidebar = sidebars.get(uuid);
            if (sidebar != null && player.getScoreboard() != sidebar.board()) {
                // Een andere plugin heeft een eigen scoreboard gezet; die laten we met rust tot de volgende join.
                sidebars.remove(uuid);
                yielded.add(uuid);
                continue;
            }
            List<SidebarLine> lines = new ArrayList<>();
            Component title = render(player, plugin.lang().languageOf(player), service, board, lines);
            boolean created = false;
            if (sidebar == null) {
                Scoreboard scoreboard = plugin.getServer().getScoreboardManager().getNewScoreboard();
                sidebar = new Sidebar(scoreboard, title);
                sidebars.put(uuid, sidebar);
                created = true;
            }
            if (created || syncTeams) {
                TeamSync.copy(main, sidebar.board());
            }
            if (created) {
                player.setScoreboard(sidebar.board());
            }
            sidebar.update(title, lines);
        }
    }

    /** Hoe het scoreboard er nu uitziet voor een toeschouwer zonder plek (voor het paneel). Null als er niets te tonen is. */
    public Component preview(Board board, String code, List<SidebarLine> lines) {
        LeaderboardService service = leaderboards();
        return service == null || board == null ? null : render(null, code, service, board, lines);
    }

    private Shared shared(String code, LeaderboardService service, Board board) {
        return cache.computeIfAbsent(new CacheKey(board, code), key -> {
            LanguageManager lang = plugin.lang();
            Shared shared = new Shared();
            List<LeaderboardService.Entry> top = service.ranking(board).top(places());
            if (top.isEmpty()) {
                shared.entries.add(new SidebarLine(lang.component(code, "scoreboard.empty"), null));
            }
            for (int index = 0; index < top.size(); index++) {
                LeaderboardService.Entry entry = top.get(index);
                shared.uuids.add(entry.uuid());
                shared.names.add(entry.name());
                shared.entries.add(new SidebarLine(
                        lang.component(code, "scoreboard.entry", Text.p("position", index + 1), Text.p("player", entry.name())),
                        value(code, service, board, entry.value())));
            }
            String title = lang.raw(code, "scoreboard.title");
            shared.titleRaw = title == null ? "<board>" : title;
            shared.title = lang.parse(shared.titleRaw, Text.p("board", service.name(board, code)));
            String footer = lang.raw(code, "scoreboard.footer");
            shared.footerRaw = footer == null || footer.isBlank() ? null : footer;
            if (shared.footerRaw != null) {
                shared.footer = lang.parse(shared.footerRaw, Text.p("player", "Rudyh0"));
            }
            return shared;
        });
    }

    private Component value(String code, LeaderboardService service, Board board, long value) {
        return plugin.lang().component(code, "scoreboard.value", Text.p("value", service.compact(board, value, code)));
    }

    /** Staan er dingen in die per speler anders zijn (%placeholders% of &lt;player&gt;)? */
    private static boolean personal(String raw) {
        return raw.indexOf('%') >= 0 || raw.contains("<player>");
    }

    /** Vult de regels voor deze speler (of null voor een voorbeeld) en geeft de titel terug. */
    Component render(Player player, String code, LeaderboardService service, Board board, List<SidebarLine> lines) {
        LanguageManager lang = plugin.lang();
        Shared shared = shared(code, service, board);
        LeaderboardService.Ranking ranking = service.ranking(board);
        lines.addAll(shared.entries);
        int self = player == null ? -1 : shared.uuids.indexOf(player.getUniqueId());
        if (self >= 0) {
            Component left = shared.selfLeft.computeIfAbsent(self, index -> lang.component(code, "scoreboard.entry-self",
                    Text.p("position", index + 1), Text.p("player", shared.names.get(index))));
            lines.set(self, new SidebarLine(left, shared.entries.get(self).right()));
        }
        if (config().getBoolean("show-own", true)) {
            lines.add(new SidebarLine(Component.empty(), null));
            int position = player == null ? 0 : ranking.position(player.getUniqueId());
            lines.add(shared.ownLines.computeIfAbsent(position, place -> {
                if (place == 0) {
                    return new SidebarLine(lang.component(code, "scoreboard.own-none"), null);
                }
                List<LeaderboardService.Entry> all = ranking.entries();
                return place > all.size() ? new SidebarLine(lang.component(code, "scoreboard.own-none"), null)
                        : new SidebarLine(lang.component(code, "scoreboard.own", Text.p("position", place)),
                        value(code, service, board, all.get(place - 1).value()));
            }));
        }
        if (shared.footerRaw != null) {
            lines.add(new SidebarLine(Component.empty(), null));
            if (player != null && personal(shared.footerRaw)) {
                lines.add(new SidebarLine(lang.parse(Placeholders.apply(player, shared.footerRaw),
                        Text.p("player", player.getName())), null));
            } else {
                lines.add(new SidebarLine(shared.footer, null));
            }
        }
        if (player != null && shared.titleRaw.indexOf('%') >= 0) {
            return lang.parse(Placeholders.apply(player, shared.titleRaw), Text.p("board", service.name(board, code)));
        }
        return shared.title;
    }

    private void remove(Player player) {
        Sidebar sidebar = sidebars.remove(player.getUniqueId());
        if (sidebar != null && player.getScoreboard() == sidebar.board()) {
            player.setScoreboard(plugin.getServer().getScoreboardManager().getMainScoreboard());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sidebars.remove(event.getPlayer().getUniqueId());
        yielded.remove(event.getPlayer().getUniqueId());
    }
}
