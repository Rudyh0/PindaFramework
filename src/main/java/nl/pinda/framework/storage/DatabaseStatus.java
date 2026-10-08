package nl.pinda.framework.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;

/**
 * Laat het dev-paneel zien hoe het met de database gaat: {@code database-status.json} (elke start)
 * en {@code database-conversion.json} (het laatste omzetten naar MySQL).
 */
final class DatabaseStatus {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private final PindaFramework plugin;

    DatabaseStatus(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /** De verbinding staat (eventueel met een waarschuwing, zoals "omzetten mislukt"). */
    void write(Database database, String warning) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("type", database.dialect() == Dialect.MYSQL ? "mysql" : "sqlite");
        status.put("target", database.description());
        status.put("ok", true);
        status.put("warning", warning);
        status.put("error", null);
        status.put("plugin", plugin.version());
        status.put("updated", System.currentTimeMillis());
        save("database-status.json", status);
    }

    /** Er kon geen verbinding gemaakt worden. */
    void writeError(DatabaseSettings settings, Exception error) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("type", settings.type() == DatabaseSettings.Type.MYSQL ? "mysql" : "sqlite");
        status.put("target", settings.describe());
        status.put("ok", false);
        status.put("warning", null);
        status.put("error", error.getMessage());
        status.put("plugin", plugin.version());
        status.put("updated", System.currentTimeMillis());
        save("database-status.json", status);
    }

    void conversionDone(SqliteConverter.Result result, File backup) {
        Map<String, Object> conversion = new LinkedHashMap<>();
        conversion.put("status", "done");
        conversion.put("at", System.currentTimeMillis());
        conversion.put("tables", result.tables());
        conversion.put("rows", result.rows());
        conversion.put("perTable", result.perTable());
        conversion.put("backup", backup.getName());
        conversion.put("error", null);
        save("database-conversion.json", conversion);
    }

    void conversionFailed(String error) {
        Map<String, Object> conversion = new LinkedHashMap<>();
        conversion.put("status", "failed");
        conversion.put("at", System.currentTimeMillis());
        conversion.put("error", error);
        save("database-conversion.json", conversion);
    }

    private void save(String name, Map<String, Object> data) {
        File file = new File(plugin.getDataFolder(), name);
        File temp = new File(plugin.getDataFolder(), name + ".tmp");
        try {
            Files.writeString(temp.toPath(), GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Kon " + name + " niet schrijven", e);
        }
    }
}
