package de.donutspin.core;

/**
 * Die komplette, oeffentliche Auszahlungstabelle. Es gibt keine weiteren Gewichtungen
 * irgendwo im Code: jeder Spin ist ein Wurf 0..9999, der hier einer Stufe zugeordnet wird.
 */
public final class PayoutTable {
    private PayoutTable() {}

    public static final int TOTAL = 10_000;

    public static final Tier[] TIERS = {
        new Tier(0,   0,  330, 0xE04A4A),
        new Tier(1,   1, 3000, 0x8A94A0),
        new Tier(2,   4, 2200, 0x2FC4D6),
        new Tier(3,  10, 2000, 0x3F7BE8),
        new Tier(4,  12, 1200, 0x2FD6A0),
        new Tier(5,  15,  663, 0x4ADE5A),
        new Tier(6,  20,   50, 0xB6F03C),
        new Tier(7,  40,  400, 0xFF5C8A),
        new Tier(8,  70,  120, 0xA45CFF),
        new Tier(9, 120,   30, 0xFFC83D),
        new Tier(10, 240,   7, 0xFF4FD8),
    };

    /** Reihenfolge der Karten auf der Walze (nur Optik, hat keinen Einfluss auf Wahrscheinlichkeiten). */
    public static final int[] REEL_ORDER = {0, 6, 1, 2, 3, 4, 5, 7, 8, 9, 10};

    private static final int[] CUM = new int[TIERS.length];

    static {
        int sum = 0;
        for (int i = 0; i < TIERS.length; i++) {
            sum += TIERS[i].weight;
            CUM[i] = sum;
        }
        if (sum != TOTAL) throw new IllegalStateException("Gewichte ergeben " + sum + " statt " + TOTAL);
    }

    public static Tier forRoll(int roll) {
        if (roll < 0 || roll >= TOTAL) throw new IllegalArgumentException("roll " + roll);
        for (int i = 0; i < CUM.length; i++) if (roll < CUM[i]) return TIERS[i];
        throw new IllegalStateException();
    }

    /** Auszahlung = Einsatz * Multiplikator, auf ganze Punkte abgerundet. */
    public static long payout(long stake, int tier) {
        return stake * TIERS[tier].mult10 / 10;
    }

    /** Summe(Gewicht * mult10); Rueckzahlung = Wert / (TOTAL * 10). */
    public static long rtpNumerator() {
        long s = 0;
        for (Tier t : TIERS) s += (long) t.weight * t.mult10;
        return s;
    }

    public static double rtpPercent() {
        return rtpNumerator() * 100.0 / (TOTAL * 10.0);
    }
}
