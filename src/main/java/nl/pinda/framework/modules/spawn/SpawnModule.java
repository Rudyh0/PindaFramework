package nl.pinda.framework.modules.spawn;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Spawn: /spawn, /setspawn, en nieuwe spelers en respawns naar de spawn sturen. */
public final class SpawnModule extends PindaModule implements Listener {

    public static final String USE = "pinda.spawn.use";
    public static final String SET = "pinda.spawn.set";

    public SpawnModule(PindaFramework plugin) {
        super(plugin, "spawn");
    }

    @Override
    protected void onEnable() {
        listen(this);
        command(new SpawnCommand(plugin, this));
        command(new SetSpawnCommand(plugin, this));
    }

    /** De ingestelde spawn, of de spawn van de hoofdwereld als er nog niets is ingesteld. */
    public Location spawn() {
        ConfigurationSection section = config().getConfigurationSection("location");
        if (section != null) {
            World world = plugin.getServer().getWorld(section.getString("world", ""));
            if (world != null) {
                return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                        (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
            }
        }
        return plugin.getServer().getWorlds().getFirst().getSpawnLocation();
    }

    public void setSpawn(Location location) {
        config().set("location.world", location.getWorld().getName());
        config().set("location.x", location.getX());
        config().set("location.y", location.getY());
        config().set("location.z", location.getZ());
        config().set("location.yaw", (double) location.getYaw());
        config().set("location.pitch", (double) location.getPitch());
        saveConfig();
        location.getWorld().setSpawnLocation(location);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean firstJoin = !player.hasPlayedBefore();
        boolean always = config().getBoolean("teleport-on-join", false);
        if ((firstJoin && config().getBoolean("teleport-on-first-join", true)) || always) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.teleportAsync(spawn(), PlayerTeleportEvent.TeleportCause.PLUGIN);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!config().getBoolean("respawn-at-spawn", true)) {
            return;
        }
        if (event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        event.setRespawnLocation(spawn());
    }
}
