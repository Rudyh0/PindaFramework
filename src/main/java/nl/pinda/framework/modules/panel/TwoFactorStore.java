package nl.pinda.framework.modules.panel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import nl.pinda.framework.PindaFramework;

/** Bewaart per speler het 2FA-geheim en de laatst gebruikte code. */
final class TwoFactorStore {

    /** Een gekoppelde authenticator. */
    record Entry(String secret, long created, long lastStep) {
    }

    private final PindaFramework plugin;

    TwoFactorStore(PindaFramework plugin) {
        this.plugin = plugin;
    }

    CompletableFuture<Entry> get(UUID uuid) {
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT secret, created, last_step FROM pinda_panel_2fa WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? new Entry(result.getString(1), result.getLong(2), result.getLong(3)) : null;
                }
            }
        });
    }

    CompletableFuture<Void> save(UUID uuid, String secret, long step) {
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_panel_2fa (uuid, secret, created, last_step) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET secret = excluded.secret, created = excluded.created, "
                            + "last_step = excluded.last_step")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, secret);
                statement.setLong(3, System.currentTimeMillis());
                statement.setLong(4, step);
                statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> used(UUID uuid, long step) {
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pinda_panel_2fa SET last_step = ? WHERE uuid = ? AND last_step < ?")) {
                statement.setLong(1, step);
                statement.setString(2, uuid.toString());
                statement.setLong(3, step);
                statement.executeUpdate();
            }
        });
    }

    /** Ontkoppelt de authenticator. Geeft true als er een was. */
    CompletableFuture<Boolean> remove(UUID uuid) {
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pinda_panel_2fa WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                return statement.executeUpdate() > 0;
            }
        });
    }
}
