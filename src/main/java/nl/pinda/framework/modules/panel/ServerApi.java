package nl.pinda.framework.modules.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import nl.pinda.framework.lang.Text;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Serverbeheer: tijd, weer, mededelingen, whitelist, opslaan, herladen en stoppen. */
final class ServerApi extends PanelApi {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    ServerApi(PanelModule module) {
        super(module);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/server", PanelUser.SERVER, this::overview);
        server.post("/api/server/time", PanelUser.SERVER, this::time);
        server.post("/api/server/weather", PanelUser.SERVER, this::weather);
        server.post("/api/server/broadcast", PanelUser.SERVER, this::broadcast);
        server.post("/api/server/whitelist", PanelUser.SERVER, this::whitelist);
        server.post("/api/server/save", PanelUser.SERVER, this::save);
        server.post("/api/server/reload", PanelUser.SERVER, this::reload);
        server.post("/api/server/stop", PanelUser.STOP, this::stop);
    }

    private Object overview(PanelRequest request) throws Exception {
        return sync(() -> map(
                "worlds", new DashboardApi(module).worlds(),
                "whitelist", whitelistData(),
                "maxPlayers", plugin.getServer().getMaxPlayers(),
                "viewDistance", plugin.getServer().getViewDistance(),
                "simulationDistance", plugin.getServer().getSimulationDistance()));
    }

    private Map<String, Object> whitelistData() {
        List<String> names = new ArrayList<>();
        for (OfflinePlayer player : plugin.getServer().getWhitelistedPlayers()) {
            names.add(player.getName() == null ? player.getUniqueId().toString() : player.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return map("enabled", plugin.getServer().hasWhitelist(), "players", names);
    }

    /** De gekozen wereld, of alle normale werelden (geen nether/end) als er niets gekozen is. */
    private List<World> worlds(String name) throws ApiException {
        if (name != null) {
            World world = plugin.getServer().getWorld(name);
            if (world == null) {
                throw ApiException.notFound("Wereld '" + name + "' bestaat niet.");
            }
            return List.of(world);
        }
        List<World> worlds = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    private Object time(PanelRequest request) throws Exception {
        String value = request.string("value", "Kies een tijd.").toLowerCase(Locale.ROOT);
        long ticks = switch (value) {
            case "sunrise" -> 23000L;
            case "day" -> 1000L;
            case "noon" -> 6000L;
            case "sunset" -> 12000L;
            case "night" -> 13000L;
            case "midnight" -> 18000L;
            default -> throw ApiException.badRequest("Onbekende tijd.");
        };
        String worldName = request.optString("world");
        String names = sync(() -> {
            List<String> done = new ArrayList<>();
            for (World world : worlds(worldName)) {
                world.setTime(ticks);
                done.add(world.getName());
            }
            return String.join(", ", done);
        });
        module.log().add(request, "tijd", names, value);
        return map("worlds", names);
    }

    private Object weather(PanelRequest request) throws Exception {
        String value = request.string("value", "Kies het weer.").toLowerCase(Locale.ROOT);
        if (!value.equals("clear") && !value.equals("rain") && !value.equals("thunder")) {
            throw ApiException.badRequest("Onbekend weer.");
        }
        String worldName = request.optString("world");
        String names = sync(() -> {
            List<String> done = new ArrayList<>();
            for (World world : worlds(worldName)) {
                switch (value) {
                    case "clear" -> {
                        world.setStorm(false);
                        world.setThundering(false);
                        world.setClearWeatherDuration(20 * 60 * 15);
                    }
                    case "rain" -> {
                        world.setStorm(true);
                        world.setThundering(false);
                        world.setWeatherDuration(20 * 60 * 10);
                    }
                    default -> {
                        world.setStorm(true);
                        world.setThundering(true);
                        world.setWeatherDuration(20 * 60 * 10);
                        world.setThunderDuration(20 * 60 * 10);
                    }
                }
                done.add(world.getName());
            }
            return String.join(", ", done);
        });
        module.log().add(request, "weer", names, value);
        return map("worlds", names);
    }

    private Object broadcast(PanelRequest request) throws Exception {
        String message = request.string("message", "Typ een bericht.");
        if (message.length() > 256) {
            throw ApiException.badRequest("Het bericht is te lang (maximaal 256 tekens).");
        }
        boolean title = request.body().has("title") && request.body().get("title").getAsBoolean();
        String actor = request.user().name();
        sync(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                plugin.lang().send(player, "panel.broadcast", Text.p("message", message), Text.p("actor", actor));
                if (title) {
                    plugin.lang().sendTitle(player, "panel.broadcast-title", "panel.broadcast-subtitle",
                            Text.p("message", message), Text.p("actor", actor));
                }
                plugin.theme().play(player, "tip");
            }
            plugin.lang().send(plugin.getServer().getConsoleSender(), "panel.broadcast",
                    Text.p("message", message), Text.p("actor", actor));
            return null;
        });
        module.log().add(request, "mededeling", null, message);
        return null;
    }

    private Object whitelist(PanelRequest request) throws Exception {
        String action = request.string("action", "Kies een actie.").toLowerCase(Locale.ROOT);
        String name = request.optString("name");
        if ((action.equals("add") || action.equals("remove")) && (name == null || !NAME.matcher(name).matches())) {
            throw ApiException.badRequest("Vul een geldige Minecraft-naam in.");
        }
        Map<String, Object> result = sync(() -> {
            switch (action) {
                case "enable" -> plugin.getServer().setWhitelist(true);
                case "disable" -> plugin.getServer().setWhitelist(false);
                case "add" -> {
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:whitelist add " + name);
                    if (!contains(name)) {
                        throw ApiException.notFound("Speler '" + name + "' is niet gevonden bij Mojang.");
                    }
                }
                case "remove" -> {
                    if (!contains(name)) {
                        throw ApiException.notFound(name + " staat niet op de whitelist.");
                    }
                    plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), "minecraft:whitelist remove " + name);
                }
                default -> throw ApiException.badRequest("Onbekende actie.");
            }
            return whitelistData();
        });
        module.log().add(request, "whitelist " + action, name, null);
        return result;
    }

    private boolean contains(String name) {
        for (OfflinePlayer player : plugin.getServer().getWhitelistedPlayers()) {
            if (name.equalsIgnoreCase(player.getName())) {
                return true;
            }
        }
        return false;
    }

    private Object save(PanelRequest request) throws Exception {
        sync(() -> {
            plugin.getServer().savePlayers();
            for (World world : plugin.getServer().getWorlds()) {
                world.save();
            }
            plugin.players().saveAll();
            return null;
        });
        module.log().add(request, "opslaan", null, null);
        return null;
    }

    private Object reload(PanelRequest request) throws Exception {
        long time = sync(() -> plugin.reload());
        module.log().add(request, "herladen", null, time + "ms");
        return map("time", time);
    }

    private Object stop(PanelRequest request) throws Exception {
        module.log().add(request, "server stoppen", null, null);
        sync(() -> {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> plugin.getServer().shutdown(), 40L);
            return null;
        });
        return null;
    }
}
