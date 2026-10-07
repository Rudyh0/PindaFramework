package nl.pinda.framework.modules.skills;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;

/** De regels uit skills.yml, ingelezen in snelle tabellen. Wordt bij elke reload opnieuw gemaakt. */
final class SkillRules {

    final SkillCurve curve;
    final double xpMultiplier;
    final boolean rewardEnabled;
    final double rewardBase;
    final double rewardPerLevel;
    final Map<Integer, Double> milestones = new TreeMap<>();
    final Set<Integer> announceLevels = new HashSet<>();
    final boolean notifyDefault;
    final boolean notifyInSetup;
    final long comboMillis;
    final Set<String> disabledWorlds = new HashSet<>();
    final boolean creativeGivesXp;
    final boolean placedGivesXp;
    final boolean generatedGivesXp;
    final double spawnerMultiplier;
    final Set<CreatureSpawnEvent.SpawnReason> reducedReasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
    final Set<Skill> enabled = EnumSet.noneOf(Skill.class);

    final Map<Material, Double> mining = new EnumMap<>(Material.class);
    final Map<Material, Double> woodcutting = new EnumMap<>(Material.class);
    final Map<Material, Double> fish = new EnumMap<>(Material.class);
    final double fishTreasure;
    final double fishJunk;
    final Map<EntityType, Double> mobs = new HashMap<>();
    final double hostileDefault;
    final double passiveDefault;
    final double playerKill;
    final Map<Material, Double> smelt = new EnumMap<>(Material.class);
    final double foodDefault;
    final Map<Material, Double> craft = new EnumMap<>(Material.class);
    final Map<Material, Double> crops = new EnumMap<>(Material.class);
    final Map<Material, Double> farmBlocks = new EnumMap<>(Material.class);
    final Map<Material, Double> harvest = new EnumMap<>(Material.class);
    final double breed;
    final double archeryMultiplier;
    final double archeryDistance;
    final double archeryBonus;
    final Map<Material, Double> ingredients = new EnumMap<>(Material.class);
    final double ingredientDefault;

    /** Blokken waarvan we bijhouden of een speler ze neerzette. */
    final Set<Material> tracked = EnumSet.noneOf(Material.class);

    SkillRules(YamlConfiguration config, Logger logger) {
        curve = new SkillCurve(config.getInt("max-level", 99), config.getDouble("curve.multiplier", 0.1));
        xpMultiplier = Math.max(0, config.getDouble("xp-multiplier", 1.0));
        rewardEnabled = config.getBoolean("reward.enabled", true);
        rewardBase = config.getDouble("reward.base", 10);
        rewardPerLevel = config.getDouble("reward.per-level", 5);
        ConfigurationSection milestoneSection = config.getConfigurationSection("reward.milestones");
        if (milestoneSection != null) {
            for (String key : milestoneSection.getKeys(false)) {
                try {
                    milestones.put(Integer.parseInt(key.trim()), milestoneSection.getDouble(key));
                } catch (NumberFormatException e) {
                    logger.warning("skills.yml: '" + key + "' onder reward.milestones is geen level");
                }
            }
        }
        announceLevels.addAll(config.getIntegerList("announce-levels"));
        notifyDefault = config.getBoolean("notifications.default-enabled", true);
        notifyInSetup = config.getBoolean("notifications.show-in-setup", false);
        comboMillis = Math.max(0, (long) (config.getDouble("notifications.combo-seconds", 3) * 1000));
        for (String world : config.getStringList("disabled-worlds")) {
            disabledWorlds.add(world.toLowerCase(Locale.ROOT));
        }
        creativeGivesXp = config.getBoolean("creative-gives-xp", false);
        placedGivesXp = config.getBoolean("placed-blocks-give-xp", false);
        generatedGivesXp = config.getBoolean("generated-blocks-give-xp", false);
        spawnerMultiplier = Math.max(0, config.getDouble("spawner-mob-multiplier", 0.25));
        for (String reason : config.getStringList("reduced-spawn-reasons")) {
            try {
                reducedReasons.add(CreatureSpawnEvent.SpawnReason.valueOf(reason.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning("skills.yml: onbekende spawn-reden '" + reason + "'");
            }
        }
        for (Skill skill : Skill.values()) {
            if (config.getBoolean("skills." + skill.id() + ".enabled", true)) {
                enabled.add(skill);
            }
        }

        materials(config, "skills.mining.blocks", mining, logger);
        materials(config, "skills.woodcutting.blocks", woodcutting, logger);
        materials(config, "skills.fishing.fish", fish, logger);
        fishTreasure = config.getDouble("skills.fishing.treasure", 40);
        fishJunk = config.getDouble("skills.fishing.junk", 5);
        ConfigurationSection mobSection = config.getConfigurationSection("skills.fighting.mobs");
        if (mobSection != null) {
            for (String key : mobSection.getKeys(false)) {
                EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(key.trim().toLowerCase(Locale.ROOT)));
                if (type == null) {
                    logger.warning("skills.yml: onbekende mob '" + key + "' onder skills.fighting.mobs");
                    continue;
                }
                mobs.put(type, mobSection.getDouble(key));
            }
        }
        hostileDefault = config.getDouble("skills.fighting.default-hostile", 8);
        passiveDefault = config.getDouble("skills.fighting.default-passive", 2);
        playerKill = config.getDouble("skills.fighting.player", 0);
        materials(config, "skills.cooking.smelt", smelt, logger);
        foodDefault = config.getDouble("skills.cooking.default-food", 3);
        materials(config, "skills.cooking.craft", craft, logger);
        materials(config, "skills.farming.crops", crops, logger);
        materials(config, "skills.farming.blocks", farmBlocks, logger);
        materials(config, "skills.farming.harvest", harvest, logger);
        breed = config.getDouble("skills.farming.breed", 6);
        archeryMultiplier = config.getDouble("skills.archery.multiplier", 1.0);
        archeryDistance = config.getDouble("skills.archery.distance", 30);
        archeryBonus = config.getDouble("skills.archery.distance-bonus", 0.5);
        materials(config, "skills.alchemy.ingredients", ingredients, logger);
        ingredientDefault = config.getDouble("skills.alchemy.default", 6);

        tracked.addAll(mining.keySet());
        tracked.addAll(woodcutting.keySet());
        tracked.addAll(farmBlocks.keySet());
    }

    private static void materials(YamlConfiguration config, String path, Map<Material, Double> target, Logger logger) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key.trim());
            if (material == null) {
                logger.warning("skills.yml: onbekend blok of item '" + key + "' onder " + path);
                continue;
            }
            target.put(material, section.getDouble(key));
        }
    }

    boolean isEnabled(Skill skill) {
        return enabled.contains(skill);
    }

    /** Het geld (in hele PindaCredits) voor het bereiken van dit level. */
    double reward(int level) {
        if (!rewardEnabled) {
            return 0;
        }
        return Math.max(0, rewardBase + rewardPerLevel * level) + milestones.getOrDefault(level, 0.0);
    }
}
