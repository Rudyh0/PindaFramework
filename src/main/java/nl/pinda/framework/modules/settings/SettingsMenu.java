package nl.pinda.framework.modules.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Language;
import nl.pinda.framework.lang.LanguageManager;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.menu.ItemBuilder;
import nl.pinda.framework.menu.Menu;
import nl.pinda.framework.menu.MenuClick;
import nl.pinda.framework.player.PindaPlayer;
import nl.pinda.framework.player.PlayerSetting;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;

/**
 * Het instellingenmenu. In SETUP-modus is het het welkomstmenu bij de eerste keer joinen.
 *
 * <pre>
 * rij 0: [hoofd van de speler]
 * rij 1: talen
 * rij 2: -
 * rij 3: instellingen (icoon)
 * rij 4: instellingen (aan/uit)
 * rij 5: [klaar / sluiten]
 * </pre>
 */
public final class SettingsMenu extends Menu {

    public enum Mode { SETUP, NORMAL }

    private static final int HEAD_SLOT = 4;
    private static final int LANGUAGE_ROW = 1;
    private static final int SETTINGS_ROW = 3;
    private static final int BUTTON_SLOT = 49;
    private static final int MAX_SETTINGS = 7;

    private final Mode mode;
    private boolean finished;

    public SettingsMenu(PindaFramework plugin, Player viewer, Mode mode) {
        super(plugin, viewer, 6, plugin.lang().component(viewer,
                mode == Mode.SETUP ? "settings.menu.title-setup" : "settings.menu.title"));
        this.mode = mode;
    }

    @Override
    protected void render() {
        LanguageManager lang = plugin.lang();
        String code = lang.languageOf(viewer);
        TagResolver player = Text.p("player", viewer.getName());

        // Hoofd van de speler met uitleg
        set(HEAD_SLOT, ItemBuilder.of(Material.PLAYER_HEAD)
                .head(viewer)
                .name(lang.component(code, "settings.menu.head.name", player))
                .lore(lang.components(code, mode == Mode.SETUP ? "settings.menu.head.lore-setup" : "settings.menu.head.lore", player))
                .build());

        renderLanguages(lang, code);
        renderSettings(lang, code);

        // Knop onderaan
        if (mode == Mode.SETUP) {
            set(BUTTON_SLOT, ItemBuilder.of(Material.LIME_CONCRETE)
                    .name(lang.component(code, "settings.menu.confirm.name"))
                    .lore(lang.components(code, "settings.menu.confirm.lore"))
                    .build(), click -> finish());
        } else {
            set(BUTTON_SLOT, ItemBuilder.of(Material.BARRIER)
                    .name(lang.component(code, "settings.menu.close.name"))
                    .lore(lang.components(code, "settings.menu.close.lore"))
                    .build(), click -> {
                plugin.theme().play(viewer, "click");
                closeLater();
            });
        }

        fillEmpty(ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build());
    }

    private void renderLanguages(LanguageManager lang, String current) {
        List<Language> languages = new ArrayList<>(lang.languages());
        int[] slots = centered(LANGUAGE_ROW, languages.size());
        for (int i = 0; i < slots.length; i++) {
            Language language = languages.get(i);
            boolean selected = language.code().equals(current);
            TagResolver name = Text.p("language", language.name());
            set(slots[i], ItemBuilder.of(language.icon())
                    .name(lang.component(current, "settings.menu.language.name", name))
                    .lore(lang.components(current, selected
                            ? "settings.menu.language.lore-selected"
                            : "settings.menu.language.lore-select", name))
                    .glint(selected)
                    .build(), click -> selectLanguage(language));
        }
    }

    private void renderSettings(LanguageManager lang, String code) {
        List<PlayerSetting> settings = plugin.settings().all().stream()
                .filter(setting -> mode == Mode.NORMAL || setting.showInSetup())
                .limit(MAX_SETTINGS)
                .toList();
        int[] slots = centered(SETTINGS_ROW, settings.size());
        for (int i = 0; i < slots.length; i++) {
            PlayerSetting setting = settings.get(i);
            boolean on = plugin.settings().isEnabled(viewer, setting.id());
            Consumer<MenuClick> toggle = click -> toggle(setting);
            String base = "settings.toggles." + setting.id();

            Component status = lang.component(code, on ? "general.enabled" : "general.disabled");
            List<Component> lore = new ArrayList<>(lang.components(code, base + ".description"));
            lore.add(Component.empty());
            lore.add(lang.component(code, "settings.menu.toggle.status", Text.c("status", status)));
            lore.add(lang.component(code, "settings.menu.toggle.click"));

            set(slots[i], ItemBuilder.of(setting.icon())
                    .name(lang.component(code, base + ".name"))
                    .lore(lore)
                    .glint(on)
                    .build(), toggle);

            set(slots[i] + 9, ItemBuilder.of(on ? Material.LIME_DYE : Material.GRAY_DYE)
                    .name(lang.component(code, on ? "settings.menu.toggle.indicator-on" : "settings.menu.toggle.indicator-off"))
                    .lore(lang.component(code, "settings.menu.toggle.click"))
                    .build(), toggle);
        }
    }

    private void selectLanguage(Language language) {
        PindaPlayer data = plugin.players().get(viewer);
        if (language.code().equals(data.language())) {
            return;
        }
        data.language(language.code());
        plugin.players().save(data);
        plugin.theme().play(viewer, "click");
        // Opnieuw openen, zodat ook de titel van het menu in de nieuwe taal staat.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                new SettingsMenu(plugin, viewer, mode).open();
            }
        });
    }

    private void toggle(PlayerSetting setting) {
        plugin.settings().toggle(viewer, setting.id());
        plugin.theme().play(viewer, "click");
        refresh();
    }

    private void finish() {
        finished = true;
        completeSetup();
        closeLater();
        plugin.lang().sendTitle(viewer, "settings.setup.done-title", "settings.setup.done-subtitle");
        plugin.lang().send(viewer, "settings.setup.done");
        plugin.theme().play(viewer, "success");
    }

    @Override
    protected void onClose(InventoryCloseEvent.Reason reason) {
        if (mode != Mode.SETUP || finished || !plugin.isEnabled()) {
            return;
        }
        // Wisselen van taal opent een nieuw menu; uitloggen laat de setup bij de volgende join terugkomen.
        if (reason == InventoryCloseEvent.Reason.OPEN_NEW || reason == InventoryCloseEvent.Reason.DISCONNECT) {
            return;
        }
        finished = true;
        completeSetup();
        plugin.lang().send(viewer, "settings.setup.skipped");
    }

    private void completeSetup() {
        PindaPlayer data = plugin.players().get(viewer);
        if (data.language() == null) {
            data.language(plugin.lang().languageOf(viewer));
        }
        data.setupCompleted(true);
        plugin.players().save(data);
    }
}
