package nl.pinda.framework.modules.locks;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Sloten op kisten, vaten, shulkerboxen, deuren, luiken en hekken, plus partners.
 *
 * <p>Toegang (openen/gebruiken): eigenaar, spelers met toegang, partners en staff.
 * Afbreken: eigenaar, partners en staff.
 */
public final class LockService {

    public static final String BYPASS = "pinda.lock.bypass";
    private static final long REQUEST_TIMEOUT_MS = 5 * 60_000L;

    /** Soorten blokken die op slot kunnen. */
    public enum Kind { CONTAINER, DOOR, TRAPDOOR, FENCE_GATE }

    private final PindaFramework plugin;
    private final LockModule module;
    private final Map<BlockKey, Lock> locks = new ConcurrentHashMap<>();
    private final Map<BlockKey, UUID> hoppers = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> partners = new ConcurrentHashMap<>();
    /** Openstaande partnerverzoeken: ontvanger -> (aanvrager -> tijdstip). */
    private final Map<UUID, Map<UUID, Long>> requests = new ConcurrentHashMap<>();

    public LockService(PindaFramework plugin, LockModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private YamlConfiguration cfg() {
        return module.cfg();
    }

    // ============================================================ soorten blokken

    public static Kind kindOf(Material material) {
        if (material == null || !material.isBlock() || material == Material.ENDER_CHEST) {
            return null;
        }
        String name = material.name();
        if (name.equals("CHEST") || name.endsWith("_CHEST") || material == Material.BARREL || name.endsWith("SHULKER_BOX")) {
            return Kind.CONTAINER;
        }
        if (name.endsWith("_TRAPDOOR")) {
            return material == Material.IRON_TRAPDOOR ? null : Kind.TRAPDOOR;
        }
        if (name.endsWith("_DOOR")) {
            return material == Material.IRON_DOOR ? null : Kind.DOOR;
        }
        if (name.endsWith("_FENCE_GATE")) {
            return Kind.FENCE_GATE;
        }
        return null;
    }

    public boolean isLockable(Material material) {
        Kind kind = kindOf(material);
        if (kind == null) {
            return false;
        }
        return switch (kind) {
            case CONTAINER -> cfg().getBoolean("lock.containers", true);
            case DOOR -> cfg().getBoolean("lock.doors", true);
            case TRAPDOOR -> cfg().getBoolean("lock.trapdoors", true);
            case FENCE_GATE -> cfg().getBoolean("lock.fence-gates", true);
        };
    }

    /** Bij een deur telt altijd het onderste blok. */
    public static Block normalize(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Bisected bisected && kindOf(block.getType()) == Kind.DOOR
                && bisected.getHalf() == Bisected.Half.TOP) {
            return block.getRelative(BlockFace.DOWN);
        }
        return block;
    }

    /** De andere helft van een dubbele kist, of null. */
    public static Block otherChestHalf(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest chest)
                || chest.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) {
            return null;
        }
        BlockState state = block.getState(false);
        if (!(state instanceof InventoryHolder holder) || !(holder.getInventory() instanceof DoubleChestInventory inventory)) {
            return null;
        }
        for (Location location : new Location[]{inventory.getLeftSide().getLocation(), inventory.getRightSide().getLocation()}) {
            if (location != null) {
                Block half = location.getBlock();
                if (half.getX() != block.getX() || half.getY() != block.getY() || half.getZ() != block.getZ()) {
                    return half;
                }
            }
        }
        return null;
    }

    /** De blokken die bij één slot horen: het blok zelf (deur: onderkant) en eventueel de andere kisthelft. */
    public List<Block> lockBlocks(Block block) {
        List<Block> blocks = new ArrayList<>(2);
        Block base = normalize(block);
        blocks.add(base);
        Block other = otherChestHalf(base);
        if (other != null) {
            blocks.add(other);
        }
        return blocks;
    }

    // ============================================================ sloten

    /** Het slot op dit blok (of op de andere helft van een dubbele kist), of null. */
    public Lock find(Block block) {
        if (!isLockable(block.getType())) {
            return null;
        }
        Block base = normalize(block);
        Lock lock = locks.get(BlockKey.of(base));
        if (lock != null) {
            return lock;
        }
        Block other = otherChestHalf(base);
        return other == null ? null : locks.get(BlockKey.of(other));
    }

    /** Alle sloten van dit blok (bij een dubbele kist allebei de helften). */
    public List<Lock> locksFor(Block block) {
        List<Lock> result = new ArrayList<>(2);
        for (Block part : lockBlocks(block)) {
            Lock lock = locks.get(BlockKey.of(part));
            if (lock != null) {
                result.add(lock);
            }
        }
        return result;
    }

    public Lock create(Block block, UUID owner) {
        Block base = normalize(block);
        Lock lock = new Lock(BlockKey.of(base), owner, false);
        locks.put(lock.key(), lock);
        saveLock(lock);
        return lock;
    }

    public void remove(Block block) {
        Lock lock = locks.remove(BlockKey.of(normalize(block)));
        if (lock != null) {
            deleteLock(lock.key());
        }
    }

    public boolean canAccess(Player player, Lock lock) {
        return canAccess(player.getUniqueId(), lock) || player.hasPermission(BYPASS);
    }

    /** Toegang zonder staff-bypass (ook voor hoppers). */
    public boolean canAccess(UUID player, Lock lock) {
        return lock.everyone()
                || lock.owner().equals(player)
                || lock.rawTrusted().contains(player)
                || arePartners(lock.owner(), player);
    }

    public boolean canBreak(Player player, Lock lock) {
        UUID uuid = player.getUniqueId();
        return lock.owner().equals(uuid) || arePartners(lock.owner(), uuid) || player.hasPermission(BYPASS);
    }

    public void trust(Block block, UUID player, boolean trusted) {
        for (Lock lock : locksFor(block)) {
            if (trusted) {
                lock.rawTrusted().add(player);
            } else {
                lock.rawTrusted().remove(player);
            }
            saveTrusted(lock, player, trusted);
        }
    }

    public void setEveryone(Block block, boolean everyone) {
        for (Lock lock : locksFor(block)) {
            lock.everyone(everyone);
            saveLock(lock);
        }
    }

    // ============================================================ hoppers

    public void setHopperOwner(Block hopper, UUID owner) {
        BlockKey key = BlockKey.of(hopper);
        hoppers.put(key, owner);
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO pinda_lock_hoppers (world, x, y, z, owner) VALUES (?, ?, ?, ?, ?)")) {
                bindKey(statement, key, 1);
                statement.setString(5, owner.toString());
                statement.executeUpdate();
            }
        }).exceptionally(this::logError);
    }

    public UUID hopperOwner(Block hopper) {
        return hoppers.get(BlockKey.of(hopper));
    }

    public void removeHopper(Block hopper) {
        BlockKey key = BlockKey.of(hopper);
        if (hoppers.remove(key) != null) {
            plugin.database().execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM pinda_lock_hoppers WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                    bindKey(statement, key, 1);
                    statement.executeUpdate();
                }
            }).exceptionally(this::logError);
        }
    }

    // ============================================================ partners

    public Set<UUID> partners(UUID player) {
        Set<UUID> set = partners.get(player);
        return set == null ? Set.of() : Set.copyOf(set);
    }

    public boolean arePartners(UUID a, UUID b) {
        Set<UUID> set = partners.get(a);
        return set != null && set.contains(b);
    }

    public int maxPartners() {
        return Math.max(1, cfg().getInt("max-partners", 10));
    }

    public void addPartners(UUID a, UUID b) {
        partners.computeIfAbsent(a, ignored -> ConcurrentHashMap.newKeySet()).add(b);
        partners.computeIfAbsent(b, ignored -> ConcurrentHashMap.newKeySet()).add(a);
        String first = first(a, b);
        String second = second(a, b);
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO pinda_partners (a, b, since) VALUES (?, ?, ?)")) {
                statement.setString(1, first);
                statement.setString(2, second);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }).exceptionally(this::logError);
    }

    public void removePartners(UUID a, UUID b) {
        Set<UUID> setA = partners.get(a);
        if (setA != null) {
            setA.remove(b);
        }
        Set<UUID> setB = partners.get(b);
        if (setB != null) {
            setB.remove(a);
        }
        String first = first(a, b);
        String second = second(a, b);
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pinda_partners WHERE a = ? AND b = ?")) {
                statement.setString(1, first);
                statement.setString(2, second);
                statement.executeUpdate();
            }
        }).exceptionally(this::logError);
    }

    /** Slaat een verzoek op. */
    public void addRequest(UUID from, UUID to) {
        requests.computeIfAbsent(to, ignored -> new ConcurrentHashMap<>()).put(from, System.currentTimeMillis());
    }

    public boolean hasRequest(UUID from, UUID to) {
        Map<UUID, Long> incoming = requests.get(to);
        if (incoming == null) {
            return false;
        }
        Long time = incoming.get(from);
        if (time == null) {
            return false;
        }
        if (System.currentTimeMillis() - time > REQUEST_TIMEOUT_MS) {
            incoming.remove(from);
            return false;
        }
        return true;
    }

    public void removeRequest(UUID from, UUID to) {
        Map<UUID, Long> incoming = requests.get(to);
        if (incoming != null) {
            incoming.remove(from);
        }
    }

    /** Wie deze speler een (nog geldig) verzoek stuurde. */
    public List<UUID> incomingRequests(UUID to) {
        Map<UUID, Long> incoming = requests.get(to);
        if (incoming == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        incoming.values().removeIf(time -> now - time > REQUEST_TIMEOUT_MS);
        return new ArrayList<>(incoming.keySet());
    }

    private static String first(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? a.toString() : b.toString();
    }

    private static String second(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? b.toString() : a.toString();
    }

    // ============================================================ database

    public void loadAll() throws Exception {
        record Loaded(Map<BlockKey, Lock> locks, Map<BlockKey, UUID> hoppers, Map<UUID, Set<UUID>> partners) {
        }
        Loaded loaded = plugin.database().query(connection -> {
            Map<BlockKey, Lock> lockMap = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT world, x, y, z, owner, everyone FROM pinda_locks");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    BlockKey key = readKey(rows);
                    lockMap.put(key, new Lock(key, UUID.fromString(rows.getString("owner")), rows.getInt("everyone") == 1));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT world, x, y, z, player FROM pinda_lock_trusted");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Lock lock = lockMap.get(readKey(rows));
                    if (lock != null) {
                        lock.rawTrusted().add(UUID.fromString(rows.getString("player")));
                    }
                }
            }
            Map<BlockKey, UUID> hopperMap = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT world, x, y, z, owner FROM pinda_lock_hoppers");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    hopperMap.put(readKey(rows), UUID.fromString(rows.getString("owner")));
                }
            }
            Map<UUID, Set<UUID>> partnerMap = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT a, b FROM pinda_partners");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID a = UUID.fromString(rows.getString("a"));
                    UUID b = UUID.fromString(rows.getString("b"));
                    partnerMap.computeIfAbsent(a, ignored -> ConcurrentHashMap.newKeySet()).add(b);
                    partnerMap.computeIfAbsent(b, ignored -> ConcurrentHashMap.newKeySet()).add(a);
                }
            }
            return new Loaded(lockMap, hopperMap, partnerMap);
        }).get();
        locks.clear();
        locks.putAll(loaded.locks());
        hoppers.clear();
        hoppers.putAll(loaded.hoppers());
        partners.clear();
        partners.putAll(loaded.partners());
    }

    private static BlockKey readKey(ResultSet rows) throws java.sql.SQLException {
        return new BlockKey(rows.getString("world"), rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
    }

    private static void bindKey(PreparedStatement statement, BlockKey key, int start) throws java.sql.SQLException {
        statement.setString(start, key.world());
        statement.setInt(start + 1, key.x());
        statement.setInt(start + 2, key.y());
        statement.setInt(start + 3, key.z());
    }

    private void saveLock(Lock lock) {
        final BlockKey key = lock.key();
        final String owner = lock.owner().toString();
        final boolean everyone = lock.everyone();
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pinda_locks (world, x, y, z, owner, everyone, created) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(world, x, y, z) DO UPDATE SET owner = excluded.owner, everyone = excluded.everyone""")) {
                bindKey(statement, key, 1);
                statement.setString(5, owner);
                statement.setInt(6, everyone ? 1 : 0);
                statement.setLong(7, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }).exceptionally(this::logError);
    }

    private void deleteLock(BlockKey key) {
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pinda_locks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                bindKey(statement, key, 1);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pinda_lock_trusted WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                bindKey(statement, key, 1);
                statement.executeUpdate();
            }
        }).exceptionally(this::logError);
    }

    private void saveTrusted(Lock lock, UUID player, boolean trusted) {
        final BlockKey key = lock.key();
        plugin.database().execute(connection -> {
            String sql = trusted
                    ? "INSERT OR IGNORE INTO pinda_lock_trusted (world, x, y, z, player) VALUES (?, ?, ?, ?, ?)"
                    : "DELETE FROM pinda_lock_trusted WHERE world = ? AND x = ? AND y = ? AND z = ? AND player = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindKey(statement, key, 1);
                statement.setString(5, player.toString());
                statement.executeUpdate();
            }
        }).exceptionally(this::logError);
    }

    private Void logError(Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Fout bij opslaan van sloten", error);
        return null;
    }
}
