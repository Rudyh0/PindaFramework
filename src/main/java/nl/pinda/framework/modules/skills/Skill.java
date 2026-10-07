package nl.pinda.framework.modules.skills;

import java.util.Locale;
import org.bukkit.Material;

/** De skills. De namen en uitleg staan in de taalbestanden onder skills.names en skills.info. */
public enum Skill {

    MINING("mining", Material.IRON_PICKAXE),
    WOODCUTTING("woodcutting", Material.IRON_AXE),
    FISHING("fishing", Material.FISHING_ROD),
    FIGHTING("fighting", Material.IRON_SWORD),
    COOKING("cooking", Material.COOKED_BEEF),
    FARMING("farming", Material.WHEAT),
    ARCHERY("archery", Material.BOW),
    ALCHEMY("alchemy", Material.BREWING_STAND);

    private final String id;
    private final Material icon;

    Skill(String id, Material icon) {
        this.id = id;
        this.icon = icon;
    }

    public String id() {
        return id;
    }

    public Material icon() {
        return icon;
    }

    /** Zoekt een skill op id ("mining") of op een naam uit de taalbestanden ("Mijnbouw"). */
    public static Skill find(String input, java.util.function.Function<Skill, String> displayName) {
        if (input == null) {
            return null;
        }
        String clean = input.trim().toLowerCase(Locale.ROOT);
        for (Skill skill : values()) {
            if (skill.id.equals(clean) || skill.name().equalsIgnoreCase(clean)) {
                return skill;
            }
            String name = displayName == null ? null : displayName.apply(skill);
            if (name != null && name.equalsIgnoreCase(clean)) {
                return skill;
            }
        }
        return null;
    }
}
