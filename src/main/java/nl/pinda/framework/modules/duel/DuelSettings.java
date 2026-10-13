package nl.pinda.framework.modules.duel;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import nl.pinda.framework.modules.economy.Money;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

/** De instellingen uit modules/duel.yml, in één keer ingelezen. */
public record DuelSettings(
        boolean blockPvp,
        String pvpMessage,
        int pvpCooldownSeconds,
        int inviteSeconds,
        int wagerSeconds,
        int rematchSeconds,
        int maxFightMinutes,
        int arenaRadius,
        boolean stakeMoney,
        boolean stakeItems,
        long moneyStep,
        long maxMoney,
        Set<Material> blockedItems,
        boolean allowTotems,
        boolean forceSurvival,
        boolean healOnStart,
        Set<String> allowedCommands,
        boolean broadcastStart,
        boolean broadcastResult,
        Spot spawn1,
        Spot spawn2,
        Spot spectate,
        boolean protect,
        int protectMargin,
        Area area) {

    public static final int MAX_MESSAGE = 600;
    public static final String DEFAULT_PVP_MESSAGE = "<prefix><error>Vechten zonder consent? Dat mag niet!</error> "
            + "<text>Daag elkaar uit en ga de ring in. <challenge><primary>[Daag <player> uit]</primary></challenge>";
    /** Commando's die tijdens een gevecht altijd mogen (het gevecht zelf). */
    static final Set<String> ALWAYS_ALLOWED = Set.of("duel", "1v1", "uitdagen");

    public static DuelSettings read(YamlConfiguration config) {
        Set<Material> blocked = new LinkedHashSet<>();
        for (String name : config.getStringList("stakes.blocked-items")) {
            Material material = name == null ? null : Material.matchMaterial(name.trim());
            if (material != null) {
                blocked.add(material);
            }
        }
        Set<String> commands = new HashSet<>();
        for (String command : config.getStringList("fight.allowed-commands")) {
            String label = normalize(command);
            if (!label.isEmpty()) {
                commands.add(label);
            }
        }
        String message = config.getString("messages.pvp", DEFAULT_PVP_MESSAGE);
        if (message != null && message.length() > MAX_MESSAGE) {
            message = message.substring(0, MAX_MESSAGE);
        }
        return new DuelSettings(
                config.getBoolean("block-pvp", true),
                message == null ? "" : message,
                clamp(config.getInt("pvp-message-cooldown", 5), 1, 300),
                clamp(config.getInt("invite-seconds", 60), 10, 600),
                clamp(config.getInt("wager-seconds", 120), 20, 900),
                clamp(config.getInt("rematch-seconds", 20), 5, 120),
                clamp(config.getInt("max-fight-minutes", 5), 0, 60),
                clamp(config.getInt("arena-radius", 40), 0, 1000),
                config.getBoolean("stakes.money", true),
                config.getBoolean("stakes.items", true),
                Math.max(1, Money.toCents(Math.max(0.01, config.getDouble("stakes.money-step", 10)))),
                Math.max(0, Money.toCents(Math.max(0, config.getDouble("stakes.max-money", 0)))),
                Set.copyOf(blocked),
                config.getBoolean("fight.allow-totems", true),
                config.getBoolean("fight.force-survival", true),
                config.getBoolean("fight.heal-on-start", true),
                Set.copyOf(commands),
                config.getBoolean("broadcast.start", true),
                config.getBoolean("broadcast.result", true),
                Spot.read(config.getConfigurationSection("arena.spawn1")),
                Spot.read(config.getConfigurationSection("arena.spawn2")),
                Spot.read(config.getConfigurationSection("arena.spectate")),
                config.getBoolean("protection.enabled", true),
                clamp(config.getInt("protection.margin", 15), 0, 200),
                Area.read(config.getConfigurationSection("arena.area")));
    }

    /** "/Essentials:Msg" → "msg". */
    static String normalize(String command) {
        if (command == null) {
            return "";
        }
        String label = command.strip().toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        int space = label.indexOf(' ');
        if (space >= 0) {
            label = label.substring(0, space);
        }
        int colon = label.indexOf(':');
        return colon < 0 ? label : label.substring(colon + 1);
    }

    /** Mag dit commando tijdens een gevecht? */
    boolean commandAllowed(String line) {
        String label = normalize(line);
        return ALWAYS_ALLOWED.contains(label) || allowedCommands.contains(label);
    }

    /** Zijn beide plekken voor de spelers ingesteld? */
    public boolean arenaReady() {
        return spawn1 != null && spawn2 != null;
    }

    /** Het midden tussen de twee plekken (voor de grens van de arena), of null. */
    Spot center() {
        if (!arenaReady() || !spawn1.world().equals(spawn2.world())) {
            return null;
        }
        return new Spot(spawn1.world(), (spawn1.x() + spawn2.x()) / 2, (spawn1.y() + spawn2.y()) / 2,
                (spawn1.z() + spawn2.z()) / 2, 0, 0);
    }

    /**
     * Het beschermde gebied (blokken, zonder de hoogte: die vult de module in met de hoogte van de
     * wereld): het ingestelde gebied, of anders alles rond de plekken van de arena met de marge.
     * Null als er nog niets is ingesteld.
     */
    Area autoArea() {
        if (spawn1 == null && spawn2 == null) {
            return null;
        }
        Spot first = spawn1 != null ? spawn1 : spawn2;
        int minX = first.blockX();
        int maxX = minX;
        int minZ = first.blockZ();
        int maxZ = minZ;
        for (Spot spot : new Spot[] {spawn1, spawn2, spectate}) {
            if (spot == null || !spot.world().equals(first.world())) {
                continue;
            }
            minX = Math.min(minX, spot.blockX());
            maxX = Math.max(maxX, spot.blockX());
            minZ = Math.min(minZ, spot.blockZ());
            maxZ = Math.max(maxZ, spot.blockZ());
        }
        return new Area(first.world(), minX, 0, minZ, maxX, 0, maxZ).grow(protectMargin);
    }

    List<String> allowedCommandList() {
        return allowedCommands.stream().sorted().toList();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
