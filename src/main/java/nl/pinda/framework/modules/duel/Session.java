package nl.pinda.framework.modules.duel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import nl.pinda.framework.PindaFramework;
import nl.pinda.framework.lang.Text;
import nl.pinda.framework.modules.economy.EconomyService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

/**
 * Eén 1v1 in de arena, van accepteren tot iedereen weer terug is: inzet kiezen, aftellen,
 * vechten, de prijs, en eventueel een rematch (dan opnieuw inzet kiezen).
 *
 * <p>Alles gebeurt op de hoofdthread. Een item zit altijd op precies één plek: in de inventory
 * van de speler, in zijn inzet, of in "owed" (gewonnen, maar paste niet). Wat iemand terug moet
 * krijgen als de server onverwacht stopt, staat steeds in de database (zie {@link DuelStore.Pending}).
 */
final class Session {

    enum Phase { WAGER, COUNTDOWN, FIGHT, REMATCH, ENDED }

    /** Hoe een gevecht afliep. */
    enum Outcome { KILLED, FORFEIT, QUIT }

    /** Vakjes voor items in de inzet (4 x 4). */
    static final int STAKE_SLOTS = 16;

    /** Een van de twee spelers. */
    static final class Side {
        final UUID uuid;
        final String name;
        final int number;
        final Spot returnTo;
        final GameMode gameMode;
        final boolean allowFlight;
        final List<ItemStack> items = new ArrayList<>();
        final List<ItemStack> owed = new ArrayList<>();
        long money;
        boolean moneyTaken;
        boolean ready;
        Boolean rematch;
        int wins;
        boolean left;
        boolean prepared;
        Collection<PotionEffect> effects = List.of();
        WagerMenu wagerMenu;
        RematchMenu rematchMenu;

        Side(Player player, int number) {
            this.uuid = player.getUniqueId();
            this.name = player.getName();
            this.number = number;
            this.returnTo = Spot.of(player.getLocation());
            this.gameMode = player.getGameMode();
            this.allowFlight = player.getAllowFlight();
        }

        int itemCount() {
            int count = 0;
            for (ItemStack item : items) {
                count += item.getAmount();
            }
            return count;
        }
    }

    /** Een toeschouwer en waar hij vandaan kwam. */
    record Watcher(UUID uuid, String name, Spot returnTo) {
    }

    private final DuelModule module;
    private final PindaFramework plugin;
    final Side one;
    final Side two;
    final Map<UUID, Watcher> watchers = new LinkedHashMap<>();
    Phase phase = Phase.WAGER;
    long phaseStarted = System.currentTimeMillis();
    long fightStarted;
    int round = 1;
    /** Even geen "je hebt het menu dichtgedaan"-melding: wij doen de menu's zelf dicht. */
    boolean closingMenus;
    private BukkitTask timer;
    private BukkitTask warning;
    private boolean saveQueued;

    Session(DuelModule module, Player first, Player second) {
        this.module = module;
        this.plugin = module.plugin();
        this.one = new Side(first, 1);
        this.two = new Side(second, 2);
    }

    // ============================================================ opzoeken

    Side side(UUID uuid) {
        if (one.uuid.equals(uuid)) {
            return one;
        }
        if (two.uuid.equals(uuid)) {
            return two;
        }
        return null;
    }

    Side side(Player player) {
        return side(player.getUniqueId());
    }

    Side other(Side side) {
        return side == one ? two : one;
    }

    boolean isDuelist(UUID uuid) {
        return side(uuid) != null;
    }

    boolean isWatcher(UUID uuid) {
        return watchers.containsKey(uuid);
    }

    boolean involves(UUID uuid) {
        return isDuelist(uuid) || isWatcher(uuid);
    }

    /** Twee spelers die nu tegen elkaar vechten (en elkaar dus mogen raken). */
    boolean fighting(Player a, Player b) {
        Side first = side(a);
        Side second = side(b);
        return phase == Phase.FIGHT && first != null && second != null && first != second;
    }

    /** Staan de spelers nu stil (inzet kiezen, aftellen, kiezen voor een rematch)? */
    boolean frozen() {
        return phase == Phase.WAGER || phase == Phase.COUNTDOWN || phase == Phase.REMATCH;
    }

    Player player(Side side) {
        return side.left ? null : plugin.getServer().getPlayer(side.uuid);
    }

    DuelSettings settings() {
        return module.settings();
    }

    EconomyService economy() {
        return module.economy();
    }

    /** Waar deze speler in de arena staat (plek 1 of 2). */
    Location spawn(Side side) {
        Spot spot = side.number == 1 ? settings().spawn1() : settings().spawn2();
        return spot == null ? null : spot.location(plugin.getServer());
    }

    // ============================================================ beginnen

    /** Beide spelers naar de arena en de inzet kiezen. False als dat niet kan (arena weg). */
    boolean start() {
        Location first = spawn(one);
        Location second = spawn(two);
        if (first == null || second == null) {
            return false;
        }
        save(one);
        save(two);
        enterWager();
        for (Side side : sides()) {
            Player player = player(side);
            if (player == null) {
                continue;
            }
            player.closeInventory();
            module.move(player, side == one ? first : second).thenAccept(ok -> {
                if (phase != Phase.WAGER || !player.isOnline()) {
                    return;
                }
                if (!Boolean.TRUE.equals(ok)) {
                    module.lang(player, "duel.teleport-failed");
                    cancel("teleport");
                    return;
                }
                module.lang(player, "duel.arena.arrived", Text.p("player", other(side).name),
                        Text.p("seconds", settings().wagerSeconds()));
                plugin.theme().play(player, "teleport");
                openWager(side);
            });
        }
        return true;
    }

    private Side[] sides() {
        return new Side[] {one, two};
    }

    /** (Opnieuw) inzet kiezen: alles klaar op nee, de tijd loopt. */
    private void enterWager() {
        phase = Phase.WAGER;
        phaseStarted = System.currentTimeMillis();
        for (Side side : sides()) {
            side.ready = false;
            side.rematch = null;
        }
        cancelTimers();
        int seconds = settings().wagerSeconds();
        timer = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (phase == Phase.WAGER) {
                both("duel.wager.timeout");
                cancel("timeout");
            }
        }, seconds * 20L);
        if (seconds > 30) {
            warning = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (phase == Phase.WAGER) {
                    both("duel.wager.hurry", Text.p("seconds", 15));
                }
            }, (seconds - 15) * 20L);
        }
    }

    void openWager(Side side) {
        Player player = player(side);
        if (player == null || phase != Phase.WAGER) {
            return;
        }
        side.wagerMenu = new WagerMenu(plugin, player, this, side);
        side.wagerMenu.open();
    }

    long secondsLeft(int total) {
        long passed = (System.currentTimeMillis() - phaseStarted) / 1000;
        return Math.max(0, total - passed);
    }

    // ============================================================ inzet

    /** Een item (of een deel van een stapel) uit de inventory in de inzet. */
    void stake(Side side, Inventory from, int slot, int amount) {
        Player player = player(side);
        if (player == null || phase != Phase.WAGER) {
            return;
        }
        if (!settings().stakeItems()) {
            module.lang(player, "duel.wager.items-off");
            plugin.theme().play(player, "error");
            return;
        }
        ItemStack stack = from.getItem(slot);
        if (stack == null || stack.isEmpty()) {
            return;
        }
        if (!module.mayStake(stack)) {
            module.lang(player, "duel.wager.item-blocked");
            plugin.theme().play(player, "error");
            return;
        }
        ItemStack moving = stack.clone();
        moving.setAmount(Math.max(1, Math.min(amount, stack.getAmount())));
        int fit = room(side.items, moving);
        if (fit <= 0) {
            module.lang(player, "duel.wager.full");
            plugin.theme().play(player, "error");
            return;
        }
        moving.setAmount(Math.min(fit, moving.getAmount()));
        add(side.items, moving);
        int rest = stack.getAmount() - moving.getAmount();
        if (rest <= 0) {
            from.setItem(slot, null);
        } else {
            stack.setAmount(rest);
            from.setItem(slot, stack);
        }
        plugin.theme().play(player, "click");
        changed(side);
    }

    /** Een item uit de inzet terug naar de inventory. */
    void unstake(Side side, int index, int amount) {
        Player player = player(side);
        if (player == null || phase != Phase.WAGER || index < 0 || index >= side.items.size()) {
            return;
        }
        ItemStack item = side.items.get(index);
        ItemStack moving = item.clone();
        moving.setAmount(Math.max(1, Math.min(amount, item.getAmount())));
        int wanted = moving.getAmount();
        int left = 0;
        for (ItemStack rest : player.getInventory().addItem(moving).values()) {
            left += rest.getAmount();
        }
        int returned = wanted - left;
        if (returned <= 0) {
            module.lang(player, "duel.wager.inventory-full");
            plugin.theme().play(player, "error");
            return;
        }
        if (item.getAmount() <= returned) {
            side.items.remove(index);
        } else {
            item.setAmount(item.getAmount() - returned);
        }
        plugin.theme().play(player, "click");
        changed(side);
    }

    /** Hoeveel er van dit item nog in de inzet past. */
    static int room(List<ItemStack> items, ItemStack item) {
        int max = Math.max(1, item.getMaxStackSize());
        int room = (STAKE_SLOTS - items.size()) * max;
        for (ItemStack existing : items) {
            if (existing.isSimilar(item)) {
                room += Math.max(0, max - existing.getAmount());
            }
        }
        return room;
    }

    /** Voegt toe, eerst bij gelijke stapels. Aanroepen na {@link #room}. */
    static void add(List<ItemStack> items, ItemStack item) {
        int max = Math.max(1, item.getMaxStackSize());
        int left = item.getAmount();
        for (ItemStack existing : items) {
            if (left <= 0) {
                break;
            }
            if (existing.isSimilar(item) && existing.getAmount() < max) {
                int moved = Math.min(left, max - existing.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                left -= moved;
            }
        }
        while (left > 0 && items.size() < STAKE_SLOTS) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(left, max));
            items.add(stack);
            left -= stack.getAmount();
        }
    }

    /** Hoeveel geld deze speler hooguit kan inzetten. */
    long maxMoney(Player player) {
        EconomyService economy = module.economy();
        if (economy == null || !settings().stakeMoney()) {
            return 0;
        }
        long spendable = economy.spendable(economy.account(player));
        long max = settings().maxMoney();
        return max > 0 ? Math.min(max, spendable) : spendable;
    }

    /** Zet het bedrag (begrensd op wat de speler heeft). */
    void setMoney(Side side, long cents) {
        Player player = player(side);
        if (player == null || phase != Phase.WAGER) {
            return;
        }
        long max = maxMoney(player);
        long value = Math.max(0, Math.min(cents, max));
        if (cents > max) {
            EconomyService economy = module.economy();
            module.lang(player, settings().maxMoney() > 0 && max == settings().maxMoney() ? "duel.wager.money-max" : "duel.wager.money-short",
                    Text.p("amount", economy == null ? "0" : economy.format(max)));
            plugin.theme().play(player, "error");
        }
        if (value == side.money) {
            return;
        }
        side.money = value;
        plugin.theme().play(player, "click");
        changed(side);
    }

    /** Er veranderde iets aan een inzet: niemand is meer klaar, beide menu's bijwerken. */
    private void changed(Side side) {
        boolean wasReady = one.ready || two.ready;
        one.ready = false;
        two.ready = false;
        if (wasReady) {
            Player other = player(other(side));
            if (other != null) {
                module.lang(other, "duel.wager.changed", Text.p("player", side.name));
            }
        }
        refreshMenus();
        queueSave();
    }

    void refreshMenus() {
        for (Side side : sides()) {
            Player player = player(side);
            if (player == null) {
                continue;
            }
            Object holder = player.getOpenInventory().getTopInventory().getHolder(false);
            if (side.wagerMenu != null && holder == side.wagerMenu) {
                side.wagerMenu.refresh();
            }
            if (side.rematchMenu != null && holder == side.rematchMenu) {
                side.rematchMenu.refresh();
            }
        }
    }

    void toggleReady(Side side) {
        Player player = player(side);
        if (player == null || phase != Phase.WAGER) {
            return;
        }
        if (!side.ready && side.money > 0) {
            EconomyService economy = module.economy();
            if (economy == null || economy.spendable(economy.account(player)) < side.money) {
                module.lang(player, "duel.wager.money-short",
                        Text.p("amount", economy == null ? "0" : economy.format(economy.spendable(economy.account(player)))));
                plugin.theme().play(player, "error");
                return;
            }
        }
        side.ready = !side.ready;
        plugin.theme().play(player, side.ready ? "success" : "click");
        Player other = player(other(side));
        if (other != null && side.ready) {
            module.lang(other, "duel.wager.other-ready", Text.p("player", side.name));
            plugin.theme().play(other, "request");
        }
        refreshMenus();
        if (one.ready && two.ready) {
            lock();
        }
    }

    /** Beide klaar: het geld gaat van de rekening af en het aftellen begint. */
    private void lock() {
        EconomyService economy = module.economy();
        Player first = player(one);
        Player second = player(two);
        if (first == null || second == null) {
            return;
        }
        if (economy == null) {
            one.money = 0;
            two.money = 0;
        }
        if (one.money > 0 && !economy.take(first, one.money, "duel-stake", "1v1 tegen " + two.name)) {
            notEnough(one, first);
            return;
        }
        one.moneyTaken = one.money > 0;
        if (two.money > 0 && !economy.take(second, two.money, "duel-stake", "1v1 tegen " + one.name)) {
            if (one.moneyTaken) {
                economy.give(one.uuid, one.money, false, "duel-refund", "1v1 gaat niet door");
                one.moneyTaken = false;
            }
            notEnough(two, second);
            return;
        }
        two.moneyTaken = two.money > 0;
        save(one);
        save(two);
        saveData();
        countdown();
    }

    private void notEnough(Side side, Player player) {
        side.ready = false;
        other(side).ready = false;
        EconomyService economy = module.economy();
        module.lang(player, "duel.wager.money-short", Text.p("amount", economy.format(economy.spendable(economy.account(player)))));
        plugin.theme().play(player, "error");
        refreshMenus();
    }

    // ============================================================ aftellen en vechten

    private void countdown() {
        cancelTimers();
        phase = Phase.COUNTDOWN;
        phaseStarted = System.currentTimeMillis();
        closeMenus();
        for (Side side : sides()) {
            Player player = player(side);
            Location spawn = spawn(side);
            if (player == null || spawn == null) {
                continue;
            }
            if (!player.getWorld().equals(spawn.getWorld()) || player.getLocation().distanceSquared(spawn) > 1) {
                module.move(player, spawn);
            }
            prepare(side, player);
        }
        module.forcePvp(spawn(one));
        announce();
        int[] count = {3};
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (phase != Phase.COUNTDOWN) {
                return;
            }
            if (count[0] > 0) {
                for (Side side : sides()) {
                    Player player = player(side);
                    if (player != null) {
                        title(player, "duel.countdown.title", "duel.countdown.subtitle", 0, 900, 100, Text.p("count", count[0]),
                                Text.p("player", other(side).name));
                        plugin.theme().play(player, "countdown");
                    }
                }
                count[0]--;
                return;
            }
            fight();
        }, 0L, 20L);
    }

    /** Klaarzetten voor het gevecht: survival, niet vliegen, volle levens. */
    private void prepare(Side side, Player player) {
        side.prepared = true;
        side.effects = List.copyOf(player.getActivePotionEffects());
        if (settings().forceSurvival()) {
            if (player.getGameMode() != GameMode.SURVIVAL) {
                player.setGameMode(GameMode.SURVIVAL);
            }
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        if (settings().healOnStart()) {
            heal(player);
        }
    }

    private void announce() {
        if (!settings().broadcastStart()) {
            return;
        }
        boolean canWatch = settings().spectate() != null;
        String key = round > 1 ? "duel.broadcast.rematch" : "duel.broadcast.start";
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (involves(online.getUniqueId())) {
                continue;
            }
            TagResolver watch = Placeholder.styling("watch", ClickEvent.runCommand("/duel kijken"),
                    HoverEvent.showText(module.component(online, "duel.broadcast.watch-hover")));
            module.lang(online, canWatch && online.hasPermission(DuelModule.SPECTATE) ? key : key + "-plain",
                    Text.p("one", one.name), Text.p("two", two.name), watch);
        }
    }

    private void fight() {
        cancelTimers();
        phase = Phase.FIGHT;
        phaseStarted = System.currentTimeMillis();
        fightStarted = phaseStarted;
        for (Side side : sides()) {
            Player player = player(side);
            if (player != null) {
                title(player, "duel.countdown.fight", "duel.countdown.fight-subtitle", 0, 1200, 400, Text.p("player", other(side).name));
                plugin.theme().play(player, "levelup");
            }
        }
        watchersSend("duel.watch.started", Text.p("one", one.name), Text.p("two", two.name));
        timer = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** Elke seconde tijdens het gevecht: de stand in de actiebalk, de tijdslimiet en de grens van de arena. */
    private void tick() {
        if (phase != Phase.FIGHT) {
            return;
        }
        int maxMinutes = settings().maxFightMinutes();
        long elapsed = (System.currentTimeMillis() - fightStarted) / 1000;
        long left = maxMinutes > 0 ? Math.max(0, maxMinutes * 60L - elapsed) : -1;
        for (Side side : sides()) {
            Player player = player(side);
            Player opponent = player(other(side));
            if (player == null) {
                continue;
            }
            leash(side, player);
            String health = opponent == null ? "-" : String.format(java.util.Locale.ROOT, "%.1f", opponent.getHealth() / 2.0);
            module.lang(player, left >= 0 ? "duel.fight.actionbar-timed" : "duel.fight.actionbar",
                    Text.p("player", other(side).name), Text.p("health", health), Text.p("time", clock(left)));
        }
        if (left == 0) {
            draw();
        }
    }

    /** Te ver van de arena? Dan terug naar je plek. */
    void leash(Side side, Player player) {
        Area area = settings().area();
        int radius = settings().arenaRadius();
        Spot center = settings().center();
        Location location = player.getLocation();
        boolean away;
        if (area != null) {
            away = !area.containsColumn(location); // het ingestelde gebied is de grens
        } else if (radius <= 0 || center == null) {
            return;
        } else {
            away = !location.getWorld().getName().equals(center.world())
                    || square(location.getX() - center.x()) + square(location.getZ() - center.z()) > square(radius);
        }
        if (away) {
            Location spawn = spawn(side);
            if (spawn != null) {
                module.move(player, spawn);
                module.lang(player, "duel.fight.leash");
            }
        }
    }

    /** Mag deze speler (tijdens het gevecht) naar die plek, bijv. met een enderparel? */
    boolean insideArena(Location location) {
        if (settings().area() != null) {
            return settings().area().containsColumn(location);
        }
        int radius = settings().arenaRadius();
        Spot center = settings().center();
        if (center == null) {
            return spawn(one) != null && location.getWorld().equals(spawn(one).getWorld());
        }
        if (!location.getWorld().getName().equals(center.world())) {
            return false;
        }
        return radius <= 0 || square(location.getX() - center.x()) + square(location.getZ() - center.z()) <= square(radius);
    }

    private static double square(double value) {
        return value * value;
    }

    static String clock(long seconds) {
        if (seconds < 0) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ============================================================ de uitslag

    /** Deze speler is verslagen (een dodelijke klap, opgegeven of uitgelogd). */
    void defeated(Side loser, Outcome outcome) {
        if (phase != Phase.FIGHT) {
            return;
        }
        Side winner = other(loser);
        cancelTimers();
        long pot = (one.moneyTaken ? one.money : 0) + (two.moneyTaken ? two.money : 0);
        int itemCount = one.itemCount() + two.itemCount();
        List<ItemStack> prize = new ArrayList<>(winner.items);
        prize.addAll(loser.items);
        clearStakes();
        if (pot > 0) {
            EconomyService economy = module.economy();
            if (economy != null) {
                economy.give(winner.uuid, pot, false, "duel-win", "1v1 tegen " + loser.name);
            } else {
                plugin.getLogger().warning("1v1: " + pot + " centen voor " + winner.name + " konden niet worden uitbetaald (economy staat uit).");
            }
        }
        handOut(winner, prize);
        winner.wins++;
        int seconds = (int) ((System.currentTimeMillis() - fightStarted) / 1000);
        module.store().record(winner.uuid, winner.name, 1, 0, 0);
        module.store().record(loser.uuid, loser.name, 0, 1, 0);
        module.store().addFight(new DuelStore.Fight(System.currentTimeMillis(), winner.uuid, winner.name, loser.uuid, loser.name,
                false, pot, itemCount, seconds, outcome.name().toLowerCase(java.util.Locale.ROOT)));

        String prizeText = prizeText(pot, itemCount);
        Player winning = player(winner);
        Player losing = player(loser);
        String reason = switch (outcome) {
            case KILLED -> "killed";
            case FORFEIT -> "forfeit";
            case QUIT -> "quit";
        };
        if (winning != null) {
            title(winning, "duel.result.win-title", "duel.result.win-subtitle", 200, 2500, 700, Text.p("player", loser.name));
            module.lang(winning, "duel.result.won-" + reason, Text.p("player", loser.name), Text.p("prize", prizeText));
            plugin.theme().play(winning, "levelup");
        }
        if (losing != null) {
            title(losing, "duel.result.lose-title", "duel.result.lose-subtitle", 200, 2500, 700, Text.p("player", winner.name));
            module.lang(losing, "duel.result.lost-" + reason, Text.p("player", winner.name), Text.p("prize", prizeText));
            plugin.theme().play(losing, "error");
            module.deathEffect(losing);
        }
        watchersSend("duel.watch.result", Text.p("winner", winner.name), Text.p("loser", loser.name));
        if (settings().broadcastResult()) {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (!involves(online.getUniqueId())) {
                    module.lang(online, "duel.broadcast.result", Text.p("winner", winner.name), Text.p("loser", loser.name),
                            Text.p("prize", prizeText));
                }
            }
        }
        afterFight();
        if (outcome == Outcome.QUIT) {
            end();
        } else {
            rematchPhase();
        }
    }

    /** De tijd is om: gelijkspel, iedereen krijgt zijn eigen inzet terug. */
    void draw() {
        if (phase != Phase.FIGHT) {
            return;
        }
        cancelTimers();
        int seconds = (int) ((System.currentTimeMillis() - fightStarted) / 1000);
        long pot = (one.moneyTaken ? one.money : 0) + (two.moneyTaken ? two.money : 0);
        int itemCount = one.itemCount() + two.itemCount();
        refundStakes("duel-draw");
        module.store().record(one.uuid, one.name, 0, 0, 1);
        module.store().record(two.uuid, two.name, 0, 0, 1);
        module.store().addFight(new DuelStore.Fight(System.currentTimeMillis(), one.uuid, one.name, two.uuid, two.name,
                true, pot, itemCount, seconds, "time"));
        for (Side side : sides()) {
            Player player = player(side);
            if (player != null) {
                title(player, "duel.result.draw-title", "duel.result.draw-subtitle", 200, 2500, 700, Text.p("player", other(side).name));
                module.lang(player, "duel.result.draw", Text.p("player", other(side).name));
            }
        }
        watchersSend("duel.watch.draw", Text.p("one", one.name), Text.p("two", two.name));
        afterFight();
        rematchPhase();
    }

    /** Na het gevecht: weer heel, de effecten van daarvoor terug. */
    private void afterFight() {
        module.releasePvp();
        for (Side side : sides()) {
            Player player = player(side);
            if (player == null || player.isDead()) {
                continue;
            }
            heal(player);
            restoreEffects(side, player);
        }
        save(one);
        save(two);
        saveData();
    }

    private void restoreEffects(Side side, Player player) {
        if (!side.prepared) {
            return;
        }
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        player.addPotionEffects(side.effects);
    }

    static void heal(Player player) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        player.setFoodLevel(20);
        player.setSaturation(10f);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0);
    }

    private String prizeText(long money, int items) {
        EconomyService economy = module.economy();
        String code = plugin.lang().defaultLanguage();
        String moneyText = economy == null ? "" : economy.format(money);
        String key = money > 0 && items > 0 ? "duel.prize.both" : money > 0 ? "duel.prize.money" : items > 0 ? "duel.prize.items" : "duel.prize.none";
        String template = plugin.lang().raw(code, key);
        if (template == null) {
            return moneyText;
        }
        return template.replace("<money>", moneyText).replace("<count>", String.valueOf(items));
    }

    /** Geeft items aan een speler; wat niet past (of als hij weg is) komt in "owed". */
    private void handOut(Side side, List<ItemStack> items) {
        if (items.isEmpty()) {
            return;
        }
        Player player = player(side);
        if (player == null) {
            side.owed.addAll(items);
            return;
        }
        boolean full = false;
        for (ItemStack item : items) {
            Collection<ItemStack> rest = player.getInventory().addItem(item.clone()).values();
            if (!rest.isEmpty()) {
                side.owed.addAll(rest);
                full = true;
            }
        }
        if (full) {
            module.lang(player, "duel.result.owed");
        }
    }

    private void clearStakes() {
        for (Side side : sides()) {
            side.items.clear();
            side.money = 0;
            side.moneyTaken = false;
            side.ready = false;
        }
    }

    /** Iedereen krijgt zijn eigen inzet terug (geld en items). */
    private void refundStakes(String type) {
        EconomyService economy = module.economy();
        for (Side side : sides()) {
            if (side.moneyTaken && side.money > 0 && economy != null) {
                economy.give(side.uuid, side.money, false, type, "1v1 inzet terug");
            }
            handOut(side, new ArrayList<>(side.items));
        }
        clearStakes();
    }

    // ============================================================ rematch

    private void rematchPhase() {
        phase = Phase.REMATCH;
        phaseStarted = System.currentTimeMillis();
        one.rematch = null;
        two.rematch = null;
        for (Side side : sides()) {
            Player player = player(side);
            Location spawn = spawn(side);
            if (player == null || spawn == null || player.isDead()) {
                continue;
            }
            module.move(player, spawn).thenAccept(ok -> {
                if (phase == Phase.REMATCH && player.isOnline()) {
                    openRematch(side);
                }
            });
            askRematch(side, player);
        }
        timer = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (phase == Phase.REMATCH) {
                both("duel.rematch.timeout");
                end();
            }
        }, settings().rematchSeconds() * 20L);
    }

    /** De vraag in de chat (voor als het menu dicht is). */
    void askRematch(Side side, Player player) {
        TagResolver yes = Placeholder.styling("rematch", ClickEvent.runCommand("/duel rematch"),
                HoverEvent.showText(module.component(player, "duel.rematch.hover-yes")));
        TagResolver no = Placeholder.styling("stop", ClickEvent.runCommand("/duel stoppen"),
                HoverEvent.showText(module.component(player, "duel.rematch.hover-no")));
        module.lang(player, "duel.rematch.ask", Text.p("player", other(side).name), Text.p("seconds", settings().rematchSeconds()),
                Text.p("score", score(side)), yes, no);
    }

    void openRematch(Side side) {
        Player player = player(side);
        if (player == null || phase != Phase.REMATCH || side.rematch != null) {
            return;
        }
        side.rematchMenu = new RematchMenu(plugin, player, this, side);
        side.rematchMenu.open();
    }

    /** De stand in deze reeks, vanuit deze speler: bijv. "2 - 1". */
    String score(Side side) {
        return side.wins + " - " + other(side).wins;
    }

    void rematch(Side side, boolean yes) {
        Player player = player(side);
        if (player == null || phase != Phase.REMATCH || side.rematch != null) {
            return;
        }
        side.rematch = yes;
        Side other = other(side);
        Player otherPlayer = player(other);
        if (!yes) {
            module.lang(player, "duel.rematch.you-stopped");
            if (otherPlayer != null) {
                module.lang(otherPlayer, "duel.rematch.other-stopped", Text.p("player", side.name));
            }
            end();
            return;
        }
        if (Boolean.TRUE.equals(other.rematch)) {
            round++;
            both("duel.rematch.starting", Text.p("round", round));
            closeMenus();
            enterWager();
            for (Side each : sides()) {
                Player eachPlayer = player(each);
                if (eachPlayer != null) {
                    plugin.theme().play(eachPlayer, "success");
                }
                openWager(each);
            }
            return;
        }
        module.lang(player, "duel.rematch.waiting", Text.p("player", other.name));
        plugin.theme().play(player, "click");
        if (otherPlayer != null) {
            module.lang(otherPlayer, "duel.rematch.other-wants", Text.p("player", side.name));
            plugin.theme().play(otherPlayer, "request");
        }
        refreshMenus();
    }

    // ============================================================ afblazen en stoppen

    /** Een speler deed het inzetmenu dicht: in de chat hoe het weer opengaat. */
    void wagerClosed(Side side) {
        Player player = player(side);
        if (player == null || phase != Phase.WAGER
                || player.getOpenInventory().getTopInventory().getHolder(false) instanceof WagerMenu) {
            return;
        }
        TagResolver open = Placeholder.styling("open", ClickEvent.runCommand("/duel inzet"),
                HoverEvent.showText(module.component(player, "duel.wager.hover-open")));
        TagResolver cancel = Placeholder.styling("cancel", ClickEvent.runCommand("/duel annuleer"),
                HoverEvent.showText(module.component(player, "duel.wager.hover-cancel")));
        module.lang(player, "duel.wager.closed", open, cancel, Text.p("seconds", secondsLeft(settings().wagerSeconds())));
    }

    /** Een van de spelers blaast het af (voor het gevecht begint). */
    void cancelBy(Side side) {
        if (phase != Phase.WAGER && phase != Phase.COUNTDOWN) {
            return;
        }
        Player player = player(side);
        Player other = player(other(side));
        if (player != null) {
            module.lang(player, "duel.cancelled-you");
        }
        if (other != null) {
            module.lang(other, "duel.cancelled-other", Text.p("player", side.name));
            plugin.theme().play(other, "error");
        }
        cancel("player");
    }

    /** Het gevecht gaat niet door (of wordt afgebroken): iedereen krijgt zijn inzet terug. */
    void cancel(String reason) {
        if (phase == Phase.ENDED) {
            return;
        }
        if (phase == Phase.FIGHT || phase == Phase.COUNTDOWN) {
            module.releasePvp();
        }
        refundStakes("duel-refund");
        end();
    }

    /** Een speler logt uit. */
    void quit(Side side) {
        side.left = true;
        switch (phase) {
            case FIGHT -> defeated(side, Outcome.QUIT);
            case WAGER, COUNTDOWN -> {
                Player other = player(other(side));
                if (other != null) {
                    module.lang(other, "duel.cancelled-quit", Text.p("player", side.name));
                }
                cancel("quit");
            }
            case REMATCH -> {
                Player other = player(other(side));
                if (other != null) {
                    module.lang(other, "duel.rematch.other-left", Text.p("player", side.name));
                }
                end();
            }
            case ENDED -> {
            }
        }
    }

    /** Klaar: iedereen terug naar waar hij was. */
    void end() {
        if (phase == Phase.ENDED) {
            return;
        }
        phase = Phase.ENDED;
        cancelTimers();
        closeMenus();
        for (Side side : sides()) {
            finish(side);
        }
        for (Watcher watcher : List.copyOf(watchers.values())) {
            Player player = plugin.getServer().getPlayer(watcher.uuid());
            watchers.remove(watcher.uuid());
            if (player != null) {
                module.lang(player, "duel.watch.ended");
                module.sendBack(player, watcher.returnTo(), List.of(), null);
            }
        }
        module.ended(this);
    }

    /** Een speler terug naar waar hij was, met wat hij nog kreeg, in zijn eigen spelmodus. */
    private void finish(Side side) {
        Player player = player(side);
        if (player == null) {
            // Uitgelogd: bij de volgende keer joinen terugzetten.
            module.remember(pending(side));
            return;
        }
        if (side.prepared && settings().forceSurvival()) {
            if (player.getGameMode() != side.gameMode) {
                player.setGameMode(side.gameMode);
            }
            player.setAllowFlight(side.allowFlight || side.gameMode == GameMode.CREATIVE || side.gameMode == GameMode.SPECTATOR);
        }
        List<ItemStack> owed = new ArrayList<>(side.owed);
        side.owed.clear();
        module.sendBack(player, side.returnTo, owed, null);
    }

    // ============================================================ toeschouwers

    void watch(Player player, Location spot) {
        Watcher watcher = new Watcher(player.getUniqueId(), player.getName(), Spot.of(player.getLocation()));
        watchers.put(player.getUniqueId(), watcher);
        module.store().save(new DuelStore.Pending(watcher.uuid(), watcher.name(), "spectator", watcher.returnTo(), 0, List.of(), null));
        player.closeInventory();
        module.move(player, spot).thenAccept(ok -> {
            if (!Boolean.TRUE.equals(ok)) {
                watchers.remove(player.getUniqueId());
                module.store().delete(player.getUniqueId());
                module.lang(player, "duel.teleport-failed");
                return;
            }
            module.lang(player, "duel.watch.joined", Text.p("one", one.name), Text.p("two", two.name));
        });
        Player first = player(one);
        Player second = player(two);
        for (Player duelist : new Player[] {first, second}) {
            if (duelist != null) {
                module.lang(duelist, "duel.watch.someone-watches", Text.p("player", player.getName()));
            }
        }
    }

    /** Stoppen met kijken. teleport = terug naar waar hij was. */
    void unwatch(Player player, boolean teleport) {
        Watcher watcher = watchers.remove(player.getUniqueId());
        if (watcher == null) {
            return;
        }
        if (teleport) {
            module.sendBack(player, watcher.returnTo(), List.of(), null);
        } else {
            module.store().delete(player.getUniqueId());
        }
    }

    /** Een toeschouwer logt uit: bij de volgende keer joinen terug naar waar hij was. */
    void watcherQuit(Player player) {
        Watcher watcher = watchers.remove(player.getUniqueId());
        if (watcher != null) {
            module.remember(new DuelStore.Pending(watcher.uuid(), watcher.name(), "spectator", watcher.returnTo(), 0, List.of(), null));
        }
    }

    // ============================================================ veiligstellen

    /** Wat deze speler terug moet krijgen als het nu stopt. */
    DuelStore.Pending pending(Side side) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : side.items) {
            items.add(item.clone());
        }
        for (ItemStack item : side.owed) {
            items.add(item.clone());
        }
        return new DuelStore.Pending(side.uuid, side.name, "duelist", side.returnTo, side.moneyTaken ? side.money : 0, items,
                side.prepared && settings().forceSurvival() ? side.gameMode.name() : null);
    }

    void save(Side side) {
        module.store().save(pending(side));
    }

    /** Na een wijziging in de inzet: over een seconde opslaan (niet bij elke klik). */
    private void queueSave() {
        if (saveQueued) {
            return;
        }
        saveQueued = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            saveQueued = false;
            if (phase == Phase.ENDED) {
                return;
            }
            save(one);
            save(two);
            saveData();
        }, 20L);
    }

    /**
     * Ook de inventory van de spelers meteen opslaan: anders zou na een crash een oude
     * inventory (met de ingezette items nog erin) terugkomen én de inzet terugbetaald worden.
     */
    private void saveData() {
        for (Side side : sides()) {
            Player player = player(side);
            if (player != null) {
                player.saveData();
            }
        }
    }

    // ============================================================ hulpjes

    void closeMenus() {
        closingMenus = true;
        try {
            for (Side side : sides()) {
                Player player = player(side);
                if (player == null) {
                    continue;
                }
                Object holder = player.getOpenInventory().getTopInventory().getHolder(false);
                if ((side.wagerMenu != null && holder == side.wagerMenu) || (side.rematchMenu != null && holder == side.rematchMenu)) {
                    player.closeInventory();
                }
            }
        } finally {
            closingMenus = false;
        }
    }

    private void cancelTimers() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (warning != null) {
            warning.cancel();
            warning = null;
        }
    }

    private void both(String key, TagResolver... resolvers) {
        for (Side side : sides()) {
            Player player = player(side);
            if (player != null) {
                module.lang(player, key, resolvers);
            }
        }
    }

    private void watchersSend(String key, TagResolver... resolvers) {
        for (Watcher watcher : watchers.values()) {
            Player player = plugin.getServer().getPlayer(watcher.uuid());
            if (player != null) {
                module.lang(player, key, resolvers);
            }
        }
    }

    private void title(Player player, String titleKey, String subtitleKey, int fadeIn, int stay, int fadeOut, TagResolver... resolvers) {
        Component title = module.component(player, titleKey, resolvers);
        Component subtitle = module.component(player, subtitleKey, resolvers);
        player.showTitle(Title.title(title, subtitle,
                Title.Times.times(Duration.ofMillis(fadeIn), Duration.ofMillis(stay), Duration.ofMillis(fadeOut))));
    }

    /** Laat een item vallen dat alleen deze speler kan oppakken (als zijn inventory vol is). */
    static void dropFor(Player player, ItemStack item) {
        Item dropped = player.getWorld().dropItem(player.getLocation(), item);
        dropped.setOwner(player.getUniqueId());
        dropped.setThrower(player.getUniqueId());
        dropped.setCanMobPickup(false);
    }
}
