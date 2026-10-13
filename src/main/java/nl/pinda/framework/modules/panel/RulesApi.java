package nl.pinda.framework.modules.panel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import nl.pinda.framework.modules.rules.RulesModule;
import nl.pinda.framework.modules.rules.RulesModule.Content;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * De regels in het paneel: titel, inleiding, de regels zelf en een afsluiting, met een voorbeeld
 * van het boek zoals spelers het zien (dezelfde bladzijden als in het spel).
 */
final class RulesApi extends PanelApi {

    private static final String FILE = "modules/rules.yml";

    private final ConfigEditor editor;

    RulesApi(PanelModule module) {
        super(module);
        this.editor = new ConfigEditor(plugin);
    }

    @Override
    void register(PanelServer server) {
        server.get("/api/rules", PanelUser.CONFIG, this::overview);
        server.post("/api/rules/preview", PanelUser.CONFIG, this::preview);
        server.post("/api/rules", PanelUser.CONFIG, this::save);
    }

    private RulesModule rules() throws ApiException {
        return require(RulesModule.class, "rules");
    }

    private Object overview(PanelRequest request) throws Exception {
        RulesModule rules = rules();
        Content content = rules.content();
        return map("content", map("title", content.title(), "intro", content.intro(), "rules", content.rules(),
                        "outro", content.outro(), "number", content.number()),
                "pages", pages(rules, content, request.user().name()),
                "online", sync(() -> plugin.getServer().getOnlinePlayers().size()));
    }

    private Object preview(PanelRequest request) throws Exception {
        RulesModule rules = rules();
        return map("pages", pages(rules, content(request.body()), request.user().name()));
    }

    /** Elke bladzijde als regels HTML (zoals het boek ze afbreekt). */
    private List<Map<String, Object>> pages(RulesModule rules, Content content, String viewer) throws Exception {
        String language = plugin.lang().defaultLanguage();
        return sync(() -> {
            List<Map<String, Object>> pages = new ArrayList<>();
            rules.pages(content, language, viewer).forEach(page -> {
                List<String> lines = new ArrayList<>();
                for (Component line : page.lines()) {
                    lines.add(ComponentHtml.render(line));
                }
                pages.add(map("lines", lines));
            });
            return pages;
        });
    }

    /** De inhoud uit het verzoek, gecontroleerd. */
    private static Content content(JsonObject body) throws ApiException {
        String title = text(body, "title", RulesModule.MAX_TITLE, "De titel");
        String intro = text(body, "intro", RulesModule.MAX_TEXT * 2, "De inleiding");
        String outro = text(body, "outro", RulesModule.MAX_TEXT * 2, "De afsluiting");
        String number = text(body, "number", 100, "De opmaak van het nummer");
        if (number.isEmpty()) {
            number = "<bold><number>.</bold> ";
        } else if (!number.contains("<number>")) {
            throw ApiException.badRequest("Zet <number> in de opmaak van het nummer, bijv. <bold><number>.</bold>");
        } else if (!number.endsWith(" ")) {
            number = number + " ";
        }
        List<String> rules = new ArrayList<>();
        JsonElement list = body.get("rules");
        if (list != null && !list.isJsonNull()) {
            if (!list.isJsonArray()) {
                throw ApiException.badRequest("Ongeldige lijst met regels.");
            }
            for (JsonElement element : list.getAsJsonArray()) {
                if (!element.isJsonPrimitive()) {
                    throw ApiException.badRequest("Ongeldige regel.");
                }
                String rule = clean(element.getAsString());
                if (rule.isEmpty()) {
                    continue;
                }
                if (rule.length() > RulesModule.MAX_TEXT) {
                    throw ApiException.badRequest("Regel " + (rules.size() + 1) + " is te lang (hooguit " + RulesModule.MAX_TEXT + " tekens).");
                }
                if (rules.size() >= RulesModule.MAX_RULES) {
                    throw ApiException.badRequest("Hooguit " + RulesModule.MAX_RULES + " regels.");
                }
                rules.add(rule);
            }
        }
        return new Content(title, intro, List.copyOf(rules), outro, number);
    }

    private static String text(JsonObject body, String key, int max, String what) throws ApiException {
        JsonElement element = body.get(key);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (!element.isJsonPrimitive()) {
            throw ApiException.badRequest(what + " is ongeldig.");
        }
        String value = clean(element.getAsString());
        if (value.length() > max) {
            throw ApiException.badRequest(what + " is te lang (hooguit " + max + " tekens).");
        }
        return value;
    }

    /** Enters uit een tekstvak worden &lt;newline&gt;; geen andere stuurtekens. */
    private static String clean(String value) {
        return value.replace("\r\n", "\n").replace("\n", "<newline>").replaceAll("\\p{Cntrl}", "").strip();
    }

    private Object save(PanelRequest request) throws Exception {
        rules();
        Content content = content(request.body());
        YamlConfiguration yaml = editor.read(FILE);
        yaml.set("title", content.title());
        yaml.set("intro", content.intro());
        yaml.set("rules", content.rules());
        yaml.set("outro", content.outro());
        yaml.set("number", content.number());
        editor.write(FILE, yaml);
        sync(() -> {
            editor.reload(FILE);
            return null;
        });
        module.log().add(request, "regels aangepast", null, content.rules().size() + " regels");
        return overview(request);
    }
}
