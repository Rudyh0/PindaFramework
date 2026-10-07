package nl.pinda.framework.modules.shop;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Shopborden: een bord met [shop] op de eerste regel wordt het bord van je shop.
 * Klikken opent de shop. Alleen de eigenaar (of staff) kan het bord afbreken;
 * ook het blok waar het aan hangt, explosies en zuigers kunnen er niet bij.
 */
public final class ShopSignListener implements Listener {

    private final PindaFramework plugin;
    private final ShopModule module;
    private final ShopService service;

    public ShopSignListener(PindaFramework plugin, ShopModule module, ShopService service) {
        this.plugin = plugin;
        this.module = module;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Component first = event.line(0);
        if (first == null) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(first).trim().toLowerCase(Locale.ROOT);
        if (!module.shortcodes().contains(text)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(ShopModule.SIGN)) {
            fail(player, "general.no-permission");
            return;
        }
        Shop shop = service.shop(player.getUniqueId());
        if (shop == null) {
            fail(player, "shop.sign-no-shop");
            return;
        }
        Block block = event.getBlock();
        Shop.SignLocation current = shop.sign();
        if (current != null && !current.equals(ShopService.key(block)) && service.signExists(shop)) {
            event.setCancelled(true);
            fail(player, "shop.sign-limit", Text.p("x", current.x()), Text.p("y", current.y()), Text.p("z", current.z()));
            return;
        }

        service.linkSign(shop, block);
        List<Component> lines = service.signLines(shop);
        for (int i = 0; i < lines.size(); i++) {
            event.line(i, lines.get(i));
        }
        // Volgende tick: beide kanten beschrijven en het bord vastzetten (niet meer bewerkbaar)
        plugin.getServer().getScheduler().runTask(plugin, () -> service.updateSign(shop));
        plugin.lang().send(player, "shop.sign-created");
        plugin.theme().play(player, "success");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Shop shop = service.shopAtSign(block);
        if (shop == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getUniqueId().equals(shop.owner())) {
            new ShopManageMenu(plugin, player, service, shop).open();
            plugin.theme().play(player, "menu-open");
            return;
        }
        if (!player.hasPermission(ShopModule.USE)) {
            fail(player, "general.no-permission");
            return;
        }
        if (!service.isOpenForBuyers(shop)) {
            fail(player, "shop.closed", Text.p("player", shop.ownerName()), Text.p("shop", shop.name()));
            return;
        }
        new ShopViewMenu(plugin, player, service, shop, false).open();
        plugin.theme().play(player, "menu-open");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        Shop shop = service.shopAtSign(block);
        boolean isSign = shop != null;
        if (shop == null) {
            shop = service.shopSupportedBy(block);
        }
        if (shop == null) {
            return;
        }
        boolean allowed = player.getUniqueId().equals(shop.owner()) || player.hasPermission(ShopModule.ADMIN);
        if (!allowed) {
            event.setCancelled(true);
            fail(player, "shop.sign-protected", Text.p("player", shop.ownerName()));
            return;
        }
        service.unlinkSign(shop);
        plugin.lang().send(player, isSign ? "shop.sign-broken" : "shop.sign-broken-support");
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isProtected)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean isProtected(Block block) {
        return service.shopAtSign(block) != null || service.shopSupportedBy(block) != null;
    }

    private void fail(Player player, String key, TagResolver... resolvers) {
        plugin.lang().send(player, key, resolvers);
        plugin.theme().play(player, "error");
    }
}
