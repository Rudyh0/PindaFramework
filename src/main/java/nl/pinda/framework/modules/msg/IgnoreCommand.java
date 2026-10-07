package nl.pinda.framework.modules.msg;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.player.PindaPlayer;
import nl.pinda.framework.player.PlayerManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /ignore [speler]: negeert een speler (of zet dat weer uit). Genegeerde spelers kunnen je
 * geen privéberichten en TPA-verzoeken sturen. Zonder naam: lijst van wie je negeert.
 */
public final class IgnoreCommand extends PindaCommand {

    public IgnoreCommand(PindaFramework plugin) {
        super(plugin, "ignore", "Negeer een speler", MsgModule.IGNORE, "unignore");
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        if (args.length == 0 || args[0].isBlank()) {
            list(player);
            return;
        }
        plugin.players().findKnown(args[0]).thenAccept(known -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (known == null) {
                plugin.lang().send(player, "general.player-unknown", Text.p("player", args[0]));
                plugin.theme().play(player, "error");
                return;
            }
            if (known.uuid().equals(player.getUniqueId())) {
                plugin.lang().send(player, "msg.ignore-self");
                plugin.theme().play(player, "error");
                return;
            }
            PindaPlayer data = plugin.players().get(player);
            boolean ignore = !data.isIgnoring(known.uuid());
            if (ignore) {
                Player online = plugin.getServer().getPlayer(known.uuid());
                if (online != null && online.hasPermission(PlayerManager.IGNORE_EXEMPT)) {
                    plugin.lang().send(player, "msg.ignore-exempt", Text.p("player", known.name()));
                    plugin.theme().play(player, "error");
                    return;
                }
            }
            plugin.players().setIgnoring(data, known.uuid(), ignore);
            plugin.lang().send(player, ignore ? "msg.ignore-added" : "msg.ignore-removed", Text.p("player", known.name()));
            plugin.theme().play(player, "success");
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler " + args[0] + " niet opzoeken", error);
            return null;
        });
    }

    private void list(Player player) {
        List<String> names = new ArrayList<>();
        for (UUID uuid : plugin.players().get(player).ignored()) {
            OfflinePlayer offline = plugin.getServer().getOfflinePlayer(uuid);
            names.add(offline.getName() != null ? offline.getName() : uuid.toString().substring(0, 8));
        }
        if (names.isEmpty()) {
            plugin.lang().send(player, "msg.ignore-list-empty");
            return;
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        plugin.lang().send(player, "msg.ignore-list", Text.p("players", String.join(", ", names)));
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) != 0) {
            return List.of();
        }
        return visiblePlayers(sender).stream()
                .filter(name -> !name.equals(sender.getName()))
                .toList();
    }
}
