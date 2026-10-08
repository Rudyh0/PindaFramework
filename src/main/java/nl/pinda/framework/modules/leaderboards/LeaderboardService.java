package nl.pinda.framework.modules.leaderboards;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.economy.Account;
import nl.pinda.framework.modules.economy.EconomyModule;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.skills.SkillService;
import nl.pinda.framework.modules.skills.SkillsModule;
import nl.pinda.framework.modules.timber.TimberModule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Rekent de toplijsten uit en bewaart ze in het geheugen. Het scoreboard, /top, de placeholders
 * en het paneel lezen alleen uit dat geheugen, dus dat is altijd snel.
 */
public final class LeaderboardService {

    /** Eén regel op een toplijst. De waarde is in centen (geld), levels (skills), seconden (speeltijd) of een aantal. */
    public record Entry(UUID uuid, String name, long value) {
    }

    /** Een hele toplijst, met de plek en waarde van iedereen erop. */
    public record Ranking(Board board, List<Entry> entries, Map<UUID, Integer> positions) {

        static Ranking of(Board board, List<Entry> entries) {
            Map<UUID, Integer> positions = new HashMap<>();
            for (int index = 0; index < entries.size(); index++) {
                positions.put(entries.get(index).uuid(), index + 1);
            }
            return new Ranking(board, List.copyOf(entries), Map.copyOf(positions));
        }

        /** De plek van een speler (1 = bovenaan), of 0 als hij er niet op staat. */
        public int position(UUID uuid) {
            return positions.getOrDefault(uuid, 0);
        }

        /** De regel van een speler, of null. */
        public Entry entry(UUID uuid) {
            int position = position(uuid);
            return position == 0 ? null : entries.get(position - 1);
        }

        public List<Entry> top(int limit) {
            return entries.size() <= limit ? entries : entries.subList(0, limit);
        }
    }

    private final PindaFramework plugin;
    private final LeaderboardsModule module;
    private final StatsTracker tracker;
    private volatile Map<Board, Ranking> rankings = Map.of();
    private volatile long updated;
    private CompletableFuture<Void> running;

    LeaderboardService(PindaFramework plugin, LeaderboardsModule module, StatsTracker tracker) {
        this.plugin = plugin;
        this.module = module;
        this.tracker = tracker;
    }

    public StatsTracker stats() {
        return tracker;
    }

    // ============================================================ welke lijsten

    /** De toplijsten die aan staan en kunnen (geld alleen met economy, skills alleen met skills), in volgorde uit de config. */
    public List<Board> boards() {
        List<Board> boards = new ArrayList<>();
        for (String id : config().getStringList("boards")) {
            Board board = Board.find(id);
            if (board != null && !boards.contains(board) && available(board)) {
                boards.add(board);
            }
        }
        return boards;
    }

    public boolean available(Board board) {
        return switch (board) {
            case MONEY -> economy() != null;
            case SKILLS -> skills() != null;
            case TREES -> {
                PindaModule timber = plugin.modules().get(TimberModule.class);
                yield timber != null && timber.isEnabled();
            }
            default -> true;
        };
    }

    /** De toplijst zoals hij het laatst berekend is (leeg als hij nog niet klaar is). */
    public Ranking ranking(Board board) {
        Ranking ranking = rankings.get(board);
        return ranking != null ? ranking : new Ranking(board, List.of(), Map.of());
    }

    /** Wanneer de toplijsten voor het laatst zijn berekend (0 = nog nooit). */
    public long updated() {
        return updated;
    }

    // ============================================================ berekenen

    /** Rekent alle toplijsten opnieuw uit. Aanroepen op de hoofdthread. */
    public CompletableFuture<Void> refresh() {
        if (running != null && !running.isDone()) {
            return running;
        }
        List<Board> boards = boards();
        Set<String> hidden = new HashSet<>();
        for (String name : config().getStringList("hidden-players")) {
            hidden.add(name.trim().toLowerCase(Locale.ROOT));
        }

        // Live saldo's en namen van online spelers: die zijn nieuwer dan de database.
        Map<UUID, Long> liveMoney = new HashMap<>();
        Map<UUID, String> onlineNames = new HashMap<>();
        EconomyService economy = economy();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            onlineNames.put(player.getUniqueId(), player.getName());
            Account account = economy == null ? null : economy.cached(player.getUniqueId());
            if (account != null) {
                liveMoney.put(player.getUniqueId(), account.total());
            }
        }
        SkillService skills = skills();
        CompletableFuture<List<SkillService.TopEntry>> skillTop = boards.contains(Board.SKILLS) && skills != null
                ? skills.top(null, Integer.MAX_VALUE)
                : CompletableFuture.completedFuture(List.of());

        running = tracker.snapshotOnline()
                .thenCombine(skillTop, (ignored, top) -> top)
                .thenCompose(top -> plugin.database().query(connection -> {
                    Map<Board, Ranking> result = new EnumMap<>(Board.class);
                    for (Board board : boards) {
                        List<Entry> entries = switch (board) {
                            case MONEY -> money(connection, liveMoney, onlineNames);
                            case SKILLS -> skillEntries(top);
                            case PLAYTIME -> stat(connection, "playtime");
                            case KILLS -> stat(connection, "player_kills");
                            case MOB_KILLS -> stat(connection, "mob_kills");
                            case DEATHS -> stat(connection, "deaths");
                            case TREES -> trees(connection);
                        };
                        if (!hidden.isEmpty()) {
                            entries.removeIf(entry -> entry.name() != null && hidden.contains(entry.name().toLowerCase(Locale.ROOT)));
                        }
                        result.put(board, Ranking.of(board, entries));
                    }
                    return result;
                }))
                .thenAccept(result -> {
                    rankings = Collections.unmodifiableMap(result);
                    updated = System.currentTimeMillis();
                })
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Kon de toplijsten niet berekenen", error);
                    return null;
                });
        return running;
    }

    private static List<Entry> money(Connection connection, Map<UUID, Long> live, Map<UUID, String> names) throws SQLException {
        Map<UUID, Entry> entries = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.uuid, COALESCE(p.name, e.uuid) AS name, e.cash + e.bank AS total
                FROM pinda_economy e LEFT JOIN pinda_players p ON p.uuid = e.uuid""");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID uuid = uuid(result.getString("uuid"));
                if (uuid != null) {
                    entries.put(uuid, new Entry(uuid, result.getString("name"), result.getLong("total")));
                }
            }
        }
        for (Map.Entry<UUID, Long> online : live.entrySet()) {
            entries.put(online.getKey(), new Entry(online.getKey(), names.get(online.getKey()), online.getValue()));
        }
        List<Entry> list = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.value() > 0) {
                list.add(entry);
            }
        }
        list.sort(Comparator.comparingLong(Entry::value).reversed().thenComparing(Entry::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        return list;
    }

    private static List<Entry> skillEntries(List<SkillService.TopEntry> top) {
        List<Entry> list = new ArrayList<>();
        for (SkillService.TopEntry entry : top) {
            if (entry.level() > 0) {
                list.add(new Entry(entry.uuid(), entry.name(), entry.level()));
            }
        }
        return list;
    }

    /** Een kolom uit pinda_stats. Alleen vaste kolomnamen, nooit invoer van buiten. */
    private static List<Entry> stat(Connection connection, String column) throws SQLException {
        List<Entry> list = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT s.uuid, COALESCE(p.name, s.name, s.uuid) AS name, s." + column + " AS value "
                        + "FROM pinda_stats s LEFT JOIN pinda_players p ON p.uuid = s.uuid "
                        + "WHERE s." + column + " > 0 ORDER BY s." + column + " DESC, name COLLATE NOCASE");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID uuid = uuid(result.getString("uuid"));
                if (uuid != null) {
                    list.add(new Entry(uuid, result.getString("name"), result.getLong("value")));
                }
            }
        }
        return list;
    }

    /** Hoeveel bomen iemand in één keer omhakte (timber). */
    private static List<Entry> trees(Connection connection) throws SQLException {
        List<Entry> list = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.uuid, COALESCE(p.name, s.name, t.uuid) AS name, t.trees AS value
                FROM pinda_timber t LEFT JOIN pinda_players p ON p.uuid = t.uuid LEFT JOIN pinda_stats s ON s.uuid = t.uuid
                WHERE t.trees > 0 ORDER BY t.trees DESC, name COLLATE NOCASE""");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID uuid = uuid(result.getString("uuid"));
                if (uuid != null) {
                    list.add(new Entry(uuid, result.getString("name"), result.getLong("value")));
                }
            }
        }
        return list;
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ============================================================ tekst

    /** De naam van een toplijst in een taal, bijv. "Rijkste spelers". */
    public String name(Board board, String code) {
        String name = plugin.lang().raw(code, "top.boards." + board.id() + ".name");
        return name == null ? board.id() : name;
    }

    /** Een waarde zoals spelers hem zien in /top en het menu, bijv. "1.250 PindaCredits" of "3d 4u". */
    public String format(Board board, long value, String code) {
        return switch (board) {
            case MONEY -> {
                EconomyService economy = economy();
                yield economy == null ? number(value / 100.0, code) : economy.format(value);
            }
            case SKILLS -> {
                String template = plugin.lang().raw(code, "top.values.skills");
                yield template == null ? String.valueOf(value) : template.replace("<value>", number(value, code));
            }
            case PLAYTIME -> duration(value, code);
            default -> number(value, code);
        };
    }

    /** Een korte waarde voor het scoreboard, bijv. "12,3K" of "3d 4u". */
    public String compact(Board board, long value, String code) {
        return switch (board) {
            case MONEY -> compactNumber(value / 100.0, code);
            case PLAYTIME -> duration(value, code);
            default -> compactNumber(value, code);
        };
    }

    public static String number(double value, String code) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag(code));
        format.setMaximumFractionDigits(value == Math.rint(value) ? 0 : 2);
        format.setMinimumFractionDigits(value == Math.rint(value) ? 0 : 2);
        return format.format(value);
    }

    /** 950 -> "950", 12.345 -> "12,3K", 2.500.000 -> "2,5M". */
    public static String compactNumber(double value, String code) {
        double abs = Math.abs(value);
        if (abs < 10_000) {
            return number(abs < 1000 ? value : Math.floor(value), code);
        }
        String[] suffixes = {"K", "M", "B", "T"};
        double scaled = value;
        int index = -1;
        while (Math.abs(scaled) >= 1000 && index < suffixes.length - 1) {
            scaled /= 1000;
            index++;
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag(code));
        format.setMaximumFractionDigits(Math.abs(scaled) < 100 ? 1 : 0);
        return format.format(scaled) + suffixes[index];
    }

    /** Speeltijd in seconden als "3d 4u", "5u 12m" of "8m". */
    public String duration(long seconds, String code) {
        String raw = plugin.lang().raw(code, "top.units");
        String[] units = (raw == null ? "d,u,m" : raw).split(",");
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) {
            return days + unit(units, 0) + (hours > 0 ? " " + hours + unit(units, 1) : "");
        }
        if (hours > 0) {
            return hours + unit(units, 1) + (minutes > 0 ? " " + minutes + unit(units, 2) : "");
        }
        return minutes + unit(units, 2);
    }

    private static String unit(String[] units, int index) {
        return index < units.length ? units[index].trim() : "";
    }

    // ============================================================ hulp

    private YamlConfiguration config() {
        return module.settings();
    }

    private EconomyService economy() {
        PindaModule found = plugin.modules().get(EconomyModule.class);
        return found != null && found.isEnabled() ? ((EconomyModule) found).service() : null;
    }

    private SkillService skills() {
        PindaModule found = plugin.modules().get(SkillsModule.class);
        return found != null && found.isEnabled() ? ((SkillsModule) found).service() : null;
    }
}
