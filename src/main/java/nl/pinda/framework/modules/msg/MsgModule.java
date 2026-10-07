package nl.pinda.framework.modules.msg;

import java.util.HashMap;
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
import nl.pinda.framework.player.PindaPlayer;
import nl.pinda.framework.player.PlayerManager;
import nl.pinda.framework.player.PlayerSetting;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Privéberichten: /msg, /r, /socialspy en /ignore. */
public final class MsgModule extends PindaModule implements Listener {

    public static final String SETTING = "msg";
    public static final String SPY_SETTING = "socialspy";
    public static final String USE = "pinda.msg.use";
    public static final String SPY = "pinda.msg.socialspy";
    public static final String BYPASS = "pinda.msg.bypass";
    public static final String IGNORE = "pinda.ignore.use";

    /** Met wie elke speler het laatst berichten uitwisselde, voor /r. */
    private final Map<UUID, UUID> lastPartner = new HashMap<>();

    public MsgModule(PindaFramework plugin) {
        super(plugin, "msg");
    }

    @Override
    protected void onEnable() {
        registerSetting();
        listen(this);
        command(new MsgCommand(plugin, this));
        command(new ReplyCommand(plugin, this));
        command(new SocialSpyCommand(plugin));
        command(new IgnoreCommand(plugin));
    }

    @Override
    protected void onDisable() {
        plugin.settings().unregister(SETTING);
        lastPartner.clear();
    }

    @Override
    protected void onReload() {
        registerSetting();
    }

    private void registerSetting() {
        plugin.settings().register(new PlayerSetting(SETTING,
                config().getBoolean("default-enabled", true),
                Material.PAPER,
                config().getBoolean("show-in-setup", true)));
    }

    /** Stuurt een privébericht. De afzender kan ook de console zijn. */
    public void send(CommandSender sender, Player target, String message) {
        LanguageManager lang = plugin.lang();
        Player senderPlayer = sender instanceof Player player ? player : null;

        if (senderPlayer != null) {
            if (senderPlayer.getUniqueId().equals(target.getUniqueId())
                    && !config().getBoolean("allow-self-messages", false)) {
                fail(sender, "msg.self");
                return;
            }
            if (!senderPlayer.hasPermission(BYPASS)) {
                PindaPlayer targetData = plugin.players().get(target);
                boolean ignored = targetData.isIgnoring(senderPlayer.getUniqueId())
                        && !senderPlayer.hasPermission(PlayerManager.IGNORE_EXEMPT);
                if (!plugin.settings().isEnabled(target, SETTING) || ignored) {
                    fail(sender, "msg.disabled-target", Text.p("player", target.getName()));
                    return;
                }
            }
        }

        String consoleName = lang.raw(lang.languageOf(target), "msg.console-name");
        String senderName = senderPlayer != null
                ? senderPlayer.getName()
                : (consoleName == null || consoleName.isBlank() ? "Console" : consoleName);
        TagResolver text = Text.p("message", message);

        lang.send(sender, "msg.format-sent",
                Text.p("target", target.getName()), text, replyTag(sender, target.getName()));
        lang.send(target, "msg.format-received",
                Text.p("sender", senderName), text,
                senderPlayer != null ? replyTag(target, senderName) : Placeholder.styling("reply"));
        plugin.theme().play(target, "message");

        if (senderPlayer != null) {
            lastPartner.put(senderPlayer.getUniqueId(), target.getUniqueId());
            lastPartner.put(target.getUniqueId(), senderPlayer.getUniqueId());
        }

        // Social spy voor staff
        for (Player spy : plugin.getServer().getOnlinePlayers()) {
            if (spy == sender || spy == target || !spy.hasPermission(SPY)) {
                continue;
            }
            if (plugin.players().get(spy).getBoolean(SPY_SETTING, false)) {
                lang.send(spy, "msg.format-spy",
                        Text.p("sender", senderName), Text.p("target", target.getName()), text);
            }
        }

        if (config().getBoolean("log-to-console", false)) {
            plugin.getLogger().info("[MSG] " + senderName + " -> " + target.getName() + ": " + message);
        }
    }

    /** De speler met wie deze speler het laatst berichten uitwisselde, als die online is. */
    public Player lastPartner(Player player) {
        UUID partner = lastPartner.get(player.getUniqueId());
        return partner == null ? null : plugin.getServer().getPlayer(partner);
    }

    public boolean hasPartner(Player player) {
        return lastPartner.containsKey(player.getUniqueId());
    }

    /** Een tag &lt;reply&gt;: klikken vult "/msg naam " in de chat in. */
    private TagResolver replyTag(CommandSender viewer, String name) {
        return Placeholder.styling("reply",
                ClickEvent.suggestCommand("/msg " + name + " "),
                HoverEvent.showText(plugin.lang().component(viewer, "msg.hover-reply")));
    }

    private void fail(CommandSender sender, String key, TagResolver... resolvers) {
        plugin.lang().send(sender, key, resolvers);
        plugin.theme().play(sender, "error");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPartner.remove(event.getPlayer().getUniqueId());
    }
}
