package nl.pinda.framework.player;

import org.bukkit.Material;

/**
 * Een aan/uit-instelling die spelers zelf kunnen wijzigen in /instellingen.
 *
 * <p>Modules registreren hun eigen instellingen. De teksten staan in de taalbestanden onder
 * {@code settings.toggles.<id>.name} en {@code settings.toggles.<id>.description}.
 *
 * @param id           unieke naam, bijv. "tips"
 * @param defaultValue de waarde voor spelers die niets gekozen hebben
 * @param icon         het item in het menu
 * @param showInSetup  ook tonen in het setupmenu bij de eerste keer joinen
 */
public record PlayerSetting(String id, boolean defaultValue, Material icon, boolean showInSetup) {
}
