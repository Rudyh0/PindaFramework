package nl.pinda.framework.modules.panel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import nl.pinda.framework.modules.economy.EconomyService;

/** Overzicht van de economie: totalen, de rijkste spelers en de laatste transacties. */
final class EconomyApi extends PanelApi {

    EconomyApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/economy", PanelUser.ECONOMY_VIEW, this::overview);
    }

    private record Rich(String uuid, String name, long cash, long bank) {
    }

    private record Totals(long accounts, long cash, long bank) {
    }

    private Object overview(PanelRequest request) throws Exception {
        EconomyService economy = economy();
        if (economy == null) {
            throw new ApiException(409, "De module 'economy' staat uit.");
        }
        int limit = request.queryInt("limit", 50, 1, 500);
        Totals totals = await(plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(cash), 0), COALESCE(SUM(bank), 0) FROM pinda_economy");
                 ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new Totals(rows.getLong(1), rows.getLong(2), rows.getLong(3));
            }
        }));
        List<Rich> richest = await(plugin.database().query(connection -> {
            List<Rich> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT e.uuid, COALESCE(p.name, e.uuid) AS name, e.cash, e.bank
                    FROM pinda_economy e LEFT JOIN pinda_players p ON p.uuid = e.uuid
                    ORDER BY e.cash + e.bank DESC LIMIT 15""");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new Rich(result.getString("uuid"), result.getString("name"),
                            result.getLong("cash"), result.getLong("bank")));
                }
            }
            return rows;
        }));
        List<Map<String, Object>> top = new ArrayList<>();
        for (Rich rich : richest) {
            top.add(map("uuid", rich.uuid(), "name", rich.name(), "cash", money(rich.cash()),
                    "bank", money(rich.bank()), "total", money(rich.cash() + rich.bank())));
        }
        return map(
                "totals", map("accounts", totals.accounts(), "cash", money(totals.cash()),
                        "bank", money(totals.bank()), "total", money(totals.cash() + totals.bank())),
                "top", top,
                "log", log(this, null, limit));
    }

    /** Transacties uit het economy-log, van één speler of van iedereen (uuid null). */
    static List<Map<String, Object>> log(PanelApi api, UUID uuid, int limit) throws Exception {
        record Entry(long time, String uuid, String name, String type, long amount, Long cash, Long bank, String note) {
        }
        List<Entry> entries = PanelModule.await(api.plugin.database().query(connection -> {
            List<Entry> rows = new ArrayList<>();
            String sql = "SELECT l.time, l.uuid, COALESCE(p.name, l.uuid) AS name, l.type, l.amount, l.cash_after, "
                    + "l.bank_after, l.note FROM pinda_economy_log l LEFT JOIN pinda_players p ON p.uuid = l.uuid "
                    + (uuid == null ? "" : "WHERE l.uuid = ? ") + "ORDER BY l.id DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (uuid != null) {
                    statement.setString(index++, uuid.toString());
                }
                statement.setInt(index, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        long cash = result.getLong("cash_after");
                        boolean noCash = result.wasNull();
                        long bank = result.getLong("bank_after");
                        boolean noBank = result.wasNull();
                        rows.add(new Entry(result.getLong("time"), result.getString("uuid"), result.getString("name"),
                                result.getString("type"), result.getLong("amount"), noCash ? null : cash,
                                noBank ? null : bank, result.getString("note")));
                    }
                }
            }
            return rows;
        }));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : entries) {
            list.add(map("time", entry.time(), "uuid", entry.uuid(), "name", entry.name(), "type", entry.type(),
                    "amount", api.money(entry.amount()),
                    "cash", entry.cash() == null ? null : api.money(entry.cash()),
                    "bank", entry.bank() == null ? null : api.money(entry.bank()),
                    "note", entry.note()));
        }
        return list;
    }
}
