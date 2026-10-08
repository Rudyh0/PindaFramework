package nl.pinda.framework.modules.leaderboards;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /top - het menu met alle toplijsten
 * /top &lt;lijst&gt; - een toplijst in de chat (geld, skills, speeltijd, kills, mobkills, doden)
 */
final class TopCommand extends PindaCommand {

    private final LeaderboardsModule module;

    TopCommand(PindaFramework plugin, LeaderboardsModule module) {
        super(plugin, "top", "De toplijsten van de server", LeaderboardsModule.USE,
                "toplijst", "toplijsten", "leaderboard", "leaderboards", "lb");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        LeaderboardService service = module.service();
        if (args.length == 0) {
            if (sender instanceof Player player) {
                TopMenu.open(plugin, module, player, null);
            } else {
                plugin.lang().send(sender, "top.usage", Text.p("boards", ids()));
            }
            return;
        }
        Board board = Board.find(args[0]);
        if (board == null || !service.boards().contains(board)) {
            plugin.lang().send(sender, "top.unknown", Text.p("input", args[0]), Text.p("boards", ids()));
            plugin.theme().play(sender, "error");
            return;
        }
        if (service.updated() == 0) {
            plugin.lang().send(sender, "top.loading");
            return;
        }
        String code = plugin.lang().languageOf(sender);
        LeaderboardService.Ranking ranking = service.ranking(board);
        List<LeaderboardService.Entry> entries = ranking.top(module.chatPlaces());
        if (entries.isEmpty()) {
            plugin.lang().send(sender, "top.empty", Text.p("board", service.name(board, code)));
            return;
        }
        plugin.lang().send(sender, "top.header", Text.p("board", service.name(board, code)));
        UUID self = sender instanceof Player player ? player.getUniqueId() : null;
        for (int index = 0; index < entries.size(); index++) {
            LeaderboardService.Entry entry = entries.get(index);
            plugin.lang().send(sender, entry.uuid().equals(self) ? "top.entry-self" : "top.entry",
                    Text.p("position", index + 1), Text.p("player", entry.name()),
                    Text.p("value", service.format(board, entry.value(), code)));
        }
        if (self != null) {
            LeaderboardService.Entry own = ranking.entry(self);
            if (own == null) {
                plugin.lang().send(sender, "top.own-none");
            } else if (ranking.position(self) > entries.size()) {
                plugin.lang().send(sender, "top.own", Text.p("position", ranking.position(self)),
                        Text.p("value", service.format(board, own.value(), code)));
            }
        }
    }

    private String ids() {
        List<String> ids = new ArrayList<>();
        for (Board board : module.service().boards()) {
            ids.add(board.id());
        }
        return String.join(", ", ids);
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) != 0) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (Board board : module.service().boards()) {
            options.add(board.id());
        }
        return options;
    }
}
