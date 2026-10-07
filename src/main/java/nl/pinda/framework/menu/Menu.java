package nl.pinda.framework.menu;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Basis voor een menu (GUI). Spelers kunnen er niets uit pakken of in leggen;
 * klikken op een knop roept de bijbehorende actie aan.
 */
public abstract class Menu implements InventoryHolder {

    private static final long CLICK_COOLDOWN_MS = 150L;

    protected final PindaFramework plugin;
    protected final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Consumer<MenuClick>> actions = new HashMap<>();
    private long lastClick;

    protected Menu(PindaFramework plugin, Player viewer, int rows, Component title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = plugin.getServer().createInventory(this, Math.max(1, Math.min(6, rows)) * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Vult het menu met knoppen. Wordt aangeroepen bij openen en bij {@link #refresh()}. */
    protected abstract void render();

    /** Wordt aangeroepen als het menu sluit. */
    protected void onClose(InventoryCloseEvent.Reason reason) {
    }

    public void open() {
        refresh();
        viewer.openInventory(inventory);
    }

    /** Tekent het menu opnieuw, zonder het te sluiten. */
    public void refresh() {
        actions.clear();
        inventory.clear();
        render();
    }

    /** Sluit het menu op de volgende tick (veilig vanuit een klik). */
    protected void closeLater() {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (viewer.getOpenInventory().getTopInventory().getHolder(false) == this) {
                viewer.closeInventory();
            }
        });
    }

    protected void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    protected void set(int slot, ItemStack item, Consumer<MenuClick> action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        } else {
            actions.remove(slot);
        }
    }

    /** Vult alle lege vakjes met het opgegeven item. */
    protected void fillEmpty(ItemStack filler) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.isEmpty()) {
                inventory.setItem(slot, filler);
            }
        }
    }

    /**
     * Vakjes om {@code count} knoppen netjes gecentreerd in een rij te zetten.
     * Tot vier knoppen met een tussenruimte, daarboven aaneengesloten (maximaal 9).
     */
    public static int[] centered(int row, int count) {
        int amount = Math.max(0, Math.min(9, count));
        int[] slots = new int[amount];
        if (amount == 0) {
            return slots;
        }
        if (amount <= 4) {
            int start = 4 - (amount - 1);
            for (int i = 0; i < amount; i++) {
                slots[i] = row * 9 + start + i * 2;
            }
        } else {
            int start = (9 - amount) / 2;
            for (int i = 0; i < amount; i++) {
                slots[i] = row * 9 + start + i;
            }
        }
        return slots;
    }

    /**
     * Voor menu's die zelf met items willen werken (zoals een shop waar je items in sleept).
     * Geef true terug als je de klik volledig zelf afhandelt, inclusief het annuleren.
     * Standaard false: alles is vergrendeld en alleen knoppen werken.
     */
    protected boolean onRawClick(InventoryClickEvent event) {
        return false;
    }

    /** Zelfde als {@link #onRawClick} maar voor slepen. */
    protected boolean onRawDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        return false;
    }

    /** Voert de knop-actie van het aangeklikte vakje uit (voor gebruik vanuit {@link #onRawClick}). */
    protected final void runAction(InventoryClickEvent event) {
        handleClick(event);
    }

    // ------------------------------------------------- aangeroepen door MenuListener

    final boolean handleRawClick(InventoryClickEvent event) {
        return onRawClick(event);
    }

    final boolean handleRawDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        return onRawDrag(event);
    }

    final void handleClick(InventoryClickEvent event) {
        Consumer<MenuClick> action = actions.get(event.getRawSlot());
        if (action == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClick < CLICK_COOLDOWN_MS) {
            return;
        }
        lastClick = now;
        action.accept(new MenuClick(viewer, event.getClick(), event.getRawSlot()));
    }

    final void handleClose(InventoryCloseEvent.Reason reason) {
        onClose(reason);
    }
}
