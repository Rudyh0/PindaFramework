package nl.pinda.framework.modules.duel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import org.bukkit.inventory.ItemStack;

/**
 * De database van de 1v1-gevechten: de stand per speler (gewonnen/verloren), de laatste
 * gevechten, en wat spelers nog terug moeten krijgen als een gevecht onverwacht stopt.
 */
public final class DuelStore {

    /** De databasetabellen (ook gebruikt bij het omzetten naar MySQL). */
    public static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_duel_stats (
                        uuid TEXT PRIMARY KEY,
                        name TEXT,
                        wins INTEGER NOT NULL DEFAULT 0,
                        losses INTEGER NOT NULL DEFAULT 0,
                        draws INTEGER NOT NULL DEFAULT 0,
                        updated INTEGER NOT NULL DEFAULT 0
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS pinda_duel_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        winner TEXT,
                        winner_name TEXT,
                        loser TEXT,
                        loser_name TEXT,
                        draw INTEGER NOT NULL DEFAULT 0,
                        money INTEGER NOT NULL DEFAULT 0,
                        items INTEGER NOT NULL DEFAULT 0,
                        seconds INTEGER NOT NULL DEFAULT 0,
                        reason TEXT
                    )""",
                    "CREATE INDEX IF NOT EXISTS idx_pinda_duel_history_time ON pinda_duel_history (time)",
                    """
                    CREATE TABLE IF NOT EXISTS pinda_duel_pending (
                        uuid TEXT PRIMARY KEY,
                        name TEXT,
                        role TEXT NOT NULL,
                        location TEXT,
                        money INTEGER NOT NULL DEFAULT 0,
                        items BLOB,
                        gamemode TEXT,
                        updated INTEGER NOT NULL DEFAULT 0
                    )"""
            )
    );

    /** De stand van één speler. */
    public record Stats(UUID uuid, String name, int wins, int losses, int draws) {

        public int fights() {
            return wins + losses + draws;
        }

        /** Gewonnen gedeeld door verloren (zonder verlies: het aantal gewonnen). */
        public double ratio() {
            return losses == 0 ? wins : (double) wins / losses;
        }
    }

    /** Eén gevecht uit de geschiedenis. Bij gelijkspel zijn winner en loser gewoon de twee spelers. */
    public record Fight(long time, UUID winner, String winnerName, UUID loser, String loserName, boolean draw,
                        long money, int items, int seconds, String reason) {
    }

    /**
     * Wat een speler terug moet krijgen als een gevecht onverwacht stopt (crash, uitloggen):
     * waar hij heen moet, zijn inzet (of gewonnen items die niet pasten) en zijn spelmodus.
     */
    public record Pending(UUID uuid, String name, String role, Spot location, long money, List<ItemStack> items, String gamemode) {

        boolean isEmpty() {
            return location == null && money <= 0 && items.isEmpty() && gamemode == null;
        }
    }

    private final PindaFramework plugin;

    DuelStore(PindaFramework plugin) {
        this.plugin = plugin;
    }

    // ============================================================ stand

    /** Telt een uitslag bij de stand op. */
    CompletableFuture<Void> record(UUID uuid, String name, int wins, int losses, int draws) {
        long now = System.currentTimeMillis();
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_duel_stats (uuid, name, wins, losses, draws, updated) VALUES (?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, wins = wins + excluded.wins, "
                            + "losses = losses + excluded.losses, draws = draws + excluded.draws, updated = excluded.updated")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, name);
                statement.setInt(3, wins);
                statement.setInt(4, losses);
                statement.setInt(5, draws);
                statement.setLong(6, now);
                statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> addFight(Fight fight) {
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_duel_history (time, winner, winner_name, loser, loser_name, draw, money, items, seconds, reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setLong(1, fight.time());
                statement.setString(2, fight.winner() == null ? null : fight.winner().toString());
                statement.setString(3, fight.winnerName());
                statement.setString(4, fight.loser() == null ? null : fight.loser().toString());
                statement.setString(5, fight.loserName());
                statement.setInt(6, fight.draw() ? 1 : 0);
                statement.setLong(7, fight.money());
                statement.setInt(8, fight.items());
                statement.setInt(9, fight.seconds());
                statement.setString(10, fight.reason());
                statement.executeUpdate();
            }
        });
    }

    /** De stand van één speler (nullen als hij nog nooit vocht). */
    public CompletableFuture<Stats> stats(UUID uuid) {
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, wins, losses, draws FROM pinda_duel_stats WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return new Stats(uuid, result.getString("name"), result.getInt("wins"), result.getInt("losses"), result.getInt("draws"));
                    }
                }
            }
            return new Stats(uuid, null, 0, 0, 0);
        });
    }

    /** De beste spelers: meeste gewonnen, dan de beste verhouding. */
    public CompletableFuture<List<Stats>> top(int limit) {
        return plugin.database().query(connection -> {
            List<Stats> list = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT s.uuid, COALESCE(p.name, s.name, s.uuid) AS name, s.wins, s.losses, s.draws
                    FROM pinda_duel_stats s LEFT JOIN pinda_players p ON p.uuid = s.uuid
                    WHERE s.wins + s.losses + s.draws > 0
                    ORDER BY s.wins DESC, s.losses ASC, name COLLATE NOCASE LIMIT ?""")) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        UUID uuid = uuid(result.getString("uuid"));
                        if (uuid != null) {
                            list.add(new Stats(uuid, result.getString("name"), result.getInt("wins"), result.getInt("losses"), result.getInt("draws")));
                        }
                    }
                }
            }
            return list;
        });
    }

    /** De laatste gevechten, nieuwste eerst (van één speler, of van iedereen met null). */
    public CompletableFuture<List<Fight>> history(UUID player, int limit) {
        return plugin.database().query(connection -> {
            List<Fight> list = new ArrayList<>();
            String sql = "SELECT time, winner, winner_name, loser, loser_name, draw, money, items, seconds, reason FROM pinda_duel_history"
                    + (player == null ? "" : " WHERE winner = ? OR loser = ?") + " ORDER BY time DESC, id DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (player != null) {
                    statement.setString(index++, player.toString());
                    statement.setString(index++, player.toString());
                }
                statement.setInt(index, limit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        list.add(new Fight(result.getLong("time"), uuid(result.getString("winner")), result.getString("winner_name"),
                                uuid(result.getString("loser")), result.getString("loser_name"), result.getInt("draw") != 0,
                                result.getLong("money"), result.getInt("items"), result.getInt("seconds"), result.getString("reason")));
                    }
                }
            }
            return list;
        });
    }

    /** Hoeveel gevechten er in totaal waren. */
    public CompletableFuture<Integer> fightCount() {
        return plugin.database().query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM pinda_duel_history");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        });
    }

    /** Zet de stand van één speler op nul (voor het paneel). */
    public CompletableFuture<Void> reset(UUID uuid) {
        return plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pinda_duel_stats WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
        });
    }

    // ============================================================ wat nog terug moet

    /** Slaat op wat deze speler terug moet krijgen. Items eerst omzetten (hoofdthread), schrijven op de achtergrond. */
    void save(Pending pending) {
        if (pending.isEmpty()) {
            delete(pending.uuid());
            return;
        }
        byte[] items = serialize(pending.items());
        String location = pending.location() == null ? null : pending.location().serialize();
        long now = System.currentTimeMillis();
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_duel_pending (uuid, name, role, location, money, items, gamemode, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, role = excluded.role, location = excluded.location, "
                            + "money = excluded.money, items = excluded.items, gamemode = excluded.gamemode, updated = excluded.updated")) {
                statement.setString(1, pending.uuid().toString());
                statement.setString(2, pending.name());
                statement.setString(3, pending.role());
                statement.setString(4, location);
                statement.setLong(5, pending.money());
                statement.setBytes(6, items);
                statement.setString(7, pending.gamemode());
                statement.setLong(8, now);
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon de inzet van " + pending.name() + " niet veiligstellen", error);
            return null;
        });
    }

    void delete(UUID uuid) {
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pinda_duel_pending WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kon een afgerond gevecht niet opruimen", error);
            return null;
        });
    }

    /** Alles wat nog terug moet (na een crash). De items worden op de hoofdthread weer items. */
    CompletableFuture<Map<UUID, RawPending>> loadPending() {
        return plugin.database().query(connection -> {
            Map<UUID, RawPending> all = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid, name, role, location, money, items, gamemode FROM pinda_duel_pending");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID uuid = uuid(result.getString("uuid"));
                    if (uuid != null) {
                        all.put(uuid, new RawPending(uuid, result.getString("name"), result.getString("role"),
                                result.getString("location"), result.getLong("money"), result.getBytes("items"), result.getString("gamemode")));
                    }
                }
            }
            return all;
        });
    }

    /** Zoals in de database; {@link #toPending()} maakt er items van (hoofdthread). */
    record RawPending(UUID uuid, String name, String role, String location, long money, byte[] items, String gamemode) {

        Pending toPending() {
            return new Pending(uuid, name, role, Spot.parse(location), money, deserialize(items), gamemode);
        }
    }

    // ============================================================ items omzetten

    static byte[] serialize(List<ItemStack> items) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            int count = 0;
            for (ItemStack item : items) {
                if (item != null && !item.isEmpty()) {
                    count++;
                }
            }
            out.writeInt(count);
            for (ItemStack item : items) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                byte[] data = item.serializeAsBytes();
                out.writeInt(data.length);
                out.write(data);
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<ItemStack> deserialize(byte[] data) {
        List<ItemStack> items = new ArrayList<>();
        if (data == null || data.length < 8) {
            return items;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            in.readInt(); // versie
            int count = Math.max(0, Math.min(4096, in.readInt()));
            for (int index = 0; index < count; index++) {
                int length = in.readInt();
                if (length <= 0 || length > 10_000_000) {
                    break;
                }
                byte[] item = new byte[length];
                in.readFully(item);
                items.add(ItemStack.deserializeBytes(item));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return items;
    }

    private static UUID uuid(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
