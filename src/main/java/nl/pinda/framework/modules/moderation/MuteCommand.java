package nl.pinda.framework.modules.moderation;

import java.util.Arrays;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import org.bukkit.command.CommandSender;

/** /mute &lt;speler&gt; [duur] [reden]. Zonder duur is de mute permanent. */
public final class MuteCommand extends PindaCommand {

    private final ModerationActions actions;

    public MuteCommand(PindaFramework plugin, ModerationActions actions) {
        super(plugin, "mute", "Mute een speler", ModerationModule.MUTE);
        this.actions = actions;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            plugin.lang().send(sender, "moderation.usage-mute");
            return;
        }
        long duration = args.length >= 2 ? Durations.parse(args[1]) : -1;
        int reasonStart = duration > 0 ? 2 : 1;
        String reason = String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length));
        actions.punish(sender, args[0], Punishment.Type.MUTE, duration > 0 ? duration : null, actions.reason(sender, reason));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        if (index == 0) {
            return visiblePlayers(sender);
        }
        if (index == 1) {
            return List.of("10m", "30m", "1u", "1d");
        }
        return List.of();
    }
}
