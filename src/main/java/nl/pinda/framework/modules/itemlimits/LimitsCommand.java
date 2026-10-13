package nl.pinda.framework.modules.itemlimits;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /limieten - hoeveel beperkte items je hebt en hoeveel je er mag hebben.
 * /limieten &lt;speler&gt; - hetzelfde voor iemand anders, met waar de items liggen (staff).
 */
final class LimitsCommand extends PindaCommand {

    private final ItemLimitModule module;

    LimitsCommand(PindaFramework plugin, ItemLimitModule module) {
        super(plugin, "limieten", "Je beperkte items", ItemLimitModule.USE, "itemlimits", "itemlimiet", "limits");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0) {
            Player player = asPlayer(sender);
            if (player != null) {
                module.scan(player);
                show(sender, player.getUniqueId(), player.getName(), false);
            }
            return;
        }
        if (!checkPermission(sender, ItemLimitModule.OTHERS)) {
            return;
        }
        plugin.players().findKnown(args[0]).thenAccept(known -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (known == null) {
                plugin.lang().send(sender, "general.player-not-found", Text.p("player", args[0]));
                return;
            }
            Player online = plugin.getServer().getPlayer(known.uuid());
            if (online != null) {
                module.scan(online);
            }
            show(sender, known.uuid(), known.name(), true);
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler " + args[0] + " niet opzoeken", error);
            return null;
        });
    }

    private void show(CommandSender sender, UUID uuid, String name, boolean details) {
        List<LimitRule> rules = module.rules().stream().filter(LimitRule::enabled).toList();
        if (rules.isEmpty()) {
            plugin.lang().send(sender, "itemlimits.list.none");
            return;
        }
        String code = plugin.lang().languageOf(sender);
        plugin.lang().send(sender, "itemlimits.list.header", Text.p("player", name));
        List<LimitedItem> items = module.itemsOf(uuid);
        Component giftTag = plugin.lang().component(code, "itemlimits.list.gift-tag");
        for (LimitRule rule : rules) {
            // Cadeaus (voor hemzelf) tellen niet mee: die staan er apart achter.
            long count = items.stream().filter(item -> item.rule().equals(rule.id()) && !item.gift()).count();
            long gifts = items.stream().filter(item -> item.rule().equals(rule.id()) && item.gift()).count();
            Component giftText = gifts > 0 ? plugin.lang().component(code, "itemlimits.list.gifts", Text.p("count", gifts)) : Component.empty();
            if (rule.perPlayer() > 0) {
                plugin.lang().send(sender, count >= rule.perPlayer() ? "itemlimits.list.line-full" : "itemlimits.list.line",
                        Text.c("item", rule.displayName()), Text.p("count", count), Text.p("max", rule.perPlayer()), Text.c("gifts", giftText));
            } else {
                plugin.lang().send(sender, "itemlimits.list.line-nomax", Text.c("item", rule.displayName()), Text.p("count", count),
                        Text.c("gifts", giftText));
            }
            if (details) {
                for (LimitedItem item : items) {
                    if (item.rule().equals(rule.id())) {
                        plugin.lang().send(sender, "itemlimits.list.item", Text.p("serial", item.serial()),
                                Text.c("where", module.describe(item.location(), code)), Text.c("gift", item.gift() ? giftTag : Component.empty()));
                    }
                }
            }
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender.hasPermission(ItemLimitModule.OTHERS)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }
}
