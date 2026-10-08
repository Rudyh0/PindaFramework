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

    private void tick() {
        LeaderboardService service = leaderboards();
        Board board = current();
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
            if (sidebar == null) {
                Scoreboard scoreboard = plugin.getServer().getScoreboardManager().getNewScoreboard();
                sidebar = new Sidebar(scoreboard, title);
                sidebars.put(uuid, sidebar);
                player.setScoreboard(scoreboard);
            }
            sidebar.update(title, lines);
        }
    }

    /** Hoe het scoreboard er nu uitziet voor een toeschouwer zonder plek (voor het paneel). Null als er niets te tonen is. */
    public Component preview(Board board, String code, List<SidebarLine> lines) {
        LeaderboardService service = leaderboards();
        return service == null || board == null ? null : render(null, code, service, board, lines);
    }

    /** Vult de regels voor deze speler (of null voor een voorbeeld) en geeft de titel terug. */
    Component render(Player player, String code, LeaderboardService service, Board board, List<SidebarLine> lines) {
        LanguageManager lang = plugin.lang();
        String boardName = service.name(board, code);
        LeaderboardService.Ranking ranking = service.ranking(board);
        List<LeaderboardService.Entry> top = ranking.top(places());

        if (top.isEmpty()) {
            lines.add(new SidebarLine(lang.component(code, "scoreboard.empty"), null));
        }
        for (int index = 0; index < top.size(); index++) {
            LeaderboardService.Entry entry = top.get(index);
            boolean self = player != null && entry.uuid().equals(player.getUniqueId());
            lines.add(new SidebarLine(
                    lang.component(code, self ? "scoreboard.entry-self" : "scoreboard.entry",
                            Text.p("position", index + 1), Text.p("player", entry.name())),
                    lang.component(code, "scoreboard.value", Text.p("value", service.compact(board, entry.value(), code)))));
        }
        if (config().getBoolean("show-own", true)) {
            lines.add(new SidebarLine(Component.empty(), null));
            LeaderboardService.Entry own = player == null ? null : ranking.entry(player.getUniqueId());
            lines.add(own == null
                    ? new SidebarLine(lang.component(code, "scoreboard.own-none"), null)
                    : new SidebarLine(lang.component(code, "scoreboard.own", Text.p("position", ranking.position(player.getUniqueId()))),
                    lang.component(code, "scoreboard.value", Text.p("value", service.compact(board, own.value(), code)))));
        }
        String footer = lang.raw(code, "scoreboard.footer");
        if (footer != null && !footer.isBlank()) {
            lines.add(new SidebarLine(Component.empty(), null));
            String text = player == null ? footer : Placeholders.apply(player, footer);
            lines.add(new SidebarLine(lang.parse(text, Text.p("player", player == null ? "Rudyh0" : player.getName())), null));
        }
        String title = lang.raw(code, "scoreboard.title");
        title = title == null ? "<board>" : title;
        return lang.parse(player == null ? title : Placeholders.apply(player, title), Text.p("board", boardName));
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
