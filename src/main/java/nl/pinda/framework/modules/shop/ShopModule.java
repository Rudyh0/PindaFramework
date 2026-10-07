package nl.pinda.framework.modules.shop;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.modules.economy.EconomyModule;
import org.bukkit.Chunk;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Shops: elke speler kan één shop hebben. Items sleep je erin, kopers vinden je via de
 * marktplaats (/shop) of je shopbord. De shop is alleen open als je online bent.
 */
public final class ShopModule extends PindaModule implements Listener {

    public static final String USE = "pinda.shop.use";
    public static final String SIGN = "pinda.shop.sign";
    public static final String ADMIN = "pinda.shop.admin";

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS pinda_shops (
                        owner TEXT PRIMARY KEY,
                        owner_name TEXT NOT NULL,
                        name TEXT NOT NULL,
                        open INTEGER NOT NULL DEFAULT 0,
                        fee_day TEXT,
                        sign_world TEXT,
                        sign_x INTEGER,
                        sign_y INTEGER,
                        sign_z INTEGER,
                        created INTEGER NOT NULL,
                        sales INTEGER NOT NULL DEFAULT 0,
                        earned INTEGER NOT NULL DEFAULT 0
                    )""",
                    """
                    CREATE TABLE IF NOT EXISTS pinda_shop_items (
                        owner TEXT NOT NULL,
                        slot INTEGER NOT NULL,
                        item BLOB NOT NULL,
                        stock INTEGER NOT NULL,
                        price INTEGER NOT NULL,
                        PRIMARY KEY (owner, slot)
                    )"""
            )
    );

    private ShopService service;

    public ShopModule(PindaFramework plugin) {
        super(plugin, "shop");
    }

    @Override
    protected void onEnable() {
        EconomyModule economy = plugin.modules().get(EconomyModule.class);
        if (economy == null || !economy.isEnabled() || economy.service() == null) {
            throw new IllegalStateException("De shop-module heeft de economy-module nodig. Zet 'economy' aan in config.yml.");
        }
        try {
            plugin.database().migrate("shop", MIGRATIONS);
        } catch (SQLException e) {
            throw new IllegalStateException("Kon de shop-tabellen niet aanmaken", e);
        }
        service = new ShopService(plugin, this, economy.service());
        try {
            service.loadAll();
        } catch (Exception e) {
            throw new IllegalStateException("Kon de shops niet laden", e);
        }
        listen(this);
        listen(new ShopSignListener(plugin, this, service));
        command(new ShopCommand(plugin, this, service));
        repeat(service::dailyCheck, 20L * 60, 20L * 60);
        plugin.getServer().getScheduler().runTask(plugin, service::updateAllSigns);
    }

    YamlConfiguration cfg() {
        return config();
    }

    public ShopService service() {
        return service;
    }

    /** De teksten die van een bord een shopbord maken, zoals [shop]. */
    public List<String> shortcodes() {
        List<String> codes = config().getStringList("sign-shortcodes");
        if (codes.isEmpty()) {
            return List.of("[shop]");
        }
        return codes.stream().map(code -> code.toLowerCase(Locale.ROOT).trim()).toList();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.updateOwnerName(player);
        Shop shop = service.shop(player.getUniqueId());
        if (shop == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (shop.wantsOpen() && service.ensureFee(shop)) {
                plugin.lang().send(player, shop.hasStock() ? "shop.reopened" : "shop.reopened-empty");
            }
            service.updateSign(shop);
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Shop shop = service.shop(event.getPlayer().getUniqueId());
        if (shop != null) {
            // Volgende tick is de speler echt offline, dan staat het bord op gesloten.
            plugin.getServer().getScheduler().runTask(plugin, () -> service.updateSign(shop));
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        String world = chunk.getWorld().getName();
        for (Shop shop : service.all()) {
            updateIfInChunk(shop, world, chunk);
        }
    }

    private void updateIfInChunk(Shop shop, String world, Chunk chunk) {
        Shop.SignLocation sign = shop.sign();
        if (sign != null && sign.world().equals(world) && sign.x() >> 4 == chunk.getX() && sign.z() >> 4 == chunk.getZ()) {
            service.updateSign(shop);
        }
    }
}
