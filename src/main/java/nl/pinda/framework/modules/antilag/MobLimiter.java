package nl.pinda.framework.modules.antilag;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;

/**
 * Een maximum aantal mobs per chunk, zodat farms de server niet laten haperen. Alleen voor de
 * manieren van spawnen uit de config (fokken, spawners, eieren, ...); gewone mobs 's nachts niet.
 */
final class MobLimiter implements Listener {

    private final PindaFramework plugin;
    private final AntilagModule module;
    private volatile Set<String> reasons = Set.of();

    MobLimiter(PindaFramework plugin, AntilagModule module) {
        this.plugin = plugin;
        this.module = module;
        reload();
    }

    /** Leest de manieren van spawnen opnieuw in (na /pinda reload of opslaan in het paneel). */
    void reload() {
        Set<String> set = new HashSet<>();
        for (String value : cfg().getStringList("reasons")) {
            set.add(value.trim().toUpperCase(Locale.ROOT));
        }
        reasons = Set.copyOf(set);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection section = module.settings().getConfigurationSection("mob-limit");
        return section != null ? section : module.settings().createSection("mob-limit");
    }

    /** Het maximum voor deze soort per chunk (0 = geen limiet). */
    int limit(EntityType type) {
        ConfigurationSection cfg = cfg();
        ConfigurationSection types = cfg.getConfigurationSection("types");
        String name = type.name();
        if (types != null && types.contains(name)) {
            return Math.max(0, types.getInt(name));
        }
        return Math.max(0, cfg.getInt("per-type", 40));
    }

    private boolean limited(String reason) {
        return reasons.contains(reason);
    }

    /** Zit deze chunk vol voor deze soort? Geeft het maximum terug als dat zo is, anders 0. */
    private int full(Location location, EntityType type) {
        if (!cfg().getBoolean("enabled", true) || location.getWorld() == null) {
            return 0;
        }
        int perType = limit(type);
        int total = Math.max(0, cfg().getInt("total", 0));
        if (perType == 0 && total == 0) {
            return 0;
        }
        Chunk chunk = location.getChunk();
        int same = 0;
        int all = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof LivingEntity) || entity instanceof Player || entity instanceof ArmorStand) {
                continue;
            }
            all++;
            if (entity.getType() == type) {
                same++;
            }
        }
        if (perType > 0 && same >= perType) {
            return perType;
        }
        if (total > 0 && all >= total) {
            return total;
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!limited(event.getSpawnReason().name())) {
            return;
        }
        if (full(event.getLocation(), event.getEntityType()) > 0) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!limited("BREEDING")) {
            return;
        }
        EntityType type = event.getEntity().getType();
        int limit = full(event.getMother().getLocation(), type);
        if (limit == 0) {
            return;
        }
        event.setCancelled(true);
        if (event.getBreeder() instanceof Player breeder) {
            Component mob = Component.translatable(type.translationKey());
            plugin.lang().send(breeder, "antilag.breed-limit", Text.c("mob", mob), Text.p("limit", limit));
        }
    }
}
