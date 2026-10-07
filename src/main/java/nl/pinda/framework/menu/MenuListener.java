package nl.pinda.framework.menu;

import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/** Stuurt klikken en sluiten door naar het juiste menu en voorkomt dat items verschuiven. */
public final class MenuListener implements Listener {

    private final PindaFramework plugin;

    public MenuListener(PindaFramework plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof Menu menu)) {
            return;
        }
        try {
            if (menu.handleRawClick(event)) {
                return;
            }
        } catch (Exception e) {
            event.setCancelled(true);
            plugin.getLogger().log(Level.SEVERE, "Fout bij een klik in menu " + menu.getClass().getSimpleName(), e);
            return;
        }
        // Alles in een menu is vergrendeld, ook shift-klikken vanuit de eigen inventory.
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
            return;
        }
        try {
            menu.handleClick(event);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Fout bij een klik in menu " + menu.getClass().getSimpleName(), e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu menu) {
            try {
                if (menu.handleRawDrag(event)) {
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Fout bij slepen in menu " + menu.getClass().getSimpleName(), e);
            }
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu menu) {
            try {
                menu.handleClose(event.getReason());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Fout bij het sluiten van menu " + menu.getClass().getSimpleName(), e);
            }
        }
    }
}
