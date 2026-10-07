package nl.pinda.framework.modules.moderation;

import java.util.logging.Level;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.command.PindaCommand;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;

/** /banlist: de 15 nieuwste actieve bans. */
public final class BanlistCommand extends PindaCommand {

    private final ModerationService service;

    public BanlistCommand(PindaFramework plugin, ModerationService service) {
        super(plugin, "banlist", "Actieve bans bekijken", ModerationModule.HISTORY, "bans");
        this.service = service;
    }

    @Override
    protected void run(CommandSender sender, String[] args) {
        service.activeBans(15).thenAccept(bans -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (bans.isEmpty()) {
                plugin.lang().send(sender, "moderation.banlist-empty");
                return;
            }
            String code = plugin.lang().languageOf(sender);
            plugin.lang().send(sender, "moderation.banlist-header", Text.p("count", bans.size()));
            for (Punishment ban : bans) {
                plugin.lang().send(sender, "moderation.banlist-entry",
                        Text.p("player", ban.targetName()), Text.p("reason", ban.reason()),
                        Text.p("actor", ban.actorName()), Text.p("expires", service.expiryText(code, ban)));
            }
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon de banlijst niet laden", error);
            return null;
        });
    }
}
