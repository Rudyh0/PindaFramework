package nl.pinda.framework.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Zet alle gegevens van SQLite ({@code data.db}) over naar MySQL.
 *
 * <p>Eerst krijgen beide databases alle tabellen in de nieuwste versie (van de kern en alle
 * modules, ook die uit staan). Daarna wordt elke tabel gekopieerd en geteld of alles er is.
 */
final class SqliteConverter {

    /** Hoeveel tabellen en rijen er zijn overgezet. */
    record Result(int tables, long rows, Map<String, Long> perTable) {
    }

    private static final int BATCH = 500;
    /** Zolang dit in pinda_migrations staat, loopt er een omzetting (of is er een mislukt). */
    private static final String MARKER = "sqlite-conversion";

    private SqliteConverter() {
    }

    static Result convert(File sqliteFile, Connection mysql, Logger logger) throws SQLException {
        logger.info("Alles van " + sqliteFile.getName() + " overzetten naar MySQL...");
        try (Connection sqlite = Migrations.openSqlite(sqliteFile)) {
            Migrations.migrateAll(sqlite, Dialect.SQLITE, logger);
            Migrations.migrateAll(mysql, Dialect.MYSQL, logger);
            guardTarget(mysql);

            List<String> tables = new ArrayList<>();
            try (Statement statement = sqlite.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'pinda\\_%' ESCAPE '\\' ORDER BY name")) {
                while (result.next()) {
                    String name = result.getString(1);
                    if (!name.equals("pinda_migrations")) {
                        tables.add(name);
                    }
                }
            }

            Map<String, Long> perTable = new LinkedHashMap<>();
            long total = 0;
            for (String table : tables) {
                Map<String, String> target = mysqlColumns(mysql, table);
                if (target.isEmpty()) {
                    logger.warning("Tabel " + table + " bestaat niet in MySQL (van een oude of andere plugin?) en wordt overgeslagen.");
                    continue;
                }
                List<String> columns = new ArrayList<>();
                for (String column : sqliteColumns(sqlite, table)) {
                    String match = target.get(column.toLowerCase(Locale.ROOT));
                    if (match != null) {
                        columns.add(match);
                    }
                }
                long copied = copy(sqlite, mysql, table, columns);
                long expected = count(sqlite, table, false);
                long actual = count(mysql, table, true);
                if (expected != actual) {
                    throw new SQLException("Tabel " + table + ": " + expected + " rijen in SQLite maar " + actual + " in MySQL.");
                }
                perTable.put(table, copied);
                total += copied;
                logger.info("  " + table + ": " + copied + " rijen");
            }
            try (PreparedStatement done = mysql.prepareStatement("DELETE FROM pinda_migrations WHERE module = ?")) {
                done.setString(1, MARKER);
                done.executeUpdate();
            }
            return new Result(perTable.size(), total, perTable);
        }
    }

    /**
     * Staan er al gegevens van PindaFramework in MySQL, dan zetten we niets over: dat zou ze
     * overschrijven. Alleen een eerdere, mislukte omzetting (te zien aan de markering) mag
     * opnieuw beginnen.
     */
    private static void guardTarget(Connection mysql) throws SQLException {
        try (PreparedStatement marker = mysql.prepareStatement("SELECT version FROM pinda_migrations WHERE module = ?")) {
            marker.setString(1, MARKER);
            try (ResultSet result = marker.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }
        List<String> tables = new ArrayList<>();
        try (Statement statement = mysql.createStatement();
             ResultSet result = statement.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                     + "AND TABLE_NAME LIKE 'pinda\\_%' AND TABLE_NAME <> 'pinda_migrations'")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        for (String table : tables) {
            try (Statement statement = mysql.createStatement();
                 ResultSet result = statement.executeQuery("SELECT 1 FROM " + MysqlSql.quote(table) + " LIMIT 1")) {
                if (result.next()) {
                    throw new SQLException("De MySQL-database bevat al gegevens van PindaFramework (tabel " + table + "). "
                            + "Omzetten zou die overschrijven. Gebruik een lege database, of zet convert-from-sqlite op false.");
                }
            }
        }
        try (PreparedStatement marker = mysql.prepareStatement("INSERT INTO pinda_migrations (module, version) VALUES (?, 0)")) {
            marker.setString(1, MARKER);
            marker.executeUpdate();
        }
    }

    private static long copy(Connection sqlite, Connection mysql, String table, List<String> columns) throws SQLException {
        List<String> source = new ArrayList<>();
        List<String> target = new ArrayList<>();
        List<String> marks = new ArrayList<>();
        for (String column : columns) {
            source.add("\"" + column.replace("\"", "\"\"") + "\"");
            target.add(MysqlSql.quote(column));
            marks.add("?");
        }
        String insert = "INSERT INTO " + MysqlSql.quote(table) + " (" + String.join(", ", target) + ") VALUES ("
                + String.join(", ", marks) + ")";
        long copied = 0;
        boolean autoCommit = mysql.getAutoCommit();
        mysql.setAutoCommit(false);
        try (Statement clear = mysql.createStatement()) {
            // Een eerdere, mislukte poging kan al rijen hebben neergezet.
            clear.executeUpdate("DELETE FROM " + MysqlSql.quote(table));
            try (Statement select = sqlite.createStatement();
                 ResultSet rows = select.executeQuery("SELECT " + String.join(", ", source) + " FROM \"" + table + "\"");
                 PreparedStatement statement = mysql.prepareStatement(insert)) {
                int pending = 0;
                while (rows.next()) {
                    for (int i = 1; i <= columns.size(); i++) {
                        statement.setObject(i, rows.getObject(i));
                    }
                    statement.addBatch();
                    copied++;
                    if (++pending >= BATCH) {
                        statement.executeBatch();
                        pending = 0;
                    }
                }
                if (pending > 0) {
                    statement.executeBatch();
                }
            }
            mysql.commit();
        } catch (SQLException | RuntimeException e) {
            mysql.rollback();
            throw e;
        } finally {
            mysql.setAutoCommit(autoCommit);
        }
        return copied;
    }

    private static List<String> sqliteColumns(Connection sqlite, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = sqlite.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        return columns;
    }

    /** De kolommen in MySQL, op naam in kleine letters. */
    private static Map<String, String> mysqlColumns(Connection mysql, String table) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (PreparedStatement statement = mysql.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    columns.put(result.getString(1).toLowerCase(Locale.ROOT), result.getString(1));
                }
            }
        }
        return columns;
    }

    private static long count(Connection connection, String table, boolean mysql) throws SQLException {
        String name = mysql ? MysqlSql.quote(table) : "\"" + table + "\"";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + name)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }

    /**
     * Zet het SQLite-bestand opzij als backup (met de -wal en -shm erbij), zodat het niet nog
     * eens wordt gebruikt.
     */
    static File moveAway(File sqliteFile) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File backup = new File(sqliteFile.getParentFile(), sqliteFile.getName() + ".omgezet-" + stamp);
        Files.move(sqliteFile.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE);
        for (String suffix : new String[]{"-wal", "-shm"}) {
            File extra = new File(sqliteFile.getPath() + suffix);
            if (extra.exists()) {
                Files.move(extra.toPath(), new File(backup.getPath() + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return backup;
    }
}
