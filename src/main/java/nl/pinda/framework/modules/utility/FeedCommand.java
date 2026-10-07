package nl.pinda.framework.modules.utility;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.TargetCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /feed [speler]: geen honger meer. */
public final class FeedCommand extends TargetCommand {

    public FeedCommand(PindaFramework plugin) {
        super(plugin, "feed", "Stil je honger", "pinda.feed", "pinda.feed.others", "eat");
    }

    @Override
    protected void apply(CommandSender sender, Player target, boolean self) {
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setExhaustion(0f);
        feedback(sender, target, self, "utility.fed", "utility.fed-other");
    }
}
