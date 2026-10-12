package nl.pinda.framework.modules.modpack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.modpack.ModpackModule.PlayerState;
import nl.pinda.framework.modules.modpack.ModpackModule.Status;
import nl.pinda.framework.modules.modpack.ModpackSettings.Mode;
import nl.pinda.framework.player.KnownPlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /modpack - de link naar de modpack en of je alle mods hebt die nodig zijn.
 * /modpack &lt;speler&gt; - hoe het ervoor staat bij iemand anders (staff).
 */
final class ModpackCommand extends PindaCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(ZoneId.systemDefault());

    private record Lookup(KnownPlayer known, ModpackModule.Stored stored) {
    }

    private final ModpackModule module;

    ModpackCommand(PindaFramework plugin, ModpackModule module) {
        super(plugin, "modpack", "Onze modpack, en of je de juiste mods hebt", ModpackModule.USE);
        this.module = module;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        if (args.length > 0 && sender.hasPermission(ModpackModule.OTHERS)) {
            other(sender, args[0]);
            return;
        }
        ModpackSettings settings = module.settings();
        if (settings.url().isEmpty()) {
            plugin.lang().send(sender, "modpack.no-link");
        } else {
            plugin.lang().send(sender, "modpack.link", Text.c("url", module.link(true)));
        }
        if (!(sender instanceof Player player)) {
            return;
        }
        if (settings.mode() == Mode.OFF) {
            plugin.lang().send(sender, "modpack.disabled");
        }
        PlayerState state = module.state(player);
        if (state == null) {
            return;
        }
        if (state.exempt()) {
            plugin.lang().send(sender, "modpack.exempt");
            return;
        }
        switch (state.status()) {
            case OK -> plugin.lang().send(sender, "modpack.ok");
            case MISSING -> plugin.lang().send(sender, "modpack.missing", Text.p("missing", ModpackModule.labels(state.missing())));
            case NO_MOD -> plugin.lang().send(sender, "modpack.no-mod");
            case CHECKING -> plugin.lang().send(sender, "modpack.checking");
        }
    }

    private void other(CommandSender sender, String name) {
        Player online = findPlayer(sender, name);
        PlayerState state = online == null ? null : module.state(online);
        if (state != null) {
            plugin.lang().send(sender, "modpack.other.header", Text.p("player", state.name()));
            if (state.report() != null) {
                client(sender, state.report(), "online");
            }
            if (state.exempt()) {
                plugin.lang().send(sender, "modpack.other.exempt");
            }
            status(sender, state.status(), state.missing());
            return;
        }
        plugin.players().findKnown(name).thenCompose(known -> known == null
                        ? CompletableFuture.completedFuture((Lookup) null)
                        : module.stored(known.uuid()).thenApply(stored -> new Lookup(known, stored)))
                .thenAccept(found -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (found == null) {
                        plugin.lang().send(sender, "general.player-not-found", Text.p("player", name));
                        return;
                    }
                    if (found.stored() == null) {
                        plugin.lang().send(sender, "modpack.other.never", Text.p("player", found.known().name()));
                        return;
                    }
                    plugin.lang().send(sender, "modpack.other.header", Text.p("player", found.known().name()));
                    client(sender, found.stored().report(), TIME.format(Instant.ofEpochMilli(found.stored().updated())));
                    List<ModpackSettings.Required> missing = module.missing(found.stored().report());
                    status(sender, missing.isEmpty() ? Status.OK : Status.MISSING, missing);
                }))
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Kon de modlijst van " + name + " niet opzoeken", error);
                    return null;
                });
    }

    private void client(CommandSender sender, ClientReport report, String time) {
        plugin.lang().send(sender, "modpack.other.client", Text.p("client", report.loader() + " " + report.loaderVersion()),
                Text.p("count", report.topLevel().size()), Text.p("time", time));
    }

    private void status(CommandSender sender, Status status, List<ModpackSettings.Required> missing) {
        switch (status) {
            case OK -> plugin.lang().send(sender, "modpack.other.ok");
            case MISSING -> plugin.lang().send(sender, "modpack.other.missing", Text.p("missing", ModpackModule.labels(missing)));
            case NO_MOD -> plugin.lang().send(sender, "modpack.other.no-mod");
            case CHECKING -> plugin.lang().send(sender, "modpack.other.checking");
        }
    }

    @Override
    protected List<String> complete(CommandSender sender, String[] args) {
        if (argIndex(args) == 0 && sender.hasPermission(ModpackModule.OTHERS)) {
            return visiblePlayers(sender);
        }
        return List.of();
    }
}
