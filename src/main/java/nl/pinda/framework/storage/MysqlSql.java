package nl.pinda.framework.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vertaalt de SQL van het framework (geschreven voor SQLite) naar MySQL/MariaDB.
 *
 * <p>Modules schrijven gewoon SQLite-SQL. Voor MySQL passen we hier de paar verschillen aan:
 * upserts ({@code ON CONFLICT ... DO UPDATE}), {@code INSERT OR IGNORE}, {@code COLLATE NOCASE},
 * hoofdletterongevoelig zoeken met {@code LIKE}, en de kolomtypes in {@code CREATE TABLE}.
 *
 * <p>Tabellen krijgen in MySQL de collatie {@code utf8mb4_bin}: net als in SQLite wordt dan
 * standaard op hoofdletters gelet, behalve waar de SQL zelf {@code COLLATE NOCASE} vraagt.
 */
public final class MysqlSql {

    /** Lengte voor tekstkolommen die in een sleutel of index zitten (uuid's, namen, ...). */
    static final int KEY_LENGTH = 191;
    static final String CASE_INSENSITIVE = "utf8mb4_general_ci";
    static final String TABLE_OPTIONS = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private static final Pattern INSERT_OR_IGNORE = Pattern.compile("(?i)\\bINSERT\\s+OR\\s+IGNORE\\s+INTO\\b");
    private static final Pattern INSERT_OR_REPLACE = Pattern.compile("(?i)\\bINSERT\\s+OR\\s+REPLACE\\s+INTO\\b");
    private static final Pattern INSERT_INTO = Pattern.compile("(?i)\\bINSERT\\s+INTO\\b");
    private static final Pattern DO_NOTHING = Pattern.compile("(?is)\\s*\\bON\\s+CONFLICT\\s*(\\([^)]*\\))?\\s*DO\\s+NOTHING\\b");
    private static final Pattern DO_UPDATE = Pattern.compile("(?is)\\bON\\s+CONFLICT\\s*(\\([^)]*\\))?\\s*DO\\s+UPDATE\\s+SET\\b");
    private static final Pattern EXCLUDED = Pattern.compile("(?i)\\bexcluded\\.(`?)(\\w+)\\1");
    private static final Pattern LIKE_PARAM = Pattern.compile("(?i)\\bLIKE\\s+\\?");
    private static final Pattern ESCAPE_BACKSLASH = Pattern.compile("(?i)\\bESCAPE\\s+'\\\\'");
    private static final Pattern NOCASE = Pattern.compile("(?i)\\s+COLLATE\\s+NOCASE\\b");
    private static final Pattern ORDER_BY = Pattern.compile("(?i)\\bORDER\\s+BY\\b");

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s*\\((.*)\\)\\s*;?\\s*$");
    private static final Pattern CREATE_INDEX = Pattern.compile(
            "(?is)^\\s*CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?(\\w+)`?\\s+ON\\s+`?(\\w+)`?\\s*\\((.*)\\)\\s*;?\\s*$");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+`?(\\w+)`?\\s+ADD\\s+(COLUMN\\s+)?(.*?)\\s*;?\\s*$");
    private static final Pattern TABLE_KEY = Pattern.compile("(?is)^(PRIMARY\\s+KEY|UNIQUE)\\s*\\((.*)\\)\\s*$");

    private MysqlSql() {
    }

    // ============================================================ gewone queries

    /** Vertaalt een gewone query (SELECT, INSERT, UPDATE, DELETE). Wordt onthouden. */
    public static String statement(String sql) {
        return CACHE.computeIfAbsent(sql, MysqlSql::translate);
    }

    static String translate(String sql) {
        String result = sql;
        result = INSERT_OR_IGNORE.matcher(result).replaceAll("INSERT IGNORE INTO");
        result = INSERT_OR_REPLACE.matcher(result).replaceAll("REPLACE INTO");

        Matcher nothing = DO_NOTHING.matcher(result);
        if (nothing.find()) {
            result = nothing.replaceAll("");
            result = INSERT_INTO.matcher(result).replaceFirst("INSERT IGNORE INTO");
        }
        Matcher update = DO_UPDATE.matcher(result);
        if (update.find()) {
            String head = result.substring(0, update.start());
            String tail = result.substring(update.end());
            tail = EXCLUDED.matcher(tail).replaceAll("VALUES(`$2`)");
            result = head + "ON DUPLICATE KEY UPDATE" + tail;
        }

        // Zoeken met LIKE: in SQLite zonder hoofdletters (voor gewone letters), hier ook.
        result = LIKE_PARAM.matcher(result).replaceAll("LIKE ? COLLATE " + CASE_INSENSITIVE);
        // In MySQL is de backslash in een tekst zelf een escape-teken.
        result = ESCAPE_BACKSLASH.matcher(result).replaceAll(Matcher.quoteReplacement("ESCAPE '\\\\'"));

        // COLLATE NOCASE: bij sorteren mag het weg (hooguit een andere volgorde bij gelijke namen),
        // bij vergelijken wordt het de hoofdletterongevoelige collatie van MySQL.
        Matcher order = ORDER_BY.matcher(result);
        if (order.find()) {
            String head = result.substring(0, order.start());
            String tail = result.substring(order.start());
            result = nocase(head) + NOCASE.matcher(tail).replaceAll("");
        } else {
            result = nocase(result);
        }
        return result;
    }

    private static String nocase(String sql) {
        return NOCASE.matcher(sql).replaceAll(" COLLATE " + CASE_INSENSITIVE);
    }

    // ============================================================ migraties

    /**
     * Vertaalt één versie van een migratie. Tekstkolommen die in deze versie een sleutel of index
     * krijgen, worden VARCHAR; voor latere indexen op een lange tekstkolom gebruiken we een prefix.
     */
    static List<String> migration(List<String> statements, Connection connection) throws SQLException {
        Map<String, Set<String>> indexed = new HashMap<>();
        for (String sql : statements) {
            Matcher index = CREATE_INDEX.matcher(sql);
            if (index.matches()) {
                Set<String> columns = indexed.computeIfAbsent(index.group(4).toLowerCase(Locale.ROOT), table -> new HashSet<>());
                for (String part : splitTopLevel(index.group(5))) {
                    columns.add(firstWord(part).toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, Map<String, String>> created = new HashMap<>();
        List<String> result = new ArrayList<>();
        for (String sql : statements) {
            Matcher table = CREATE_TABLE.matcher(sql);
            Matcher index = CREATE_INDEX.matcher(sql);
            Matcher column = ADD_COLUMN.matcher(sql);
            if (table.matches()) {
                String name = table.group(2);
                Map<String, String> types = new HashMap<>();
                result.add(createTable(table.group(1) != null, name, table.group(3),
                        indexed.getOrDefault(name.toLowerCase(Locale.ROOT), Set.of()), types));
                created.put(name.toLowerCase(Locale.ROOT), types);
            } else if (index.matches()) {
                // Bestaat hij al (een half gelukte migratie die opnieuw draait), dan overslaan.
                // "IF NOT EXISTS" kent alleen MariaDB, niet MySQL 8.
                if (connection == null || !exists(connection, "STATISTICS", "INDEX_NAME", index.group(4), index.group(3))) {
                    result.add(createIndex(index, created, connection));
                }
            } else if (column.matches()) {
                String definition = columnDefinition(column.group(3), false, null);
                String name = unquote(firstWord(column.group(3)));
                if (connection == null) {
                    result.add("ALTER TABLE " + quote(column.group(1)) + " ADD COLUMN IF NOT EXISTS " + definition);
                } else if (!exists(connection, "COLUMNS", "COLUMN_NAME", column.group(1), name)) {
                    result.add("ALTER TABLE " + quote(column.group(1)) + " ADD COLUMN " + definition);
                }
            } else {
                result.add(statement(sql));
            }
        }
        return result;
    }

    private static String createTable(boolean ifNotExists, String name, String body, Set<String> indexed, Map<String, String> types) {
        List<String> parts = splitTopLevel(body);
        Set<String> keys = new HashSet<>(indexed);
        for (String part : parts) {
            Matcher key = TABLE_KEY.matcher(part.trim());
            if (key.matches()) {
                for (String column : splitTopLevel(key.group(2))) {
                    keys.add(firstWord(column).toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> definitions = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            Matcher key = TABLE_KEY.matcher(trimmed);
            if (key.matches()) {
                List<String> columns = new ArrayList<>();
                for (String column : splitTopLevel(key.group(2))) {
                    columns.add(quote(firstWord(column)));
                }
                definitions.add(key.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ") + " (" + String.join(", ", columns) + ")");
            } else {
                String column = firstWord(trimmed).toLowerCase(Locale.ROOT);
                boolean isKey = keys.contains(column) || trimmed.toUpperCase(Locale.ROOT).matches("(?s).*\\b(PRIMARY\\s+KEY|UNIQUE)\\b.*");
                definitions.add(columnDefinition(trimmed, isKey, types));
            }
        }
        return "CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "") + quote(name) + " (" + String.join(", ", definitions) + ")" + TABLE_OPTIONS;
    }

    /** "name TEXT NOT NULL" -> "`name` VARCHAR(191) NOT NULL" (of MEDIUMTEXT als het geen sleutel is). */
    private static String columnDefinition(String definition, boolean key, Map<String, String> types) {
        String trimmed = definition.trim();
        String name = firstWord(trimmed);
        String rest = trimmed.substring(name.length()).trim();
        String type = firstWord(rest);
        String after = rest.substring(type.length());
        String upper = type.toUpperCase(Locale.ROOT);
        String mapped = switch (upper) {
            case "TEXT", "VARCHAR", "CHAR", "STRING" -> key ? "VARCHAR(" + KEY_LENGTH + ")" : "MEDIUMTEXT";
            case "INTEGER", "INT", "BIGINT" -> "BIGINT";
            case "REAL", "DOUBLE", "FLOAT" -> "DOUBLE";
            case "BLOB" -> "LONGBLOB";
            case "BOOLEAN" -> "TINYINT";
            default -> null;
        };
        if (mapped == null) {
            mapped = type;
        } else if (after.startsWith("(")) {
            // Een lengte zoals VARCHAR(64): bij de types die we zelf kiezen, bepalen we die ook zelf.
            int close = after.indexOf(')');
            after = close > 0 ? after.substring(close + 1) : after;
        }
        after = after.replaceAll("(?i)\\bAUTOINCREMENT\\b", "AUTO_INCREMENT");
        after = NOCASE.matcher(after).replaceAll("");
        if (types != null) {
            types.put(unquote(name).toLowerCase(Locale.ROOT), mapped);
        }
        return quote(name) + " " + mapped + after;
    }

    private static String createIndex(Matcher index, Map<String, Map<String, String>> created, Connection connection) throws SQLException {
        String table = index.group(4);
        List<String> columns = new ArrayList<>();
        for (String part : splitTopLevel(index.group(5))) {
            String column = firstWord(part.trim());
            String type = columnType(table, unquote(column), created, connection);
            boolean text = type != null && (type.contains("TEXT") || type.contains("BLOB"));
            String order = part.trim().substring(column.length()).replaceAll("(?i)\\s*COLLATE\\s+NOCASE", "").trim();
            columns.add(quote(column) + (text ? "(" + KEY_LENGTH + ")" : "") + (order.isEmpty() ? "" : " " + order));
        }
        // Zonder verbinding (alleen bij testen) met IF NOT EXISTS; anders is al gecontroleerd of hij bestaat.
        return "CREATE " + (index.group(1) != null ? "UNIQUE " : "") + "INDEX " + (connection == null && index.group(2) != null ? "IF NOT EXISTS " : "")
                + quote(index.group(3)) + " ON " + quote(table) + " (" + String.join(", ", columns) + ")";
    }

    /** Bestaat deze index of kolom al in de database? */
    private static boolean exists(Connection connection, String view, String column, String table, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema." + view
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND " + column + " = ?")) {
            statement.setString(1, table);
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) > 0;
            }
        }
    }

    /** Het type van een kolom: uit deze migratie, of uit de database zelf. */
    private static String columnType(String table, String column, Map<String, Map<String, String>> created, Connection connection) throws SQLException {
        Map<String, String> types = created.get(table.toLowerCase(Locale.ROOT));
        if (types != null && types.containsKey(column.toLowerCase(Locale.ROOT))) {
            return types.get(column.toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT);
        }
        if (connection == null) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1).toUpperCase(Locale.ROOT) : null;
            }
        }
    }

    // ============================================================ hulpjes

    /** Splitst op komma's die niet tussen haakjes of aanhalingstekens staan. */
    static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = text.substring(start).trim();
        if (!last.isEmpty()) {
            parts.add(last);
        }
        return parts;
    }

    private static String firstWord(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("`")) {
            int end = trimmed.indexOf('`', 1);
            return end > 0 ? trimmed.substring(0, end + 1) : trimmed;
        }
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end)) && trimmed.charAt(end) != '(') {
            end++;
        }
        return trimmed.substring(0, end);
    }

    private static String unquote(String name) {
        return name.startsWith("`") && name.endsWith("`") && name.length() > 1 ? name.substring(1, name.length() - 1) : name;
    }

    /** Zet backticks om een naam, zodat ook namen als "key" werken. */
    static String quote(String name) {
        return "`" + unquote(name).replace("`", "``") + "`";
    }
}
