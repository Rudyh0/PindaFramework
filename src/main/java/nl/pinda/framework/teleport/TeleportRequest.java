package nl.pinda.framework.teleport;

import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Een teleport die door de {@link TeleportService} wordt uitgevoerd.
 *
 * @param traveler    de speler die verplaatst wordt
 * @param payer       de speler die eventuele kosten betaalt (bij /tpahere de aanvrager)
 * @param type        soort teleport, voor kosten
 * @param destination de bestemming; wordt pas na de wachttijd opgevraagd (handig als die beweegt)
 * @param safe        zoek een veilige plek als de bestemming gevaarlijk is
 * @param onSuccess   wordt uitgevoerd na een geslaagde teleport (mag null zijn)
 */
public record TeleportRequest(Player traveler, Player payer, TeleportType type,
                              Supplier<Location> destination, boolean safe, Runnable onSuccess) {
}
