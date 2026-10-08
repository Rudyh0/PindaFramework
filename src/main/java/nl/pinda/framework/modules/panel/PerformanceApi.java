package nl.pinda.framework.modules.panel;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import nl.pinda.framework.modules.antilag.AntilagModule;
import org.bukkit.configuration.file.YamlConfiguration;

/** Prestaties en antilag: TPS, drukste chunks, items opruimen en de belangrijkste instellingen. */
final class PerformanceApi extends PanelApi {

    private static final String FILE = "modules/antilag.yml";

    private final ConfigEditor editor;
    /** De live-gegevens even bewaren: alle entities tellen is werk voor de server. */
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    PerformanceApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/performance", PanelUser.SERVER, request -> overview());
        server.post("/api/performance/clear", PanelUser.SERVER, this::clear);
        server.post("/api/performance/settings", PanelUser.CONFIG, this::saveSettings);
    }

    private AntilagModule antilag() throws ApiException {
        return require(AntilagModule.class, "antilag");
    }

    private Object overview() throws Exception {
        AntilagModule antilag = antilag();
        YamlConfiguration yaml = editor.read(FILE);
        Map<String, Object> live = live(antilag);
        live.put("settings", map(
                "clearEnabled", yaml.getBoolean("clear-items.enabled", true),
                "intervalMinutes", yaml.getInt("clear-items.interval-minutes", 15),
                "mobLimitEnabled", yaml.getBoolean("mob-limit.enabled", true),
                "perType", yaml.getInt("mob-limit.per-type", 40),
                "guardEnabled", yaml.getBoolean("lag-guard.enabled", true),
                "tpsBelow", yaml.getDouble("lag-guard.tps-below", 15.0)));
        return live;
    }

    private Map<String, Object> live(AntilagModule antilag) throws Exception {
        Map<String, Object> recent = cached;
        if (recent != null && System.currentTimeMillis() - cachedAt < 5000) {
            return new LinkedHashMap<>(recent);
        }
        Map<String, Object> live = sync(() -> {
            double[] tps = plugin.getServer().getTPS();
            List<Map<String, Object>> worlds = new ArrayList<>();
            for (AntilagModule.WorldLoad world : antilag.worlds()) {
                worlds.add(map("name", world.name(), "environment", world.environment(), "players", world.players(),
                        "entities", world.entities(), "living", world.living(), "items", world.items(), "chunks", world.chunks()));
            }
            List<Map<String, Object>> chunks = new ArrayList<>();
            for (AntilagModule.ChunkLoad chunk : antilag.busiest(10)) {
                chunks.add(map("world", chunk.world(), "x", chunk.blockX(), "z", chunk.blockZ(),
                        "entities", chunk.entities(), "items", chunk.items(), "types", chunk.types()));
            }
            return map(
                    "tps", new double[]{round(Math.min(20, tps[0])), round(Math.min(20, tps[1])), round(Math.min(20, tps[2]))},
                    "recentTps", round(antilag.recentTps()),
                    "mspt", round(plugin.getServer().getAverageTickTime()),
                    "lagging", antilag.lagging(),
                    "laggingSince", antilag.lagging() ? antilag.laggingSince() : 0,
                    "threshold", antilag.tpsThreshold(),
                    "worlds", worlds, "chunks", chunks,
                    "clear", map("next", antilag.nextClear(), "counting", antilag.clearCounting(),
                            "last", antilag.lastClear(), "lastCount", antilag.lastCount()));
        });
        cached = live;
        cachedAt = System.currentTimeMillis();
        return new LinkedHashMap<>(live);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private Object clear(PanelRequest request) throws Exception {
        AntilagModule antilag = antilag();
        cachedAt = 0;
        boolean now = request.body().has("now") && request.body().get("now").getAsBoolean();
        if (now) {
            int removed = sync(antilag::clearNow);
            module.log().add(request, "items opgeruimd", null, removed + " items");
            return map("removed", removed);
        }
        int seconds = request.body().has("seconds") ? request.body().get("seconds").getAsInt() : 30;
        if (seconds < 5 || seconds > 600) {
            throw ApiException.badRequest("De aftelling moet tussen 5 en 600 seconden zijn.");
        }
        boolean started = sync(() -> antilag.startClear(seconds));
        if (!started) {
            throw ApiException.badRequest("Er loopt al een aftelling om items op te ruimen.");
        }
        module.log().add(request, "items opruimen", null, "over " + seconds + " seconden");
        return map("seconds", seconds);
    }

    private Object saveSettings(PanelRequest request) throws Exception {
        antilag();
        JsonObject body = request.body();
        YamlConfiguration yaml = editor.read(FILE);
        if (body.has("clearEnabled")) {
            yaml.set("clear-items.enabled", body.get("clearEnabled").getAsBoolean());
        }
        if (body.has("intervalMinutes")) {
            int minutes = body.get("intervalMinutes").getAsInt();
            if (minutes < 1 || minutes > 1440) {
                throw ApiException.badRequest("Opruimen kan elke 1 tot 1440 minuten.");
            }
            yaml.set("clear-items.interval-minutes", minutes);
        }
        if (body.has("mobLimitEnabled")) {
            yaml.set("mob-limit.enabled", body.get("mobLimitEnabled").getAsBoolean());
        }
        if (body.has("perType")) {
            int perType = body.get("perType").getAsInt();
            if (perType < 0 || perType > 10_000) {
                throw ApiException.badRequest("Ongeldig maximum per soort.");
            }
            yaml.set("mob-limit.per-type", perType);
        }
        if (body.has("guardEnabled")) {
            yaml.set("lag-guard.enabled", body.get("guardEnabled").getAsBoolean());
        }
        if (body.has("tpsBelow")) {
            double tps = body.get("tpsBelow").getAsDouble();
            if (tps < 1 || tps > 19.5) {
                throw ApiException.badRequest("De TPS-grens moet tussen 1 en 19,5 liggen.");
            }
            yaml.set("lag-guard.tps-below", tps);
        }
        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
        cachedAt = 0;
        module.log().add(request, "antilag-instellingen", null, null);
        return overview();
    }
}
