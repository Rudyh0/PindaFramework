package nl.pinda.pindacraft;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * De twee berichten tussen deze mod en de server (de PindaFramework-plugin).
 *
 * <p>De inhoud is gewone tekst (UTF-8) zonder lengte ervoor, zodat een Paper-plugin hem met
 * {@code new String(bytes, UTF_8)} kan lezen. Hetzelfde kanaal werkt tijdens het configureren
 * (vóór je de wereld in gaat) en tijdens het spelen.
 */
final class Payloads {

	static final String NAMESPACE = "pindacraft";

	private Payloads() {
	}

	/** Server → client: "stuur je modlijst". De inhoud doet (nog) niet ter zake. */
	record Request(byte[] data) implements CustomPacketPayload {
		static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "request"));
		static final StreamCodec<FriendlyByteBuf, Request> CODEC = CustomPacketPayload.codec(Request::write, Request::new);

		Request(FriendlyByteBuf buf) {
			this(rest(buf));
		}

		void write(FriendlyByteBuf buf) {
			buf.writeBytes(data);
		}

		@Override
		public Type<Request> type() {
			return TYPE;
		}
	}

	/** Client → server: (een deel van) de modlijst, zie {@link ModList}. */
	record Report(byte[] data) implements CustomPacketPayload {
		static final Type<Report> TYPE = new Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, "mods"));
		static final StreamCodec<FriendlyByteBuf, Report> CODEC = CustomPacketPayload.codec(Report::write, Report::new);

		Report(FriendlyByteBuf buf) {
			this(rest(buf));
		}

		void write(FriendlyByteBuf buf) {
			buf.writeBytes(data);
		}

		@Override
		public Type<Report> type() {
			return TYPE;
		}
	}

	private static byte[] rest(FriendlyByteBuf buf) {
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		return bytes;
	}
}
