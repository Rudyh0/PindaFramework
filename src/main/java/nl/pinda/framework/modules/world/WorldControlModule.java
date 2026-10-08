package nl.pinda.framework.modules.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.player.PlayerSetting;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

/**
 * Wereldbeheer: explosies zonder blokschade, endermen en akkers, phantoms, hoeveel mobs er
 * spawnen en de ender dragon die vanzelf terugkomt.
 */
public final class WorldControlModule extends PindaModule {

    public static final String DRAGON = "pinda.world.dragon";
    static final String PHANTOM_SETTING = "phantoms";

    /** Een groep mobs met zijn maximum: normaal en nu (in de hoofdwereld). */
    public record SpawnLimit(String id, int percent, int base, int current) {
    }

    private GriefGuard grief;
    private SpawnControl spawning;
    private DragonControl dragon;

    public WorldControlModule(PindaFramework plugin) {
        super(plugin, "world");
    }

    @Override
    protected void onEnable() {
        grief = new GriefGuard(this);
        spawning = new SpawnControl(plugin, this);
        dragon = new DragonControl(plugin, this);
        listen(grief);
        listen(spawning);
        listen(dragon);
        command(new DragonCommand(plugin, this));
        spawning.applyAll();
        registerSetting();
        repeat(dragon::check, 20L * 10, 20L * 5);
    }

    @Override
    protected void onDisable() {
        if (spawning != null) {
            spawning.restore();
        }
        if (dragon != null) {
            dragon.stop();
        }
        plugin.settings().unregister(PHANTOM_SETTING);
    }

    @Override
    protected void onReload() {
        grief.reload();
        spawning.reload();
        spawning.applyAll();
        registerSetting();
    }

    /** Spelers kiezen zelf of ze phantoms willen, als dat aan staat. */
    private void registerSetting() {
        if (config().getBoolean("phantoms.player-choice", false)) {
            plugin.settings().register(new PlayerSetting(PHANTOM_SETTING, config().getBoolean("phantoms.enabled", false),
                    Material.PHANTOM_MEMBRANE, false));
        } else {
            plugin.settings().unregister(PHANTOM_SETTING);
        }
    }

    /** De instellingen (modules/world.yml). */
    YamlConfiguration settings() {
        return config();
    }

    // ============================================================ voor /dragon en het paneel

    public DragonControl.Status dragonStatus() {
        return dragon.status();
    }

    public DragonControl.Result respawnDragon() {
        return dragon.respawnNow();
    }

    /** De maximums per groep mobs, zoals ze nu in de hoofdwereld gelden. */
    public List<SpawnLimit> spawnLimits() {
        List<SpawnLimit> list = new ArrayList<>();
        World main = plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().getFirst();
        for (SpawnControl.Group group : SpawnControl.Group.values()) {
            int base = main == null ? 0 : spawning.base(main, group);
            int current = main == null ? 0 : main.getSpawnLimit(group.category);
            list.add(new SpawnLimit(group.id, spawning.percent(group), base, current));
        }
        return list;
    }

    /** De kans per mob (0-100) uit de config. */
    public Map<String, Integer> mobChances() {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (Map.Entry<EntityType, Integer> entry : spawning.chances().entrySet()) {
            map.put(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return map;
    }

    /** Alle mobs waarvoor je een kans kunt instellen, op naam. */
    public static List<String> mobTypes() {
        List<String> list = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            if (SpawnControl.spawnableMob(type) && type != EntityType.ENDER_DRAGON && type != EntityType.WITHER) {
                list.add(type.name().toLowerCase(Locale.ROOT));
            }
        }
        list.sort(null);
        return list;
    }

    public static boolean isMobType(String name) {
        EntityType type = SpawnControl.mobType(name);
        return type != null && type != EntityType.ENDER_DRAGON && type != EntityType.WITHER;
    }

    public static int maxPercent() {
        return SpawnControl.MAX_PERCENT;
    }

    /** "2u 5m", "3d 4u" of "12m", in de taal van de lezer. */
    public String duration(long millis, String code) {
        String raw = plugin.lang().raw(code, "world.units");
        String[] units = (raw == null ? "d,u,m" : raw).split(",");
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long rest = minutes % 60;
        if (days > 0) {
            return days + unit(units, 0) + (hours > 0 ? " " + hours + unit(units, 1) : "");
        }
        if (hours > 0) {
            return hours + unit(units, 1) + (rest > 0 ? " " + rest + unit(units, 2) : "");
        }
        return rest + unit(units, 2);
    }

    private static String unit(String[] units, int index) {
        return index < units.length ? units[index].trim() : "";
    }
}
