package edu.campus.print.payment;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Who students pay with XeoGo Pay: the Xerox center's UPI ID.
 *
 * UPI_ID in backend/.env is either a UPI ID ("xeroxshop@okaxis") or the whole
 * text of the shop's UPI QR code ("upi://pay?pa=...&pn=...&mc=5411..."; scan
 * the QR with Google Lens and copy the text). From a QR code the name and the
 * merchant code are taken too. A merchant code (mc) marks a business UPI ID,
 * which UPI apps accept from payment links more readily than a personal one.
 *
 * The money goes straight from the student's bank to the account behind this
 * UPI ID. No bank account number or IFSC is ever needed here.
 */
public record UpiPayee(String vpa, String name, String merchantCode) {

    private static final Pattern VPA = Pattern.compile("[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9.-]{1,64}");
    private static final Pattern MCC = Pattern.compile("[0-9]{4}");

    /** Reads UPI_ID / UPI_NAME / UPI_MERCHANT_CODE. Throws with a clear message if the UPI ID is not usable. */
    public static UpiPayee from(String upiId, String name, String merchantCode) {
        String id = upiId == null ? "" : upiId.trim();
        String vpa = id;
        String qrName = null;
        String qrCode = null;
        if (id.toLowerCase(Locale.ROOT).startsWith("upi://")) {
            Map<String, String> q = query(id);
            vpa = q.getOrDefault("pa", "");
            qrName = q.get("pn");
            qrCode = q.get("mc");
        }
        if (!VPA.matcher(vpa).matches()) {
            throw new IllegalStateException("PAYMENT_MODE is upi but UPI_ID is not a UPI ID. Put your UPI ID in "
                    + "backend/.env (on Render: your service -> Environment), e.g. UPI_ID=xeroxshop@okaxis, or paste "
                    + "the text of your shop's UPI QR code (upi://pay?pa=...). See docs/campuspay-upi.md.");
        }
        String n = clean(firstNonBlank(name, qrName), 50);
        String code = firstNonBlank(merchantCode, qrCode);
        code = code == null ? null : code.trim();
        // "0000" is what personal UPI QR codes carry: not a merchant.
        if (code != null && (!MCC.matcher(code).matches() || code.equals("0000"))) code = null;
        return new UpiPayee(vpa.toLowerCase(Locale.ROOT), n.isEmpty() ? null : n, code);
    }

    public boolean merchant() {
        return merchantCode != null;
    }

    /** The name UPI apps show; the shop's name if none was configured. */
    public String displayName(String fallback) {
        if (name != null) return name;
        String f = clean(fallback, 50);
        return f.isEmpty() ? "Xerox Center" : f;
    }

    /**
     * The payment link every UPI app understands (NPCI UPI linking spec):
     * upi://pay?pa=..&pn=..&am=10.03&cu=INR&tn=...
     * A business UPI ID also gets its merchant code and a transaction reference
     * (tr), which UPI apps require for payments to merchants.
     */
    public String uri(String payeeName, int amountPaise, String note, String transactionRef) {
        StringBuilder b = new StringBuilder("upi://pay?pa=").append(encode(vpa))
                .append("&pn=").append(encode(payeeName));
        if (merchant()) {
            b.append("&mc=").append(merchantCode).append("&tr=").append(encode(transactionRef));
        }
        b.append("&tn=").append(encode(clean(note, 40)))
         .append("&am=").append(amountText(amountPaise))
         .append("&cu=INR");
        return b.toString();
    }

    /** 1003 -> "10.03". UPI apps want exactly two decimals. */
    public static String amountText(int paise) {
        return (paise / 100) + "." + String.format("%02d", paise % 100);
    }

    /** Letters, digits, spaces and . - & only: some UPI apps refuse anything else in names and notes. */
    static String clean(String s, int max) {
        if (s == null) return "";
        String c = s.replaceAll("[^A-Za-z0-9 .&-]", " ").replaceAll("\\s+", " ").trim();
        return c.length() > max ? c.substring(0, max).trim() : c;
    }

    /** Percent-encoding for a UPI link: everything but letters, digits, - . _ ~ and @. */
    static String encode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == '@') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private static Map<String, String> query(String uri) {
        Map<String, String> out = new LinkedHashMap<>();
        int q = uri.indexOf('?');
        if (q < 0) return out;
        for (String part : uri.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(part.substring(0, eq).toLowerCase(Locale.ROOT),
                        URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                out.put(part.substring(0, eq).toLowerCase(Locale.ROOT), part.substring(eq + 1));
            }
        }
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b != null && !b.isBlank() ? b : null;
    }
}
