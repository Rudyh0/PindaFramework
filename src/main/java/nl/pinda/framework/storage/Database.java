package nl.pinda.framework.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;

/**
 * De SQLite-database van het framework.
 *
 * <p>Alle databasewerk loopt via één eigen thread, zodat de server nooit hoeft te wachten
 * en schrijfacties altijd in de juiste volgorde gebeuren.
 */
public final class Database {

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    private final PindaFramework plugin;
    private final ExecutorService executor;
    private Connection connection;

    public Database(PindaFramework plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PindaFramework-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void connect(String fileName) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite-driver niet gevonden", e);
        }
        File file = new File(plugin.getDataFolder(), fileName);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Kon map niet aanmaken: " + parent.getPath());
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    /** Voert databasewerk uit op de databasethread en geeft het resultaat terug. */
    public <T> CompletableFuture<T> query(SqlFunction<T> function) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return function.apply(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /** Voert databasewerk uit op de databasethread zonder resultaat. */
    public CompletableFuture<Void> execute(SqlConsumer consumer) {
        return this.<Void>query(connection -> {
            consumer.accept(connection);
            return null;
        });
    }

    /** Voert alles in één transactie uit: alles slaagt, of er verandert niets. */
    public static void transaction(Connection connection, SqlConsumer body) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            body.accept(connection);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * Brengt de tabellen van een module naar de nieuwste versie.
     *
     * <p>Elke versie is een lijst SQL-statements. Nieuwe versies worden alleen toegevoegd,
     * nooit aangepast, zodat bestaande servers netjes worden bijgewerkt.
     */
    public void migrate(String module, List<List<String>> versions) throws SQLException {
        try {
            this.<Void>query(conn -> {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS pinda_migrations ("
                            + "module TEXT PRIMARY KEY, version INTEGER NOT NULL)");
                }
                int current = 0;
                try (PreparedStatement select = conn.prepareStatement(
                        "SELECT version FROM pinda_migrations WHERE module = ?")) {
                    select.setString(1, module);
                    try (ResultSet result = select.executeQuery()) {
                        if (result.next()) {
                            current = result.getInt(1);
                        }
                    }
                }
                for (int index = current; index < versions.size(); index++) {
                    final List<String> statements = versions.get(index);
                    final int target = index + 1;
                    transaction(conn, tx -> {
                        try (Statement statement = tx.createStatement()) {
                            for (String sql : statements) {
                                statement.execute(sql);
                            }
                        }
                        try (PreparedStatement update = tx.prepareStatement(
                                "INSERT INTO pinda_migrations (module, version) VALUES (?, ?) "
                                        + "ON CONFLICT(module) DO UPDATE SET version = excluded.version")) {
                            update.setString(1, module);
                            update.setInt(2, target);
                            update.executeUpdate();
                        }
                    });
                    plugin.getLogger().info("Database bijgewerkt: " + module + " -> versie " + target);
                }
                return null;
            }).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof SQLException sql) {
                throw sql;
            }
            throw e;
        }
    }

    /** Wacht tot al het openstaande databasewerk klaar is en sluit de verbinding. */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Niet al het databasewerk was binnen 15 seconden klaar.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Kon de database niet netjes sluiten", e);
        }
    }
}
