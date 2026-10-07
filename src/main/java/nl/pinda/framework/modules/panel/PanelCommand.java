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

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return argIndex(args) == 0 ? List.of("logout") : List.of();
    }
}
