package nl.pinda.framework.modules.shop;

import java.util.List;
import java.util.Locale;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /shop: het shopmenu. Snelkoppelingen: /shop markt, /shop beheer, /shop open, /shop sluit.
 */
public final class ShopCommand extends PindaCommand {

    private final ShopService service;

    public ShopCommand(PindaFramework plugin, ShopModule module, ShopService service) {
        super(plugin, "shop", "Shops en de marktplaats", ShopModule.USE, "winkel", "markt", "market");
        this.service = service;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Shop own = service.shop(player.getUniqueId());
        switch (sub) {
            case "markt", "market", "marktplaats" -> new MarketMenu(plugin, player, service, 0).open();
            case "beheer", "manage", "mijn", "mine" -> {
                if (own == null) {
                    new ShopHubMenu(plugin, player, service).open();
                } else {
                    new ShopManageMenu(plugin, player, service, own).open();
                }
            }
            case "open" -> {
                if (own == null) {
                    plugin.lang().send(player, "shop.no-shop");
                    return;
                }
                service.setOpen(own, true);
                plugin.lang().send(player, own.wantsOpen()
                        ? (own.hasStock() ? "shop.opened" : "shop.opened-empty")
                        : "shop.open-failed");
            }
            case "sluit", "dicht", "close" -> {
                if (own == null) {
                    plugin.lang().send(player, "shop.no-shop");
                    return;
                }
                service.setOpen(own, false);
                plugin.lang().send(player, "shop.closed-by-owner");
            }
            default -> new ShopHubMenu(plugin, player, service).open();
        }
        plugin.theme().play(player, "menu-open");
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0) {
            return List.of("markt", "beheer", "open", "sluit");
        }
        return List.of();
    }
}
