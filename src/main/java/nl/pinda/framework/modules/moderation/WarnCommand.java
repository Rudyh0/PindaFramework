package nl.pinda.framework.modules.moderation;

import java.util.Arrays;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;

/** /warn &lt;speler&gt; &lt;reden&gt;: een waarschuwing die in de geschiedenis komt. */
public final class WarnCommand extends PindaCommand {

    private final ModerationActions actions;

    public WarnCommand(PindaFramework plugin, ModerationActions actions) {
        super(plugin, "warn", "Waarschuw een speler", ModerationModule.WARN, "waarschuw");
        this.actions = actions;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.lang().send(sender, "moderation.usage-warn");
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        actions.punish(sender, args[0], Punishment.Type.WARN, null, actions.reason(sender, reason));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return argIndex(args) == 0 ? visiblePlayers(sender) : List.of();
    }
}
