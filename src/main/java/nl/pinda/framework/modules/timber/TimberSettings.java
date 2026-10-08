package nl.pinda.framework.modules.timber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;

/** De instellingen uit modules/timber.yml, één keer ingelezen (opnieuw na /pinda reload). */
final class TimberSettings {

    /** Wanneer omhakken werkt. */
    enum Mode { ALWAYS, SNEAKING, NOT_SNEAKING }

    /** Een boomsoort: welk hout, welke bladeren en welke sapling. */
    record TreeType(String id, Set<Material> logs, Set<Material> leaves, Material sapling,
                    double branchDistance, int leafDistance) {
    }

    final Mode mode;
    final boolean creative;
    final Set<String> disabledWorlds = new HashSet<>();
    final long cooldownMillis;

    final int maxLogs;
    final int minLeaves;
    final boolean onlyUpwards;
    final boolean leaves;
    final boolean requireGround;
    /** Waar een echte boom op staat: aarde, gras, modder, mangrovewortels, ... */
    final Set<Material> ground = EnumSet.noneOf(Material.class);

    final boolean axeOnly;
    final boolean realisticDamage;
    final boolean protectTool;

    final boolean replant;
    final long replantProtectMillis;
    final Set<Material> soil = EnumSet.noneOf(Material.class);

    final String dropMode;
    final boolean leafDrops;

    final boolean animate;
    final int durationTicks;
    final int lingerTicks;
    final int maxBlocks;
    final double damage;
    final String soundFall;
    final String soundLand;
    final boolean particles;

    final boolean breakEvents;
    final Set<Material> attachments = EnumSet.noneOf(Material.class);
    final List<TreeType> trees = new ArrayList<>();
    final Map<Material, List<TreeType>> byLog = new EnumMap<>(Material.class);

    TimberSettings(ConfigurationSection cfg, Logger logger) {
        mode = switch (cfg.getString("only-while", "always").toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "sneaking", "bukken" -> Mode.SNEAKING;
            case "not-sneaking", "niet-bukken" -> Mode.NOT_SNEAKING;
            default -> Mode.ALWAYS;
        };
        creative = cfg.getBoolean("creative", false);
        for (String world : cfg.getStringList("disabled-worlds")) {
            disabledWorlds.add(world.toLowerCase(Locale.ROOT));
        }
        cooldownMillis = Math.max(0, cfg.getLong("cooldown-seconds", 0)) * 1000L;

        maxLogs = Math.max(1, cfg.getInt("detection.max-logs", 250));
        minLeaves = Math.max(0, cfg.getInt("detection.min-leaves", 5));
        onlyUpwards = cfg.getBoolean("detection.only-upwards", true);
        leaves = cfg.getBoolean("detection.leaves", true);
        requireGround = cfg.getBoolean("detection.require-ground", true);

        axeOnly = cfg.getBoolean("tool.axe-only", true);
        realisticDamage = cfg.getBoolean("tool.realistic-damage", true);
        protectTool = cfg.getBoolean("tool.protect", true);

        replant = cfg.getBoolean("replant.enabled", true);
        replantProtectMillis = Math.max(0, cfg.getLong("replant.protect-seconds", 3)) * 1000L;
        materials(cfg.getStringList("replant.soil"), soil, logger);
        ground.addAll(soil);
        ground.addAll(Tag.DIRT.getValues());
        for (String name : List.of("MANGROVE_ROOTS", "MUDDY_MANGROVE_ROOTS", "MUD", "MYCELIUM", "PODZOL", "MOSS_BLOCK", "PALE_MOSS_BLOCK")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                ground.add(material);
            }
        }

        String drops = cfg.getString("drops.mode", "landing").toLowerCase(Locale.ROOT);
        dropMode = drops.equals("base") || drops.equals("inventory") ? drops : "landing";
        leafDrops = cfg.getBoolean("drops.leaves", true);

        animate = !"none".equalsIgnoreCase(cfg.getString("animation.type", "fall"));
        durationTicks = Math.max(6, Math.min(200, cfg.getInt("animation.duration-ticks", 30)));
        lingerTicks = Math.max(0, Math.min(200, cfg.getInt("animation.linger-ticks", 20)));
        maxBlocks = Math.max(0, cfg.getInt("animation.max-blocks", 500));
        damage = Math.max(0, cfg.getDouble("animation.damage", 2));
        soundFall = cfg.getString("animation.sound-fall", "");
        soundLand = cfg.getString("animation.sound-land", "");
        particles = cfg.getBoolean("animation.particles", true);

        breakEvents = cfg.getBoolean("break-events", true);
        materials(cfg.getStringList("attachments"), attachments, logger);

        ConfigurationSection section = cfg.getConfigurationSection("trees");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection tree = section.getConfigurationSection(id);
                if (tree == null || !tree.getBoolean("enabled", true)) {
                    continue;
                }
                Set<Material> logs = EnumSet.noneOf(Material.class);
                Set<Material> leafTypes = EnumSet.noneOf(Material.class);
                materials(tree.getStringList("logs"), logs, logger);
                materials(tree.getStringList("leaves"), leafTypes, logger);
                Material sapling = material(tree.getString("sapling", ""), logger);
                if (logs.isEmpty()) {
                    continue;
                }
                TreeType type = new TreeType(id, Collections.unmodifiableSet(logs), Collections.unmodifiableSet(leafTypes),
                        sapling, Math.max(0.5, tree.getDouble("branch-distance", 4)), Math.max(1, tree.getInt("leaf-distance", 6)));
                trees.add(type);
                for (Material log : logs) {
                    byLog.computeIfAbsent(log, key -> new ArrayList<>()).add(type);
                }
            }
        }
    }

    private static void materials(List<String> names, Set<Material> target, Logger logger) {
        for (String name : names) {
            Material material = material(name, logger);
            if (material != null) {
                target.add(material);
            }
        }
    }

    private static Material material(String name, Logger logger) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(name.trim());
        if (material == null) {
            logger.warning("modules/timber.yml: onbekend blok '" + name + "' (overgeslagen)");
        }
        return material;
    }
}
