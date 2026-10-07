package nl.pinda.framework.modules.back;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.teleport.TeleportRequest;
import nl.pinda.framework.teleport.TeleportType;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /back: terug naar je vorige plek. */
public final class BackCommand extends PindaCommand {

    private final BackModule module;

    public BackCommand(PindaFramework plugin, BackModule module) {
        super(plugin, "back", "Ga terug naar je vorige plek", BackModule.USE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Location last = module.last(player);
        if (last == null || last.getWorld() == null) {
            plugin.lang().send(player, "back.none");
            plugin.theme().play(player, "error");
            return;
        }
        plugin.teleports().teleport(new TeleportRequest(player, player, TeleportType.BACK,
                () -> last, true, () -> plugin.lang().send(player, "back.teleported")));
    }
}
