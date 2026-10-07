package nl.pinda.framework.modules.moderation;

import java.util.Arrays;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /kick &lt;speler&gt; [reden] */
public final class KickCommand extends PindaCommand {

    private final ModerationActions actions;

    public KickCommand(PindaFramework plugin, ModerationActions actions) {
        super(plugin, "kick", "Kick een speler", ModerationModule.KICK);
        this.actions = actions;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            plugin.lang().send(sender, "moderation.usage-kick");
            return;
        }
        Player target = findPlayerOrFail(sender, args[0]);
        if (target == null) {
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        actions.punish(sender, target.getName(), Punishment.Type.KICK, null, actions.reason(sender, reason));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return argIndex(args) == 0 ? visiblePlayers(sender) : List.of();
    }
}
