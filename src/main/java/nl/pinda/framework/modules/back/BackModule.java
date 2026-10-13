package nl.pinda.framework.modules.back;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.backpack.BackpackModule;
import nl.pinda.framework.modules.duel.DuelModule;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** /back: terug naar je plek van vóór je laatste teleport, of naar waar je doodging. */
public final class BackModule extends PindaModule implements Listener {

    public static final String USE = "pinda.back.use";
    public static final String DEATH = "pinda.back.death";
    /** Zo lang (ms) na de waarschuwing is nog een keer /back genoeg om door te gaan. */
    static final long CONFIRM_MILLIS = 20_000L;

    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Set<UUID> diedRecently = new HashSet<>();
    /** Wie met /back terug zou gaan naar waar hij doodging (en niet naar een plek van vóór een teleport). */
    private final Set<UUID> lastIsDeath = new HashSet<>();
    /** Wie de waarschuwing over zijn rugtas al kreeg, en tot wanneer nog een keer /back genoeg is. */
    private final Map<UUID, Long> warned = new HashMap<>();

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
        lastIsDeath.clear();
        warned.clear();
    }

    public Location last(Player player) {
        Location location = lastLocations.get(player.getUniqueId());
        return location == null ? null : location.clone();
    }

    /** Gaat /back naar de plek waar deze speler doodging? */
    boolean lastIsDeath(Player player) {
        return lastIsDeath.contains(player.getUniqueId());
    }

    /** Kost /back (naar waar je doodging) je gevallen rugtas? Staat aan in back.yml. */
    boolean deathCostsBackpack() {
        return config().getBoolean("death-loses-backpack", true);
    }

    /** Onthoudt dat de speler gewaarschuwd is; true als hij binnen de tijd opnieuw /back deed (= doorgaan). */
    boolean confirmWarning(Player player, boolean explicit) {
        long now = System.currentTimeMillis();
        Long until = warned.get(player.getUniqueId());
        if (explicit || until != null && until >= now) {
            warned.remove(player.getUniqueId());
            return true;
        }
        warned.put(player.getUniqueId(), now + CONFIRM_MILLIS);
        return false;
    }

    /** De gevallen rugtas die /back zou kosten (of null): alleen als /back naar de sterfplek gaat. */
    BackpackModule.DroppedInfo droppedBackpack(Player player) {
        if (!deathCostsBackpack() || !lastIsDeath(player)) {
            return null;
        }
        BackpackModule backpacks = plugin.modules().get(BackpackModule.class);
        BackpackModule.DroppedInfo bag = backpacks == null || !backpacks.isEnabled() ? null : backpacks.droppedOf(player.getUniqueId());
        Location target = lastLocations.get(player.getUniqueId());
        if (bag == null || target == null || target.getWorld() == null || !target.getWorld().getName().equals(bag.world())) {
            return null;
        }
        // Alleen de rugtas die (ongeveer) daar ligt: een oude rugtas ergens anders blijft gewoon liggen.
        double dx = target.getX() - bag.x();
        double dz = target.getZ() - bag.z();
        return dx * dx + dz * dz <= 32 * 32 ? bag : null;
    }

    /** Een 1v1: de teleports naar de arena en terug (en doodgaan daar) onthoudt /back niet. */
    private boolean inDuel(Player player) {
        DuelModule duel = plugin.modules().get(DuelModule.class);
        return duel != null && duel.isEnabled() && (duel.ownTeleport(player) || duel.involved(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return;
        }
        if (inDuel(event.getPlayer())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        boolean otherWorld = from.getWorld() != to.getWorld();
        if (otherWorld || from.distanceSquared(to) > 4) {
            lastLocations.put(event.getPlayer().getUniqueId(), from.clone());
            lastIsDeath.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!config().getBoolean("on-death", true) || !player.hasPermission(DEATH) || inDuel(player)) {
            return;
        }
        lastLocations.put(player.getUniqueId(), player.getLocation().clone());
        lastIsDeath.add(player.getUniqueId());
        warned.remove(player.getUniqueId());
        diedRecently.add(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        warned.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (diedRecently.remove(player.getUniqueId()) && config().getBoolean("death-hint", true)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    // Ligt zijn rugtas daar? Dan meteen zeggen dat /back die kost.
                    plugin.lang().send(player, droppedBackpack(player) != null ? "back.death-hint-backpack" : "back.death-hint");
                }
            }, 20L);
        }
    }
}
