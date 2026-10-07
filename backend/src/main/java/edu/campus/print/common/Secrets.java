package edu.campus.print.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random keys, hashes and order numbers. */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** No 0/O, 1/I, 5/S, 8/B, 2/Z: easy to read out loud at a busy counter. */
    private static final char[] PICKUP_ALPHABET = "ACDEFHJKLMNPRTUVWXY3479".toCharArray();

    private Secrets() {
    }

    /** A 256-bit random key, safe to put in a header. */
    public static String newKey() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String newPickupCode() {
        char[] c = new char[5];
        for (int i = 0; i < c.length; i++) {
            c[i] = PICKUP_ALPHABET[RANDOM.nextInt(PICKUP_ALPHABET.length)];
        }
        return new String(c);
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Compares two strings in constant time, so timing reveals nothing. */
    public static boolean sameText(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
