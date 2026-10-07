package nl.pinda.framework.modules.shop;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.economy.EconomyService;
import nl.pinda.framework.modules.economy.Money;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.HangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Alle shops: opslag, voorraad, kopen, de dagelijkse marketplace fee en de shopborden.
 *
 * <p>Een shop is open voor kopers als de eigenaar hem op open heeft gezet, online is,
 * de fee van vandaag betaald heeft en er iets te koop is.
 */
public final class ShopService {

    private final PindaFramework plugin;
    private final ShopModule module;
    private final EconomyService economy;
    private final Map<UUID, Shop> shops = new ConcurrentHashMap<>();
    /** Shopborden en de blokken waar ze aan hangen, voor de bescherming. */
    private final Map<Shop.SignLocation, UUID> signs = new HashMap<>();
    private final Map<Shop.SignLocation, UUID> supports = new HashMap<>();

    /** Uitkomst van een aankoop. */
    public enum PurchaseResult { OK, CLOSED, SOLD_OUT, OWN_SHOP, NO_SPACE, NO_MONEY }

    public ShopService(PindaFramework plugin, ShopModule module, EconomyService economy) {
        this.plugin = plugin;
        this.module = module;
        this.economy = economy;
    }

    private YamlConfiguration cfg() {
        return module.cfg();
    }

    public EconomyService economy() {
        return economy;
    }

    // ============================================================ opvragen

    public Shop shop(UUID owner) {
        return shops.get(owner);
    }

    /** Alle shops, open of dicht. */
    public List<Shop> all() {
        return new ArrayList<>(shops.values());
    }

    /** Alle shops die nu open zijn voor kopers, op naam gesorteerd. */
    public List<Shop> openShops() {
        List<Shop> open = new ArrayList<>();
        for (Shop shop : shops.values()) {
            if (isOpenForBuyers(shop)) {
                open.add(shop);
            }
        }
        open.sort(Comparator.comparing(shop -> shop.name().toLowerCase()));
        return open;
    }

    public boolean isOwnerOnline(Shop shop) {
        return plugin.getServer().getPlayer(shop.owner()) != null;
    }

    public boolean isOpenForBuyers(Shop shop) {
        return shop.wantsOpen() && isOwnerOnline(shop) && today().equals(shop.feeDay()) && shop.hasStock();
    }

    public long dailyFee() {
        return Math.max(0, Money.toCents(cfg().getDouble("daily-fee", 25)));
    }

    public double taxPercent() {
        return Math.max(0, Math.min(100, cfg().getDouble("sales-tax-percent", 5)));
    }

    /** Testoptie: bij je eigen shop kunnen kopen. */
    public boolean allowOwnPurchases() {
        return cfg().getBoolean("allow-own-purchases", false);
    }

    public long maxPrice() {
        return Math.max(1, Money.toCents(cfg().getDouble("max-price", 1_000_000)));
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    // ============================================================ shop maken en beheren

    public Shop create(Player owner) {
        Shop existing = shops.get(owner.getUniqueId());
        if (existing != null) {
            return existing;
        }
        String name = plugin.lang().raw(plugin.lang().languageOf(owner), "shop.default-name");
        name = (name == null ? "Shop van %player%" : name).replace("%player%", owner.getName());
        Shop shop = new Shop(owner.getUniqueId(), owner.getName(), trimName(name), false, null, null,
                System.currentTimeMillis(), 0, 0);
        shops.put(shop.owner(), shop);
        saveShop(shop);
        return shop;
    }

    /** Verwijdert een lege shop. False als er nog voorraad in zit. */
    public boolean delete(Shop shop) {
        if (!shop.isEmpty()) {
            return false;
        }
        Shop.SignLocation sign = shop.sign();
        shops.remove(shop.owner());
        if (sign != null) {
            unindexSign(sign);
            writeSign(sign, null);
        }
        UUID owner = shop.owner();
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pinda_shop_items WHERE owner = ?")) {
                statement.setString(1, owner.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pinda_shops WHERE owner = ?")) {
                statement.setString(1, owner.toString());
                statement.executeUpdate();
            }
        }).exceptionally(error -> logError("Kon shop niet verwijderen", error));
        return true;
    }

    public void rename(Shop shop, String name) {
        shop.name(trimName(name));
        saveShop(shop);
        updateSign(shop);
    }

    public static String trimName(String name) {
        String clean = name.replaceAll("\\s+", " ").trim();
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    /** Zet de shop open of dicht. Bij openen wordt zo nodig de fee van vandaag betaald. */
    public void setOpen(Shop shop, boolean open) {
        shop.wantsOpen(open);
        saveShop(shop);
        if (open) {
            ensureFee(shop);
        }
        updateSign(shop);
    }

    /**
     * Betaalt de marketplace fee van vandaag als de shop open staat, de eigenaar online is
     * en er iets te koop is. Lukt betalen niet, dan gaat de shop dicht.
     *
     * @return true als de shop (nog) open mag zijn
     */
    public boolean ensureFee(Shop shop) {
        if (!shop.wantsOpen()) {
            return false;
        }
        Player owner = plugin.getServer().getPlayer(shop.owner());
        if (owner == null || !shop.hasStock()) {
            return true;
        }
        String today = today();
        if (today.equals(shop.feeDay())) {
            return true;
        }
        long fee = dailyFee();
        if (fee > 0 && !economy.take(owner, fee, "shop-fee", null)) {
            shop.wantsOpen(false);
            saveShop(shop);
            updateSign(shop);
            plugin.lang().send(owner, "shop.fee-failed", Text.p("amount", economy.format(fee)));
            plugin.theme().play(owner, "error");
            return false;
        }
        shop.feeDay(today);
        saveShop(shop);
        updateSign(shop);
        if (fee > 0) {
            plugin.lang().send(owner, "shop.fee-paid", Text.p("amount", economy.format(fee)));
        }
        return true;
    }

    /** Elke minuut: na middernacht de fee van de nieuwe dag innen voor open shops. */
    public void dailyCheck() {
        for (Shop shop : shops.values()) {
            if (shop.wantsOpen() && isOwnerOnline(shop) && shop.hasStock() && !today().equals(shop.feeDay())) {
                ensureFee(shop);
            }
        }
    }

    // ============================================================ voorraad (eigenaar)

    /**
     * Legt items in een vak van de shop. Een leeg vak wordt een nieuw aanbod.
     *
     * @return false als er in dat vak al een ander soort item ligt
     */
    public boolean addStock(Shop shop, int slot, ItemStack items) {
        Listing listing = shop.listing(slot);
        if (listing == null) {
            listing = new Listing(slot, items, items.getAmount(), 0);
            shop.putListing(listing);
        } else if (listing.matches(items)) {
            listing.stock(listing.stock() + items.getAmount());
        } else {
            return false;
        }
        saveListing(shop.owner(), listing);
        if (listing.forSale()) {
            ensureFee(shop);
        }
        updateSign(shop);
        return true;
    }

    /** Het vak waar dit item bij hoort: een vak met hetzelfde item, anders het eerste lege. -1 als vol. */
    public int slotFor(Shop shop, ItemStack items) {
        for (Listing listing : shop.listings()) {
            if (listing.matches(items)) {
                return listing.slot();
            }
        }
        return shop.firstFreeSlot();
    }

    /**
     * Haalt items uit de shop terug naar de inventory van de eigenaar.
     *
     * @return het aantal teruggenomen items (0 als de inventory vol is)
     */
    public int takeBack(Shop shop, Listing listing, Player owner, int wanted) {
        int amount = Math.min(Math.min(wanted, listing.stock()), freeSpace(owner, listing.template()));
        if (amount <= 0) {
            return 0;
        }
        listing.stock(listing.stock() - amount);
        giveItems(owner, listing.template(), amount);
        if (listing.stock() == 0) {
            shop.removeListing(listing.slot());
            deleteListing(shop.owner(), listing.slot());
        } else {
            saveListing(shop.owner(), listing);
        }
        updateSign(shop);
        return amount;
    }

    /** Verwijdert een leeg vak (voorraad 0). */
    public void removeEmpty(Shop shop, Listing listing) {
        if (listing.stock() > 0) {
            return;
        }
        shop.removeListing(listing.slot());
        deleteListing(shop.owner(), listing.slot());
    }

    public void setPrice(Shop shop, Listing listing, long cents) {
        listing.price(cents);
        saveListing(shop.owner(), listing);
        if (listing.forSale()) {
            ensureFee(shop);
        }
        updateSign(shop);
    }

    // ============================================================ kopen

    /** Hoeveel deze koper maximaal kan kopen: voorraad, ruimte in de inventory en geld. */
    public int maxBuyable(Player buyer, Listing listing) {
        int amount = Math.min(listing.stock(), freeSpace(buyer, listing.template()));
        if (listing.price() > 0) {
            long affordable = economy.spendable(economy.account(buyer)) / listing.price();
            amount = (int) Math.min(amount, affordable);
        }
        return Math.max(0, amount);
    }

    public PurchaseResult buy(Player buyer, Shop shop, int slot, int requested) {
        if (buyer.getUniqueId().equals(shop.owner()) && !allowOwnPurchases()) {
            return PurchaseResult.OWN_SHOP;
        }
        if (!isOpenForBuyers(shop)) {
            return PurchaseResult.CLOSED;
        }
        Listing listing = shop.listing(slot);
        if (listing == null || !listing.forSale()) {
            return PurchaseResult.SOLD_OUT;
        }
        int amount = Math.min(requested, listing.stock());
        int space = freeSpace(buyer, listing.template());
        if (space <= 0) {
            return PurchaseResult.NO_SPACE;
        }
        amount = Math.min(amount, space);
        if (amount <= 0) {
            return PurchaseResult.SOLD_OUT;
        }
        long total;
        try {
            total = Math.multiplyExact(listing.price(), amount);
        } catch (ArithmeticException e) {
            return PurchaseResult.NO_MONEY;
        }
        String itemText = amount + "x " + listing.template().getType().getKey().getKey();
        if (!economy.take(buyer, total, "shop-buy", itemText + " van " + shop.ownerName())) {
            return PurchaseResult.NO_MONEY;
        }

        listing.stock(listing.stock() - amount);
        saveListing(shop.owner(), listing);
        giveItems(buyer, listing.template(), amount);

        long tax = Money.percentage(total, taxPercent());
        long net = total - tax;
        economy.give(shop.owner(), net, true, "shop-sale", itemText + " aan " + buyer.getName());
        shop.addSale(net);
        saveShop(shop);

        Component item = itemName(listing.template());
        plugin.lang().send(buyer, "shop.bought",
                Text.p("amount", amount), Text.c("item", item),
                Text.p("price", economy.format(total)), Text.p("shop", shop.name()));
        plugin.theme().play(buyer, "success");

        Player owner = plugin.getServer().getPlayer(shop.owner());
        if (owner != null) {
            plugin.lang().send(owner, "shop.sold",
                    Text.p("player", buyer.getName()), Text.p("amount", amount), Text.c("item", item),
                    Text.p("price", economy.format(total)), Text.p("net", economy.format(net)),
                    Text.p("tax", economy.format(tax)));
            plugin.theme().play(owner, "message");
            if (listing.stock() == 0) {
                plugin.lang().send(owner, shop.hasStock() ? "shop.item-sold-out" : "shop.sold-out", Text.c("item", item));
            }
        }
        updateSign(shop);
        return PurchaseResult.OK;
    }

    // ============================================================ items

    /** Hoeveel van dit item er nog in de inventory past. */
    public static int freeSpace(Player player, ItemStack template) {
        int max = template.getMaxStackSize();
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.isEmpty()) {
                free += max;
            } else if (stack.isSimilar(template)) {
                free += Math.max(0, max - stack.getAmount());
            }
        }
        return free;
    }

    private static void giveItems(Player player, ItemStack template, int amount) {
        int max = template.getMaxStackSize();
        int left = amount;
        while (left > 0) {
            int count = Math.min(max, left);
            ItemStack stack = template.clone();
            stack.setAmount(count);
            for (ItemStack rest : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
            left -= count;
        }
    }

    /** De naam van een item zoals de speler hem ziet (eigen naam, of de Minecraft-naam in zijn taal). */
    public static Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component custom = meta.displayName();
            if (custom != null) {
                return custom;
            }
        }
        return Component.translatable(item.translationKey());
    }

    // ============================================================ shopborden

    public Shop shopAtSign(Block block) {
        UUID owner = signs.get(key(block));
        return owner == null ? null : shops.get(owner);
    }

    /** De shop waarvan dit blok het bord draagt, of null. */
    public Shop shopSupportedBy(Block block) {
        UUID owner = supports.get(key(block));
        return owner == null ? null : shops.get(owner);
    }

    /** Is het bord van deze shop er nog? */
    public boolean signExists(Shop shop) {
        Shop.SignLocation location = shop.sign();
        if (location == null) {
            return false;
        }
        World world = plugin.getServer().getWorld(location.world());
        if (world == null || !world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) {
            return true; // niet te controleren; ga ervan uit dat het er nog is
        }
        return world.getBlockAt(location.x(), location.y(), location.z()).getState() instanceof Sign;
    }

    public void linkSign(Shop shop, Block block) {
        if (shop.sign() != null) {
            unindexSign(shop.sign());
        }
        Shop.SignLocation location = key(block);
        shop.sign(location);
        indexSign(shop, block);
        saveShop(shop);
    }

    public void unlinkSign(Shop shop) {
        if (shop.sign() != null) {
            unindexSign(shop.sign());
            shop.sign(null);
            saveShop(shop);
        }
    }

    /** Werkt de tekst op het shopbord bij (open/gesloten, naam). */
    public void updateSign(Shop shop) {
        if (shop.sign() != null) {
            writeSign(shop.sign(), shop);
        }
    }

    public void updateAllSigns() {
        for (Shop shop : shops.values()) {
            updateSign(shop);
        }
    }

    /** De regels voor op het bord, in de standaardtaal (een bord ziet iedereen hetzelfde). */
    public List<Component> signLines(Shop shop) {
        String code = plugin.lang().defaultLanguage();
        TagResolver[] placeholders = {
                Text.p("shop", shop.name().length() > 15 ? shop.name().substring(0, 15) : shop.name()),
                Text.p("player", shop.ownerName())
        };
        String status = isOpenForBuyers(shop) ? "shop.sign.open" : "shop.sign.closed";
        return List.of(
                plugin.lang().component(code, "shop.sign.line-1", placeholders),
                plugin.lang().component(code, "shop.sign.line-2", placeholders),
                plugin.lang().component(code, "shop.sign.line-3", placeholders),
                plugin.lang().component(code, status, placeholders));
    }

    private void writeSign(Shop.SignLocation location, Shop shop) {
        World world = plugin.getServer().getWorld(location.world());
        if (world == null || !world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) {
            return;
        }
        BlockState state = world.getBlockAt(location.x(), location.y(), location.z()).getState();
        if (!(state instanceof Sign sign)) {
            return;
        }
        List<Component> lines = shop != null
                ? signLines(shop)
                : List.of(Component.empty(), plugin.lang().component(plugin.lang().defaultLanguage(), "shop.sign.removed"),
                        Component.empty(), Component.empty());
        for (Side side : Side.values()) {
            SignSide signSide = sign.getSide(side);
            for (int i = 0; i < 4; i++) {
                signSide.line(i, lines.get(i));
            }
        }
        sign.setWaxed(shop != null);
        sign.update();
    }

    private void indexSign(Shop shop, Block block) {
        signs.put(key(block), shop.owner());
        Block support = supportOf(block);
        if (support != null) {
            supports.put(key(support), shop.owner());
        }
    }

    private void unindexSign(Shop.SignLocation location) {
        UUID owner = signs.remove(location);
        if (owner != null) {
            // Elke speler heeft maximaal één bord, dus alle steunblokken van deze eigenaar vervallen.
            supports.values().removeIf(owner::equals);
        }
    }

    private Block blockAt(Shop.SignLocation location) {
        World world = plugin.getServer().getWorld(location.world());
        return world == null ? null : world.getBlockAt(location.x(), location.y(), location.z());
    }

    /** Het blok waar een bord aan hangt of op staat. Null als dat niet te bepalen is. */
    public static Block supportOf(Block sign) {
        BlockData data = sign.getBlockData();
        if (data instanceof WallSign wallSign) {
            return sign.getRelative(wallSign.getFacing().getOppositeFace());
        }
        if (data instanceof HangingSign) {
            return sign.getRelative(BlockFace.UP);
        }
        if (data instanceof org.bukkit.block.data.type.Sign) {
            return sign.getRelative(BlockFace.DOWN);
        }
        return null;
    }

    public static Shop.SignLocation key(Block block) {
        return new Shop.SignLocation(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static Location toLocation(PindaFramework plugin, Shop.SignLocation sign) {
        World world = plugin.getServer().getWorld(sign.world());
        return world == null ? null : new Location(world, sign.x() + 0.5, sign.y(), sign.z() + 0.5);
    }

    // ============================================================ database

    public void loadAll() throws Exception {
        Map<UUID, Shop> loaded = plugin.database().query(connection -> {
            Map<UUID, Shop> result = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT owner, owner_name, name, open, fee_day, sign_world, sign_x, sign_y, sign_z, created, sales, earned "
                            + "FROM pinda_shops")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        UUID owner = UUID.fromString(rows.getString("owner"));
                        String world = rows.getString("sign_world");
                        Shop.SignLocation sign = world == null ? null
                                : new Shop.SignLocation(world, rows.getInt("sign_x"), rows.getInt("sign_y"), rows.getInt("sign_z"));
                        result.put(owner, new Shop(owner, rows.getString("owner_name"), rows.getString("name"),
                                rows.getInt("open") == 1, rows.getString("fee_day"), sign, rows.getLong("created"),
                                rows.getInt("sales"), rows.getLong("earned")));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT owner, slot, item, stock, price FROM pinda_shop_items")) {
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        Shop shop = result.get(UUID.fromString(rows.getString("owner")));
                        if (shop == null) {
                            continue;
                        }
                        try {
                            ItemStack item = ItemStack.deserializeBytes(rows.getBytes("item"));
                            shop.putListing(new Listing(rows.getInt("slot"), item, rows.getInt("stock"), rows.getLong("price")));
                        } catch (RuntimeException e) {
                            plugin.getLogger().log(Level.WARNING, "Kon een item uit de shop van " + shop.ownerName() + " niet lezen", e);
                        }
                    }
                }
            }
            return result;
        }).get();
        shops.clear();
        shops.putAll(loaded);
        signs.clear();
        supports.clear();
        for (Shop shop : shops.values()) {
            if (shop.sign() != null) {
                signs.put(shop.sign(), shop.owner());
                Block block = blockAt(shop.sign());
                if (block != null) {
                    Block support = supportOf(block);
                    if (support != null) {
                        supports.put(key(support), shop.owner());
                    }
                }
            }
        }
    }

    public void saveShop(Shop shop) {
        final String owner = shop.owner().toString();
        final String ownerName = shop.ownerName();
        final String name = shop.name();
        final boolean open = shop.wantsOpen();
        final String feeDay = shop.feeDay();
        final Shop.SignLocation sign = shop.sign();
        final long created = shop.created();
        final int sales = shop.sales();
        final long earned = shop.earned();
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pinda_shops (owner, owner_name, name, open, fee_day, sign_world, sign_x, sign_y, sign_z, created, sales, earned)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(owner) DO UPDATE SET owner_name = excluded.owner_name, name = excluded.name,
                        open = excluded.open, fee_day = excluded.fee_day, sign_world = excluded.sign_world,
                        sign_x = excluded.sign_x, sign_y = excluded.sign_y, sign_z = excluded.sign_z,
                        sales = excluded.sales, earned = excluded.earned""")) {
                statement.setString(1, owner);
                statement.setString(2, ownerName);
                statement.setString(3, name);
                statement.setInt(4, open ? 1 : 0);
                statement.setString(5, feeDay);
                if (sign == null) {
                    statement.setNull(6, Types.VARCHAR);
                    statement.setNull(7, Types.INTEGER);
                    statement.setNull(8, Types.INTEGER);
                    statement.setNull(9, Types.INTEGER);
                } else {
                    statement.setString(6, sign.world());
                    statement.setInt(7, sign.x());
                    statement.setInt(8, sign.y());
                    statement.setInt(9, sign.z());
                }
                statement.setLong(10, created);
                statement.setInt(11, sales);
                statement.setLong(12, earned);
                statement.executeUpdate();
            }
        }).exceptionally(error -> logError("Kon shop van " + ownerName + " niet opslaan", error));
    }

    private void saveListing(UUID owner, Listing listing) {
        final byte[] item = listing.template().serializeAsBytes();
        final int slot = listing.slot();
        final int stock = listing.stock();
        final long price = listing.price();
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO pinda_shop_items (owner, slot, item, stock, price) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(owner, slot) DO UPDATE SET item = excluded.item, stock = excluded.stock, price = excluded.price""")) {
                statement.setString(1, owner.toString());
                statement.setInt(2, slot);
                statement.setBytes(3, item);
                statement.setInt(4, stock);
                statement.setLong(5, price);
                statement.executeUpdate();
            }
        }).exceptionally(error -> logError("Kon shopvak niet opslaan", error));
    }

    private void deleteListing(UUID owner, int slot) {
        plugin.database().execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM pinda_shop_items WHERE owner = ? AND slot = ?")) {
                statement.setString(1, owner.toString());
                statement.setInt(2, slot);
                statement.executeUpdate();
            }
        }).exceptionally(error -> logError("Kon shopvak niet verwijderen", error));
    }

    private Void logError(String message, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, message, error);
        return null;
    }

    /** Werkt de naam van de eigenaar bij als die veranderd is. */
    public void updateOwnerName(Player player) {
        Shop shop = shops.get(player.getUniqueId());
        if (shop != null && !player.getName().equals(shop.ownerName())) {
            shop.ownerName(player.getName());
            saveShop(shop);
        }
    }
}
