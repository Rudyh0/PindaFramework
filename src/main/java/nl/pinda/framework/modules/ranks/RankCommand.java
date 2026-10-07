package nl.pinda.framework.modules.ranks;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /rank set &lt;speler&gt; &lt;rang&gt;, /rank info [speler] en /rank list.
 * Werkt ook vanuit de console, zodat je jezelf de eerste keer admin kunt maken.
 */
public final class RankCommand extends PindaCommand {

    private final RankService service;

    public RankCommand(PindaFramework plugin, RankService service) {
        super(plugin, "rank", "Rangen bekijken en geven", null, "rang");
        this.service = service;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set", "geef", "zet" -> set(sender, args);
            case "list", "lijst" -> list(sender);
            default -> info(sender, args);
        }
    }

    private void set(CommandSender sender, String[] args) {
        if (!checkPermission(sender, RankModule.SET)) {
            return;
        }
        if (args.length < 3) {
            plugin.lang().send(sender, "rank.usage-set");
            return;
        }
        Rank rank = service.find(args[2]);
        if (rank == null) {
            plugin.lang().send(sender, "rank.unknown", Text.p("rank", args[2]));
            plugin.theme().play(sender, "error");
            return;
        }
        String name = args[1];
        plugin.players().findKnown(name).thenAccept(known -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (known == null) {
                plugin.lang().send(sender, "general.player-unknown", Text.p("player", name));
                plugin.theme().play(sender, "error");
                return;
            }
            service.setRank(known.uuid(), rank).whenComplete((ignored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Kon rang niet opslaan", error);
                    plugin.lang().send(sender, "general.command-error");
                    return;
                }
                plugin.lang().send(sender, "rank.set", Text.p("player", known.name()),
                        Text.c("rank", service.prefix(rank)), Text.p("rank_name", rank.displayName()));
                plugin.theme().play(sender, "success");
                Player target = plugin.getServer().getPlayer(known.uuid());
                if (target != null && target != sender) {
                    plugin.lang().send(target, "rank.received", Text.c("rank", service.prefix(rank)),
                            Text.p("rank_name", rank.displayName()));
                    plugin.theme().play(target, "success");
                }
            }));
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler niet opzoeken", error);
            return null;
        });
    }

    private void info(CommandSender sender, String[] args) {
        String name = args.length >= 2 ? args[1] : (args.length == 1 && !args[0].equalsIgnoreCase("info") ? args[0] : null);
        if (name == null) {
            Player player = asPlayer(sender);
            if (player == null) {
                return;
            }
            if (!checkPermission(sender, RankModule.INFO)) {
                return;
            }
            Rank rank = service.rankOf(player);
            plugin.lang().send(sender, "rank.info-own", Text.c("rank", service.prefix(rank)),
                    Text.p("rank_name", rank.displayName()));
            return;
        }
        if (!checkPermission(sender, RankModule.INFO_OTHERS)) {
            return;
        }
        plugin.players().findKnown(name).thenCompose(known -> known == null
                ? CompletableFuture.<Lookup>completedFuture(null)
                : service.rankOf(known.uuid()).thenApply(rank -> new Lookup(known.name(), rank)))
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (result == null) {
                        plugin.lang().send(sender, "general.player-unknown", Text.p("player", name));
                        return;
                    }
                    plugin.lang().send(sender, "rank.info", Text.p("player", result.name()),
                            Text.c("rank", service.prefix(result.rank())), Text.p("rank_name", result.rank().displayName()));
                }))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.SEVERE, "Kon rang niet opzoeken", error);
                    return null;
                });
    }

    private record Lookup(String name, Rank rank) {
    }

    private void list(CommandSender sender) {
        if (!checkPermission(sender, RankModule.LIST)) {
            return;
        }
        plugin.lang().send(sender, "rank.list-header");
        for (Rank rank : service.ranks()) {
            plugin.lang().send(sender, "rank.list-entry", Text.c("rank", service.prefix(rank)),
                    Text.p("rank_name", rank.displayName()), Text.p("id", rank.id()));
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        int index = argIndex(args);
        if (index == 0) {
            return sender.hasPermission(RankModule.SET) ? List.of("set", "info", "list") : List.of("info", "list");
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (index == 1 && (sub.equals("set") || sub.equals("info"))) {
            return visiblePlayers(sender);
        }
        if (index == 2 && sub.equals("set") && sender.hasPermission(RankModule.SET)) {
            return service.ranks().stream().map(Rank::id).toList();
        }
        return List.of();
    }
}
