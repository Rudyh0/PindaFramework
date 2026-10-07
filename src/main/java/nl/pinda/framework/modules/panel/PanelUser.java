package nl.pinda.framework.modules.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Wie er is ingelogd op het paneel en wat hij mag. Wordt bij elk verzoek (kort gecachet)
 * opnieuw bepaald, zodat een andere rang meteen effect heeft.
 *
 * @param operator    mag alles (rang met operator: true)
 * @param permissions alle permissies van de rang, inclusief geërfde
 */
record PanelUser(UUID uuid, String name, String rankId, String rankName, String rankColor, int weight,
                 boolean operator, Map<String, Boolean> permissions) {

    static final String USE = "pinda.panel.use";
    static final String PLAYERS = "pinda.panel.players";
    static final String MODERATE = "pinda.panel.moderate";
    static final String ECONOMY_VIEW = "pinda.panel.economy.view";
    static final String ECONOMY_EDIT = "pinda.panel.economy.edit";
    static final String RANKS = "pinda.panel.ranks";
    static final String SHOPS = "pinda.panel.shops";
    static final String SHOPS_MANAGE = "pinda.panel.shops.manage";
    static final String SERVER = "pinda.panel.server";
    static final String STOP = "pinda.panel.stop";
    static final String CONSOLE = "pinda.panel.console";
    static final String LOG = "pinda.panel.log";

    /** Alle permissies die het paneel kent; de website laat alleen zien wat je mag. */
    static final List<String> NODES = List.of(USE, PLAYERS, MODERATE, ECONOMY_VIEW, ECONOMY_EDIT, RANKS,
            SHOPS, SHOPS_MANAGE, SERVER, STOP, CONSOLE, LOG, "pinda.mod.ban.permanent");

    boolean has(String node) {
        if (operator) {
            return true;
        }
        String current = node.toLowerCase(Locale.ROOT);
        Boolean value = permissions.get(current);
        if (value != null) {
            return value;
        }
        int dot;
        while ((dot = current.lastIndexOf('.')) > 0) {
            current = current.substring(0, dot);
            Boolean wildcard = permissions.get(current + ".*");
            if (wildcard != null) {
                return wildcard;
            }
        }
        return Boolean.TRUE.equals(permissions.get("*"));
    }

    /** De paneelpermissies die deze gebruiker heeft. */
    List<String> granted() {
        List<String> granted = new ArrayList<>();
        for (String node : NODES) {
            if (has(node)) {
                granted.add(node);
            }
        }
        return granted;
    }
}
