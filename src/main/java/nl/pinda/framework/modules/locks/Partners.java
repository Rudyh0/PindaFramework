package nl.pinda.framework.modules.locks;

import java.util.UUID;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Partnerverzoeken sturen, accepteren, weigeren en partners verwijderen.
 * Partners hebben toegang tot al elkaars sloten, dus het werkt altijd twee kanten op.
 */
final class Partners {

    private Partners() {
    }

    static void request(PindaFramework plugin, LockService service, Player from, Player to) {
        if (from.getUniqueId().equals(to.getUniqueId())) {
            fail(plugin, from, "partner.self");
            return;
        }
        if (service.arePartners(from.getUniqueId(), to.getUniqueId())) {
            fail(plugin, from, "partner.already", Text.p("player", to.getName()));
            return;
        }
        if (service.partners(from.getUniqueId()).size() >= service.maxPartners()) {
            fail(plugin, from, "partner.max", Text.p("max", service.maxPartners()));
            return;
        }
        // Had de ander jou al gevraagd? Dan direct partners.
        if (service.hasRequest(to.getUniqueId(), from.getUniqueId())) {
            accept(plugin, service, from, to.getUniqueId());
            return;
        }
        if (service.hasRequest(from.getUniqueId(), to.getUniqueId())) {
            fail(plugin, from, "partner.already-sent", Text.p("player", to.getName()));
            return;
        }
        service.addRequest(from.getUniqueId(), to.getUniqueId());
        plugin.lang().send(from, "partner.request-sent", Text.p("player", to.getName()));
        plugin.theme().play(from, "success");

        TagResolver accept = Placeholder.styling("accept",
                ClickEvent.runCommand("/partner accept " + from.getName()),
                HoverEvent.showText(plugin.lang().component(to, "partner.hover-accept")));
        TagResolver deny = Placeholder.styling("deny",
                ClickEvent.runCommand("/partner deny " + from.getName()),
                HoverEvent.showText(plugin.lang().component(to, "partner.hover-deny")));
        plugin.lang().send(to, "partner.request-received", Text.p("player", from.getName()), accept, deny);
        plugin.theme().play(to, "request");
    }

    static void accept(PindaFramework plugin, LockService service, Player to, UUID from) {
        if (!service.hasRequest(from, to.getUniqueId())) {
            fail(plugin, to, "partner.no-request", Text.p("player", name(plugin, from)));
            return;
        }
        service.removeRequest(from, to.getUniqueId());
        service.removeRequest(to.getUniqueId(), from);
        if (service.partners(to.getUniqueId()).size() >= service.maxPartners()
                || service.partners(from).size() >= service.maxPartners()) {
            fail(plugin, to, "partner.max", Text.p("max", service.maxPartners()));
            return;
        }
        service.addPartners(from, to.getUniqueId());
        plugin.lang().send(to, "partner.added", Text.p("player", name(plugin, from)));
        plugin.theme().play(to, "success");
        Player requester = plugin.getServer().getPlayer(from);
        if (requester != null) {
            plugin.lang().send(requester, "partner.added", Text.p("player", to.getName()));
            plugin.theme().play(requester, "success");
        }
    }

    static void deny(PindaFramework plugin, LockService service, Player to, UUID from) {
        if (!service.hasRequest(from, to.getUniqueId())) {
            fail(plugin, to, "partner.no-request", Text.p("player", name(plugin, from)));
            return;
        }
        service.removeRequest(from, to.getUniqueId());
        plugin.lang().send(to, "partner.denied", Text.p("player", name(plugin, from)));
        Player requester = plugin.getServer().getPlayer(from);
        if (requester != null) {
            plugin.lang().send(requester, "partner.denied-sender", Text.p("player", to.getName()));
        }
    }

    static void remove(PindaFramework plugin, LockService service, Player player, UUID other) {
        if (!service.arePartners(player.getUniqueId(), other)) {
            fail(plugin, player, "partner.not-partners", Text.p("player", name(plugin, other)));
            return;
        }
        service.removePartners(player.getUniqueId(), other);
        plugin.lang().send(player, "partner.removed", Text.p("player", name(plugin, other)));
        plugin.theme().play(player, "click");
        Player otherPlayer = plugin.getServer().getPlayer(other);
        if (otherPlayer != null) {
            plugin.lang().send(otherPlayer, "partner.removed-by", Text.p("player", player.getName()));
        }
    }

    static String name(PindaFramework plugin, UUID uuid) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(uuid);
        return player.getName() != null ? player.getName() : "?";
    }

    private static void fail(PindaFramework plugin, Player player, String key, TagResolver... resolvers) {
        plugin.lang().send(player, key, resolvers);
        plugin.theme().play(player, "error");
    }
}
