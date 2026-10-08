package nl.pinda.framework.modules.world;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.storage.ServerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.DragonBattle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * De ender dragon: komt na een instelbare tijd vanzelf terug, laat elke keer een drakenei
 * achter en iedereen hoort wie hem verslagen heeft.
 *
 * <p>Minecraft laat de draak alleen terugkomen als er spelers in de buurt van het eiland zijn.
 * Is hij "klaar" terwijl er niemand in de End is, dan begint het zodra er iemand komt.
 */
public final class DragonControl implements Listener {

    private static final String KILLED = "dragon.killed-at";
    private static final String KILLER = "dragon.killer";
    private static final String KILLS = "dragon.kills";
    private static final String FORCE = "dragon.force-respawn";
    /** Vanilla kijkt binnen 192 blokken van het midden (0, 128, 0) wie er bij het gevecht is. */
    private static final double ARENA_RANGE = 192;

    /** Hoe het met de draak gaat, voor /dragon en het paneel. */
    public record Status(boolean end, boolean alive, boolean loaded, double health, double maxHealth, long killedAt, String killer,
                  int kills, boolean respawning, boolean forced, long respawnAt, int playersNear) {

        static Status noEnd() {
            return new Status(false, false, false, 0, 0, 0, null, 0, false, false, 0, 0);
        }
    }

    /** Wat er gebeurde bij "nu terug laten komen". */
    public enum Result { STARTED, WAITING, ALIVE, BUSY, NO_END, FAILED }

    private final PindaFramework plugin;
    private final WorldControlModule module;
    private final List<BukkitTask> eggs = new ArrayList<>();

    DragonControl(PindaFramework plugin, WorldControlModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    void stop() {
        for (BukkitTask task : eggs) {
            task.cancel();
        }
        eggs.clear();
    }

    private ServerData data() {
        return plugin.serverData();
    }

    private YamlConfiguration cfg() {
        return module.settings();
    }

    boolean autoRespawn() {
        return cfg().getBoolean("dragon.respawn", true);
    }

    long delayMillis() {
        return Math.max(0, cfg().getLong("dragon.respawn-minutes", 120)) * 60_000L;
    }

    /** De End met een drakengevecht, of null. */
    static World end(PindaFramework plugin) {
        for (World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() == World.Environment.THE_END && world.getEnderDragonBattle() != null) {
                return world;
            }
        }
        return null;
    }

    /**
     * Leeft de draak? De bossbar van het gevecht is zichtbaar zolang de draak niet verslagen is
     * (dat zet Minecraft elke tick), ook als de draak zelf niet geladen is.
     */
    private static boolean alive(DragonBattle battle) {
        return battle.getEnderDragon() != null || battle.getBossBar().isVisible();
    }

    private static List<Player> playersNear(World end) {
        Location center = new Location(end, 0, 128, 0);
        List<Player> near = new ArrayList<>();
        for (Player player : end.getPlayers()) {
            if (player.getLocation().distanceSquared(center) <= ARENA_RANGE * ARENA_RANGE) {
                near.add(player);
            }
        }
        return near;
    }

    // ============================================================ elke 5 seconden

    void check() {
        World end = end(plugin);
        if (end == null) {
            return;
        }
        DragonBattle battle = end.getEnderDragonBattle();
        if (alive(battle)) {
            if (data().getLong(KILLED, 0) != 0) {
                data().set(KILLED, 0);
            }
            if (data().get(FORCE) != null) {
                data().remove(FORCE);
            }
            return;
        }
        long now = System.currentTimeMillis();
        long killed = data().getLong(KILLED, 0);
        if (killed <= 0) {
            // Verslagen terwijl we het niet zagen (bijv. met /kill of voordat deze module er was).
            killed = now;
            data().set(KILLED, now);
        }
        if (battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
            return;
        }
        boolean forced = data().get(FORCE) != null;
        if (!forced && (!autoRespawn() || now < killed + delayMillis())) {
            return;
        }
        if (playersNear(end).isEmpty()) {
            return;
        }
        if (startRespawn(end, battle)) {
            data().remove(FORCE);
            announceRespawn();
        }
    }

    /**
     * Start het terugkomen zoals Minecraft het doet als een speler vier end crystals op het
     * portaal zet: we zetten die crystals er zelf neer.
     */
    private boolean startRespawn(World end, DragonBattle battle) {
        Location portal = battle.getEndPortalLocation();
        if (portal == null) {
            battle.generateEndPortal(true);
            portal = battle.getEndPortalLocation();
        }
        if (portal == null || !end.isChunkLoaded(portal.getBlockX() >> 4, portal.getBlockZ() >> 4)) {
            return false;
        }
        List<EnderCrystal> crystals = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Location at = new Location(end, portal.getBlockX() + face.getModX() * 3 + 0.5, portal.getBlockY() + 1,
                    portal.getBlockZ() + face.getModZ() * 3 + 0.5);
            crystals.add(end.spawn(at, EnderCrystal.class, crystal -> crystal.setShowingBottom(false)));
        }
        boolean started;
        try {
            started = battle.initiateRespawn(crystals);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("De ender dragon kon niet terugkomen: " + e.getMessage());
            started = false;
        }
        if (!started) {
            crystals.forEach(Entity::remove);
            return false;
        }
        plugin.getLogger().info("De ender dragon komt terug.");
        return true;
    }

    /** Laat de draak nu terugkomen (of zodra er iemand in de End is). */
    Result respawnNow() {
        World end = end(plugin);
        if (end == null) {
            return Result.NO_END;
        }
        DragonBattle battle = end.getEnderDragonBattle();
        if (alive(battle)) {
            return Result.ALIVE;
        }
        if (battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE) {
            return Result.BUSY;
        }
        if (playersNear(end).isEmpty()) {
            data().set(FORCE, 1);
            return Result.WAITING;
        }
        if (!startRespawn(end, battle)) {
            return Result.FAILED;
        }
        data().remove(FORCE);
        announceRespawn();
        return Result.STARTED;
    }

    Status status() {
        World end = end(plugin);
        if (end == null) {
            return Status.noEnd();
        }
        DragonBattle battle = end.getEnderDragonBattle();
        boolean alive = alive(battle);
        EnderDragon dragon = battle.getEnderDragon();
        double health = 0;
        double max = 200;
        if (dragon != null) {
            health = dragon.getHealth();
            AttributeInstance attribute = dragon.getAttribute(Attribute.MAX_HEALTH);
            max = attribute != null ? attribute.getValue() : 200;
        }
        long killed = alive ? 0 : data().getLong(KILLED, 0);
        boolean respawning = !alive && battle.getRespawnPhase() != DragonBattle.RespawnPhase.NONE;
        boolean forced = !alive && data().get(FORCE) != null;
        long respawnAt = 0;
        if (!alive && !respawning) {
            if (forced) {
                respawnAt = System.currentTimeMillis();
            } else if (autoRespawn()) {
                respawnAt = (killed > 0 ? killed : System.currentTimeMillis()) + delayMillis();
            }
        }
        String killer = data().get(KILLER);
        return new Status(true, alive, dragon != null, health, max, killed, killer == null || killer.isEmpty() ? null : killer,
                (int) data().getLong(KILLS, 0), respawning, forced, respawnAt, playersNear(end).size());
    }

    // ============================================================ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        DragonBattle battle = dragon.getDragonBattle();
        if (battle == null) {
            return;
        }
        boolean first = !battle.hasBeenPreviouslyKilled();
        Player killer = dragon.getKiller();
        data().set(KILLED, System.currentTimeMillis());
        data().set(KILLER, killer != null ? killer.getName() : "");
        data().set(KILLS, data().getLong(KILLS, 0) + 1);
        data().remove(FORCE);
        if (cfg().getBoolean("dragon.announce-kill", true)) {
            if (killer != null) {
                broadcast("world.dragon.killed", Text.p("player", killer.getName()));
            } else {
                broadcast("world.dragon.killed-unknown");
            }
        }
        // Minecraft legt alleen bij de eerste keer een ei, na de doodsanimatie (10 seconden).
        if (!first && cfg().getBoolean("dragon.egg-every-kill", true)) {
            World world = dragon.getWorld();
            BukkitTask[] holder = new BukkitTask[1];
            holder[0] = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                eggs.remove(holder[0]);
                placeEgg(world);
            }, 20L * 12);
            eggs.add(holder[0]);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon && dragon.getDragonBattle() != null) {
            data().set(KILLED, 0);
            data().remove(FORCE);
        }
    }

    private void placeEgg(World world) {
        DragonBattle battle = world.getEnderDragonBattle();
        Location portal = battle == null ? null : battle.getEndPortalLocation();
        if (portal == null) {
            return;
        }
        // Bovenop de pilaar in het midden van het portaal, net als het eerste ei.
        Block block = world.getHighestBlockAt(portal.getBlockX(), portal.getBlockZ()).getRelative(BlockFace.UP);
        Block below = block.getRelative(BlockFace.DOWN);
        if (below.getType() == Material.BEDROCK && block.getType().isAir()) {
            block.setType(Material.DRAGON_EGG);
        }
    }

    // ============================================================ meldingen

    private void broadcast(String key, TagResolver... resolvers) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            plugin.lang().send(player, key, resolvers);
        }
        plugin.lang().send(plugin.getServer().getConsoleSender(), key, resolvers);
    }

    private void announceRespawn() {
        if (!cfg().getBoolean("dragon.announce-respawn", true)) {
            return;
        }
        broadcast("world.dragon.respawn");
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.4f, 1f);
        }
    }
}
