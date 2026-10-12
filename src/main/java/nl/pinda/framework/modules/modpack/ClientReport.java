package nl.pinda.framework.modules.modpack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Wat de PindaCraft-mod van een speler meldt: de loader, de Minecraft-versie en alle mods.
 *
 * <p>Alles hierin komt van de client en is dus niet te vertrouwen (een aangepaste client kan
 * liegen). Het is bedoeld om spelers aan de juiste mods te helpen, niet als anti-cheat.
 *
 * @param mods     de mods; {@code included} = zit ín een andere mod (bijv. de onderdelen van Fabric API)
 * @param provides extra ID's die een mod ook levert (alias → mod), bijv. "fabric" → "fabric-api"
 */
public record ClientReport(String loader, String loaderVersion, String minecraft, String client,
                           List<Mod> mods, Map<String, String> provides, long time) {

    public record Mod(String id, String version, String name, boolean included) {
    }

    /** Een mod-ID zoals Fabric ze toestaat (en wat in een YAML-sleutel past: geen punten). */
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    static final int MAX_MODS = 4000;
    private static final int MAX_TEXT = 80;
    private static final int MAX_TOKEN = 64;

    public ClientReport {
        mods = List.copyOf(mods);
        provides = Collections.unmodifiableMap(new LinkedHashMap<>(provides));
    }

    /** Heeft de speler deze mod (los, ingebouwd in een andere mod, of geleverd door een andere)? */
    public boolean has(String id) {
        if (provides.containsKey(id)) {
            return true;
        }
        for (Mod mod : mods) {
            if (mod.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Alle ID's die meetellen voor {@link #has(String)}. */
    public Set<String> ids() {
        Set<String> ids = new HashSet<>(provides.keySet());
        for (Mod mod : mods) {
            ids.add(mod.id());
        }
        return ids;
    }

    /** De mods die de speler zelf heeft toegevoegd (niet de ingebouwde onderdelen). */
    public List<Mod> topLevel() {
        return mods.stream().filter(mod -> !mod.included()).toList();
    }

    public Mod mod(String id) {
        for (Mod mod : mods) {
            if (mod.id().equals(id)) {
                return mod;
            }
        }
        String provider = provides.get(id);
        return provider == null ? null : mod(provider);
    }

    // ============================================================ tekstvorm

    /** De opslagvorm in de database: dezelfde regels als de mod stuurt, zonder de kop. */
    public String serialize() {
        StringBuilder text = new StringBuilder();
        text.append("loader ").append(loader).append(' ').append(loaderVersion).append('\n');
        text.append("minecraft ").append(minecraft).append('\n');
        text.append("client ").append(client).append('\n');
        for (Mod mod : mods) {
            text.append(mod.included() ? "inc " : "mod ").append(mod.id()).append(' ').append(mod.version()).append(' ').append(mod.name()).append('\n');
        }
        provides.forEach((alias, provider) -> text.append("prov ").append(alias).append(' ').append(provider).append('\n'));
        return text.toString();
    }

    /** Leest de regels (van de mod of uit de database). Onbekende regels worden overgeslagen. */
    public static ClientReport parse(Iterable<String> lines, long time) {
        String loader = "?";
        String loaderVersion = "?";
        String minecraft = "?";
        String client = "?";
        Map<String, Mod> mods = new LinkedHashMap<>();
        Map<String, String> provides = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String kind = line.substring(0, space);
            String rest = line.substring(space + 1);
            switch (kind) {
                case "loader" -> {
                    String[] parts = rest.split(" ", 3);
                    loader = token(parts[0]);
                    loaderVersion = parts.length > 1 ? token(parts[1]) : "?";
                }
                case "minecraft" -> minecraft = token(rest.split(" ", 2)[0]);
                case "client" -> client = token(rest.split(" ", 2)[0]);
                case "mod", "inc" -> {
                    String[] parts = rest.split(" ", 3);
                    String id = id(parts[0]);
                    if (id == null || mods.size() >= MAX_MODS) {
                        continue;
                    }
                    String version = parts.length > 1 ? token(parts[1]) : "?";
                    String name = parts.length > 2 ? text(parts[2]) : "";
                    Mod mod = new Mod(id, version, name.isEmpty() ? id : name, kind.equals("inc"));
                    Mod existing = mods.get(id);
                    if (existing == null || existing.included() && !mod.included()) {
                        mods.put(id, mod);
                    }
                }
                case "prov" -> {
                    String[] parts = rest.split(" ", 3);
                    String alias = id(parts[0]);
                    String provider = parts.length > 1 ? id(parts[1]) : null;
                    if (alias != null && provider != null && provides.size() < MAX_MODS) {
                        provides.putIfAbsent(alias, provider);
                    }
                }
                default -> {
                    // Iets van een nieuwere versie van de mod: overslaan.
                }
            }
        }
        return new ClientReport(loader, loaderVersion, minecraft, client, new ArrayList<>(mods.values()), provides, time);
    }

    static String id(String value) {
        String id = value.strip().toLowerCase(Locale.ROOT);
        return ID.matcher(id).matches() ? id : null;
    }

    private static String token(String value) {
        String clean = value.strip().replaceAll("[\\s\\p{Cntrl}<>]+", "_");
        if (clean.isEmpty()) {
            return "?";
        }
        return clean.length() > MAX_TOKEN ? clean.substring(0, MAX_TOKEN) : clean;
    }

    private static String text(String value) {
        String clean = value.replaceAll("\\p{Cntrl}+", " ").strip();
        return clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean;
    }

    // ============================================================ in delen ontvangen

    /**
     * Zet de berichten van de mod weer in elkaar. Een grote lijst komt in delen (Minecraft
     * laat hooguit 32 KB per bericht toe); pas als alle delen er zijn, is het verslag compleet.
     * Veilig vanaf elke thread.
     */
    static final class Assembler {

        private static final int MAX_PARTS = 16;
        private static final int MAX_BYTES = 32_767;

        private record Pending(String session, String[] parts) {
        }

        private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

        /** Het complete verslag als dit het laatste deel was; anders (of bij onzin) null. */
        ClientReport accept(UUID uuid, byte[] message, long now) {
            if (message.length == 0 || message.length > MAX_BYTES) {
                return null;
            }
            String[] lines = new String(message, StandardCharsets.UTF_8).split("\n");
            if (!lines[0].startsWith("pindacraft ")) {
                return null;
            }
            try {
                if (Integer.parseInt(lines[0].substring("pindacraft ".length()).strip()) < 1) {
                    return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
            String session = "";
            int part = 1;
            int total = 1;
            StringBuilder body = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("session ")) {
                    session = line.substring("session ".length()).strip();
                } else if (line.startsWith("part ")) {
                    String[] numbers = line.substring("part ".length()).strip().split(" ");
                    try {
                        part = Integer.parseInt(numbers[0]);
                        total = numbers.length > 1 ? Integer.parseInt(numbers[1]) : 1;
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    body.append(line).append('\n');
                }
            }
            if (total < 1 || total > MAX_PARTS || part < 1 || part > total || session.length() > 32) {
                return null;
            }
            if (total == 1) {
                pending.remove(uuid);
                return parse(List.of(body.toString().split("\n")), now);
            }
            String sessionId = session;
            int index = part - 1;
            int count = total;
            String[] done = new String[1];
            pending.compute(uuid, (key, current) -> {
                Pending entry = current != null && current.session().equals(sessionId) && current.parts().length == count
                        ? current : new Pending(sessionId, new String[count]);
                entry.parts()[index] = body.toString();
                for (String piece : entry.parts()) {
                    if (piece == null) {
                        return entry;
                    }
                }
                done[0] = String.join("", entry.parts());
                return null;
            });
            return done[0] == null ? null : parse(List.of(done[0].split("\n")), now);
        }

        void forget(UUID uuid) {
            pending.remove(uuid);
        }
    }
}
