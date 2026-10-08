package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import nl.pinda.framework.modules.world.DragonControl;
import nl.pinda.framework.modules.world.ExplosionSource;
import nl.pinda.framework.modules.world.WorldControlModule;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Wereldbeheer: explosies, endermen en akkers, phantoms, spawnen en de ender dragon. */
final class WorldApi extends PanelApi {

    private static final String FILE = "modules/world.yml";
    private static final Pattern WORLD_NAME = Pattern.compile("[A-Za-z0-9_./-]{1,64}");
    private static final List<String> LIMITS = List.of("monsters", "animals", "water-animals", "water-ambient",
            "underground-water", "ambient", "axolotls");

    private final ConfigEditor editor;

    WorldApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/world", PanelUser.SERVER, request -> overview());
        server.post("/api/world/settings", PanelUser.CONFIG, this::save);
        server.post("/api/world/dragon/respawn", PanelUser.SERVER, this::respawn);
    }

    private WorldControlModule world() throws ApiException {
        return require(WorldControlModule.class, "world");
    }

    private Object overview() throws Exception {
        WorldControlModule world = world();
        YamlConfiguration yaml = editor.read(FILE);
        Map<String, Object> live = sync(() -> {
            List<String> worlds = new ArrayList<>();
            for (World loaded : plugin.getServer().getWorlds()) {
                worlds.add(loaded.getName());
            }
            List<Map<String, Object>> limits = new ArrayList<>();
            for (WorldControlModule.SpawnLimit limit : world.spawnLimits()) {
                limits.add(map("id", limit.id(), "percent", limit.percent(), "base", limit.base(), "current", limit.current()));
            }
            return map("worlds", worlds, "limits", limits, "dragon", dragon(world.dragonStatus()));
        });

        List<Map<String, Object>> sources = new ArrayList<>();
        for (ExplosionSource source : ExplosionSource.values()) {
            sources.add(map("id", source.id(), "damage", yaml.getBoolean("explosions.block-damage." + source.id(), source.defaultDamage())));
        }
        List<Map<String, Object>> mobs = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("spawning.mobs");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                mobs.add(map("type", key.toLowerCase(Locale.ROOT), "chance", Math.max(0, Math.min(100, section.getInt(key, 100)))));
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> dragon = (Map<String, Object>) live.get("dragon");
        dragon.put("settings", map(
                "respawn", yaml.getBoolean("dragon.respawn", true),
                "respawnMinutes", yaml.getInt("dragon.respawn-minutes", 120),
                "eggEveryKill", yaml.getBoolean("dragon.egg-every-kill", true),
                "announceKill", yaml.getBoolean("dragon.announce-kill", true),
                "announceRespawn", yaml.getBoolean("dragon.announce-respawn", true)));
        return map(
                "worlds", live.get("worlds"),
                "explosions", map(
                        "sources", sources,
                        "tntChain", yaml.getBoolean("explosions.tnt-chain", true),
                        "protectEntities", yaml.getBoolean("explosions.protect-entities", true),
                        "worldsWithDamage", yaml.getStringList("explosions.worlds-with-damage")),
                "griefing", map(
                        "endermanPickup", yaml.getBoolean("griefing.enderman-pickup", false),
                        "trampleFarmland", yaml.getBoolean("griefing.trample-farmland", true)),
                "phantoms", map(
                        "enabled", yaml.getBoolean("phantoms.enabled", false),
                        "playerChoice", yaml.getBoolean("phantoms.player-choice", false)),
                "spawning", map(
                        "limits", live.get("limits"),
                        "mobs", mobs,
                        "mobTypes", WorldControlModule.mobTypes(),
                        "disabledWorlds", yaml.getStringList("spawning.disabled-worlds"),
                        "maxPercent", WorldControlModule.maxPercent()),
                "dragon", dragon);
    }

    private static Map<String, Object> dragon(DragonControl.Status status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("end", status.end());
        map.put("alive", status.alive());
        map.put("loaded", status.loaded());
        map.put("health", Math.round(status.health()));
        map.put("maxHealth", Math.round(status.maxHealth()));
        map.put("killedAt", status.killedAt());
        map.put("killer", status.killer());
        map.put("kills", status.kills());
        map.put("respawning", status.respawning());
        map.put("forced", status.forced());
        map.put("respawnAt", status.respawnAt());
        map.put("playersNear", status.playersNear());
        return map;
    }

    // ============================================================ opslaan

    private Object save(PanelRequest request) throws Exception {
        world();
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(FILE);

        JsonElement damage = body.get("blockDamage");
        if (damage != null && damage.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : damage.getAsJsonObject().entrySet()) {
                ExplosionSource source = ExplosionSource.byId(entry.getKey());
                if (source == null) {
                    throw ApiException.badRequest("Onbekende explosie: " + entry.getKey());
                }
                yaml.set("explosions.block-damage." + source.id(), bool(entry.getValue(), entry.getKey()));
            }
        }
        setBool(body, "tntChain", yaml, "explosions.tnt-chain");
        setBool(body, "protectEntities", yaml, "explosions.protect-entities");
        setWorlds(body, "worldsWithDamage", yaml, "explosions.worlds-with-damage");
        setBool(body, "endermanPickup", yaml, "griefing.enderman-pickup");
        setBool(body, "trampleFarmland", yaml, "griefing.trample-farmland");
        setBool(body, "phantoms", yaml, "phantoms.enabled");
        setBool(body, "phantomChoice", yaml, "phantoms.player-choice");

        JsonElement limits = body.get("limits");
        if (limits != null && limits.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : limits.getAsJsonObject().entrySet()) {
                if (!LIMITS.contains(entry.getKey())) {
                    throw ApiException.badRequest("Onbekende groep mobs: " + entry.getKey());
                }
                int percent = integer(entry.getValue(), "percentage");
                if (percent < 0 || percent > WorldControlModule.maxPercent()) {
                    throw ApiException.badRequest("Een percentage moet tussen 0 en " + WorldControlModule.maxPercent() + " liggen.");
                }
                yaml.set("spawning.limits." + entry.getKey(), percent);
            }
        }
        JsonElement mobs = body.get("mobs");
        if (mobs != null && mobs.isJsonObject()) {
            Map<String, Integer> chances = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : mobs.getAsJsonObject().entrySet()) {
                String type = entry.getKey().trim().toLowerCase(Locale.ROOT);
                if (!WorldControlModule.isMobType(type)) {
                    throw ApiException.badRequest("Onbekende mob: " + entry.getKey());
                }
                int chance = integer(entry.getValue(), "kans");
                if (chance < 0 || chance > 100) {
                    throw ApiException.badRequest("Een kans moet tussen 0 en 100 procent liggen.");
                }
                chances.put(type, chance);
            }
            if (chances.size() > 200) {
                throw ApiException.badRequest("Te veel mobs.");
            }
            yaml.set("spawning.mobs", null);
            ConfigurationSection section = yaml.createSection("spawning.mobs");
            for (Map.Entry<String, Integer> entry : chances.entrySet()) {
                section.set(entry.getKey(), entry.getValue());
            }
        }
        setWorlds(body, "spawnDisabledWorlds", yaml, "spawning.disabled-worlds");

        setBool(body, "dragonRespawn", yaml, "dragon.respawn");
        if (body.has("respawnMinutes")) {
            int minutes = integer(body.get("respawnMinutes"), "minuten");
            if (minutes < 1 || minutes > 43_200) {
                throw ApiException.badRequest("De draak kan na 1 minuut tot 30 dagen terugkomen.");
            }
            yaml.set("dragon.respawn-minutes", minutes);
        }
        setBool(body, "eggEveryKill", yaml, "dragon.egg-every-kill");
        setBool(body, "announceKill", yaml, "dragon.announce-kill");
        setBool(body, "announceRespawn", yaml, "dragon.announce-respawn");

        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
        module.log().add(request, "wereldinstellingen", null, null);
        return overview();
    }

    private Object respawn(PanelRequest request) throws Exception {
        WorldControlModule world = world();
        DragonControl.Result result = sync(world::respawnDragon);
        String message = switch (result) {
            case STARTED -> "De ender dragon komt terug.";
            case WAITING -> "De ender dragon komt terug zodra er iemand in de End is.";
            case ALIVE -> throw ApiException.badRequest("De ender dragon leeft nog.");
            case BUSY -> throw ApiException.badRequest("De ender dragon komt al terug.");
            case NO_END -> throw ApiException.badRequest("Er is geen End op deze server.");
            case FAILED -> throw new ApiException(500, "Het lukte niet om de ender dragon terug te laten komen. Kijk in de console.");
        };
        module.log().add(request, "ender dragon terug", null, result == DragonControl.Result.WAITING ? "zodra er iemand in de End is" : null);
        Map<String, Object> overview = new LinkedHashMap<>(castMap(overview()));
        overview.put("message", message);
        return overview;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    // ============================================================ helpers

    private static boolean bool(JsonElement element, String name) throws ApiException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw ApiException.badRequest("Ongeldige waarde voor " + name + ".");
        }
        return element.getAsBoolean();
    }

    private static int integer(JsonElement element, String name) throws ApiException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw ApiException.badRequest("Ongeldige waarde voor " + name + ".");
        }
        double value = element.getAsDouble();
        if (value != Math.rint(value) || Math.abs(value) > 1_000_000) {
            throw ApiException.badRequest("Ongeldige waarde voor " + name + ".");
        }
        return (int) value;
    }

    private static void setBool(JsonObject body, String key, YamlConfiguration yaml, String path) throws ApiException {
        if (body.has(key)) {
            yaml.set(path, bool(body.get(key), key));
        }
    }

    private static void setWorlds(JsonObject body, String key, YamlConfiguration yaml, String path) throws ApiException {
        JsonElement element = body.get(key);
        if (element == null) {
            return;
        }
        if (!element.isJsonArray()) {
            throw ApiException.badRequest("Ongeldige lijst met werelden.");
        }
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            String name = item.isJsonPrimitive() ? item.getAsString().trim() : "";
            if (name.isEmpty()) {
                continue;
            }
            if (!WORLD_NAME.matcher(name).matches()) {
                throw ApiException.badRequest("Ongeldige wereldnaam: " + name);
            }
            names.add(name);
        }
        if (names.size() > 50) {
            throw ApiException.badRequest("Te veel werelden.");
        }
        yaml.set(path, new ArrayList<>(names));
    }
}
