package de.donutspin.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public final class Amounts {
    private Amounts() {}

    /** "1m", "2,5M", "500M", "1.000.000", "3b" -> Punkte; -1 bei ungueltiger Eingabe. */
    public static long parse(String in) {
        if (in == null) return -1;
        String s = in.trim().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        if (s.isEmpty()) return -1;
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') mult = 1_000L;
        else if (last == 'm') mult = 1_000_000L;
        else if (last == 'b') mult = 1_000_000_000L;
        if (mult != 1) {
            s = s.substring(0, s.length() - 1).replace(',', '.');
        } else {
            s = s.replace(".", "").replace(",", "");
        }
        try {
            BigDecimal v = new BigDecimal(s).multiply(BigDecimal.valueOf(mult)).setScale(0, RoundingMode.DOWN);
            if (v.signum() <= 0 || v.compareTo(BigDecimal.valueOf(Long.MAX_VALUE / 4)) > 0) return -1;
            return v.longValueExact();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static String fmt(long v) {
        if (v < 0) return "-" + fmt(-v);
        if (v >= 1_000_000_000L) return scaled(v, 1_000_000_000L, "B");
        if (v >= 1_000_000L) return scaled(v, 1_000_000L, "M");
        if (v >= 1_000L) return scaled(v, 1_000L, "K");
        return Long.toString(v);
    }

    private static String scaled(long v, long unit, String suffix) {
        return BigDecimal.valueOf(v).divide(BigDecimal.valueOf(unit), 2, RoundingMode.DOWN)
            .stripTrailingZeros().toPlainString() + suffix;
    }

    public static String signed(long v) {
        return (v > 0 ? "+" : "") + fmt(v);
    }

    public static String full(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', '.');
    }
}
