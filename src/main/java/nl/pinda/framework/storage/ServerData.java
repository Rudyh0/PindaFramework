package nl.pinda.framework.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;

/**
 * Kleine gegevens van de server zelf (geen spelers), zoals een lopende XP-boost of het
 * Discord-bericht dat bijgewerkt wordt. Alles staat in het geheugen en wordt op de
 * achtergrond opgeslagen.
 */
public final class ServerData {

    private final PindaFramework plugin;
    private final Map<String, String> values = new ConcurrentHashMap<>();

    public ServerData(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /** Leest alles in bij het opstarten. */
    public void load() throws SQLException {
        try {
            values.putAll(plugin.database().query(connection -> {
                Map<String, String> loaded = new ConcurrentHashMap<>();
                try (PreparedStatement statement = connection.prepareStatement("SELECT key, value FROM pinda_server_data");
                     ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        loaded.put(result.getString(1), result.getString(2));
                    }
                }
                return loaded;
            }).join());
        } catch (CompletionException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw e;
        }
    }

    public String get(String key) {
        return values.get(key);
    }

    public String get(String key, String fallback) {
        return values.getOrDefault(key, fallback);
    }

    public long getLong(String key, long fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        String value = values.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Slaat een waarde op; null verwijdert hem. */
    public void set(String key, Object value) {
        if (value == null) {
            remove(key);
            return;
        }
        String text = String.valueOf(value);
        values.put(key, text);
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_server_data (key, value) VALUES (?, ?) "
                            + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                statement.setString(1, key);
                statement.setString(2, text);
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kon servergegeven " + key + " niet opslaan", error);
            return null;
        });
    }

    public void remove(String key) {
        values.remove(key);
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pinda_server_data WHERE key = ?")) {
                statement.setString(1, key);
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kon servergegeven " + key + " niet verwijderen", error);
            return null;
        });
    }
}
