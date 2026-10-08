package nl.pinda.framework.modules.leaderboards;

import java.util.List;
import java.util.Locale;
import org.bukkit.Material;

/** De toplijsten. De id staat in de config, de taalbestanden en de placeholders. */
public enum Board {

    MONEY("money", Material.GOLD_INGOT, "geld", "rijkste", "rijk", "baltop"),
    SKILLS("skills", Material.EXPERIENCE_BOTTLE, "skill", "levels", "level"),
    PLAYTIME("playtime", Material.CLOCK, "speeltijd", "tijd", "online"),
    KILLS("kills", Material.IRON_SWORD, "spelerkills", "pvp", "kill"),
    MOB_KILLS("mobkills", Material.ZOMBIE_HEAD, "mobs", "monsters", "mobkill"),
    DEATHS("deaths", Material.SKELETON_SKULL, "doden", "dood", "death");

    private final String id;
    private final Material icon;
    private final List<String> aliases;

    Board(String id, Material icon, String... aliases) {
        this.id = id;
        this.icon = icon;
        this.aliases = List.of(aliases);
    }

    public String id() {
        return id;
    }

    public Material icon() {
        return icon;
    }

    /** Zoekt een toplijst op id of een Nederlandse naam (geld, speeltijd, doden, ...). Null als hij niet bestaat. */
    public static Board find(String input) {
        if (input == null) {
            return null;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        for (Board board : values()) {
            if (board.id.equals(value) || board.aliases.contains(value)) {
                return board;
            }
        }
        return null;
    }
}
