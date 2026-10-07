package nl.pinda.framework.modules.economy;

import java.util.UUID;

/**
 * Het saldo van één speler. Bedragen staan in centen (100 = 1 PindaCredit),
 * zodat er nooit afrondingsfouten ontstaan.
 */
public final class Account {

    private final UUID uuid;
    private long cash;
    private long bank;
    private int playtime;

    public Account(UUID uuid, long cash, long bank, int playtime) {
        this.uuid = uuid;
        this.cash = cash;
        this.bank = bank;
        this.playtime = playtime;
    }

    public UUID uuid() {
        return uuid;
    }

    public synchronized long cash() {
        return cash;
    }

    public synchronized long bank() {
        return bank;
    }

    public synchronized long total() {
        return cash + bank;
    }

    /** Minuten gespeeld richting de volgende online-bonus. */
    public synchronized int playtime() {
        return playtime;
    }

    synchronized void cash(long cash) {
        this.cash = cash;
    }

    synchronized void bank(long bank) {
        this.bank = bank;
    }

    synchronized void playtime(int playtime) {
        this.playtime = playtime;
    }

    synchronized void addCash(long amount) {
        this.cash += amount;
    }

    synchronized void addBank(long amount) {
        this.bank += amount;
    }
}
