package nl.pinda.framework.modules.homes;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.regex.Pattern;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.player.KnownPlayer;
import nl.pinda.framework.teleport.TeleportRequest;
import nl.pinda.framework.teleport.TeleportType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;

/** Homes: /sethome, /home, /delhome en het /homes-menu. */
public final class HomesModule extends PindaModule implements Listener {

    public static final String USE = "pinda.homes.use";
    public static final String UNLIMITED = "pinda.homes.unlimited";
    public static final String OTHERS = "pinda.homes.others";
    public static final String OTHERS_DELETE = "pinda.homes.others.delete";
    private static final String LIMIT_PREFIX = "pinda.homes.limit.";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,16}");
    private static final long PRELOAD_TIMEOUT_MS = 60_000L;

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of("""
                    CREATE TABLE IF NOT EXISTS pinda_homes (
                        uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        world TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        z REAL NOT NULL,
                        yaw REAL NOT NULL,
                        pitch REAL NOT NULL,
                        created INTEGER NOT NULL,
                        PRIMARY KEY (uuid, name)
                    )""")
    );

    private final Map<UUID, Map<String, Home>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loadedAt = new ConcurrentHashMap<>();

    public HomesModule(PindaFramework plugin) {
        super(plugin, "homes");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("homes", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de homes-tabel niet aanmaken", e);
        }
        listen(this);
        command(new SetHomeCommand(plugin, this));
        command(new HomeCommand(plugin, this));
        command(new DelHomeCommand(plugin, this));
        command(new HomesCommand(plugin, this));
        repeat(this::cleanup, 20L * 60, 20L * 60);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            homes(player);
        }
    }

    @Override
    protected void onDisable() {
        cache.clear();
        loadedAt.clear();
    }

    // ------------------------------------------------------------- opvragen

    /** De homes van een online speler (naam -> home). Laadt ze direct als dat nog niet gebeurd is. */
    public Map<String, Home> homes(Player player) {
        Map<String, Home> homes = cache.get(player.getUniqueId());
        if (homes == null) {
            try {
                homes = load(player.getUniqueId()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                plugin.getLogger().log(Level.SEVERE, "Kon de homes van " + player.getName() + " niet laden", e);
                return Map.of();
            }
            cache.put(player.getUniqueId(), homes);
            loadedAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
        return homes;
    }

    /** Homes gesorteerd op naam. */
    public List<Home> sorted(Map<String, Home> homes) {
        List<Home> list = new ArrayList<>(homes.values());
        list.sort(Comparator.comparing(Home::name));
        return list;
    }

    /**
     * De homes van een willekeurige speler, ook offline.
     * Let op: de future wordt niet op de hoofdthread afgerond.
     */
    public CompletableFuture<Map<String, Home>> homesOf(UUID owner) {
        Map<String, Home> cached = cache.get(owner);
        if (cached != null) {
            return CompletableFuture.completedFuture(new ConcurrentHashMap<>(cached));
        }
        return load(owner);
    }

    /** Het maximum aantal homes: de standaard uit de config, of meer via pinda.homes.limit.&lt;aantal&gt;. */
    public int limit(Player player) {
        if (player.hasPermission(UNLIMITED)) {
            return Integer.MAX_VALUE;
        }
        int limit = config().getInt("default-limit", 5);
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String permission = info.getPermission();
            if (info.getValue() && permission.startsWith(LIMIT_PREFIX)) {
                try {
                    limit = Math.max(limit, Integer.parseInt(permission.substring(LIMIT_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // geen getal, bijv. pinda.homes.limit.*
                }
            }
        }
        return limit;
    }

    /** "∞" voor onbeperkt, anders het getal. */
    public static String formatLimit(int limit) {
        return limit == Integer.MAX_VALUE ? "∞" : String.valueOf(limit);
    }

    public boolean isWorldBlocked(World world) {
        for (String blocked : config().getStringList("blocked-worlds")) {
            if (blocked.equalsIgnoreCase(world.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Maakt een naam netjes (kleine letters) of geeft null als hij ongeldig is. */
    public static String normalizeName(String input) {
        String name = input.toLowerCase(Locale.ROOT);
        return NAME.matcher(name).matches() ? name : null;
    }

    // ------------------------------------------------------------ wijzigen

    public void saveHome(Home home) {
        Map<String, Home> homes = cache.get(home.owner());
        if (homes != null) {
            homes.put(home.name(), home);
        }
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pinda_homes (uuid, name, world, x, y, z, yaw, pitch, created)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid, name) DO UPDATE SET
                        world = excluded.world, x = excluded.x, y = excluded.y, z = excluded.z,
                        yaw = excluded.yaw, pitch = excluded.pitch""")) {
                statement.setString(1, home.owner().toString());
                statement.setString(2, home.name());
                statement.setString(3, home.world());
                statement.setDouble(4, home.x());
                statement.setDouble(5, home.y());
                statement.setDouble(6, home.z());
                statement.setFloat(7, home.yaw());
                statement.setFloat(8, home.pitch());
                statement.setLong(9, home.created());
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon home " + home.name() + " niet opslaan", error);
            return null;
        });
    }

    public void deleteHome(UUID owner, String name) {
        Map<String, Home> homes = cache.get(owner);
        if (homes != null) {
            homes.remove(name);
        }
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pinda_homes WHERE uuid = ? AND name = ?")) {
                statement.setString(1, owner.toString());
                statement.setString(2, name);
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon home " + name + " niet verwijderen", error);
            return null;
        });
    }

    // ---------------------------------------------------------- teleporteren

    /**
     * Teleporteert een speler naar een home (met wachttijd, cooldown en kosten).
     *
     * @param owner de eigenaar, of null als het de eigen home is
     */
    public void teleport(Player traveler, Home home, KnownPlayer owner) {
        Location location = home.toLocation();
        if (location == null) {
            plugin.lang().send(traveler, "homes.world-missing");
            plugin.theme().play(traveler, "error");
            return;
        }
        Runnable onSuccess = owner == null
                ? () -> plugin.lang().send(traveler, "homes.teleported", Text.p("home", home.name()))
                : () -> plugin.lang().send(traveler, "homes.others-teleported",
                        Text.p("home", home.name()),
                        Text.p("player", owner.name()));
        plugin.teleports().teleport(new TeleportRequest(traveler, traveler, TeleportType.HOME,
                () -> location, true, onSuccess));
    }

    /**
     * Zoekt een (eventueel offline) speler en zijn homes op, en voert daarna de actie uit
     * op de hoofdthread. Bestaat de speler niet, dan krijgt de afzender een melding.
     */
    public void withOwnerHomes(CommandSender sender, String ownerName, BiConsumer<KnownPlayer, Map<String, Home>> action) {
        plugin.players().findKnown(ownerName)
                .thenCompose(known -> known == null
                        ? CompletableFuture.<OwnerHomes>completedFuture(null)
                        : homesOf(known.uuid()).thenApply(homes -> new OwnerHomes(known, homes)))
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (sender instanceof Player player && !player.isOnline()) {
                        return;
                    }
                    if (result == null) {
                        plugin.lang().send(sender, "homes.player-unknown", Text.p("player", ownerName));
                        plugin.theme().play(sender, "error");
                        return;
                    }
                    action.accept(result.owner(), result.homes());
                }))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.SEVERE, "Kon de homes van " + ownerName + " niet opzoeken", error);
                    return null;
                });
    }

    private record OwnerHomes(KnownPlayer owner, Map<String, Home> homes) {
    }

    // -------------------------------------------------------- laden en events

    private CompletableFuture<Map<String, Home>> load(UUID owner) {
        return plugin.database().query(connection -> {
            Map<String, Home> homes = new ConcurrentHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name, world, x, y, z, yaw, pitch, created FROM pinda_homes WHERE uuid = ?")) {
                statement.setString(1, owner.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Home home = new Home(owner, result.getString("name"), result.getString("world"),
                                result.getDouble("x"), result.getDouble("y"), result.getDouble("z"),
                                result.getFloat("yaw"), result.getFloat("pitch"), result.getLong("created"));
                        homes.put(home.name(), home);
                    }
                }
            }
            return homes;
        });
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            cache.put(event.getUniqueId(), load(event.getUniqueId()).get(10, TimeUnit.SECONDS));
            loadedAt.put(event.getUniqueId(), System.currentTimeMillis());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Niet erg: bij het eerste gebruik proberen we het opnieuw.
            plugin.getLogger().log(Level.WARNING, "Kon de homes van " + event.getName() + " niet vooraf laden", e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
        loadedAt.remove(event.getPlayer().getUniqueId());
    }

    /** Ruimt homes op van spelers die wel geladen zijn maar niet online kwamen. */
    private void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<UUID> iterator = cache.keySet().iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            if (plugin.getServer().getPlayer(uuid) != null) {
                continue;
            }
            Long loaded = loadedAt.get(uuid);
            if (loaded == null || now - loaded > PRELOAD_TIMEOUT_MS) {
                iterator.remove();
                loadedAt.remove(uuid);
            }
        }
    }
}
