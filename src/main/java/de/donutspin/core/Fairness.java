package de.donutspin.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Commit-Reveal: Vor der Runde wird SHA-256(seed) veroeffentlicht, nach der Runde der Seed selbst.
 * Wurf eines Spielers = erste 8 Bytes von SHA-256(seed || ":" || spielerUUID) als vorzeichenlose Zahl mod 10000.
 */
public final class Fairness {
    private Fairness() {}

    private static final SecureRandom RNG = new SecureRandom();

    public static byte[] newSeed() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return b;
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    public static String commit(byte[] seed) {
        return hex(sha().digest(seed));
    }

    public static int roll(byte[] seed, String playerId) {
        MessageDigest md = sha();
        md.update(seed);
        md.update((":" + playerId).getBytes(StandardCharsets.UTF_8));
        byte[] h = md.digest();
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xFF);
        return (int) Long.remainderUnsigned(v, PayoutTable.TOTAL);
    }
}
