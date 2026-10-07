package nl.pinda.framework.modules.moderation;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;

/** /history &lt;speler&gt;: de laatste 10 straffen van een speler. */
public final class HistoryCommand extends PindaCommand {

    private final ModerationService service;

    public HistoryCommand(PindaFramework plugin, ModerationService service) {
        super(plugin, "history", "Straffen van een speler bekijken", ModerationModule.HISTORY, "straffen", "geschiedenis");
        this.service = service;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            plugin.lang().send(sender, "moderation.usage-history");
            return;
        }
        String name = args[0];
        plugin.players().findKnown(name)
                .thenCompose(known -> known == null
                        ? CompletableFuture.<Result>completedFuture(null)
                        : service.history(known.uuid(), 10).thenApply(list -> new Result(known.name(), list)))
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> show(sender, name, result)))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.SEVERE, "Kon geschiedenis niet laden", error);
                    return null;
                });
    }

    private record Result(String name, List<Punishment> punishments) {
    }

    private void show(CommandSender sender, String input, Result result) {
        if (result == null) {
            plugin.lang().send(sender, "general.player-unknown", Text.p("player", input));
            return;
        }
        if (result.punishments().isEmpty()) {
            plugin.lang().send(sender, "moderation.history-empty", Text.p("player", result.name()));
            return;
        }
        String code = plugin.lang().languageOf(sender);
        plugin.lang().send(sender, "moderation.history-header", Text.p("player", result.name()));
        for (Punishment punishment : result.punishments()) {
            String type = punishment.type().name().toLowerCase(Locale.ROOT);
            String status = punishment.inEffect() ? "moderation.status-active"
                    : (punishment.active() || punishment.type() == Punishment.Type.KICK || punishment.type() == Punishment.Type.WARN
                    ? "moderation.status-done" : "moderation.status-lifted");
            plugin.lang().send(sender, "moderation.history-entry",
                    Text.c("type", plugin.lang().component(code, "moderation.types." + type)),
                    Text.p("date", service.formatDate(code, punishment.created())),
                    Text.p("reason", punishment.reason()),
                    Text.p("actor", punishment.actorName()),
                    Text.c("status", plugin.lang().component(code, status)),
                    Text.p("expires", punishment.type() == Punishment.Type.BAN || punishment.type() == Punishment.Type.MUTE
                            ? service.expiryText(code, punishment) : "-"));
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        return argIndex(args) == 0 ? visiblePlayers(sender) : List.of();
    }
}
