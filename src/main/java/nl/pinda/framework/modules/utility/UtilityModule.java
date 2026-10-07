package nl.pinda.framework.modules.utility;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Handige commando's: /fly, /heal, /feed, /god en /speed. */
public final class UtilityModule extends PindaModule implements Listener {

    private final Set<UUID> god = new HashSet<>();

    public UtilityModule(PindaFramework plugin) {
        super(plugin, "utility");
    }

    @Override
    protected void onEnable() {
        listen(this);
        command(new FlyCommand(plugin));
        command(new HealCommand(plugin));
        command(new FeedCommand(plugin));
        command(new GodCommand(plugin, this));
        command(new SpeedCommand(plugin));
    }

    @Override
    protected void onDisable() {
        god.clear();
    }

    public boolean isGod(Player player) {
        return god.contains(player.getUniqueId());
    }

    /** Zet god mode om en geeft de nieuwe stand terug. */
    public boolean toggleGod(Player player) {
        if (god.remove(player.getUniqueId())) {
            return false;
        }
        god.add(player.getUniqueId());
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isGod(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isGod(player)
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        god.remove(event.getPlayer().getUniqueId());
    }
}
