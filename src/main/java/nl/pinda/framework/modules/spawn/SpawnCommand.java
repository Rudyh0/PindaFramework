package nl.pinda.framework.modules.spawn;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.teleport.TeleportRequest;
import nl.pinda.framework.teleport.TeleportType;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /spawn: teleporteert naar de spawn. */
public final class SpawnCommand extends PindaCommand {

    private final SpawnModule module;

    public SpawnCommand(PindaFramework plugin, SpawnModule module) {
        super(plugin, "spawn", "Teleporteer naar de spawn", SpawnModule.USE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Location spawn = module.spawn();
        plugin.teleports().teleport(new TeleportRequest(player, player, TeleportType.SPAWN,
                () -> spawn, true, () -> plugin.lang().send(player, "spawn.teleported")));
    }
}
