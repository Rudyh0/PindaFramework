package nl.pinda.framework.modules.timber;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.skills.SkillsModule;
import nl.pinda.framework.player.PlayerSetting;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Bomen kappen zoals UltimateTimber: hak met een bijl het onderste blok van een boom om en de
 * hele boom valt om, weg van je. Er komt meteen een nieuwe sapling. Geen extra loot: alleen
 * wat de blokken in Minecraft zelf opleveren.
 */
public final class TimberModule extends PindaModule implements Listener {

    public static final String USE = "pinda.timber.use";
    public static final String BYPASS_COOLDOWN = "pinda.timber.bypass-cooldown";
    public static final String SETTING = "timber";

    /** De databasetabellen van deze module (ook gebruikt bij het omzetten naar MySQL). */
    public static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_timber (
                        uuid TEXT PRIMARY KEY,
                        trees INTEGER NOT NULL DEFAULT 0,
                        logs INTEGER NOT NULL DEFAULT 0
                    )"""
            )
    );

    private TimberSettings settings;
    private TreeFeller feller;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<Block, Long> saplings = new HashMap<>();
    private final Set<Block> busy = new HashSet<>();

    public TimberModule(PindaFramework plugin) {
        super(plugin, "timber");
    }

    @Override
    protected void onEnable() {
        try {
            plugin.database().migrate("timber", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de timber-tabel niet aanmaken", e);
        }
        settings = new TimberSettings(config(), plugin.getLogger());
        feller = new TreeFeller(plugin, this);
        registerSetting();
        listen(this);
        command(new TimberCommand(plugin, this));
        repeat(this::cleanup, 20L * 30, 20L * 30);
        SkillsModule skills = plugin.modules().get(SkillsModule.class);
        if (skills == null || !skills.isEnabled()) {
            plugin.getLogger().warning("Timber: de skills-module staat uit, dus zelf geplaatst hout wordt niet herkend. "
                    + "Bouwwerken blijven beschermd zolang er planken, glas, deuren, ... aan het hout vastzitten.");
        }
    }

    @Override
    protected void onDisable() {
        if (feller != null) {
            feller.abortAll();
        }
        saplings.clear();
        busy.clear();
        plugin.settings().unregister(SETTING);
    }

    @Override
    protected void onReload() {
        settings = new TimberSettings(config(), plugin.getLogger());
        registerSetting();
    }

    private void registerSetting() {
        plugin.settings().register(new PlayerSetting(SETTING, config().getBoolean("default-enabled", true),
                Material.IRON_AXE, config().getBoolean("show-in-setup", false)));
    }

    // ============================================================ omhakken

    /**
     * Net vóór de skills (HIGHEST): zo weten we nog of het blok zelf geplaatst was. Of het
     * hakken echt doorgaat (geen claim van iemand anders), kijken we een tick later.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (feller.isFelling()) {
            return;
        }
        Block block = event.getBlock();
        TimberSettings current = settings;
        if (!current.byLog.containsKey(block.getType()) || busy.contains(block)) {
            return;
        }
        Player player = event.getPlayer();
        if (!allowed(player, block, current)) {
            return;
        }
        if (current.cooldownMillis > 0 && !player.hasPermission(BYPASS_COOLDOWN)) {
            long left = cooldowns.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0) {
                plugin.lang().send(player, "timber.cooldown", Text.p("seconds", (left + 999) / 1000));
                return;
            }
        }
        TreeDetector.DetectedTree tree = TreeDetector.detect(block, current, this::isPlaced);
        if (tree == null || tree.logs().size() + tree.leaves().size() <= 1) {
            return;
        }
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (current.protectTool && player.getGameMode() != GameMode.CREATIVE
                && TreeFeller.remaining(tool) <= TreeFeller.expectedDamage(tool, tree.logs().size())) {
            plugin.lang().send(player, "timber.tool-protect");
            return;
        }
        int slot = player.getInventory().getHeldItemSlot();
        busy.addAll(tree.logs());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                // Een andere plugin kan het hakken nog hebben tegengehouden (claims, ...)
                if (event.isCancelled() || !player.isOnline() || !isEnabled() || block.getType() == tree.snapshot().get(block).getMaterial()) {
                    return;
                }
                cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + current.cooldownMillis);
                feller.fell(player, tree, current, slot);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Kon een boom niet omhakken", e);
            } finally {
                busy.removeAll(tree.logs());
            }
        });
    }

    private boolean allowed(Player player, Block block, TimberSettings current) {
        if (!player.hasPermission(USE) || !plugin.settings().isEnabled(player, SETTING)) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE ? !current.creative : mode != GameMode.SURVIVAL) {
            return false;
        }
        if (current.disabledWorlds.contains(block.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean sneaking = player.isSneaking();
        if (current.mode == TimberSettings.Mode.SNEAKING && !sneaking || current.mode == TimberSettings.Mode.NOT_SNEAKING && sneaking) {
            return false;
        }
        return !current.axeOnly || player.getInventory().getItemInMainHand().getType().name().endsWith("_AXE");
    }

    private boolean isPlaced(Block block) {
        SkillsModule skills = plugin.modules().get(SkillsModule.class);
        return skills != null && skills.isEnabled() && skills.isPlaced(block);
    }

    // ============================================================ nieuwe saplings beschermen

    void protectSapling(Block block, long millis) {
        if (millis > 0) {
            saplings.put(block, System.currentTimeMillis() + millis);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSaplingBreak(BlockBreakEvent event) {
        Long until = saplings.get(event.getBlock());
        if (until == null) {
            return;
        }
        if (until > System.currentTimeMillis()) {
            event.setCancelled(true);
            plugin.lang().send(event.getPlayer(), "timber.sapling-protected");
        } else {
            saplings.remove(event.getBlock());
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        saplings.values().removeIf(until -> until <= now);
        cooldowns.values().removeIf(until -> until <= now);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    // ============================================================ statistieken (voor de toplijst)

    void record(Player player, int logs) {
        String uuid = player.getUniqueId().toString();
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pinda_timber (uuid, trees, logs) VALUES (?, 1, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET trees = trees + 1, logs = logs + excluded.logs")) {
                statement.setString(1, uuid);
                statement.setInt(2, logs);
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kon de bomen-statistiek niet opslaan", error);
            return null;
        });
    }

    /** Zet omhakken aan of uit voor een speler en geeft de nieuwe stand terug. */
    boolean toggle(Player player) {
        return plugin.settings().toggle(player, SETTING);
    }
}
