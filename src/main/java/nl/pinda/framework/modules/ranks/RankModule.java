package nl.pinda.framework.modules.ranks;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Eigen rangen (vervangt LuckPerms): permissies per rang, prefix en naamkleur in de chat
 * en tablist, en /rank om rangen te geven.
 */
public final class RankModule extends PindaModule implements Listener {

    public static final String SET = "pinda.rank.set";
    public static final String INFO = "pinda.rank.info";
    public static final String INFO_OTHERS = "pinda.rank.info.others";
    public static final String LIST = "pinda.rank.list";
    public static final String CHAT_FORMAT = "pinda.chat.format";

    /** Opmaak die staff in de chat mag gebruiken: kleuren, vet/cursief, gradients. Geen klik-acties. */
    private static final MiniMessage CHAT_MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(),
                    StandardTags.gradient(), StandardTags.rainbow(), StandardTags.reset()))
            .build();

    private RankService service;

    public RankModule(PindaFramework plugin) {
        super(plugin, "ranks");
    }

    @Override
    protected void onEnable() {
        service = new RankService(plugin, this);
        service.load();
        listen(this);
        command(new RankCommand(plugin, service));

        plugin.display().setPrefix("rank", player -> service.prefix(service.rankOf(player)));
        plugin.display().setNameColor(player -> service.rankOf(player).color());
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            service.bootstrap(player);
        }
        service.applyAll();
    }

    @Override
    protected void onDisable() {
        if (service != null) {
            service.clearAll();
        }
        plugin.display().removePrefix("rank");
        plugin.display().setNameColor(null);
        plugin.display().refreshAll();
    }

    @Override
    protected void onReload() {
        service.load();
        service.applyAll();
    }

    YamlConfiguration cfg() {
        return config();
    }

    public RankService service() {
        return service;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.bootstrap(player);
        service.apply(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.clear(event.getPlayer());
    }

    /** Chatopmaak met rangprefix en gekleurde naam. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!config().getBoolean("chat.enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        Rank rank = service.rankOf(player);
        Component message = event.message();
        if (player.hasPermission(CHAT_FORMAT)) {
            message = CHAT_MINI_MESSAGE.deserialize(PlainTextComponentSerializer.plainText().serialize(message));
        }
        message = message.colorIfAbsent(rank.chatColor());
        Component rendered = plugin.lang().parse(config().getString("chat.format", "<rank_prefix><name> <dark_gray>»</dark_gray> <message>"),
                Placeholder.component("rank_prefix", service.prefix(rank)),
                Placeholder.component("name", Component.text(player.getName(), rank.color())),
                Placeholder.component("message", message));
        event.renderer((source, sourceDisplayName, original, viewer) -> rendered);
    }
}
