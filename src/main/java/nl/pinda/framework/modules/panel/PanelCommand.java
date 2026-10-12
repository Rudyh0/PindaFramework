package nl.pinda.framework.modules.panel;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;

/**
 * /panel: een eenmalige inloglink voor het webpaneel. /panel logout: overal uitloggen.
 * /panel devlink &lt;naam&gt;: voor het dev-paneel (PindaHost), alleen via RCON.
 */
final class PanelCommand extends PindaCommand {

    /** Zoals de gebruikersnamen van het dev-paneel. */
    private static final Pattern DEV_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

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
        if (args.length > 0 && args[0].equalsIgnoreCase("devlink")) {
            devLink(sender, args);
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

    /**
     * /panel devlink &lt;naam&gt;: de knop "Webpaneel" in het dev-paneel. Alleen via RCON: dat kan
     * alleen het dev-paneel (vanaf de VPS zelf, met het RCON-wachtwoord). Het antwoord gaat terug
     * via RCON en komt dus niet in het serverlog, waar staff met consolerechten het kan lezen.
     * De regels zijn vast van vorm, zodat het dev-paneel ze kan lezen.
     */
    private void devLink(CommandSender sender, String[] args) {
        if (!(sender instanceof RemoteConsoleCommandSender)) {
            if (sender instanceof Player) {
                plugin.lang().send(sender, "panel.usage");
            } else {
                sender.sendMessage("PindaHost-fout: dit kan alleen via RCON (de knop Webpaneel in het dev-paneel).");
            }
            return;
        }
        if (args.length != 2 || !DEV_NAME.matcher(args[1]).matches()) {
            sender.sendMessage("PindaHost-fout: gebruik panel devlink <naam> (letters, cijfers, _ . -).");
            return;
        }
        if (!module.running()) {
            sender.sendMessage("PindaHost-fout: het webpaneel draait niet. Staat de poort al in gebruik? Kijk in de console en in modules/panel.yml.");
            return;
        }
        String url = module.createDevLink(args[1]);
        sender.sendMessage("PindaHost-link: " + url);
        plugin.getLogger().info("[Paneel] Inloglink voor " + args[1] + " gemaakt door het dev-paneel.");
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
