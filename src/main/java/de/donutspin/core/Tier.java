package de.donutspin.core;

import java.math.BigDecimal;

/** Eine Gewinnstufe: Multiplikator (in Zehnteln) und Gewicht (von 10000). */
public final class Tier {
    public final int index;
    public final int mult10;   // 24x = 240, 0.1x = 1
    public final int weight;   // Wahrscheinlichkeit in 1/10000 (330 = 3,30 %)
    public final int color;    // 0xRRGGBB

    Tier(int index, int mult10, int weight, int color) {
        this.index = index;
        this.mult10 = mult10;
        this.weight = weight;
        this.color = color;
    }

    public String label() {
        int whole = mult10 / 10, frac = mult10 % 10;
        return (frac == 0 ? Integer.toString(whole) : whole + "." + frac) + "x";
    }

    public String chance() {
        return BigDecimal.valueOf(weight).movePointLeft(2).stripTrailingZeros().toPlainString() + "%";
    }
}
