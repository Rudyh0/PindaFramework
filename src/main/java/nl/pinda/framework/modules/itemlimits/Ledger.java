package nl.pinda.framework.modules.itemlimits;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;

/**
 * Wie welk beperkt item heeft. Alles wat nog bestaat, staat in het geheugen (dat zijn er niet
 * veel); de database bewaart het, ook wat er verdwenen is (voor het paneel).
 *
 * <p>Alleen op de hoofdthread gebruiken. Het schrijven naar de database gebeurt op de achtergrond,
 * in volgorde.
 */
final class Ledger {

    /** Een item met een nummer dat nog bestaat. */
    static final class Entry {
        final String serial;
        final String rule;
        final long created;
        /** Een cadeau van de server voor deze speler (of null): telt voor hem niet mee, en nooit voor de server. */
        final UUID giftFor;
        UUID owner;
        String ownerName;
        String location;
        long updated;

        Entry(String serial, String rule, UUID owner, String ownerName, String location, long created, long updated, UUID giftFor) {
            this.serial = serial;
            this.rule = rule;
            this.giftFor = giftFor;
            this.owner = owner;
            this.ownerName = ownerName;
            this.location = location;
            this.created = created;
            this.updated = updated;
        }

        /** Een cadeau dat (weer) bij de speler is voor wie het bedoeld was. */
        boolean ownGift() {
            return giftFor != null && giftFor.equals(owner);
        }

        LimitedItem view() {
            return new LimitedItem(serial, rule, owner, ownerName, location, created, updated, giftFor, ownGift());
        }
    }

    private final PindaFramework plugin;
    private final Map<String, Entry> active = new HashMap<>();
    /** Per eigenaar, per regel: de nummers. */
    private final Map<UUID, Map<String, Set<String>>> byOwner = new HashMap<>();
    /** Zonder cadeaus: die tellen niet mee voor het maximum van de server. */
    private final Map<String, Integer> totals = new HashMap<>();
    /** Per eigenaar, per regel: hoeveel van zijn items cadeaus voor hemzelf zijn. */
    private final Map<UUID, Map<String, Integer>> ownGifts = new HashMap<>();

    Ledger(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /** Leest alles wat nog bestaat uit de database (bij het starten). */
    void load() throws Exception {
        List<Entry> loaded = plugin.database().query(connection -> {
            List<Entry> list = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT serial, rule, owner, owner_name, location, created, updated, gift_for FROM pinda_item_limits WHERE status = 'active'");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String owner = rows.getString("owner");
                    UUID uuid = null;
                    try {
                        uuid = owner == null ? null : UUID.fromString(owner);
                    } catch (IllegalArgumentException ignored) {
                        // een kapotte regel telt voor niemand
                    }
                    list.add(new Entry(rows.getString("serial"), rows.getString("rule"), uuid, rows.getString("owner_name"),
                            rows.getString("location"), rows.getLong("created"), rows.getLong("updated"), uuidOrNull(rows.getString("gift_for"))));
                }
            }
            return list;
        }).get();
        active.clear();
        byOwner.clear();
        totals.clear();
        ownGifts.clear();
        for (Entry entry : loaded) {
            index(entry);
        }
    }

    // ============================================================ lezen

    Entry get(String serial) {
        return serial == null ? null : active.get(serial);
    }

    /** Hoeveel van deze regel iemand heeft (zijn eigen cadeaus niet meegeteld). */
    int owned(UUID owner, String rule) {
        Map<String, Set<String>> rules = byOwner.get(owner);
        Set<String> serials = rules == null ? null : rules.get(rule);
        return serials == null ? 0 : serials.size() - gifts(owner, rule);
    }

    /** Hoeveel cadeaus (voor hemzelf) iemand van deze regel heeft. */
    int gifts(UUID owner, String rule) {
        Map<String, Integer> rules = ownGifts.get(owner);
        return rules == null ? 0 : rules.getOrDefault(rule, 0);
    }

    /** Hoeveel er van deze regel op de server zijn (zonder cadeaus). */
    int total(String rule) {
        return totals.getOrDefault(rule, 0);
    }

    /** De items van iemand (alle regels). */
    List<Entry> of(UUID owner) {
        List<Entry> list = new ArrayList<>();
        Map<String, Set<String>> rules = byOwner.get(owner);
        if (rules != null) {
            for (Set<String> serials : rules.values()) {
                for (String serial : serials) {
                    list.add(active.get(serial));
                }
            }
        }
        return list;
    }

    /** Alle items van één regel. */
    List<Entry> ofRule(String rule) {
        List<Entry> list = new ArrayList<>();
        for (Entry entry : active.values()) {
            if (entry.rule.equals(rule)) {
                list.add(entry);
            }
        }
        return list;
    }

    Collection<Entry> all() {
        return active.values();
    }

    // ============================================================ aanpassen

    /**
     * Een nieuw item met een nummer (of een verdwenen nummer dat weer opduikt).
     *
     * @param giftFor een cadeau van de server voor deze speler, of null (een opduikend cadeau blijft een cadeau)
     */
    Entry register(String serial, String rule, UUID owner, String ownerName, String location, UUID giftFor) {
        long now = System.currentTimeMillis();
        Entry old = active.get(serial);
        if (old != null) {
            unindex(old);
        }
        UUID gift = giftFor != null ? giftFor : old == null ? null : old.giftFor;
        Entry entry = new Entry(serial, rule, owner, ownerName, location, old == null ? now : old.created, now, gift);
        index(entry);
        write(entry, "active", null);
        return entry;
    }

    /** Iemand anders heeft het item nu. */
    void transfer(Entry entry, UUID owner, String ownerName, String location) {
        unindex(entry);
        entry.owner = owner;
        entry.ownerName = ownerName;
        entry.location = location;
        entry.updated = System.currentTimeMillis();
        index(entry);
        write(entry, "active", null);
    }

    /** Het item ligt ergens anders (zelfde eigenaar). Alleen opslaan als het echt veranderde. */
    void locate(Entry entry, String location) {
        if (location == null || location.equals(entry.location)) {
            return;
        }
        entry.location = location;
        entry.updated = System.currentTimeMillis();
        write(entry, "active", null);
    }

    /** De eigenaar heeft een andere naam (naamswijziging). */
    void rename(UUID owner, String name) {
        for (Entry entry : of(owner)) {
            if (!name.equals(entry.ownerName)) {
                entry.ownerName = name;
                write(entry, "active", null);
            }
        }
    }

    /** Het item bestaat niet meer: telt niet meer mee. Geeft de oude gegevens terug (of null). */
    Entry gone(String serial, String reason) {
        Entry entry = active.get(serial);
        if (entry == null) {
            return null;
        }
        unindex(entry);
        entry.updated = System.currentTimeMillis();
        write(entry, "gone", reason);
        return entry;
    }

    private void index(Entry entry) {
        active.put(entry.serial, entry);
        if (entry.giftFor == null) {
            totals.merge(entry.rule, 1, Integer::sum);
        }
        if (entry.owner != null) {
            byOwner.computeIfAbsent(entry.owner, key -> new HashMap<>())
                    .computeIfAbsent(entry.rule, key -> new HashSet<>()).add(entry.serial);
            if (entry.ownGift()) {
                ownGifts.computeIfAbsent(entry.owner, key -> new HashMap<>()).merge(entry.rule, 1, Integer::sum);
            }
        }
    }

    private void unindex(Entry entry) {
        if (active.remove(entry.serial) == null) {
            return;
        }
        if (entry.giftFor == null) {
            totals.computeIfPresent(entry.rule, (key, count) -> count <= 1 ? null : count - 1);
        }
        if (entry.owner != null && entry.ownGift()) {
            Map<String, Integer> gifts = ownGifts.get(entry.owner);
            if (gifts != null) {
                gifts.computeIfPresent(entry.rule, (key, count) -> count <= 1 ? null : count - 1);
                if (gifts.isEmpty()) {
                    ownGifts.remove(entry.owner);
                }
            }
        }
        if (entry.owner != null) {
            Map<String, Set<String>> rules = byOwner.get(entry.owner);
            if (rules != null) {
                Set<String> serials = rules.get(entry.rule);
                if (serials != null) {
                    serials.remove(entry.serial);
                    if (serials.isEmpty()) {
                        rules.remove(entry.rule);
                    }
                }
                if (rules.isEmpty()) {
                    byOwner.remove(entry.owner);
                }
            }
        }
    }

    private void write(Entry entry, String status, String reason) {
        String serial = entry.serial;
        String rule = entry.rule;
        String owner = entry.owner == null ? null : entry.owner.toString();
        String ownerName = entry.ownerName;
        String location = entry.location;
        long created = entry.created;
        long updated = entry.updated;
        String giftFor = entry.giftFor == null ? null : entry.giftFor.toString();
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pinda_item_limits (serial, rule, owner, owner_name, status, location, reason, created, updated, gift_for)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(serial) DO UPDATE SET rule = excluded.rule, owner = excluded.owner, owner_name = excluded.owner_name,
                    status = excluded.status, location = excluded.location, reason = excluded.reason, updated = excluded.updated,
                    gift_for = excluded.gift_for""")) {
                statement.setString(1, serial);
                statement.setString(2, rule);
                statement.setString(3, owner);
                statement.setString(4, ownerName);
                statement.setString(5, status);
                statement.setString(6, location);
                statement.setString(7, reason);
                statement.setLong(8, created);
                statement.setLong(9, updated);
                statement.setString(10, giftFor);
                statement.executeUpdate();
            }
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Kon item " + serial + " niet opslaan in de item-limiet", error);
            return null;
        });
    }

    private static UUID uuidOrNull(String value) {
        try {
            return value == null || value.isEmpty() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** De laatst verdwenen items van een regel (voor het paneel). */
    CompletableFuture<List<LimitedItem.Gone>> recentlyGone(String rule, int limit) {
        return plugin.database().query(connection -> {
            List<LimitedItem.Gone> list = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT serial, rule, owner_name, reason, updated FROM pinda_item_limits WHERE status = 'gone' AND rule = ? "
                            + "ORDER BY updated DESC LIMIT ?")) {
                statement.setString(1, rule);
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        list.add(new LimitedItem.Gone(rows.getString("serial"), rows.getString("rule"), rows.getString("owner_name"),
                                rows.getString("reason"), rows.getLong("updated")));
                    }
                }
            }
            return list;
        });
    }
}
