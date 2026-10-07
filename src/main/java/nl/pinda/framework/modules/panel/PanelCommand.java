package nl.pinda.framework.modules.panel;

import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /panel: een eenmalige inloglink voor het webpaneel. /panel logout: overal uitloggen. */
final class PanelCommand extends PindaCommand {

    private final PanelModule module;

    PanelCommand(PindaFramework plugin, PanelModule module) {
        super(plugin, "panel", "Inloglink voor het webpaneel", PanelModule.USE, "paneel", "webpanel");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("2fa")) {
            resetTwoFactor(sender, args);
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length > 0) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (action.equals("logout") || action.equals("uitloggen")) {
                int count = module.sessions().removeAll(player.getUniqueId());
                plugin.lang().send(player, "panel.logged-out", Text.p("count", count));
                plugin.theme().play(player, "success");
                return;
            }
            plugin.lang().send(player, "panel.usage");
            return;
        }
        if (!module.running()) {
            plugin.lang().send(player, "panel.offline");
            plugin.theme().play(player, "error");
            return;
        }
        String url = module.createLink(player);
        String code = plugin.lang().languageOf(player);
        plugin.lang().send(player, "panel.link",
                Placeholder.styling("link", ClickEvent.openUrl(url),
                        HoverEvent.showText(plugin.lang().component(code, "panel.link-hover"))),
                Text.p("minutes", module.linkMillis() / 60_000L));
        plugin.theme().play(player, "success");
    }

    /** /panel 2fa reset &lt;speler&gt;: de authenticator van iemand ontkoppelen (telefoon kwijt). */
    private void resetTwoFactor(CommandSender sender, String[] args) {
        if (!checkPermission(sender, PanelUser.SECURITY)) {
            return;
        }
        if (args.length < 3 || !args[1].equalsIgnoreCase("reset")) {
            plugin.lang().send(sender, "panel.2fa-usage");
            return;
        }
        String name = args[2];
        plugin.players().findKnown(name).thenCompose(known -> {
            if (known == null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.lang().send(sender, "general.player-unknown", Text.p("player", name)));
                return java.util.concurrent.CompletableFuture.<Void>completedFuture(null);
            }
            return module.resetTwoFactor(known.uuid()).thenAccept(removed -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.lang().send(sender, removed ? "panel.2fa-reset" : "panel.2fa-none", Text.p("player", known.name()));
                plugin.getLogger().info("[Paneel] 2FA van " + known.name() + " gereset door " + sender.getName()
                        + (removed ? "" : " (er was niets gekoppeld)"));
            }));
        }).exceptionally(error -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Kon 2FA niet resetten", error);
            return null;
        });
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        if (index == 0) {
            return sender.hasPermission(PanelUser.SECURITY) ? List.of("logout", "2fa") : List.of("logout");
        }
        if (args[0].equalsIgnoreCase("2fa") && sender.hasPermission(PanelUser.SECURITY)) {
            return index == 1 ? List.of("reset") : index == 2 ? visiblePlayers(sender) : List.of();
        }
        return List.of();
    }
}
