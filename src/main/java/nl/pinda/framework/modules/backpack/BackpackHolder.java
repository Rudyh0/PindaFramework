package nl.pinda.framework.modules.backpack;

import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * De open rugtas van een speler. Iedereen die dezelfde rugtas bekijkt, ziet hetzelfde inventory.
 * Grootte en het vakje van de gouden staaf horen bij dit inventory (en veranderen dus niet als
 * de instellingen herladen worden terwijl hij open is).
 */
final class BackpackHolder implements InventoryHolder {

    final UUID owner;
    final String ownerName;
    /** Het vakje van de gouden staaf, of -1 als die er (nu) niet is. */
    final int moneySlot;
    /** Is er iets veranderd sinds de laatste keer opslaan? */
    volatile boolean dirty;
    private Inventory inventory;

    BackpackHolder(UUID owner, String ownerName, int moneySlot) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.moneySlot = moneySlot;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
