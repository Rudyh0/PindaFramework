package nl.pinda.framework.economy;

import java.util.Locale;
import java.util.UUID;

/** Gebruikt zolang er geen economy-module is: alles is gratis. */
public final class DisabledEconomy implements Economy {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean has(UUID player, double amount) {
        return true;
    }

    @Override
    public boolean withdraw(UUID player, double amount) {
        return true;
    }

    @Override
    public void deposit(UUID player, double amount) {
    }

    @Override
    public String format(double amount) {
        return String.format(Locale.ROOT, "%.2f", amount);
    }
}
