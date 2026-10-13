package nl.pinda.framework.modules.rules;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * De regels van de server, als boek: /regels (of via /instellingen) opent het. Het boek komt niet
 * in je inventory; doe je het dicht, dan krijg je een melding dat je de regels altijd opnieuw kunt
 * lezen.
 *
 * <p>Minecraft meldt niet wanneer iemand een boek dichtdoet. Daarom: de knop "Gelezen" onderaan
 * (die sluit het boek), en anders de eerste keer dat de speler weer iets doet (rondkijken, lopen,
 * typen, ...). Met een boek open kan dat niet.
 */
public final class RulesModule extends PindaModule implements Listener {

    public static final String USE = "pinda.rules.use";
    public static final String OTHERS = "pinda.rules.others";
    /** Het commando dat de knop "Gelezen" onderaan het boek uitvoert. */
    static final String READ_ARGUMENT = "gelezen";

    public static final int MAX_RULES = 60;
    public static final int MAX_TEXT = 1000;
    public static final int MAX_TITLE = 200;

    /** De regels zoals in rules.yml. */
    public record Content(String title, String intro, List<String> rules, String outro, String number) {

        public static Content read(YamlConfiguration config) {
            List<String> rules = new ArrayList<>();
            for (String rule : config.getStringList("rules")) {
                if (rule != null && !rule.isBlank() && rules.size() < MAX_RULES) {
                    rules.add(rule.strip());
                }
            }
            return new Content(clip(config.getString("title", ""), MAX_TITLE), clip(config.getString("intro", ""), MAX_TEXT * 2),
                    List.copyOf(rules), clip(config.getString("outro", ""), MAX_TEXT * 2),
                    clip(config.getString("number", "<bold><number>.</bold> "), 100));
        }

        private static String clip(String value, int max) {
            String text = value == null ? "" : value.strip();
            return text.length() > max ? text.substring(0, max) : text;
        }
    }

    /** Wie het boek open heeft (sinds wanneer, en waar hij toen keek). Alleen op de hoofdthread. */
    private record Reading(long since, float yaw, float pitch) {
    }

    private final Map<UUID, Reading> reading = new HashMap<>();
    private volatile Content content;

    public RulesModule(PindaFramework plugin) {
        super(plugin, "rules");
    }

    @Override
    protected void onEnable() {
        content = Content.read(config());
        listen(this);
        command(new RulesCommand(plugin, this));
    }

    @Override
    protected void onDisable() {
        reading.clear();
    }

    @Override
    protected void onReload() {
        content = Content.read(config());
    }

    public Content content() {
        return content;
    }

    // ============================================================ het boek

    /** De bladzijden van het boek (ook voor het voorbeeld in het paneel). Leeg als er niets in staat. */
    public List<BookLayout.Page> pages(Content content, String language, String playerName) {
        List<Component> blocks = new ArrayList<>();
        if (!content.title().isEmpty()) {
            blocks.add(parse(content.title(), playerName));
        }
        if (!content.intro().isEmpty()) {
            blocks.add(parse(content.intro(), playerName));
        }
        for (int index = 0; index < content.rules().size(); index++) {
            Component number = plugin.lang().parse(content.number(), Text.p("number", index + 1));
            blocks.add(Component.empty().append(number).append(parse(content.rules().get(index), playerName)));
        }
        if (!content.outro().isEmpty()) {
            blocks.add(parse(content.outro(), playerName));
        }
        if (content.rules().isEmpty() && content.intro().isEmpty() && content.outro().isEmpty()) {
            return List.of();
        }
        // De knop onderaan: sluit het boek (Minecraft doet een boek dicht als je een commando aanklikt).
        blocks.add(plugin.lang().component(language, "rules.read-button")
                .hoverEvent(HoverEvent.showText(plugin.lang().component(language, "rules.read-hover")))
                .clickEvent(ClickEvent.runCommand("/regels " + READ_ARGUMENT)));
        return BookLayout.paginate(blocks);
    }

    private Component parse(String raw, String playerName) {
        return plugin.lang().parse(raw.replace("\\n", "<newline>"), Text.p("player", playerName));
    }

    /** Opent het boek. False als er (nog) geen regels zijn. */
    public boolean open(Player player) {
        String language = plugin.lang().languageOf(player);
        List<BookLayout.Page> pages = pages(content, language, player.getName());
        if (pages.isEmpty()) {
            return false;
        }
        List<Component> components = new ArrayList<>();
        for (BookLayout.Page page : pages) {
            components.add(page.content());
        }
        String title = plugin.lang().raw(language, "rules.book-title");
        String author = PlainTextComponentSerializer.plainText().serialize(plugin.lang().parse("<server>"));
        player.openBook(Book.book(Component.text(title == null ? "Regels" : title), Component.text(author), components));
        Location location = player.getLocation();
        reading.put(player.getUniqueId(), new Reading(System.currentTimeMillis(), location.getYaw(), location.getPitch()));
        plugin.theme().play(player, "menu-open");
        return true;
    }

    /** Het boek is dicht: de melding dat de regels altijd weer op te vragen zijn. */
    void closed(Player player) {
        if (reading.remove(player.getUniqueId()) == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                plugin.lang().send(player, "rules.closed");
            }
        });
    }

    // ============================================================ merken dat het boek dicht is

    /** Net geopend: de eerste halve seconde telt nog niet (bijv. het menu dat net dichtging). */
    private boolean justOpened(Player player) {
        Reading entry = reading.get(player.getUniqueId());
        return entry == null || System.currentTimeMillis() - entry.since() < 500;
    }

    private void activity(Player player) {
        if (!reading.isEmpty() && reading.containsKey(player.getUniqueId()) && !justOpened(player)) {
            closed(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        if (reading.isEmpty()) {
            return;
        }
        Reading entry = reading.get(event.getPlayer().getUniqueId());
        if (entry == null) {
            return;
        }
        // Rondkijken kan alleen zonder boek (met een boek open beweegt de muis de cursor).
        Location to = event.getTo();
        if (Math.abs(to.getYaw() - entry.yaw()) > 0.5f || Math.abs(to.getPitch() - entry.pitch()) > 0.5f) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHeld(PlayerItemHeldEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventory(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            activity(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // "/regels gelezen" (de knop) regelt het zelf
        if (!event.getMessage().toLowerCase(java.util.Locale.ROOT).endsWith(" " + READ_ARGUMENT)) {
            activity(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> activity(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        reading.remove(event.getPlayer().getUniqueId());
    }
}
