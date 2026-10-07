package nl.pinda.framework.modules.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** De shop van één speler. Elke speler heeft er maximaal één. */
public final class Shop {

    public static final int SLOTS = 36;

    private final UUID owner;
    private final long created;
    private final Map<Integer, Listing> listings = new TreeMap<>();
    private String ownerName;
    private String name;
    private boolean wantsOpen;
    private String feeDay;
    private SignLocation sign;
    private int sales;
    private long earned;

    /** Waar het shopbord staat. */
    public record SignLocation(String world, int x, int y, int z) {
    }

    public Shop(UUID owner, String ownerName, String name, boolean wantsOpen, String feeDay,
                SignLocation sign, long created, int sales, long earned) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.name = name;
        this.wantsOpen = wantsOpen;
        this.feeDay = feeDay;
        this.sign = sign;
        this.created = created;
        this.sales = sales;
        this.earned = earned;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    void ownerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String name() {
        return name;
    }

    void name(String name) {
        this.name = name;
    }

    /** Heeft de eigenaar de shop op "open" gezet? (Open voor kopers is meer: zie ShopService.) */
    public boolean wantsOpen() {
        return wantsOpen;
    }

    void wantsOpen(boolean wantsOpen) {
        this.wantsOpen = wantsOpen;
    }

    /** De dag (jjjj-mm-dd) waarvoor de marketplace fee betaald is. */
    public String feeDay() {
        return feeDay;
    }

    void feeDay(String feeDay) {
        this.feeDay = feeDay;
    }

    public SignLocation sign() {
        return sign;
    }

    void sign(SignLocation sign) {
        this.sign = sign;
    }

    public long created() {
        return created;
    }

    public int sales() {
        return sales;
    }

    public long earned() {
        return earned;
    }

    void addSale(long net) {
        this.sales++;
        this.earned += net;
    }

    public Listing listing(int slot) {
        return listings.get(slot);
    }

    public List<Listing> listings() {
        return new ArrayList<>(listings.values());
    }

    void putListing(Listing listing) {
        listings.put(listing.slot(), listing);
    }

    void removeListing(int slot) {
        listings.remove(slot);
    }

    /** Het eerste lege vak, of -1 als de shop vol is. */
    public int firstFreeSlot() {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!listings.containsKey(slot)) {
                return slot;
            }
        }
        return -1;
    }

    /** Is er iets te koop? Zo niet, dan is de shop uitverkocht en kost hij geen fee. */
    public boolean hasStock() {
        for (Listing listing : listings.values()) {
            if (listing.forSale()) {
                return true;
            }
        }
        return false;
    }

    /** Zijn alle vakken leeg (ook geen voorraad zonder prijs)? */
    public boolean isEmpty() {
        for (Listing listing : listings.values()) {
            if (listing.stock() > 0) {
                return false;
            }
        }
        return true;
    }

    public int itemsForSale() {
        int count = 0;
        for (Listing listing : listings.values()) {
            if (listing.forSale()) {
                count++;
            }
        }
        return count;
    }
}
