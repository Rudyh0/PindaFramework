package nl.pinda.framework.modules.shop;

import org.bukkit.inventory.ItemStack;

/**
 * Eén vak in een shop: een soort item met voorraad en een prijs per stuk (in centen).
 * Een prijs van 0 betekent: nog niet te koop.
 */
public final class Listing {

    private final int slot;
    private final ItemStack template;
    private int stock;
    private long price;

    public Listing(int slot, ItemStack template, int stock, long price) {
        this.slot = slot;
        this.template = template.asOne();
        this.stock = stock;
        this.price = price;
    }

    public int slot() {
        return slot;
    }

    /** Het item (aantal 1). Geef altijd een kopie door, nooit dit object zelf. */
    public ItemStack template() {
        return template.clone();
    }

    public boolean matches(ItemStack item) {
        return item != null && template.isSimilar(item);
    }

    public int stock() {
        return stock;
    }

    void stock(int stock) {
        this.stock = Math.max(0, stock);
    }

    public long price() {
        return price;
    }

    void price(long price) {
        this.price = price;
    }

    /** Te koop: er is voorraad en er staat een prijs op. */
    public boolean forSale() {
        return stock > 0 && price > 0;
    }
}
