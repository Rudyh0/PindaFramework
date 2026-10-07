package nl.pinda.framework.input;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.PindaFramework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Vraagt een speler om iets in de chat te typen, zoals een prijs of een naam.
 * Het getypte bericht gaat niet naar de chat. "annuleer" of "cancel" stopt de vraag.
 */
public final class ChatInput implements Listener {

    private static final Set<String> CANCEL_WORDS = Set.of("annuleer", "annuleren", "cancel", "stop");
    private static final long TIMEOUT_TICKS = 20L * 60;

    private final PindaFramework plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    private record Prompt(Consumer<String> onInput, Runnable onCancel, BukkitTask timeout) {
    }

    public ChatInput(PindaFramework plugin) {
        this.plugin = plugin;
    }

    /**
     * Sluit het open menu en wacht op het volgende chatbericht van de speler.
     * Stuur zelf eerst een bericht met de vraag.
     *
     * @param onInput  krijgt de getypte tekst, op de hoofdthread
     * @param onCancel bij annuleren of na 60 seconden (mag null zijn)
     */
    public void ask(Player player, Consumer<String> onInput, Runnable onCancel) {
        UUID uuid = player.getUniqueId();
        Prompt old = prompts.remove(uuid);
        if (old != null) {
            old.timeout().cancel();
        }
        player.closeInventory();
        BukkitTask timeout = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Prompt expired = prompts.remove(uuid);
            if (expired != null && player.isOnline()) {
                plugin.lang().send(player, "general.input-timeout");
                if (expired.onCancel() != null) {
                    expired.onCancel().run();
                }
            }
        }, TIMEOUT_TICKS);
        prompts.put(uuid, new Prompt(onInput, onCancel, timeout));
        plugin.lang().send(player, "general.input-hint");
    }

    public boolean isWaiting(Player player) {
        return prompts.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            prompt.timeout().cancel();
            if (!player.isOnline()) {
                return;
            }
            if (CANCEL_WORDS.contains(text.toLowerCase(Locale.ROOT))) {
                plugin.lang().send(player, "general.input-cancelled");
                if (prompt.onCancel() != null) {
                    prompt.onCancel().run();
                }
                return;
            }
            prompt.onInput().accept(text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt != null) {
            prompt.timeout().cancel();
        }
    }
}
