package nl.pinda.framework.modules.backpack;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** De open rugtas van een speler. Iedereen die dezelfde rugtas bekijkt, ziet hetzelfde inventory. */
final class BackpackHolder implements InventoryHolder {

    final UUID owner;
    final String ownerName;
    private Inventory inventory;

    BackpackHolder(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
