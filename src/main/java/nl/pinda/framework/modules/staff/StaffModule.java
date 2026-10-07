package nl.pinda.framework.modules.staff;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.player.PindaPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Staff-hulpmiddelen: /vanish (onzichtbaar, blijft aan na opnieuw inloggen) en /invsee.
 */
public final class StaffModule extends PindaModule implements Listener {

    public static final String VANISHED_SETTING = "vanished";
    public static final String VANISH = "pinda.vanish";
    public static final String VANISH_OTHERS = "pinda.vanish.others";
    public static final String VANISH_SEE = "pinda.vanish.see";
    public static final String INVSEE = "pinda.invsee";
    public static final String INVSEE_MODIFY = "pinda.invsee.modify";

    /** Wie welke inventory bekijkt zonder te mogen aanpassen (kijker -> doel). */
    private final Map<UUID, UUID> readOnlyViewers = new HashMap<>();

    public StaffModule(PindaFramework plugin) {
        super(plugin, "staff");
    }

    @Override
    protected void onEnable() {
        listen(this);
        command(new VanishCommand(plugin, this));
        command(new InvseeCommand(plugin, this));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isVanished(player)) {
                hide(player);
            }
        }
        repeat(this::reminder, 40L, 40L);
    }

    @Override
    protected void onDisable() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isVanished(player)) {
                show(player);
            }
        }
        readOnlyViewers.clear();
    }

    // ------------------------------------------------------------------ vanish

    public boolean isVanished(Player player) {
        return plugin.players().get(player).getBoolean(VANISHED_SETTING, false);
    }

    /** Zet vanish om en geeft de nieuwe stand terug. */
    public boolean toggleVanish(Player player) {
        boolean vanish = !isVanished(player);
        PindaPlayer data = plugin.players().get(player);
        data.setSetting(VANISHED_SETTING, Boolean.toString(vanish));
        plugin.players().save(data);
        if (vanish) {
            hide(player);
        } else {
            show(player);
        }
        // Andere staff laten weten wat er gebeurt
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online != player && online.hasPermission(VANISH_SEE)) {
                plugin.lang().send(online, vanish ? "vanish.staff-on" : "vanish.staff-off",
                        Text.p("player", player.getName()));
            }
        }
        return vanish;
    }

    private void hide(Player vanished) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other == vanished) {
                continue;
            }
            if (other.hasPermission(VANISH_SEE)) {
                other.showPlayer(plugin, vanished);
            } else {
                other.hidePlayer(plugin, vanished);
            }
        }
    }

    private void show(Player player) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other != player) {
                other.showPlayer(plugin, player);
            }
        }
    }

    private void reminder() {
        if (!config().getBoolean("vanish.actionbar-reminder", true)) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (isVanished(player) && !plugin.teleports().isPending(player)) {
                plugin.lang().send(player, "vanish.actionbar");
            }
        }
    }

    // ------------------------------------------------------------------ invsee

    public void openInventory(Player viewer, Player target) {
        boolean canModify = viewer.hasPermission(INVSEE_MODIFY);
        viewer.openInventory(target.getInventory());
        if (canModify) {
            readOnlyViewers.remove(viewer.getUniqueId());
        } else {
            readOnlyViewers.put(viewer.getUniqueId(), target.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        // Onzichtbare spelers verbergen voor wie net binnenkomt
        if (!joined.hasPermission(VANISH_SEE)) {
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                if (other != joined && isVanished(other)) {
                    joined.hidePlayer(plugin, other);
                }
            }
        }
        if (isVanished(joined)) {
            if (!joined.hasPermission(VANISH)) {
                // Rechten kwijt? Dan niet meer onzichtbaar.
                PindaPlayer data = plugin.players().get(joined);
                data.setSetting(VANISHED_SETTING, "false");
                plugin.players().save(data);
                return;
            }
            hide(joined);
            if (config().getBoolean("vanish.hide-join-quit-messages", true)) {
                event.joinMessage(null);
            }
            plugin.lang().send(joined, "vanish.still-vanished");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        readOnlyViewers.remove(player.getUniqueId());
        if (isVanished(player) && config().getBoolean("vanish.hide-join-quit-messages", true)) {
            event.quitMessage(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && config().getBoolean("vanish.prevent-mob-target", true)
                && isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && config().getBoolean("vanish.prevent-item-pickup", true)
                && isVanished(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (readOnlyViewers.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (readOnlyViewers.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        readOnlyViewers.remove(event.getPlayer().getUniqueId());
    }
}
