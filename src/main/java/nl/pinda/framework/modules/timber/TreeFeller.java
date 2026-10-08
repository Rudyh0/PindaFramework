package nl.pinda.framework.modules.timber;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.joml.Vector3f;

/**
 * Hakt een gevonden boom om: andere plugins krijgen de kans om mee te kijken (claims,
 * CoreProtect, skills-XP), de blokken verdwijnen, de bijl slijt, er komt een nieuwe sapling
 * en de boom valt om.
 */
final class TreeFeller {

    private final PindaFramework plugin;
    private final TimberModule module;
    private final Set<FallAnimation> active = new HashSet<>();
    private boolean felling;

    TreeFeller(PindaFramework plugin, TimberModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    /** True terwijl we zelf blok-events afvuren (die moeten we zelf negeren). */
    boolean isFelling() {
        return felling;
    }

    /**
     * @param slot het vak in de hotbar waarmee gehakt werd (de bijl, ook als de speler intussen wisselde)
     */
    void fell(Player player, TreeDetector.DetectedTree tree, TimberSettings settings, int slot) {
        ItemStack held = player.getInventory().getItem(slot);
        ItemStack tool = held == null ? new ItemStack(Material.AIR) : held;
        Block start = tree.start();

        // Wat er om moet (het blok dat de speler zelf hakte, doet Minecraft al)
        List<Block> targets = new ArrayList<>();
        for (Block log : tree.logs()) {
            if (!log.equals(start)) {
                targets.add(log);
            }
        }
        if (settings.leaves) {
            targets.addAll(tree.leaves());
        }
        targets.addAll(tree.extras());

        List<Block> removed = new ArrayList<>();
        Map<Block, List<ItemStack>> drops = new HashMap<>();
        int logs = 0;
        felling = true;
        try {
            for (Block block : targets) {
                BlockData before = tree.snapshot().get(block);
                if (before == null || block.getType() != before.getMaterial()) {
                    continue; // intussen veranderd
                }
                boolean dropItems = true;
                if (settings.breakEvents) {
                    BlockBreakEvent event = new BlockBreakEvent(block, player);
                    plugin.getServer().getPluginManager().callEvent(event);
                    if (event.isCancelled()) {
                        continue; // bijv. een claim van iemand anders
                    }
                    // Een andere plugin (bijv. auto-pickup) regelt de drops zelf: dan wij niet ook nog
                    dropItems = event.isDropItems();
                }
                boolean log = tree.type().logs().contains(before.getMaterial());
                boolean leaf = !log && tree.type().leaves().contains(before.getMaterial());
                boolean survival = player.getGameMode() != GameMode.CREATIVE;
                drops.put(block, dropItems && survival && (!leaf || settings.leafDrops)
                        ? new ArrayList<>(block.getDrops(tool, player)) : List.of());
                removed.add(block);
                if (log) {
                    logs++;
                }
            }
        } finally {
            felling = false;
        }

        for (Block block : removed) {
            BlockData before = tree.snapshot().get(block);
            boolean water = before instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
            // Hout met updates (fakkels en bordjes vallen eraf, losse bladeren gaan vergaan), de rest zonder (sneller)
            boolean log = tree.type().logs().contains(before.getMaterial());
            block.setType(water ? Material.WATER : Material.AIR, log);
        }

        BiConsumer<Location, List<ItemStack>> dropper = dropper(player, settings);
        try {
            replant(tree, settings);
            if (settings.realisticDamage && player.getGameMode() != GameMode.CREATIVE) {
                damage(player, slot, tool, logs, settings.protectTool);
            }
            module.record(player, logs + 1);
            if (settings.animate && !removed.isEmpty()) {
                animate(player, tree, settings, removed, drops, dropper);
                return;
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Fout bij het omhakken van een boom; de spullen vallen bij de stam", e);
        }
        dropNow(start, removed, drops, settings, dropper);
    }

    /** Zonder animatie (of als die niet lukte): alles meteen laten vallen. */
    private void dropNow(Block start, List<Block> removed, Map<Block, List<ItemStack>> drops, TimberSettings settings,
                         BiConsumer<Location, List<ItemStack>> dropper) {
        if ("landing".equals(settings.dropMode)) {
            for (Block block : removed) {
                dropper.accept(block.getLocation().add(0.5, 0.5, 0.5), drops.get(block));
            }
        } else {
            List<ItemStack> all = new ArrayList<>();
            for (Block block : removed) {
                all.addAll(drops.get(block));
            }
            dropper.accept(start.getLocation().add(0.5, 0.5, 0.5), all);
        }
    }

    // ============================================================ animatie

    private void animate(Player player, TreeDetector.DetectedTree tree, TimberSettings settings, List<Block> removed,
                         Map<Block, List<ItemStack>> drops, BiConsumer<Location, List<ItemStack>> dropper) {
        Block start = tree.start();
        World world = start.getWorld();

        // De voet van de stam (bij een 2x2-boom het midden van de vier)
        List<Block> base = base(tree);
        double cx = 0;
        double cz = 0;
        for (Block block : base) {
            cx += block.getX() + 0.5;
            cz += block.getZ() + 0.5;
        }
        cx /= base.size();
        cz /= base.size();
        int baseY = base.get(0).getY();

        // Weg van de speler vallen
        double dx = cx - player.getLocation().getX();
        double dz = cz - player.getLocation().getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.01) {
            dx = -Math.sin(Math.toRadians(player.getLocation().getYaw()));
            dz = Math.cos(Math.toRadians(player.getLocation().getYaw()));
            length = Math.sqrt(dx * dx + dz * dz);
        }
        Vector3f direction = new Vector3f((float) (dx / length), 0, (float) (dz / length));
        // Kantelen over de rand van de stam, niet door het midden
        double half = base.size() >= 4 ? 1.0 : 0.5;
        Location pivot = new Location(world, cx + direction.x * half, baseY, cz + direction.z * half);

        List<FallAnimation.Piece> pieces = new ArrayList<>();
        List<Block> overflow = new ArrayList<>();
        for (Block block : removed) {
            List<ItemStack> items = drops.get(block);
            if (pieces.size() >= settings.maxBlocks) {
                overflow.add(block); // te veel om te laten vallen: deze verdwijnen meteen
                continue;
            }
            Vector3f corner = new Vector3f((float) (block.getX() - pivot.getX()), (float) (block.getY() - pivot.getY()),
                    (float) (block.getZ() - pivot.getZ()));
            pieces.add(new FallAnimation.Piece(tree.snapshot().get(block), corner, items,
                    tree.type().logs().contains(tree.snapshot().get(block).getMaterial())));
        }
        if (pieces.isEmpty()) {
            dropNow(start, removed, drops, settings, dropper);
            return;
        }

        // Licht van boven de boom, anders wordt een vallende boom in de schaduw van zichzelf donker
        Block top = removed.get(0);
        for (Block block : removed) {
            if (block.getY() > top.getY()) {
                top = block;
            }
        }
        Block above = top.getRelative(0, 1, 0);
        Display.Brightness brightness = new Display.Brightness(above.getLightFromBlocks(), above.getLightFromSky());

        if (!settings.soundFall.isBlank()) {
            try {
                world.playSound(start.getLocation().add(0.5, 1, 0.5), settings.soundFall, 1f, 0.6f);
            } catch (RuntimeException ignored) {
                // onbekend geluid: dan maar zonder
            }
        }
        FallAnimation[] holder = new FallAnimation[1];
        holder[0] = new FallAnimation(plugin, settings, pivot, direction, pieces, player, dropper, () -> active.remove(holder[0]));
        active.add(holder[0]);
        try {
            holder[0].start(brightness);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "De valanimatie lukte niet; de spullen vallen bij de stam", e);
            holder[0].abort(); // ruimt op en laat de spullen van de vallende blokken vallen
        }
        for (Block block : overflow) {
            dropper.accept(block.getLocation().add(0.5, 0.5, 0.5), drops.get(block));
        }
    }

    /** De onderste blokken van de stam (het gehakte blok en wat op dezelfde hoogte staat). */
    private static List<Block> base(TreeDetector.DetectedTree tree) {
        int lowest = Integer.MAX_VALUE;
        for (Block log : tree.logs()) {
            lowest = Math.min(lowest, log.getY());
        }
        List<Block> base = new ArrayList<>();
        Block start = tree.start();
        for (Block log : tree.logs()) {
            if (log.getY() == lowest && Math.abs(log.getX() - start.getX()) <= 1 && Math.abs(log.getZ() - start.getZ()) <= 1) {
                base.add(log);
            }
        }
        return base.isEmpty() ? List.of(start) : base;
    }

    void abortAll() {
        for (FallAnimation animation : new ArrayList<>(active)) {
            animation.abort();
        }
        active.clear();
    }

    // ============================================================ drops

    private BiConsumer<Location, List<ItemStack>> dropper(Player player, TimberSettings settings) {
        return (location, items) -> {
            if (items == null || items.isEmpty()) {
                return;
            }
            if ("inventory".equals(settings.dropMode) && player.isOnline()) {
                Map<Integer, ItemStack> left = player.getInventory().addItem(items.toArray(new ItemStack[0]));
                for (ItemStack item : left.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
                return;
            }
            for (ItemStack item : items) {
                if (item != null && !item.getType().isAir()) {
                    location.getWorld().dropItemNaturally(location, item);
                }
            }
        };
    }

    // ============================================================ terugplanten

    private void replant(TreeDetector.DetectedTree tree, TimberSettings settings) {
        Material sapling = tree.type().sapling();
        if (!settings.replant || sapling == null) {
            return;
        }
        for (Block block : base(tree)) {
            Block below = block.getRelative(0, -1, 0);
            if (block.getType().isAir() && settings.soil.contains(below.getType())) {
                block.setType(sapling);
                module.protectSapling(block, settings.replantProtectMillis);
            }
        }
    }

    // ============================================================ de bijl

    /** Hoeveel schade de bijl ongeveer krijgt (met unbreaking). */
    static int expectedDamage(ItemStack tool, int logs) {
        int level = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        return (int) Math.ceil(logs / (level + 1.0));
    }

    /** Hoeveel de bijl nog kan hebben voordat hij breekt (Integer.MAX_VALUE = onbreekbaar of geen gereedschap). */
    static int remaining(ItemStack tool) {
        int max = tool.getType().getMaxDurability();
        ItemMeta meta = tool.getItemMeta();
        if (max <= 0 || !(meta instanceof Damageable damageable) || meta.isUnbreakable()) {
            return Integer.MAX_VALUE;
        }
        return max - damageable.getDamage();
    }

    /** Schade aan de bijl, met unbreaking zoals in Minecraft. Met 'protect' gaat de bijl nooit helemaal kapot. */
    private static void damage(Player player, int slot, ItemStack tool, int amount, boolean protect) {
        int max = tool.getType().getMaxDurability();
        ItemMeta meta = tool.getItemMeta();
        if (amount <= 0 || max <= 0 || !(meta instanceof Damageable damageable) || meta.isUnbreakable()) {
            return;
        }
        int level = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int applied = 0;
        for (int index = 0; index < amount; index++) {
            if (level == 0 || ThreadLocalRandom.current().nextInt(level + 1) == 0) {
                applied++;
            }
        }
        int total = damageable.getDamage() + applied;
        if (protect && total >= max) {
            total = max - 1;
        }
        if (total >= max) {
            player.getInventory().setItem(slot, null);
            player.getWorld().playSound(player.getLocation(), "entity.item.break", 1f, 1f);
            return;
        }
        damageable.setDamage(total);
        tool.setItemMeta(meta);
        player.getInventory().setItem(slot, tool);
    }
}
