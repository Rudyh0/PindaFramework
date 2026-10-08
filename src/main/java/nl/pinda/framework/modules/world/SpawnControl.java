package nl.pinda.framework.modules.world;

import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent;
import com.destroystokyo.paper.event.entity.PreCreatureSpawnEvent;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import nl.pinda.framework.PindaFramework;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Hoeveel mobs er vanzelf spawnen: het maximum per soort mobs (in procenten van normaal),
 * een kans per mob en of phantoms mogen komen.
 */
final class SpawnControl implements Listener {

    /** De groepen mobs waar Minecraft een maximum voor heeft, met de naam in de config. */
    enum Group {
        MONSTERS("monsters", SpawnCategory.MONSTER),
        ANIMALS("animals", SpawnCategory.ANIMAL),
        WATER_ANIMALS("water-animals", SpawnCategory.WATER_ANIMAL),
        WATER_AMBIENT("water-ambient", SpawnCategory.WATER_AMBIENT),
        UNDERGROUND_WATER("underground-water", SpawnCategory.WATER_UNDERGROUND_CREATURE),
        AMBIENT("ambient", SpawnCategory.AMBIENT),
        AXOLOTLS("axolotls", SpawnCategory.AXOLOTL);

        final String id;
        final SpawnCategory category;

        Group(String id, SpawnCategory category) {
            this.id = id;
            this.category = category;
        }

        static Group byId(String id) {
            for (Group group : values()) {
                if (group.id.equalsIgnoreCase(id)) {
                    return group;
                }
            }
            return null;
        }
    }

    static final int MAX_PERCENT = 500;

    private final PindaFramework plugin;
    private final WorldControlModule module;
    /** De oorspronkelijke maximums per wereld, zodat we ze terug kunnen zetten. */
    private final Map<String, EnumMap<SpawnCategory, Integer>> originals = new HashMap<>();
    private volatile Map<EntityType, Integer> chances = Map.of();
    private volatile Set<String> disabledWorlds = Set.of();
    private volatile boolean phantoms;
    private volatile boolean phantomChoice;

    SpawnControl(PindaFramework plugin, WorldControlModule module) {
        this.plugin = plugin;
        this.module = module;
        reload();
    }

    void reload() {
        YamlConfiguration cfg = module.settings();
        Map<EntityType, Integer> map = new LinkedHashMap<>();
        ConfigurationSection mobs = cfg.getConfigurationSection("spawning.mobs");
        if (mobs != null) {
            for (String key : mobs.getKeys(false)) {
                EntityType type = mobType(key);
                if (type == null || !chanceAllowed(type)) {
                    plugin.getLogger().warning("world.yml: onbekende mob '" + key + "' bij spawning.mobs");
                    continue;
                }
                map.put(type, Math.max(0, Math.min(100, mobs.getInt(key, 100))));
            }
        }
        chances = Map.copyOf(map);
        Set<String> worlds = new HashSet<>();
        for (String name : cfg.getStringList("spawning.disabled-worlds")) {
            worlds.add(name.trim().toLowerCase(Locale.ROOT));
        }
        disabledWorlds = Set.copyOf(worlds);
        phantoms = cfg.getBoolean("phantoms.enabled", false);
        phantomChoice = cfg.getBoolean("phantoms.player-choice", false);
    }

    /** Een mob die vanzelf kan spawnen, op naam ("creeper", "CREEPER"), of null. */
    static EntityType mobType(String name) {
        try {
            EntityType type = EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
            return spawnableMob(type) ? type : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Mobs waarvoor een kans per mob werkt. Phantoms hebben hun eigen instelling, en de draak
     * en de wither spawnen nooit vanzelf.
     */
    static boolean chanceAllowed(EntityType type) {
        return spawnableMob(type) && type != EntityType.PHANTOM && type != EntityType.ENDER_DRAGON && type != EntityType.WITHER;
    }

    /** Echte mobs (geen spelers, harnasstandaarden of mannequins). */
    static boolean spawnableMob(EntityType type) {
        Class<? extends Entity> entityClass = type.getEntityClass();
        return type != EntityType.UNKNOWN && entityClass != null && Mob.class.isAssignableFrom(entityClass);
    }

    boolean managed(World world) {
        return !disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    /** Het percentage voor deze groep uit de config (100 = normaal). */
    int percent(Group group) {
        return Math.max(0, Math.min(MAX_PERCENT, module.settings().getInt("spawning.limits." + group.id, 100)));
    }

    // ============================================================ maximums toepassen

    void applyAll() {
        for (World world : plugin.getServer().getWorlds()) {
            apply(world);
        }
    }

    void apply(World world) {
        boolean managed = managed(world);
        for (Group group : Group.values()) {
            int percent = managed ? percent(group) : 100;
            EnumMap<SpawnCategory, Integer> saved = originals.get(world.getName());
            if (percent == 100 && (saved == null || !saved.containsKey(group.category))) {
                continue;
            }
            int base = originals.computeIfAbsent(world.getName(), name -> new EnumMap<>(SpawnCategory.class))
                    .computeIfAbsent(group.category, world::getSpawnLimit);
            world.setSpawnLimit(group.category, percent == 100 ? original(group.category, base) : (int) Math.round(base * percent / 100.0));
        }
    }

    /** Het oorspronkelijke maximum terugzetten: -1 ("volg bukkit.yml") als het daaraan gelijk was. */
    private int original(SpawnCategory category, int base) {
        return base == plugin.getServer().getSpawnLimit(category) ? -1 : base;
    }

    /**
     * Zet alle maximums terug zoals ze waren. Was het gelijk aan dat van de server (bukkit.yml),
     * dan zetten we de wereld weer op "volg de server" (-1).
     */
    void restore() {
        for (Map.Entry<String, EnumMap<SpawnCategory, Integer>> entry : originals.entrySet()) {
            World world = plugin.getServer().getWorld(entry.getKey());
            if (world == null) {
                continue;
            }
            for (Map.Entry<SpawnCategory, Integer> limit : entry.getValue().entrySet()) {
                world.setSpawnLimit(limit.getKey(), original(limit.getKey(), limit.getValue()));
            }
        }
        originals.clear();
    }

    /** Het normale maximum (zonder ons percentage) voor deze groep in deze wereld. */
    int base(World world, Group group) {
        EnumMap<SpawnCategory, Integer> saved = originals.get(world.getName());
        if (saved != null && saved.containsKey(group.category)) {
            return saved.get(group.category);
        }
        return world.getSpawnLimit(group.category);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        apply(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        originals.remove(event.getWorld().getName());
    }

    // ============================================================ phantoms

    /** Mag er een phantom komen voor deze speler? */
    private boolean phantomAllowed(Entity target) {
        if (phantomChoice && target instanceof Player player) {
            return plugin.settings().isEnabled(player, WorldControlModule.PHANTOM_SETTING);
        }
        return phantoms;
    }

    // ============================================================ spawnen

    /** Paper vraagt het al vóór het spawnen: dan hoeft de server de mob niet eens te maken. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreSpawn(PreCreatureSpawnEvent event) {
        if (event.getReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (event instanceof PhantomPreSpawnEvent phantom) {
            if (!phantomAllowed(phantom.getSpawningEntity())) {
                event.setCancelled(true);
                event.setShouldAbortSpawn(true);
            }
            return;
        }
        // Alleen deze mob niet; afbreken (abort) zou ook andere mobs in dezelfde poging tegenhouden.
        Integer chance = chances.get(event.getType());
        if (chance != null && chance <= 0 && managed(event.getSpawnLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        if (event.getEntity() instanceof Phantom phantom) {
            UUID target = phantom.getSpawningEntity();
            Player player = target == null ? null : plugin.getServer().getPlayer(target);
            if (!phantomAllowed(player)) {
                event.setCancelled(true);
            }
            return;
        }
        Integer chance = chances.get(event.getEntityType());
        if (chance == null || chance >= 100 || !managed(event.getLocation().getWorld())) {
            return;
        }
        if (chance <= 0 || ThreadLocalRandom.current().nextInt(100) >= chance) {
            event.setCancelled(true);
        }
    }
}
