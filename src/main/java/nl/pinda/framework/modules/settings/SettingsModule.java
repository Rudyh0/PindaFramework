package nl.pinda.framework.modules.settings;

import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.module.PindaModule;

/**
 * Kernmodule: het /instellingen-menu, /taal en de setup bij de eerste keer joinen.
 * Staat altijd aan.
 */
public final class SettingsModule extends PindaModule {

    public SettingsModule(PindaFramework plugin) {
        super(plugin, "settings");
    }

    @Override
    public boolean isCore() {
        return true;
    }

    @Override
    protected void onEnable() {
        listen(new SetupListener(plugin, this));
        command(new SettingsCommand(plugin));
        command(new LanguageCommand(plugin));
    }

    public boolean setupEnabled() {
        return config().getBoolean("first-join-setup.enabled", true);
    }

    public long setupDelayTicks() {
        return Math.max(1L, config().getLong("first-join-setup.open-delay-ticks", 40L));
    }

    public boolean detectClientLanguage() {
        return config().getBoolean("first-join-setup.detect-client-language", true);
    }

    public String unknownClientLanguage() {
        return config().getString("first-join-setup.unknown-client-language", "en");
    }
}
