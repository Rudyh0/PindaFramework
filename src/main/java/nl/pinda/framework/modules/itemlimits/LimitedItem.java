package nl.pinda.framework.modules.itemlimits;

import java.util.UUID;

/**
 * Een beperkt item met een nummer, zoals het paneel en /limieten het laten zien.
 *
 * @param location waar het voor het laatst gezien is (zie {@link ItemLimitModule#where})
 * @param giftFor  een cadeau van de server voor deze speler (of null)
 * @param gift     een cadeau dat bij de speler is voor wie het bedoeld was: telt voor hem niet mee
 */
public record LimitedItem(String serial, String rule, UUID owner, String ownerName, String location, long created, long updated,
                          UUID giftFor, boolean gift) {

    /** Een item dat niet meer bestaat (opgebruikt, kapot, vrijgegeven, ...). */
    public record Gone(String serial, String rule, String ownerName, String reason, long updated) {
    }
}
