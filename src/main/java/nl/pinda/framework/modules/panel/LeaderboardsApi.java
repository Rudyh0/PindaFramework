package nl.pinda.framework.modules.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import nl.pinda.framework.modules.bossbar.BossBarModule;
import nl.pinda.framework.modules.leaderboards.Board;
import nl.pinda.framework.modules.leaderboards.LeaderboardService;
import nl.pinda.framework.modules.leaderboards.LeaderboardsModule;

/** Toplijsten in het paneel. */
final class LeaderboardsApi extends PanelApi {

    LeaderboardsApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/leaderboards", PanelUser.PLAYERS, this::overview);
        server.post("/api/leaderboards/refresh", PanelUser.PLAYERS, this::refresh);
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
                "bossbar", enabled(BossBarModule.class) != null, "canEditBossbar", request.user().has(PanelUser.CONFIG));
    }

    private Object refresh(PanelRequest request) throws Exception {
        LeaderboardService service = service();
        // Niet vaker dan eens per 15 seconden: opnieuw berekenen leest alle spelers uit de database
        if (System.currentTimeMillis() - service.updated() >= 15_000) {
            syncAwait(service::refresh);
        }
        return overview(request);
    }
}
