package nl.pinda.framework.modules.duel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /duel (/1v1, /uitdagen):
 * <pre>
 * /duel &lt;speler&gt;           iemand uitdagen
 * /duel accepteer [speler] een uitdaging aannemen (ook: accept, ja)
 * /duel weiger [speler]    een uitdaging afslaan (ook: deny, nee)
 * /duel intrekken          je eigen uitdaging intrekken
 * /duel inzet              het inzetmenu weer openen
 * /duel annuleer           het gevecht afblazen (voor het begint; beheerders: altijd)
 * /duel opgeven            opgeven tijdens het gevecht
 * /duel rematch | stoppen  na het gevecht: nog een keer, of terug
 * /duel kijken | verlaten  toeschouwer worden of weer weg
 * /duel stats [speler]     gewonnen, verloren en de verhouding
 * /duel top                de beste spelers
 * </pre>
 */
final class DuelCommand extends PindaCommand {

    private static final List<String> SUBCOMMANDS = List.of("accepteer", "weiger", "intrekken", "inzet", "annuleer", "opgeven",
            "rematch", "stoppen", "kijken", "verlaten", "stats", "top", "help");
    private static final Set<String> ACCEPT = Set.of("accepteer", "accepteren", "accept", "ja", "yes");
    private static final Set<String> DENY = Set.of("weiger", "weigeren", "deny", "nee", "no");
    private static final Set<String> WITHDRAW = Set.of("intrekken", "trekin", "withdraw", "uninvite");
    private static final Set<String> WAGER = Set.of("inzet", "wager", "menu");
    private static final Set<String> CANCEL = Set.of("annuleer", "annuleren", "afblazen", "cancel");
    private static final Set<String> FORFEIT = Set.of("opgeven", "opgeef", "forfeit", "surrender");
    private static final Set<String> REMATCH = Set.of("rematch", "nogeens", "again");
    private static final Set<String> STOP = Set.of("stoppen", "stop", "klaar", "done");
    private static final Set<String> WATCH = Set.of("kijken", "kijk", "spectate", "watch");
    private static final Set<String> LEAVE = Set.of("verlaten", "verlaat", "weg", "leave");
    private static final Set<String> STATS = Set.of("stats", "stand", "kd");
    private static final Set<String> TOP = Set.of("top", "beste", "leaderboard");
    private static final Set<String> HELP = Set.of("help", "hulp", "?");

    private final DuelModule module;

    DuelCommand(PindaFramework plugin, DuelModule module) {
        super(plugin, "duel", "Daag iemand uit voor een 1v1 in de arena", DuelModule.USE, "1v1", "uitdagen");
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0 || HELP.contains(args[0].toLowerCase(Locale.ROOT))) {
            plugin.lang().send(sender, "duel.help");
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String argument = args.length > 1 ? args[1] : null;

        if (STATS.contains(sub)) {
            stats(sender, argument);
            return;
        }
        if (TOP.contains(sub)) {
            top(sender);
            return;
        }
        if (CANCEL.contains(sub) && !(sender instanceof Player)) {
            adminCancel(sender);
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Session session = module.session();
        Session.Side side = session == null ? null : session.side(player);

        if (ACCEPT.contains(sub)) {
            module.accept(player, argument);
        } else if (DENY.contains(sub)) {
            module.deny(player, argument);
        } else if (WITHDRAW.contains(sub)) {
            module.withdraw(player);
        } else if (WAGER.contains(sub)) {
            if (side == null || session.phase != Session.Phase.WAGER) {
                fail(player, "duel.not-wagering");
            } else {
                session.openWager(side);
            }
        } else if (CANCEL.contains(sub)) {
            if (side != null && (session.phase == Session.Phase.WAGER || session.phase == Session.Phase.COUNTDOWN)) {
                session.cancelBy(side);
            } else if (player.hasPermission(DuelModule.ADMIN) && session != null) {
                adminCancel(player);
            } else if (side != null && session.phase == Session.Phase.FIGHT) {
                fail(player, "duel.cancel-fighting");
            } else {
                fail(player, "duel.not-in-duel");
            }
        } else if (FORFEIT.contains(sub)) {
            if (side == null || session.phase != Session.Phase.FIGHT) {
                fail(player, "duel.not-fighting");
            } else {
                module.lang(player, "duel.forfeited");
                session.defeated(side, Session.Outcome.FORFEIT);
            }
        } else if (REMATCH.contains(sub) || STOP.contains(sub)) {
            if (side == null || session.phase != Session.Phase.REMATCH) {
                fail(player, "duel.rematch.none");
            } else if (side.rematch != null) {
                fail(player, "duel.rematch.already");
            } else {
                session.rematch(side, REMATCH.contains(sub));
            }
        } else if (WATCH.contains(sub)) {
            if (checkPermission(player, DuelModule.SPECTATE)) {
                module.watch(player);
            }
        } else if (LEAVE.contains(sub)) {
            module.leave(player);
        } else {
            Player target = findPlayerOrFail(sender, args[0]);
            if (target != null) {
                module.challenge(player, target);
            }
        }
    }

    private void adminCancel(CommandSender sender) {
        if (!checkPermission(sender, DuelModule.ADMIN)) {
            return;
        }
        if (module.abort(sender.getName())) {
            plugin.lang().send(sender, "duel.aborted-admin");
        } else {
            plugin.lang().send(sender, "duel.watch.none");
        }
    }

    private void fail(Player player, String key) {
        module.lang(player, key);
        plugin.theme().play(player, "error");
    }

    // ============================================================ stand

    private void stats(CommandSender sender, String name) {
        if (name == null) {
            Player player = asPlayer(sender);
            if (player == null) {
                return;
            }
            showStats(sender, player.getUniqueId(), player.getName());
            return;
        }
        Player online = findPlayer(sender, name);
        if (online != null) {
            showStats(sender, online.getUniqueId(), online.getName());
            return;
        }
        plugin.players().findKnown(name).thenAccept(known -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (known == null) {
                plugin.lang().send(sender, "general.player-not-found", Text.p("player", name));
                return;
            }
            showStats(sender, known.uuid(), known.name());
        }));
    }

    private void showStats(CommandSender sender, java.util.UUID uuid, String name) {
        module.store().stats(uuid).thenAccept(stats -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (stats.fights() == 0) {
                plugin.lang().send(sender, "duel.stats.none", Text.p("player", name));
                return;
            }
            plugin.lang().send(sender, "duel.stats.line", Text.p("player", name), Text.p("wins", stats.wins()),
                    Text.p("losses", stats.losses()), Text.p("draws", stats.draws()), Text.p("ratio", ratio(stats.ratio())));
        })).exceptionally(error -> {
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.lang().send(sender, "general.command-error"));
            return null;
        });
    }

    static String ratio(double ratio) {
        return String.format(Locale.forLanguageTag("nl"), "%.2f", ratio);
    }

    private void top(CommandSender sender) {
        module.store().top(10).thenAccept(top -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (top.isEmpty()) {
                plugin.lang().send(sender, "duel.top.empty");
                return;
            }
            plugin.lang().send(sender, "duel.top.header");
            int position = 1;
            for (DuelStore.Stats stats : top) {
                boolean self = sender instanceof Player player && player.getUniqueId().equals(stats.uuid());
                plugin.lang().send(sender, self ? "duel.top.entry-self" : "duel.top.entry", Text.p("position", position++),
                        Text.p("player", stats.name() == null ? "?" : stats.name()), Text.p("wins", stats.wins()),
                        Text.p("losses", stats.losses()), Text.p("ratio", ratio(stats.ratio())));
            }
        }));
    }

    // ============================================================ aanvullen

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            List<String> options = new ArrayList<>(SUBCOMMANDS);
            options.addAll(visiblePlayers(sender).stream().filter(name -> !name.equals(sender.getName())).toList());
            return options;
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((ACCEPT.contains(sub) || DENY.contains(sub)) && sender instanceof Player player) {
                return module.incomingNames(player);
            }
            if (STATS.contains(sub)) {
                return visiblePlayers(sender);
            }
        }
        return List.of();
    }
}
