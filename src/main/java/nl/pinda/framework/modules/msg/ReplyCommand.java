package nl.pinda.framework.modules.msg;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /r &lt;bericht&gt;: antwoordt op het laatste privébericht. */
public final class ReplyCommand extends PindaCommand {

    private final MsgModule module;

    public ReplyCommand(PindaFramework plugin, MsgModule module) {
        super(plugin, "r", "Antwoord op een privébericht", MsgModule.USE, "reply");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0 || String.join("", args).isBlank()) {
            plugin.lang().send(player, "msg.usage-reply");
            return;
        }
        Player partner = module.lastPartner(player);
        if (partner == null) {
            plugin.lang().send(player, module.hasPartner(player) ? "msg.partner-offline" : "msg.no-reply");
            plugin.theme().play(player, "error");
            return;
        }
        module.send(player, partner, String.join(" ", args).trim());
    }
}
