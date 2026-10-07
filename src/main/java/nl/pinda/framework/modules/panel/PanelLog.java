package nl.pinda.framework.modules.panel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;

/** Het logboek van het paneel: wie heeft wat gedaan, wanneer en vanaf welk IP. */
final class PanelLog {

    private final PindaFramework plugin;

    PanelLog(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /** Schrijft een actie in het logboek en in de console. */
    void add(PanelRequest request, String action, String target, String details) {
        PanelUser user = request.user();
        String name = user == null ? "?" : user.name();
        String uuid = user == null ? null : user.uuid().toString();
        String ip = request.ip();
        long time = System.currentTimeMillis();
        plugin.getLogger().info("[Paneel] " + name + ": " + action
                + (target == null ? "" : " " + target) + (details == null ? "" : " (" + details + ")"));
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_panel_log (time, uuid, name, action, target, details, ip) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setLong(1, time);
                statement.setString(2, uuid);
                statement.setString(3, name);
                statement.setString(4, action);
                statement.setString(5, target);
                statement.setString(6, details);
                statement.setString(7, ip);
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kon paneelactie niet loggen", error);
            return null;
        });
    }

    /** De nieuwste regels uit het logboek. */
    CompletableFuture<List<Map<String, Object>>> recent(int limit, int offset) {
        return plugin.database().query(connection -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pinda_panel_log ORDER BY id DESC LIMIT ? OFFSET ?")) {
                statement.setInt(1, limit);
                statement.setInt(2, offset);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("time", result.getLong("time"));
                        row.put("uuid", result.getString("uuid"));
                        row.put("name", result.getString("name"));
                        row.put("action", result.getString("action"));
                        row.put("target", result.getString("target"));
                        row.put("details", result.getString("details"));
                        row.put("ip", result.getString("ip"));
                        rows.add(row);
                    }
                }
            }
            return rows;
        });
    }
}
