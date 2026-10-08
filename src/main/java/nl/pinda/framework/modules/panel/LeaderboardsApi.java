package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.modules.leaderboards.Board;
import nl.pinda.framework.modules.leaderboards.LeaderboardService;
import nl.pinda.framework.modules.leaderboards.LeaderboardsModule;
import nl.pinda.framework.modules.scoreboard.ScoreboardModule;
import nl.pinda.framework.modules.scoreboard.SidebarLine;
import org.bukkit.configuration.file.YamlConfiguration;

/** Toplijsten in het paneel, met een voorbeeld en de instellingen van het scoreboard. */
final class LeaderboardsApi extends PanelApi {

    private static final String SCOREBOARD_FILE = "modules/scoreboard.yml";

    private final ConfigEditor editor;

    LeaderboardsApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/leaderboards", PanelUser.PLAYERS, this::overview);
        server.post("/api/leaderboards/refresh", PanelUser.PLAYERS, this::refresh);
        server.post("/api/scoreboard", PanelUser.CONFIG, this::saveScoreboard);
    }

    private LeaderboardService service() throws ApiException {
        return require(LeaderboardsModule.class, "leaderboards").service();
    }

    private Object overview(PanelRequest request) throws Exception {
        LeaderboardService service = service();
        String code = plugin.lang().defaultLanguage();
        List<Map<String, Object>> boards = new ArrayList<>();
        for (Board board : service.boards()) {
            LeaderboardService.Ranking ranking = service.ranking(board);
            List<Map<String, Object>> entries = new ArrayList<>();
            int position = 1;
            for (LeaderboardService.Entry entry : ranking.top(10)) {
                entries.add(map("position", position++, "uuid", entry.uuid().toString(), "name", entry.name(),
                        "value", entry.value(), "text", service.format(board, entry.value(), code)));
            }
            boards.add(map("id", board.id(), "name", service.name(board, code),
                    "description", plugin.lang().raw(code, "top.boards." + board.id() + ".description"),
                    "total", ranking.entries().size(), "entries", entries));
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (Board board : Board.values()) {
            all.add(map("id", board.id(), "name", service.name(board, code), "available", service.available(board)));
        }
        return map("updated", service.updated(), "boards", boards, "allBoards", all,
                "canEdit", request.user().has(PanelUser.CONFIG), "scoreboard", scoreboard(code));
    }

    private Map<String, Object> scoreboard(String code) throws Exception {
        ScoreboardModule scoreboard = enabled(ScoreboardModule.class);
        if (scoreboard == null) {
            return map("enabled", false);
        }
        YamlConfiguration yaml = editor.read(SCOREBOARD_FILE);
        Map<String, Object> settings = map(
                "defaultEnabled", yaml.getBoolean("default-enabled", true),
                "showInSetup", yaml.getBoolean("show-in-setup", true),
                "switchSeconds", yaml.getInt("switch-seconds", 10),
                "boards", yaml.getStringList("boards"),
                "places", yaml.getInt("places", 10),
                "showOwn", yaml.getBoolean("show-own", true),
                "disabledWorlds", yaml.getStringList("disabled-worlds"));
        Map<String, Object> previews = sync(() -> {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Board board : scoreboard.boards()) {
                List<SidebarLine> lines = new ArrayList<>();
                Component title = scoreboard.preview(board, code, lines);
                if (title == null) {
                    continue;
                }
                List<Map<String, Object>> rendered = new ArrayList<>();
                for (SidebarLine line : lines) {
                    rendered.add(map("left", ComponentHtml.render(line.left()),
                            "right", line.right() == null ? null : ComponentHtml.render(line.right())));
                }
                result.put(board.id(), map("title", ComponentHtml.render(title), "lines", rendered));
            }
            return result;
        });
        Board current = scoreboard.current();
        return map("enabled", true, "settings", settings, "previews", previews, "current", current == null ? null : current.id());
    }

    private Object refresh(PanelRequest request) throws Exception {
        LeaderboardService service = service();
        // Niet vaker dan eens per 15 seconden: opnieuw berekenen leest alle spelers uit de database
        if (System.currentTimeMillis() - service.updated() >= 15_000) {
            syncAwait(service::refresh);
        }
        return overview(request);
    }

    private Object saveScoreboard(PanelRequest request) throws Exception {
        require(ScoreboardModule.class, "scoreboard");
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(SCOREBOARD_FILE);
        if (body.has("defaultEnabled")) {
            yaml.set("default-enabled", body.get("defaultEnabled").getAsBoolean());
        }
        if (body.has("showInSetup")) {
            yaml.set("show-in-setup", body.get("showInSetup").getAsBoolean());
        }
        if (body.has("showOwn")) {
            yaml.set("show-own", body.get("showOwn").getAsBoolean());
        }
        if (body.has("switchSeconds")) {
            int seconds = body.get("switchSeconds").getAsInt();
            if (seconds < 3 || seconds > 600) {
                throw ApiException.badRequest("Wisselen kan elke 3 tot 600 seconden.");
            }
            yaml.set("switch-seconds", seconds);
        }
        if (body.has("places")) {
            int places = body.get("places").getAsInt();
            if (places < 1 || places > 10) {
                throw ApiException.badRequest("Het aantal plekken moet tussen 1 en 10 liggen.");
            }
            yaml.set("places", places);
        }
        JsonElement boards = body.get("boards");
        if (boards != null && boards.isJsonArray()) {
            List<String> ids = new ArrayList<>();
            for (JsonElement element : boards.getAsJsonArray()) {
                Board board = Board.find(element.getAsString());
                if (board == null) {
                    throw ApiException.badRequest("Onbekende toplijst: " + element.getAsString());
                }
                if (!ids.contains(board.id())) {
                    ids.add(board.id());
                }
            }
            if (ids.isEmpty()) {
                throw ApiException.badRequest("Kies minimaal één toplijst.");
            }
            yaml.set("boards", ids);
        }
        JsonElement worlds = body.get("disabledWorlds");
        if (worlds != null && worlds.isJsonArray()) {
            List<String> names = new ArrayList<>();
            for (JsonElement element : worlds.getAsJsonArray()) {
                String name = element.getAsString().trim();
                if (!name.isEmpty() && name.length() <= 64) {
                    names.add(name);
                }
            }
            yaml.set("disabled-worlds", names);
        }
        editor.write(SCOREBOARD_FILE, yaml);
        sync(() -> {
            editor.reload(SCOREBOARD_FILE);
            return null;
        });
        module.log().add(request, "scoreboard", null, null);
        return overview(request);
    }
}
