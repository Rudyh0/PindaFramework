package nl.pinda.framework.modules.back;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** /back: terug naar je plek van vóór je laatste teleport, of naar waar je doodging. */
public final class BackModule extends PindaModule implements Listener {

    public static final String USE = "pinda.back.use";
    public static final String DEATH = "pinda.back.death";

    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Set<UUID> diedRecently = new HashSet<>();

    public BackModule(PindaFramework plugin) {
        super(plugin, "back");
    }

    @Override
    protected void onEnable() {
        listen(this);
        command(new BackCommand(plugin, this));
    }

    @Override
    protected void onDisable() {
        lastLocations.clear();
        diedRecently.clear();
    }

    public Location last(Player player) {
        Location location = lastLocations.get(player.getUniqueId());
        return location == null ? null : location.clone();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        boolean otherWorld = from.getWorld() != to.getWorld();
        if (otherWorld || from.distanceSquared(to) > 4) {
            lastLocations.put(event.getPlayer().getUniqueId(), from.clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!config().getBoolean("on-death", true) || !player.hasPermission(DEATH)) {
            return;
        }
        lastLocations.put(player.getUniqueId(), player.getLocation().clone());
        diedRecently.add(player.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (diedRecently.remove(player.getUniqueId()) && config().getBoolean("death-hint", true)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.lang().send(player, "back.death-hint");
                }
            }, 20L);
        }
    }
}
