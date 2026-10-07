package nl.pinda.framework.modules.skills;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Alle manieren om XP te verdienen. */
final class SkillListener implements Listener {

    private final SkillService service;
    private final PlacedBlocks placed;
    private final NamespacedKey reducedKey;
    /** Wie het laatst een brouwstandaard gebruikte (die krijgt de XP als het drankje klaar is). */
    private final Map<String, UUID> brewers = new HashMap<>();

    SkillListener(PindaFramework plugin, SkillService service, PlacedBlocks placed) {
        this.service = service;
        this.placed = placed;
        this.reducedKey = new NamespacedKey(plugin, "reduced_xp");
    }

    // ============================================================ blokken: mijnbouw, houthakken, landbouw

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        SkillRules rules = service.rules();
        boolean wasPlaced = rules.tracked.contains(type) && placed.isPlaced(block);
        if (wasPlaced) {
            placed.unmark(block);
        }
        Player player = event.getPlayer();

        Double crop = rules.crops.get(type);
        if (crop != null) {
            if (block.getBlockData() instanceof Ageable age && age.getAge() < age.getMaximumAge()) {
                return;
            }
            service.addXp(player, Skill.FARMING, crop);
            return;
        }
        if (wasPlaced && !rules.placedGivesXp) {
            return;
        }
        Double value = rules.farmBlocks.get(type);
        if (value != null) {
            service.addXp(player, Skill.FARMING, value);
            return;
        }
        value = rules.mining.get(type);
        if (value != null) {
            service.addXp(player, Skill.MINING, value);
            return;
        }
        value = rules.woodcutting.get(type);
        if (value != null) {
            service.addXp(player, Skill.WOODCUTTING, value);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        Double value = service.rules().harvest.get(event.getHarvestedBlock().getType());
        if (value != null) {
            service.addXp(event.getPlayer(), Skill.FARMING, value);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            service.addXp(player, Skill.FARMING, service.rules().breed);
        }
    }

    // ============================================================ vissen

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        SkillRules rules = service.rules();
        ItemStack stack = item.getItemStack();
        Double fish = rules.fish.get(stack.getType());
        double xp;
        if (fish != null) {
            xp = fish;
        } else if (isTreasure(stack)) {
            xp = rules.fishTreasure;
        } else {
            xp = rules.fishJunk;
        }
        service.addXp(event.getPlayer(), Skill.FISHING, xp);
    }

    private static boolean isTreasure(ItemStack stack) {
        Material type = stack.getType();
        return type == Material.ENCHANTED_BOOK || type == Material.NAME_TAG || type == Material.NAUTILUS_SHELL
                || type == Material.SADDLE || !stack.getEnchantments().isEmpty();
    }

    // ============================================================ vechten en boogschieten

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (service.rules().reducedReasons.contains(event.getSpawnReason())) {
            event.getEntity().getPersistentDataContainer().set(reducedKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || killer == entity) {
            return;
        }
        SkillRules rules = service.rules();
        double xp;
        if (entity instanceof Player) {
            xp = rules.playerKill;
        } else if (entity instanceof Mob) {
            Double listed = rules.mobs.get(entity.getType());
            xp = listed != null ? listed : entity instanceof Enemy ? rules.hostileDefault : rules.passiveDefault;
            if (entity.getPersistentDataContainer().has(reducedKey, PersistentDataType.BYTE)) {
                xp *= rules.spawnerMultiplier;
            }
        } else {
            return;
        }
        if (xp <= 0) {
            return;
        }
        DamageSource source = event.getDamageSource();
        Entity direct = source.getDirectEntity();
        if (direct instanceof Projectile) {
            double archery = xp * rules.archeryMultiplier;
            Location from = killer.getLocation();
            Location to = entity.getLocation();
            if (rules.archeryDistance > 0 && from.getWorld() == to.getWorld() && from.distance(to) >= rules.archeryDistance) {
                archery *= 1 + rules.archeryBonus;
            }
            service.addXp(killer, Skill.ARCHERY, archery);
        } else {
            service.addXp(killer, Skill.FIGHTING, xp);
        }
    }

    // ============================================================ koken

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceExtract(FurnaceExtractEvent event) {
        SkillRules rules = service.rules();
        Material type = event.getItemType();
        Double value = rules.smelt.get(type);
        if (value == null && type.isEdible()) {
            value = rules.foodDefault;
        }
        if (value != null && event.getItemAmount() > 0) {
            service.addXp(event.getPlayer(), Skill.COOKING, value * event.getItemAmount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack result = event.getRecipe().getResult();
        Double value = service.rules().craft.get(result.getType());
        if (value == null) {
            return;
        }
        int crafts = 1;
        if (event.isShiftClick()) {
            crafts = Integer.MAX_VALUE;
            for (ItemStack ingredient : event.getInventory().getMatrix()) {
                if (ingredient != null && !ingredient.isEmpty()) {
                    crafts = Math.min(crafts, ingredient.getAmount());
                }
            }
            if (crafts == Integer.MAX_VALUE) {
                crafts = 1;
            }
        }
        service.addXp(player, Skill.COOKING, value * crafts * Math.max(1, result.getAmount()));
    }

    // ============================================================ alchemie

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.BREWING && event.getPlayer() instanceof Player player) {
            remember(event.getInventory().getLocation(), player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrewingClick(InventoryClickEvent event) {
        if (event.getInventory().getType() == InventoryType.BREWING && event.getWhoClicked() instanceof Player player) {
            remember(event.getInventory().getLocation(), player);
        }
    }

    private void remember(Location location, Player player) {
        if (location == null) {
            return;
        }
        if (brewers.size() > 5000) {
            brewers.clear();
        }
        brewers.put(key(location), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        UUID brewer = brewers.get(key(event.getBlock().getLocation()));
        Player player = brewer == null ? null : event.getBlock().getWorld().getPlayers().stream()
                .filter(online -> online.getUniqueId().equals(brewer)).findFirst().orElse(null);
        if (player == null) {
            return;
        }
        ItemStack ingredient = event.getContents().getIngredient();
        if (ingredient == null || ingredient.isEmpty()) {
            return;
        }
        SkillRules rules = service.rules();
        double value = rules.ingredients.getOrDefault(ingredient.getType(), rules.ingredientDefault);
        int potions = 0;
        for (ItemStack result : event.getResults()) {
            if (result != null && !result.isEmpty()) {
                potions++;
            }
        }
        if (potions > 0) {
            service.addXp(player, Skill.ALCHEMY, value * potions);
        }
    }

    private static String key(Location location) {
        return location.getWorld().getName() + ':' + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ();
    }

    void clear() {
        brewers.clear();
    }
}
