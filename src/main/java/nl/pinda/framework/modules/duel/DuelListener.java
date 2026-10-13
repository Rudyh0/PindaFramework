package nl.pinda.framework.modules.duel;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.Collection;
import java.util.List;
import nl.pinda.framework.PindaFramework;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.projectiles.ProjectileSource;

/**
 * De events van de 1v1: PvP buiten gevechten tegenhouden (met de melding), spelers stilzetten
 * tijdens de inzet en het aftellen, een dodelijke klap opvangen (geen echte dood), en de arena
 * niet verlaten tijdens een gevecht.
 */
final class DuelListener implements Listener {

    private final PindaFramework plugin;
    private final DuelModule module;

    DuelListener(PindaFramework plugin, DuelModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    private Session session() {
        return module.session();
    }

    // ============================================================ PvP

    /** Wie er eigenlijk slaat: de speler zelf, de schutter van een pijl, wie de TNT aanstak, ... */
    static Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player source) {
            return source;
        }
        return null;
    }

    private static boolean npc(Entity entity) {
        return entity.hasMetadata("NPC");
    }

    /** Slaan met de hand of een wapen (komt ook als PvP in de wereld uit staat). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Player victim) || npc(victim) || npc(event.getPlayer())) {
            return;
        }
        Player attacker = event.getPlayer();
        String verdict = module.pvp(attacker, victim);
        if (verdict == null) {
            return;
        }
        event.setCancelled(true);
        if (verdict.equals("nudge")) {
            module.nudge(attacker, victim);
        }
    }

    /** Pijlen, drietanden, TNT, ... (en slaan, voor het geval een andere plugin dat doorliet). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || npc(victim)) {
            return;
        }
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim) || npc(attacker)) {
            return;
        }
        String verdict = module.pvp(attacker, victim);
        if (verdict == null) {
            return;
        }
        event.setCancelled(true);
        if (verdict.equals("nudge") && !(event.getDamager() instanceof Player)) {
            module.nudge(attacker, victim);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        if (!(potion.getShooter() instanceof Player thrower) || !harmful(potion.getEffects())) {
            return;
        }
        for (LivingEntity entity : List.copyOf(event.getAffectedEntities())) {
            if (entity instanceof Player victim && !victim.equals(thrower) && !npc(victim) && module.pvp(thrower, victim) != null) {
                event.setIntensity(victim, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        ProjectileSource source = cloud.getSource();
        if (!(source instanceof Player thrower)) {
            return;
        }
        boolean harmful = harmful(cloud.getCustomEffects())
                || cloud.getBasePotionType() != null && harmful(cloud.getBasePotionType().getPotionEffects());
        if (!harmful) {
            return;
        }
        event.getAffectedEntities().removeIf(entity -> entity instanceof Player victim && !victim.equals(thrower)
                && !npc(victim) && module.pvp(thrower, victim) != null);
    }

    private static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect.getType().getCategory() == PotionEffectTypeCategory.HARMFUL) {
                return true;
            }
        }
        return false;
    }

    // ============================================================ schade en doodgaan

    /**
     * Een dodelijke klap tijdens het gevecht: niet echt dood (niets laten vallen, geen death in de
     * statistieken), maar verloren. Buiten het gevecht (inzet, aftellen) geen schade in de arena.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Session session = session();
        if (session == null || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Session.Side side = session.side(player);
        if (side == null) {
            return;
        }
        if (session.phase != Session.Phase.FIGHT) {
            event.setCancelled(true);
            return;
        }
        if (event.getFinalDamage() < player.getHealth()) {
            return;
        }
        if (module.settings().allowTotems() && holdsTotem(player)) {
            return; // de totem doet zijn werk, het gevecht gaat door
        }
        event.setCancelled(true);
        session.defeated(side, Session.Outcome.KILLED);
    }

    private static boolean holdsTotem(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        return main.getType() == Material.TOTEM_OF_UNDYING || off.getType() == Material.TOTEM_OF_UNDYING;
    }

    /** Toch echt dood (bijv. /kill): niets kwijt, telt niet als death, en wel verloren. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Session session = session();
        Player player = event.getPlayer();
        if (session == null) {
            return;
        }
        if (session.isWatcher(player.getUniqueId())) {
            session.unwatch(player, false); // wie doodgaat, kijkt niet meer
            return;
        }
        Session.Side side = session.side(player);
        if (side == null) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.deathMessage(null);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getStatistic(Statistic.DEATHS) > 0) {
                player.decrementStatistic(Statistic.DEATHS);
            }
        });
        if (session.phase == Session.Phase.FIGHT) {
            session.defeated(side, Session.Outcome.KILLED);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        DuelModule.Respawn respawn = module.takeRespawn(player);
        if (respawn != null) {
            event.setRespawnLocation(respawn.location());
            if (!respawn.items().isEmpty()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        for (ItemStack item : respawn.items()) {
                            for (ItemStack left : player.getInventory().addItem(item).values()) {
                                Session.dropFor(player, left);
                            }
                        }
                    }
                });
            }
            return;
        }
        Session session = session();
        Session.Side side = session == null ? null : session.side(player);
        if (side == null) {
            return;
        }
        Location spawn = session.spawn(side);
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
        if (session.phase == Session.Phase.REMATCH) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && session.phase == Session.Phase.REMATCH) {
                    session.askRematch(side, player);
                    session.openRematch(side);
                }
            }, 5L);
        }
    }

    // ============================================================ stilstaan en in de arena blijven

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Session session = session();
        if (session == null || !event.hasChangedPosition() || !session.frozen()) {
            return;
        }
        if (session.isDuelist(event.getPlayer().getUniqueId()) && !module.ownTeleport(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Session session = session();
        Player player = event.getPlayer();
        if (session == null || module.ownTeleport(player)) {
            return;
        }
        if (session.isWatcher(player.getUniqueId())) {
            // Zelf weggegaan (/spawn, /home, ...): dan kijkt hij niet meer en hoeft hij niet terug.
            PlayerTeleportEvent.TeleportCause cause = event.getCause();
            if (cause == PlayerTeleportEvent.TeleportCause.COMMAND || cause == PlayerTeleportEvent.TeleportCause.PLUGIN
                    || !event.getTo().getWorld().equals(event.getFrom().getWorld())) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!event.isCancelled() && session.isWatcher(player.getUniqueId())) {
                        session.unwatch(player, false);
                        module.lang(player, "duel.watch.left");
                    }
                });
            }
            return;
        }
        if (!session.isDuelist(player.getUniqueId())) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        boolean small = cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT
                || cause == PlayerTeleportEvent.TeleportCause.DISMOUNT
                || cause == PlayerTeleportEvent.TeleportCause.EXIT_BED;
        if (small && session.phase == Session.Phase.FIGHT && session.insideArena(event.getTo())) {
            return;
        }
        event.setCancelled(true);
        if (cause == PlayerTeleportEvent.TeleportCause.COMMAND || cause == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            module.lang(player, "duel.fight.no-teleport");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Session session = session();
        if (session == null || !session.isDuelist(event.getPlayer().getUniqueId())) {
            return;
        }
        if (!module.settings().commandAllowed(event.getMessage())) {
            event.setCancelled(true);
            module.lang(event.getPlayer(), "duel.fight.no-command");
            plugin.theme().play(event.getPlayer(), "error");
        }
    }

    /** Bezig met de inzet of het aftellen: niets gebruiken of eten (dat zou een voorsprong geven). */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Session session = session();
        if (session != null && session.frozen() && session.isDuelist(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Session session = session();
        if (session != null && session.frozen() && session.isDuelist(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Session session = session();
        if (session != null && session.frozen() && session.isDuelist(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ============================================================ de arena heel laten

    private boolean noBuilding(Player player) {
        Session session = session();
        return session != null && session.isDuelist(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (noBuilding(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (noBuilding(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (noBuilding(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (noBuilding(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ============================================================ in- en uitloggen

    @EventHandler(priority = EventPriority.LOW)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        module.dropInvites(player);
        Session session = session();
        if (session == null) {
            return;
        }
        if (session.isWatcher(player.getUniqueId())) {
            session.watcherQuit(player);
            return;
        }
        Session.Side side = session.side(player);
        if (side != null) {
            session.quit(side);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        module.recover(event.getPlayer());
    }
}
