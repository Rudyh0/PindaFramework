package nl.pinda.framework.economy;

import java.util.UUID;

/**
 * Koppelpunt voor geld. De economy-module levert later de echte implementatie;
 * tot die tijd is {@link DisabledEconomy} actief en kost niets geld.
 */
public interface Economy {

    /** True als er een werkende economy is. */
    boolean isEnabled();

    boolean has(UUID player, double amount);

    /** Haalt geld af. Geeft false als dat niet lukt (bijv. te weinig saldo). */
    boolean withdraw(UUID player, double amount);

    void deposit(UUID player, double amount);

    /** Een bedrag als tekst, bijvoorbeeld "€ 5,00". */
    String format(double amount);
}
