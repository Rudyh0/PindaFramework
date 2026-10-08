package nl.pinda.framework.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Tabellen aanmaken en bijwerken, los van de rest van de plugin (ook gebruikt bij het omzetten). */
final class Migrations {

    private Migrations() {
    }

    @FunctionalInterface
    private interface SqlBody {
        void accept(Connection connection) throws SQLException;
    }

    private static void inTransaction(Connection connection, SqlBody body) throws SQLException {
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

    /** Opent een SQLite-bestand met de vaste instellingen van het framework. */
    static Connection openSqlite(File file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite-driver niet gevonden", e);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Kon map niet aanmaken: " + parent.getPath());
        }
        Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement statement = sqlite.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return sqlite;
    }

    /** Migreert op een losse verbinding (ook gebruikt bij het omzetten). */
    static void migrate(Connection conn, Dialect dialect, String module, List<List<String>> versions, Logger logger) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute(dialect == Dialect.MYSQL
                    ? "CREATE TABLE IF NOT EXISTS pinda_migrations (module VARCHAR(" + MysqlSql.KEY_LENGTH + ") PRIMARY KEY, "
                    + "version INT NOT NULL)" + MysqlSql.TABLE_OPTIONS
                    : "CREATE TABLE IF NOT EXISTS pinda_migrations (module TEXT PRIMARY KEY, version INTEGER NOT NULL)");
        }
        int current = 0;
        try (PreparedStatement select = conn.prepareStatement("SELECT version FROM pinda_migrations WHERE module = ?")) {
            select.setString(1, module);
            try (ResultSet result = select.executeQuery()) {
                if (result.next()) {
                    current = result.getInt(1);
                }
            }
        }
        for (int index = current; index < versions.size(); index++) {
            List<String> statements = dialect == Dialect.MYSQL ? MysqlSql.migration(versions.get(index), conn) : versions.get(index);
            int target = index + 1;
            // MySQL kan geen tabellen aanmaken binnen een transactie; daar gaat het statement voor statement.
            SqlBody body = tx -> {
                try (Statement statement = tx.createStatement()) {
                    for (String sql : statements) {
                        statement.execute(sql);
                    }
                }
                try (PreparedStatement update = tx.prepareStatement(dialect == Dialect.MYSQL
                        ? "INSERT INTO pinda_migrations (module, version) VALUES (?, ?) ON DUPLICATE KEY UPDATE version = VALUES(version)"
                        : "INSERT INTO pinda_migrations (module, version) VALUES (?, ?) "
                        + "ON CONFLICT(module) DO UPDATE SET version = excluded.version")) {
                    update.setString(1, module);
                    update.setInt(2, target);
                    update.executeUpdate();
                }
            };
            if (dialect == Dialect.MYSQL) {
                body.accept(conn);
            } else {
                inTransaction(conn, body);
            }
            logger.info("Database bijgewerkt: " + module + " -> versie " + target);
        }
    }

    /** Alle migraties in één keer: van de kern en van alle modules (ook als ze uit staan). */
    static void migrateAll(Connection conn, Dialect dialect, Logger logger) throws SQLException {
        for (Map.Entry<String, List<List<String>>> schema : Schemas.all().entrySet()) {
            migrate(conn, dialect, schema.getKey(), schema.getValue(), logger);
        }
    }

}
