package nl.pinda.framework.modules.antilag;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.module.PindaModule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Antilag: losse items opruimen met een aftelling, een maximum aantal mobs per chunk en
 * ingrijpen als de TPS te laag wordt. Met /lag zie je hoe het met de server gaat.
 */
public final class AntilagModule extends PindaModule {

    public static final String USE = "pinda.antilag.use";
    public static final String ADMIN = "pinda.antilag.admin";

    /** Een drukke chunk: hoeveel entities, hoeveel items en welke soorten het meest. */
    public record ChunkLoad(String world, int x, int z, int entities, int items, Map<String, Integer> types) {

        public int blockX() {
            return x * 16 + 8;
        }

        public int blockZ() {
            return z * 16 + 8;
        }

        /** "cow 120, item 40" */
        public String typesText() {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : types.entrySet()) {
                parts.add(entry.getKey() + " " + entry.getValue());
            }
            return String.join(", ", parts);
        }
    }

    /** Hoe druk het is in een wereld. */
    public record WorldLoad(String name, String environment, int players, int entities, int living, int items, int chunks) {
    }

    private ItemCleaner cleaner;
    private MobLimiter limiter;
    private LagGuard guard;

    public AntilagModule(PindaFramework plugin) {
        super(plugin, "antilag");
    }

    @Override
    protected void onEnable() {
        cleaner = new ItemCleaner(plugin, this);
        limiter = new MobLimiter(plugin, this);
        guard = new LagGuard(plugin, this);
        listen(limiter);
        command(new LagCommand(plugin, this));
        repeat(cleaner::tick, 20L, 20L);
        repeat(guard::check, 20L * 30, 20L * 5);
    }

    @Override
    protected void onReload() {
        if (!cleaner.counting()) {
            cleaner.reschedule();
        }
        limiter.reload();
    }

    /** De instellingen (modules/antilag.yml). */
    YamlConfiguration settings() {
        return config();
    }

    ItemCleaner cleaner() {
        return cleaner;
    }

    // ============================================================ voor /lag en het paneel

    /** Wanneer de volgende opruimbeurt is (0 = staat uit). */
    public long nextClear() {
        return cleaner.nextClear();
    }

    public boolean clearCounting() {
        return cleaner.counting();
    }

    public long lastClear() {
        return cleaner.lastClear();
    }

    /** Hoeveel items er de laatste keer zijn opgeruimd (-1 = nog niet gebeurd). */
    public int lastCount() {
        return cleaner.lastCount();
    }

    /** Start een aftelling. False als er al een loopt. */
    public boolean startClear(int seconds) {
        return cleaner.start(seconds);
    }

    /** Ruimt meteen op (met melding) en geeft het aantal terug. */
    public int clearNow() {
        return cleaner.clearNow();
    }

    public boolean lagging() {
        return guard.lagging();
    }

    public long laggingSince() {
        return guard.since();
    }

    public double recentTps() {
        return guard.recentTps();
    }

    public double tpsThreshold() {
        return guard.threshold();
    }

    /** De drukste chunks, op aantal entities (spelers niet meegeteld). */
    public List<ChunkLoad> busiest(int limit) {
        record Key(String world, int x, int z) {
        }
        Map<Key, int[]> counts = new HashMap<>();
        Map<Key, Map<String, Integer>> types = new HashMap<>();
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                Location location = entity.getLocation();
                Key key = new Key(world.getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
                int[] count = counts.computeIfAbsent(key, k -> new int[2]);
                count[0]++;
                if (entity instanceof Item) {
                    count[1]++;
                }
                types.computeIfAbsent(key, k -> new HashMap<>()).merge(entity.getType().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        List<Map.Entry<Key, int[]>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<Key, int[]> entry) -> entry.getValue()[0]).reversed());
        List<ChunkLoad> result = new ArrayList<>();
        for (Map.Entry<Key, int[]> entry : sorted) {
            if (result.size() >= limit) {
                break;
            }
            Key key = entry.getKey();
            Map<String, Integer> top = new LinkedHashMap<>();
            types.get(key).entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .forEach(type -> top.put(type.getKey(), type.getValue()));
            result.add(new ChunkLoad(key.world(), key.x(), key.z(), entry.getValue()[0], entry.getValue()[1], top));
        }
        return result;
    }

    /** Per wereld: spelers, entities, mobs, items en geladen chunks. */
    public List<WorldLoad> worlds() {
        List<WorldLoad> list = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            int items = world.getEntitiesByClass(Item.class).size();
            int living = 0;
            for (LivingEntity entity : world.getLivingEntities()) {
                if (!(entity instanceof Player)) {
                    living++;
                }
            }
            list.add(new WorldLoad(world.getName(), world.getEnvironment().name().toLowerCase(Locale.ROOT),
                    world.getPlayers().size(), world.getEntityCount(), living, items, world.getChunkCount()));
        }
        return list;
    }

    // ============================================================ opmaak

    /** Een getal met één decimaal in de standaardtaal, bijv. "19,8". */
    String number(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag(plugin.lang().defaultLanguage()));
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return format.format(value);
    }

    /** De plek van een chunk als klikbare tekst: klik = /lag tp erheen. */
    Component location(CommandSender viewer, ChunkLoad chunk) {
        String code = plugin.lang().languageOf(viewer);
        return plugin.lang().component(code, "antilag.location", Text.p("world", chunk.world()),
                        Text.p("x", chunk.blockX()), Text.p("z", chunk.blockZ()))
                .hoverEvent(HoverEvent.showText(plugin.lang().component(code, "antilag.location-hover")))
                .clickEvent(ClickEvent.runCommand("/lag tp " + chunk.world() + " " + chunk.x() + " " + chunk.z()));
    }

    /** De TPS in groen, oranje of rood. */
    Component tpsComponent(double tps) {
        double value = Math.min(20.0, tps);
        String tag = value >= 18 ? "success" : value >= 15 ? "warning" : "error";
        return plugin.lang().parse("<" + tag + ">" + number(value) + "</" + tag + ">");
    }
}
