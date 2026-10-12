package nl.pinda.pindacraft;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundConfigurationChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PindaCraft: vertelt de server welke mods je hebt, zodat hij kan controleren of je met de
 * modpack speelt (bijv. Simple Voice Chat).
 *
 * <p>De lijst gaat alleen naar servers die erom vragen: als de server een verzoek stuurt, of
 * aangeeft dat hij het kanaal {@code pindacraft:mods} ontvangt. Andere servers krijgen niets.
 */
public final class PindaCraftClient implements ClientModInitializer {

	static final Logger LOGGER = LoggerFactory.getLogger("PindaCraft");

	/** Verbindingen waar de lijst uit onszelf al heen ging (niet nog eens bij een tweede aanmelding). */
	private static final Set<Object> ANNOUNCED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.clientboundConfiguration().register(Payloads.Request.TYPE, Payloads.Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Payloads.Request.TYPE, Payloads.Request.CODEC);
		PayloadTypeRegistry.serverboundConfiguration().register(Payloads.Report.TYPE, Payloads.Report.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Payloads.Report.TYPE, Payloads.Report.CODEC);

		// De server vraagt erom: altijd antwoorden.
		ClientConfigurationNetworking.registerGlobalReceiver(Payloads.Request.TYPE, (payload, context) -> send(context.responseSender()));
		ClientPlayNetworking.registerGlobalReceiver(Payloads.Request.TYPE, (payload, context) -> send(context.responseSender()));

		// De server meldt dat hij de lijst wil ontvangen (Paper doet dat bij het joinen): één keer uit onszelf sturen.
		ServerboundConfigurationChannelEvents.REGISTER.register((listener, sender, client, channels) -> {
			if (channels.contains(Payloads.Report.TYPE.id()) && ANNOUNCED.add(listener)) {
				send(sender);
			}
		});
		ServerboundPlayChannelEvents.REGISTER.register((listener, sender, client, channels) -> {
			if (channels.contains(Payloads.Report.TYPE.id()) && ANNOUNCED.add(listener)) {
				send(sender);
			}
		});
	}

	private static void send(PacketSender sender) {
		try {
			List<Payloads.Report> parts = ModList.parts();
			for (Payloads.Report part : parts) {
				sender.sendPacket(part);
			}
			LOGGER.debug("Modlijst naar de server gestuurd ({} delen)", parts.size());
		} catch (RuntimeException e) {
			LOGGER.warn("Kon de modlijst niet naar de server sturen", e);
		}
	}
}
