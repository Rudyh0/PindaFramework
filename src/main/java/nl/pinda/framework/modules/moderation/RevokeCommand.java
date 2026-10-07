package nl.pinda.framework.modules.moderation;

import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;

/** /unban &lt;speler&gt; en /unmute &lt;speler&gt;. */
public final class RevokeCommand extends PindaCommand {

    private final ModerationActions actions;
    private final Punishment.Type type;

    public RevokeCommand(PindaFramework plugin, ModerationActions actions, Punishment.Type type) {
        super(plugin, type == Punishment.Type.BAN ? "unban" : "unmute",
                type == Punishment.Type.BAN ? "Hef een ban op" : "Hef een mute op",
                type == Punishment.Type.BAN ? ModerationModule.UNBAN : ModerationModule.MUTE);
        this.actions = actions;
        this.type = type;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            plugin.lang().send(sender, type == Punishment.Type.BAN ? "moderation.usage-unban" : "moderation.usage-unmute");
            return;
        }
        actions.revoke(sender, args[0], type);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return argIndex(args) == 0 && type == Punishment.Type.MUTE ? visiblePlayers(sender) : List.of();
    }
}
