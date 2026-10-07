package nl.pinda.framework.modules.msg;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.player.PindaPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /socialspy: staff ziet privéberichten van anderen mee. */
public final class SocialSpyCommand extends PindaCommand {

    public SocialSpyCommand(PindaFramework plugin) {
        super(plugin, "socialspy", "Lees privéberichten mee (staff)", MsgModule.SPY, "spy");
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        PindaPlayer data = plugin.players().get(player);
        boolean enabled = !data.getBoolean(MsgModule.SPY_SETTING, false);
        data.setSetting(MsgModule.SPY_SETTING, Boolean.toString(enabled));
        plugin.players().save(data);
        plugin.lang().send(player, enabled ? "msg.socialspy-on" : "msg.socialspy-off");
        plugin.theme().play(player, "click");
    }
}
