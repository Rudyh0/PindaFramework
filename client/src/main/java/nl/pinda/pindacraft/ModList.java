package nl.pinda.pindacraft;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

/**
 * De modlijst zoals de server hem krijgt. Tekst, één regel per gegeven:
 *
 * <pre>
 * pindacraft 1              (formaat)
 * session 3f9a0c1d          (zelfde nummer in elk deel)
 * part 1 2                  (deel 1 van 2)
 * loader fabric 0.19.5
 * minecraft 26.2
 * client 1.0.0+26.2         (deze mod)
 * mod voicechat 2.6.1 Simple Voice Chat
 * prov fabric fabric-api    (fabric-api levert ook "fabric")
 * inc fabric-api-base 2.0.4 Fabric API Base   (zit ín een andere mod)
 * </pre>
 *
 * <p>Minecraft laat hooguit 32 KB per bericht toe; een grote lijst gaat daarom in delen.
 */
final class ModList {

	static final int FORMAT = 1;
	private static final int MAX_PART_BYTES = 30_000;
	private static final int MAX_PARTS = 16;

	private static List<String> lines;

	private ModList() {
	}

	/** De lijst, klaar om te versturen. */
	static List<Payloads.Report> parts() {
		List<String> body = lines();
		List<List<String>> chunks = new ArrayList<>();
		List<String> chunk = new ArrayList<>();
		int size = 0;
		for (String line : body) {
			int bytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
			if (!chunk.isEmpty() && size + bytes > MAX_PART_BYTES - 100) {
				chunks.add(chunk);
				chunk = new ArrayList<>();
				size = 0;
			}
			chunk.add(line);
			size += bytes;
		}
		chunks.add(chunk);
		if (chunks.size() > MAX_PARTS) {
			// Absurd veel mods: de mods zelf staan vooraan, de ingebouwde onderdelen vallen weg.
			chunks = chunks.subList(0, MAX_PARTS);
		}
		String session = Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
		List<Payloads.Report> parts = new ArrayList<>();
		for (int i = 0; i < chunks.size(); i++) {
			StringBuilder text = new StringBuilder()
					.append("pindacraft ").append(FORMAT).append('\n')
					.append("session ").append(session).append('\n')
					.append("part ").append(i + 1).append(' ').append(chunks.size()).append('\n');
			for (String line : chunks.get(i)) {
				text.append(line).append('\n');
			}
			parts.add(new Payloads.Report(text.toString().getBytes(StandardCharsets.UTF_8)));
		}
		return parts;
	}

	/** Mods veranderen niet terwijl het spel draait: één keer opbouwen is genoeg. */
	private static synchronized List<String> lines() {
		if (lines != null) {
			return lines;
		}
		FabricLoader loader = FabricLoader.getInstance();
		List<String> result = new ArrayList<>();
		result.add("loader " + (loader.isModLoaded("quilt_loader") ? "quilt" : "fabric") + " " + version(loader, "fabricloader"));
		result.add("minecraft " + version(loader, "minecraft"));
		result.add("client " + version(loader, "pindacraft"));

		List<ModContainer> all = new ArrayList<>(loader.getAllMods());
		all.sort(Comparator.comparing(mod -> mod.getMetadata().getId()));
		List<String> provided = new ArrayList<>();
		List<String> included = new ArrayList<>();
		for (ModContainer mod : all) {
			ModMetadata meta = mod.getMetadata();
			if ("builtin".equals(meta.getType())) {
				continue; // java, minecraft, fabricloader: staan hierboven al
			}
			String id = token(meta.getId());
			String line = id + " " + token(meta.getVersion().getFriendlyString()) + " " + name(meta.getName(), id);
			if (mod.getContainingMod().isPresent()) {
				included.add("inc " + line);
			} else {
				result.add("mod " + line);
			}
			for (String alias : meta.getProvides()) {
				provided.add("prov " + token(alias) + " " + id);
			}
		}
		result.addAll(provided);
		result.addAll(included);
		lines = List.copyOf(result);
		return lines;
	}

	private static String version(FabricLoader loader, String id) {
		return loader.getModContainer(id).map(mod -> token(mod.getMetadata().getVersion().getFriendlyString())).orElse("?");
	}

	/** Eén woord: geen spaties of regeleinden, hooguit 64 tekens. */
	private static String token(String value) {
		String clean = value == null ? "" : value.strip().replaceAll("[\\s\\p{Cntrl}]+", "_");
		if (clean.isEmpty()) {
			return "?";
		}
		return clean.length() > 64 ? clean.substring(0, 64) : clean;
	}

	private static String name(String value, String fallback) {
		String clean = value == null ? "" : value.replaceAll("\\p{Cntrl}+", " ").strip();
		if (clean.isEmpty()) {
			return fallback.toLowerCase(Locale.ROOT);
		}
		return clean.length() > 80 ? clean.substring(0, 80) : clean;
	}
}
