package nl.pinda.framework.modules.moderation;

import java.util.Arrays;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;

/**
 * /ban &lt;speler&gt; [duur] [reden] en /tempban &lt;speler&gt; &lt;duur&gt; [reden].
 * Zonder duur is een ban permanent; dat mag alleen met pinda.mod.ban.permanent.
 */
public final class BanCommand extends PindaCommand {

    private final ModerationActions actions;
    private final boolean temp;

    public BanCommand(PindaFramework plugin, ModerationActions actions, boolean temp) {
        super(plugin, temp ? "tempban" : "ban", temp ? "Ban een speler tijdelijk" : "Ban een speler", ModerationModule.BAN);
        this.actions = actions;
        this.temp = temp;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].isBlank() || (temp && args.length < 2)) {
            plugin.lang().send(sender, temp ? "moderation.usage-tempban" : "moderation.usage-ban");
            return;
        }
        long duration = args.length >= 2 ? Durations.parse(args[1]) : -1;
        int reasonStart = duration > 0 ? 2 : 1;
        if (temp && duration <= 0) {
            plugin.lang().send(sender, "moderation.invalid-duration", Text.p("input", args[1]));
            plugin.theme().play(sender, "error");
            return;
        }
        boolean permanentAllowed = sender.hasPermission(ModerationModule.BAN_PERMANENT);
        long max = actions.module().maxTempBan();
        String maxText = actions.module().service().formatDuration(plugin.lang().languageOf(sender), max);
        if (duration <= 0 && !permanentAllowed) {
            plugin.lang().send(sender, "moderation.need-duration", Text.p("max", maxText));
            plugin.theme().play(sender, "error");
            return;
        }
        if (duration > max && !permanentAllowed) {
            plugin.lang().send(sender, "moderation.too-long", Text.p("max", maxText));
            plugin.theme().play(sender, "error");
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length));
        actions.punish(sender, args[0], Punishment.Type.BAN, duration > 0 ? duration : null, actions.reason(sender, reason));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        if (index == 0) {
            return visiblePlayers(sender);
        }
        if (index == 1) {
            return List.of("1u", "1d", "3d", "7d");
        }
        return List.of();
    }
}
