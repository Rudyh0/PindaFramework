package nl.pinda.framework.modules.panel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * De instellingen-editor: alle configbestanden als formulier, met de uitleg uit de bestanden.
 * Na opslaan wordt de module meteen herladen.
 */
final class SettingsApi extends PanelApi {

    private static final Pattern MAP_KEY = Pattern.compile("[A-Z0-9_]+");

    private final ConfigEditor editor;

    SettingsApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/settings", PanelUser.CONFIG, this::files);
        server.get("/api/settings/file", PanelUser.CONFIG, this::file);
        server.post("/api/settings/file", PanelUser.CONFIG, this::save);
    }

    private Object files(PanelRequest request) throws Exception {
        List<Map<String, Object>> files = new ArrayList<>();
        for (String path : editor.settingsFiles()) {
            if (path.equals("modules/ranks.yml") || (path.equals("modules/panel.yml") && !request.user().operator())) {
                continue; // Rangen hebben een eigen pagina; het paneel zelf alleen voor operators
            }
            PindaModule owner = editor.moduleFor(path);
            files.add(map("path", path,
                    "module", owner == null ? null : owner.id(),
                    "enabled", owner == null || owner.isEnabled(),
                    "description", clean(editor.read(path).options().getHeader())));
        }
        return map("files", files);
    }

    /**
     * ranks.yml heeft een eigen pagina met controles (Rangen); panel.yml (o.a. 2FA) mag alleen een operator aanpassen.
     */
    private static void guard(String path, PanelUser user) throws ApiException {
        if ("modules/ranks.yml".equals(path)) {
            throw ApiException.forbidden("Rangen pas je aan op de pagina Rangen.");
        }
        if ("modules/panel.yml".equals(path) && !user.operator()) {
            throw ApiException.forbidden("De instellingen van het webpaneel kan alleen een operator aanpassen.");
        }
    }

    private Object file(PanelRequest request) throws Exception {
        String path = request.query("path");
        guard(path, request.user());
        YamlConfiguration yaml = editor.read(path);
        return map("path", path, "description", clean(yaml.options().getHeader()), "fields", fields(yaml, yaml, ""));
    }

    // ============================================================ bestand -> formulier

    private List<Map<String, Object>> fields(YamlConfiguration root, ConfigurationSection section, String prefix) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Map<String, Object> field = map("key", key, "path", path,
                    "help", clean(root.getComments(path)), "note", clean(root.getInlineComments(path)));
            Object value = section.get(key);
            if (value instanceof ConfigurationSection child) {
                if (isNumberMap(child)) {
                    Map<String, Object> entries = new LinkedHashMap<>();
                    for (String entry : child.getKeys(false)) {
                        entries.put(entry, child.get(entry));
                    }
                    field.put("type", "map");
                    field.put("value", entries);
                } else {
                    field.put("type", "section");
                    field.put("fields", fields(root, child, path));
                }
            } else if (value instanceof Boolean) {
                field.put("type", "boolean");
                field.put("value", value);
            } else if (value instanceof Integer || value instanceof Long) {
                field.put("type", "integer");
                field.put("value", value);
            } else if (value instanceof Number) {
                field.put("type", "number");
                field.put("value", value);
            } else if (value instanceof List<?> list) {
                boolean numbers = !list.isEmpty() && list.stream().allMatch(item -> item instanceof Number);
                List<String> items = new ArrayList<>();
                for (Object item : list) {
                    items.add(String.valueOf(item));
                }
                field.put("type", numbers ? "numbers" : "list");
                field.put("value", items);
            } else {
                field.put("type", "string");
                field.put("value", value == null ? "" : String.valueOf(value));
            }
            fields.add(field);
        }
        return fields;
    }

    /** Een lijst als "STONE: 1, DIAMOND_ORE: 40" of "50: 1000": daar mag je regels aan toevoegen en weghalen. */
    private static boolean isNumberMap(ConfigurationSection section) {
        boolean any = false;
        for (String key : section.getKeys(false)) {
            if (!MAP_KEY.matcher(key).matches() || !(section.get(key) instanceof Number)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** Commentaarregels zonder lege regels aan het begin/eind en zonder de kaderlijntjes. */
    private static List<String> clean(List<String> lines) {
        List<String> result = new ArrayList<>();
        if (lines == null) {
            return result;
        }
        for (String line : lines) {
            if (line == null) {
                if (!result.isEmpty()) {
                    result.add("");
                }
                continue;
            }
            String text = line.strip();
            if (text.startsWith("┌") || text.startsWith("│") || text.startsWith("└")) {
                continue;
            }
            if (text.isEmpty() && result.isEmpty()) {
                continue;
            }
            result.add(text);
        }
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    // ============================================================ formulier -> bestand

    private Object save(PanelRequest request) throws Exception {
        String path = request.string("path", "Geen bestand gekozen.");
        guard(path, request.user());
        JsonElement valuesElement = request.body().get("values");
        if (valuesElement == null || !valuesElement.isJsonObject()) {
            throw ApiException.badRequest("Geen wijzigingen meegestuurd.");
        }
        JsonObject values = valuesElement.getAsJsonObject();
        YamlConfiguration yaml = editor.read(path);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            apply(yaml, entry.getKey(), entry.getValue());
            changed.add(entry.getKey());
        }
        if (changed.isEmpty()) {
            throw ApiException.badRequest("Er is niets veranderd.");
        }
        editor.write(path, yaml);
        sync(() -> {
            editor.reload(path);
            return null;
        });
        module.log().add(request, "instellingen", path, String.join(", ", changed));
        YamlConfiguration fresh = editor.read(path);
        return map("path", path, "description", clean(fresh.options().getHeader()), "fields", fields(fresh, fresh, ""));
    }

    private static void apply(YamlConfiguration yaml, String path, JsonElement value) throws ApiException {
        if (!yaml.contains(path, true)) {
            throw ApiException.badRequest("Onbekende instelling: " + path);
        }
        Object current = yaml.get(path);
        String label = "'" + path + "'";
        if (current instanceof ConfigurationSection section) {
            if (!isNumberMap(section) || !value.isJsonObject()) {
                throw ApiException.badRequest(label + " kan zo niet aangepast worden.");
            }
            JsonObject entries = value.getAsJsonObject();
            for (String key : section.getKeys(false)) {
                if (!entries.has(key)) {
                    section.set(key, null);
                }
            }
            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                String key = entry.getKey().trim().toUpperCase(java.util.Locale.ROOT);
                if (!MAP_KEY.matcher(key).matches()) {
                    throw ApiException.badRequest("'" + entry.getKey() + "' is geen geldige naam in " + label + ".");
                }
                section.set(key, number(entry.getValue(), label));
            }
            return;
        }
        if (current instanceof Boolean) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                throw ApiException.badRequest(label + " moet aan of uit zijn.");
            }
            yaml.set(path, value.getAsBoolean());
        } else if (current instanceof Number) {
            Number number = number(value, label);
            if ((current instanceof Integer || current instanceof Long) && number.doubleValue() == Math.rint(number.doubleValue())) {
                long whole = number.longValue();
                yaml.set(path, whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE ? (Object) (int) whole : (Object) whole);
            } else {
                yaml.set(path, number.doubleValue());
            }
        } else if (current instanceof List<?>) {
            if (!value.isJsonArray()) {
                throw ApiException.badRequest(label + " moet een lijst zijn.");
            }
            JsonArray array = value.getAsJsonArray();
            boolean numbers = array.size() > 0;
            for (JsonElement item : array) {
                if (!item.isJsonPrimitive() || !isNumeric(item.getAsString())) {
                    numbers = false;
                    break;
                }
            }
            List<Object> list = new ArrayList<>();
            for (JsonElement item : array) {
                if (item.isJsonNull()) {
                    continue;
                }
                list.add(numbers ? number(item, label) : item.getAsString());
            }
            yaml.set(path, list);
        } else {
            if (!value.isJsonPrimitive()) {
                throw ApiException.badRequest(label + " moet tekst zijn.");
            }
            yaml.set(path, value.getAsString());
        }
    }

    private static boolean isNumeric(String text) {
        return text.trim().matches("-?\\d+(\\.\\d+)?");
    }

    private static Number number(JsonElement value, String label) throws ApiException {
        if (value == null || !value.isJsonPrimitive()) {
            throw ApiException.badRequest(label + " moet een getal zijn.");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        try {
            double number = primitive.isNumber() ? primitive.getAsDouble() : Double.parseDouble(primitive.getAsString().trim().replace(',', '.'));
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new NumberFormatException();
            }
            if (number == Math.rint(number) && Math.abs(number) < Integer.MAX_VALUE) {
                return (int) number;
            }
            return number;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(label + " moet een getal zijn.");
        }
    }
}
