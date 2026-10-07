package nl.pinda.framework.modules.moderation;

import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** De gedeelde stappen van alle moderatiecommando's: opzoeken, controleren, opslaan, melden. */
final class ModerationActions {

    private final PindaFramework plugin;
    private final ModerationModule module;
    private final ModerationService service;

    ModerationActions(PindaFramework plugin, ModerationModule module, ModerationService service) {
        this.plugin = plugin;
        this.module = module;
        this.service = service;
    }

    ModerationModule module() {
        return module;
    }

    /** De opgegeven reden, of "Geen reden opgegeven". */
    String reason(CommandSender sender, String reason) {
        if (reason != null && !reason.isBlank()) {
            return reason.trim();
        }
        String fallback = plugin.lang().raw(plugin.lang().languageOf(sender), "moderation.no-reason");
        return fallback == null ? "-" : fallback;
    }

    void punish(CommandSender sender, String targetName, Punishment.Type type, Long duration, String reason) {
        plugin.players().findKnown(targetName).thenAccept(known -> runTask(() -> {
            if (known == null) {
                fail(sender, "general.player-unknown", Text.p("player", targetName));
                return;
            }
            if (sender instanceof Player player && player.getUniqueId().equals(known.uuid())) {
                fail(sender, "moderation.self");
                return;
            }
            service.canPunish(sender, known.uuid()).thenAccept(allowed -> runTask(() -> {
                if (!allowed) {
                    fail(sender, "moderation.protected", Text.p("player", known.name()));
                    return;
                }
                service.punish(known.uuid(), known.name(), type, reason, sender, duration)
                        .whenComplete((punishment, error) -> runTask(() -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.SEVERE, "Straf opslaan mislukt", error);
                                plugin.lang().send(sender, "general.command-error");
                                return;
                            }
                            applyEffects(punishment);
                            announce(sender, punishment);
                        }));
            }));
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler " + targetName + " niet opzoeken", error);
            return null;
        });
    }

    void revoke(CommandSender sender, String targetName, Punishment.Type type) {
        plugin.players().findKnown(targetName).thenAccept(known -> runTask(() -> {
            if (known == null) {
                fail(sender, "general.player-unknown", Text.p("player", targetName));
                return;
            }
            service.revoke(known.uuid(), type, sender).whenComplete((count, error) -> runTask(() -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE, "Straf opheffen mislukt", error);
                    plugin.lang().send(sender, "general.command-error");
                    return;
                }
                boolean ban = type == Punishment.Type.BAN;
                if (count == null || count == 0) {
                    fail(sender, ban ? "moderation.not-banned" : "moderation.not-muted", Text.p("player", known.name()));
                    return;
                }
                plugin.lang().send(sender, ban ? "moderation.unbanned" : "moderation.unmuted", Text.p("player", known.name()));
                plugin.theme().play(sender, "success");
                notifyStaff(sender, ban ? "moderation.notify-unban" : "moderation.notify-unmute",
                        Text.p("player", known.name()), Text.p("actor", sender.getName()));
                Player target = plugin.getServer().getPlayer(known.uuid());
                if (target != null && !ban) {
                    plugin.lang().send(target, "moderation.unmuted-target");
                }
            }));
        })).exceptionally(error -> {
            plugin.getLogger().log(Level.SEVERE, "Kon speler " + targetName + " niet opzoeken", error);
            return null;
        });
    }

    private void applyEffects(Punishment punishment) {
        Player target = plugin.getServer().getPlayer(punishment.target());
        if (target == null) {
            return;
        }
        String code = plugin.lang().languageOf(target);
        switch (punishment.type()) {
            case BAN -> target.kick(service.banScreen(code, punishment));
            case KICK -> target.kick(plugin.lang().component(code, "moderation.kick-screen",
                    Text.p("reason", punishment.reason()), Text.p("actor", punishment.actorName())));
            case MUTE -> {
                service.cacheMute(target.getUniqueId(), punishment);
                plugin.lang().send(target, "moderation.muted-target", Text.p("reason", punishment.reason()),
                        Text.p("expires", service.expiryText(code, punishment)));
                plugin.theme().play(target, "error");
            }
            case WARN -> {
                plugin.lang().send(target, "moderation.warned-target", Text.p("reason", punishment.reason()),
                        Text.p("actor", punishment.actorName()));
                plugin.lang().sendTitle(target, "moderation.warned-title", "moderation.warned-subtitle",
                        Text.p("reason", punishment.reason()));
                plugin.theme().play(target, "error");
            }
        }
    }

    private void announce(CommandSender sender, Punishment punishment) {
        String type = punishment.type().name().toLowerCase(Locale.ROOT);
        String code = plugin.lang().languageOf(sender);
        plugin.lang().send(sender, "moderation.done-" + type,
                Text.p("player", punishment.targetName()), Text.p("reason", punishment.reason()),
                Text.p("expires", service.expiryText(code, punishment)));
        plugin.theme().play(sender, "success");
        notifyStaff(sender, "moderation.notify-" + type,
                Text.p("player", punishment.targetName()), Text.p("actor", punishment.actorName()),
                Text.p("reason", punishment.reason()), Text.p("expires", service.expiryText(plugin.lang().defaultLanguage(), punishment)));
    }

    /** Meldt een straf aan staff, of aan iedereen als broadcast aan staat. Niet aan de afzender zelf. */
    private void notifyStaff(CommandSender sender, String key, TagResolver... resolvers) {
        boolean everyone = module.cfg().getBoolean("broadcast", false);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online == sender) {
                continue;
            }
            if (everyone || online.hasPermission(ModerationModule.NOTIFY)) {
                plugin.lang().send(online, key, resolvers);
            }
        }
        if (sender instanceof Player) {
            plugin.getServer().getConsoleSender().sendMessage(plugin.lang().component(plugin.lang().defaultLanguage(), key, resolvers));
        }
    }

    private void fail(CommandSender sender, String key, TagResolver... resolvers) {
        plugin.lang().send(sender, key, resolvers);
        plugin.theme().play(sender, "error");
    }

    private void runTask(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
