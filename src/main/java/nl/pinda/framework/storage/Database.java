package nl.pinda.framework.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;

/**
 * De database van het framework: SQLite (één bestand) of MySQL/MariaDB, zie {@code database.yml}.
 *
 * <p>Alle databasewerk loopt via één eigen thread, zodat de server nooit hoeft te wachten
 * en schrijfacties altijd in de juiste volgorde gebeuren. Modules schrijven SQLite-SQL; bij
 * MySQL wordt die onderweg vertaald ({@link MysqlSql}).
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

    /** Hoe lang een MySQL-verbinding ongebruikt mag zijn voordat we eerst even controleren of hij nog werkt. */
    private static final long CHECK_AFTER_MILLIS = 30_000;

    private final PindaFramework plugin;
    private final ExecutorService executor;
    private Connection connection;
    private Dialect dialect = Dialect.SQLITE;
    private DatabaseSettings settings;
    private String description = "";
    private long lastUsed;

    public Database(PindaFramework plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "PindaFramework-Database");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Bijv. "MySQL pindacraft@127.0.0.1:3306/pindacraft". */
    public String description() {
        return description;
    }

    // ============================================================ verbinden

    /**
     * Maakt verbinding zoals {@code database.yml} zegt, en zet als dat gevraagd is eerst alles
     * van SQLite over naar MySQL. Lukt dat omzetten niet, dan draait de server op SQLite verder
     * (er gaat niets verloren) en wordt het de volgende start opnieuw geprobeerd.
     */
    public void open(DatabaseSettings settings) throws SQLException {
        this.settings = settings;
        File sqlite = new File(plugin.getDataFolder(), settings.sqliteFile());
        DatabaseStatus status = new DatabaseStatus(plugin);
        if (settings.type() == DatabaseSettings.Type.SQLITE) {
            connectSqlite(sqlite);
            status.write(this, null);
            return;
        }
        try {
            connectMysql(settings);
        } catch (SQLException e) {
            status.writeError(settings, e);
            if (settings.convertFromSqlite() && sqlite.exists()) {
                plugin.getLogger().log(Level.SEVERE, "MySQL is niet bereikbaar, dus het omzetten kan niet. De server draait verder op SQLite.", e);
                status.conversionFailed("MySQL niet bereikbaar: " + e.getMessage());
                connectSqlite(sqlite);
                status.write(this, "MySQL niet bereikbaar: " + e.getMessage());
                return;
            }
            throw new SQLException("Kon geen verbinding maken met " + settings.describe() + ": " + e.getMessage(), e);
        }
        if (settings.convertFromSqlite()) {
            if (!sqlite.exists()) {
                plugin.getLogger().info("Omzetten naar MySQL: er is geen " + sqlite.getName() + ", dus er is niets om over te zetten.");
                settings.conversionDone();
            } else {
                try {
                    SqliteConverter.Result result = SqliteConverter.convert(sqlite, TranslatingConnection.unwrap(connection), plugin.getLogger());
                    File backup = SqliteConverter.moveAway(sqlite);
                    settings.conversionDone();
                    status.conversionDone(result, backup);
                    plugin.getLogger().info("Omzetten naar MySQL gelukt: " + result.rows() + " rijen uit " + result.tables()
                            + " tabellen. Het oude bestand staat nog als " + backup.getName() + ".");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Omzetten naar MySQL is mislukt. De server draait verder op SQLite; "
                            + "de volgende start wordt het opnieuw geprobeerd.", e);
                    status.conversionFailed(e.getMessage());
                    closeQuietly(connection);
                    connectSqlite(sqlite);
                    status.write(this, "Omzetten mislukt: " + e.getMessage());
                    return;
                }
            }
        }
        status.write(this, null);
    }

    private void connectSqlite(File file) throws SQLException {
        connection = Migrations.openSqlite(file);
        dialect = Dialect.SQLITE;
        description = "SQLite " + file.getName();
    }

    private void connectMysql(DatabaseSettings settings) throws SQLException {
        Connection raw = openMysql(settings);
        try (Statement statement = raw.createStatement()) {
            // Altijd utf8mb4 en vaste regels, wat de server ook als standaard heeft
            // (o.a. de backslash als escape-teken, waar de vertaling van uitgaat).
            statement.execute("SET NAMES utf8mb4 COLLATE utf8mb4_bin");
            statement.execute("SET SESSION sql_mode = 'STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'");
        } catch (SQLException e) {
            closeQuietly(raw);
            throw e;
        }
        connection = TranslatingConnection.wrap(raw);
        dialect = Dialect.MYSQL;
        description = settings.describe();
        lastUsed = System.currentTimeMillis();
    }

    /**
     * Verbinding met MySQL/MariaDB. Liefst met de MariaDB-driver (zie libraries in plugin.yml),
     * anders met de MySQL-driver die Paper zelf meelevert.
     */
    private static Connection openMysql(DatabaseSettings settings) throws SQLException {
        String address = settings.host() + ":" + settings.port() + "/" + settings.database();
        Properties properties = new Properties();
        properties.setProperty("user", settings.user());
        properties.setProperty("password", settings.password());
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("tcpKeepAlive", "true");
        if (hasDriver("org.mariadb.jdbc.Driver")) {
            // "trust": versleuteld, zonder het certificaat te controleren (hosts gebruiken vaak hun eigen).
            properties.setProperty("sslMode", settings.useSsl() ? "trust" : "disable");
            return DriverManager.getConnection("jdbc:mariadb://" + address, properties);
        }
        if (hasDriver("com.mysql.cj.jdbc.Driver")) {
            properties.setProperty("characterEncoding", "UTF-8");
            properties.setProperty("connectionCollation", "utf8mb4_bin");
            properties.setProperty("sslMode", settings.useSsl() ? "REQUIRED" : "DISABLED");
            properties.setProperty("allowPublicKeyRetrieval", "true");
            properties.setProperty("serverTimezone", "UTC");
            properties.setProperty("rewriteBatchedStatements", "true");
            return DriverManager.getConnection("jdbc:mysql://" + address, properties);
        }
        throw new SQLException("Geen MySQL- of MariaDB-driver gevonden.");
    }

    private static boolean hasDriver(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** MySQL sluit verbindingen die lang niets doen; maak dan een nieuwe. Alleen op de databasethread. */
    private void ensureOpen() throws SQLException {
        if (dialect != Dialect.MYSQL) {
            return;
        }
        long now = System.currentTimeMillis();
        if (connection != null && now - lastUsed < CHECK_AFTER_MILLIS) {
            lastUsed = now;
            return;
        }
        if (connection != null && !connection.isClosed() && connection.isValid(3)) {
            lastUsed = now;
            return;
        }
        plugin.getLogger().info("Verbinding met MySQL opnieuw opbouwen...");
        closeQuietly(connection);
        connectMysql(settings);
    }

    private static void closeQuietly(Connection connection) {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException ignored) {
            // Hij was toch al weg.
        }
    }

    // ============================================================ werk uitvoeren

    /** Voert databasewerk uit op de databasethread en geeft het resultaat terug. */
    public <T> CompletableFuture<T> query(SqlFunction<T> function) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureOpen();
                T result = function.apply(connection);
                lastUsed = System.currentTimeMillis();
                return result;
            } catch (SQLException e) {
                String state = e.getSQLState();
                if (state != null && state.startsWith("08")) {
                    // Verbindingsfout: de volgende keer eerst controleren.
                    lastUsed = 0;
                }
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

    // ============================================================ migraties

    /**
     * Brengt de tabellen van een module naar de nieuwste versie.
     *
     * <p>Elke versie is een lijst SQL-statements (in SQLite-SQL). Nieuwe versies worden alleen
     * toegevoegd, nooit aangepast, zodat bestaande servers netjes worden bijgewerkt.
     */
    public void migrate(String module, List<List<String>> versions) throws SQLException {
        try {
            this.<Void>query(conn -> {
                Migrations.migrate(TranslatingConnection.unwrap(conn), dialect, module, versions, plugin.getLogger());
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
