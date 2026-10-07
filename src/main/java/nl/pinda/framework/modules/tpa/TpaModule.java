package nl.pinda.framework.modules.tpa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import nl.pinda.framework.player.PlayerManager;
import nl.pinda.framework.player.PlayerSetting;
import nl.pinda.framework.teleport.TeleportRequest;
import nl.pinda.framework.teleport.TeleportService;
import nl.pinda.framework.teleport.TeleportType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/** TPA: /tpa, /tpahere, /tpaccept, /tpdeny en /tpacancel. */
public final class TpaModule extends PindaModule implements Listener {

    public static final String SETTING = "tpa";
    public static final String USE = "pinda.tpa.use";
    public static final String HERE = "pinda.tpa.here";

    /** Openstaande verzoeken, per aanvrager (elke speler heeft er maximaal één). */
    private final Map<UUID, Request> outgoing = new HashMap<>();

    public TpaModule(PindaFramework plugin) {
        super(plugin, "tpa");
    }

    @Override
    protected void onEnable() {
        registerSetting();
        listen(this);
        command(new TpaCommand(plugin, this, false));
        command(new TpaCommand(plugin, this, true));
        command(new TpAcceptCommand(plugin, this));
        command(new TpDenyCommand(plugin, this));
        command(new TpaCancelCommand(plugin, this));
    }

    @Override
    protected void onDisable() {
        plugin.settings().unregister(SETTING);
        for (Request request : outgoing.values()) {
            request.cancelExpiry();
        }
        outgoing.clear();
    }

    @Override
    protected void onReload() {
        registerSetting();
    }

    private void registerSetting() {
        plugin.settings().register(new PlayerSetting(SETTING,
                config().getBoolean("default-enabled", true),
                Material.ENDER_PEARL,
                config().getBoolean("show-in-setup", true)));
    }

    public boolean allowSelf() {
        return config().getBoolean("allow-self-requests", false);
    }

    // -------------------------------------------------------------- verzoeken

    /**
     * Stuurt een verzoek.
     *
     * @param here false = de aanvrager gaat naar het doel (/tpa), true = het doel komt naar de aanvrager (/tpahere)
     */
    public void request(Player requester, Player target, boolean here) {
        LanguageManager lang = plugin.lang();
        boolean self = requester.getUniqueId().equals(target.getUniqueId());
        if (self && !allowSelf()) {
            fail(requester, "tpa.self");
            return;
        }
        boolean ignored = plugin.players().get(target).isIgnoring(requester.getUniqueId())
                && !requester.hasPermission(PlayerManager.IGNORE_EXEMPT);
        if (!plugin.settings().isEnabled(target, SETTING) || ignored) {
            fail(requester, "tpa.disabled-target", Text.p("player", target.getName()));
            return;
        }
        Request existing = outgoing.get(requester.getUniqueId());
        if (existing != null && existing.target.getUniqueId().equals(target.getUniqueId()) && existing.here == here) {
            fail(requester, "tpa.already-sent", Text.p("player", target.getName()));
            return;
        }

        TeleportService teleports = plugin.teleports();
        boolean allowed = here
                ? teleports.canAfford(requester, TeleportType.TPA)
                : teleports.canStart(requester, requester, TeleportType.TPA);
        if (!allowed) {
            return;
        }

        // Een nieuw verzoek vervangt het vorige
        if (existing != null) {
            remove(existing);
            if (existing.target.isOnline() && !existing.isSelf()) {
                lang.send(existing.target, "tpa.cancelled-target", Text.p("player", requester.getName()));
            }
        }

        int timeout = Math.max(10, config().getInt("timeout-seconds", 60));
        Request request = new Request(requester, target, here);
        request.expiry = plugin.getServer().getScheduler().runTaskLater(plugin, () -> expire(request), timeout * 20L);
        outgoing.put(requester.getUniqueId(), request);

        TagResolver seconds = Text.p("seconds", timeout);
        lang.send(requester, here ? "tpa.sent-here" : "tpa.sent", Text.p("player", target.getName()), seconds);

        TagResolver accept = Placeholder.styling("accept",
                ClickEvent.runCommand("/tpaccept " + requester.getName()),
                HoverEvent.showText(lang.component(target, "tpa.hover-accept")));
        TagResolver deny = Placeholder.styling("deny",
                ClickEvent.runCommand("/tpdeny " + requester.getName()),
                HoverEvent.showText(lang.component(target, "tpa.hover-deny")));
        lang.send(target, here ? "tpa.received-here" : "tpa.received",
                Text.p("player", requester.getName()), seconds, accept, deny);
        plugin.theme().play(target, "request");
    }

    public void accept(Player target, String from) {
        Request request = findIncoming(target, from);
        if (request == null) {
            noRequest(target, from);
            return;
        }
        remove(request);
        LanguageManager lang = plugin.lang();
        Player requester = request.requester;
        if (!requester.isOnline()) {
            noRequest(target, from);
            return;
        }

        lang.send(target, "tpa.accepted-target", Text.p("player", requester.getName()));
        if (!request.isSelf()) {
            lang.send(requester, "tpa.accepted-sender", Text.p("player", target.getName()));
            plugin.theme().play(requester, "success");
        }

        Player traveler = request.here ? target : requester;
        Player destination = request.here ? requester : target;
        plugin.teleports().teleport(new TeleportRequest(traveler, requester, TeleportType.TPA,
                () -> destination.isOnline() ? destination.getLocation() : null,
                false,
                () -> lang.send(traveler, "tpa.teleported", Text.p("player", destination.getName()))));
    }

    public void deny(Player target, String from) {
        Request request = findIncoming(target, from);
        if (request == null) {
            noRequest(target, from);
            return;
        }
        remove(request);
        plugin.lang().send(target, "tpa.denied-target", Text.p("player", request.requester.getName()));
        if (request.requester.isOnline() && !request.isSelf()) {
            plugin.lang().send(request.requester, "tpa.denied-sender", Text.p("player", target.getName()));
            plugin.theme().play(request.requester, "error");
        }
    }

    public void cancel(Player requester) {
        Request request = outgoing.get(requester.getUniqueId());
        if (request == null) {
            fail(requester, "tpa.no-outgoing");
            return;
        }
        remove(request);
        plugin.lang().send(requester, "tpa.cancelled-sender", Text.p("player", request.target.getName()));
        if (request.target.isOnline() && !request.isSelf()) {
            plugin.lang().send(request.target, "tpa.cancelled-target", Text.p("player", requester.getName()));
        }
    }

    /** Namen van spelers die deze speler een verzoek stuurden (voor tab-aanvulling). */
    public List<String> incomingNames(Player target) {
        List<String> names = new ArrayList<>();
        for (Request request : outgoing.values()) {
            if (request.target.getUniqueId().equals(target.getUniqueId())) {
                names.add(request.requester.getName());
            }
        }
        return names;
    }

    // ------------------------------------------------------------------ intern

    private Request findIncoming(Player target, String from) {
        Request newest = null;
        for (Request request : outgoing.values()) {
            if (!request.target.getUniqueId().equals(target.getUniqueId())) {
                continue;
            }
            if (from != null) {
                if (request.requester.getName().equalsIgnoreCase(from)) {
                    return request;
                }
            } else if (newest == null || request.created > newest.created) {
                newest = request;
            }
        }
        return newest;
    }

    private void expire(Request request) {
        if (!outgoing.remove(request.requester.getUniqueId(), request)) {
            return;
        }
        if (request.requester.isOnline()) {
            plugin.lang().send(request.requester, "tpa.expired-sender", Text.p("player", request.target.getName()));
        }
        if (request.target.isOnline() && !request.isSelf()) {
            plugin.lang().send(request.target, "tpa.expired-target", Text.p("player", request.requester.getName()));
        }
    }

    private void remove(Request request) {
        outgoing.remove(request.requester.getUniqueId(), request);
        request.cancelExpiry();
    }

    private void noRequest(Player target, String from) {
        if (from == null) {
            fail(target, "tpa.no-request");
        } else {
            fail(target, "tpa.no-request-from", Text.p("player", from));
        }
    }

    private void fail(Player player, String key, TagResolver... resolvers) {
        plugin.lang().send(player, key, resolvers);
        plugin.theme().play(player, "error");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Iterator<Request> iterator = outgoing.values().iterator();
        while (iterator.hasNext()) {
            Request request = iterator.next();
            if (request.requester.getUniqueId().equals(uuid) || request.target.getUniqueId().equals(uuid)) {
                request.cancelExpiry();
                iterator.remove();
            }
        }
    }

    /** Een openstaand TPA-verzoek. */
    private static final class Request {
        private final Player requester;
        private final Player target;
        private final boolean here;
        private final long created = System.currentTimeMillis();
        private BukkitTask expiry;

        private Request(Player requester, Player target, boolean here) {
            this.requester = requester;
            this.target = target;
            this.here = here;
        }

        private boolean isSelf() {
            return requester.getUniqueId().equals(target.getUniqueId());
        }

        private void cancelExpiry() {
            if (expiry != null) {
                expiry.cancel();
            }
        }
    }
}
