package edu.campus.print.payment;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Can a "money received" message pay an order by itself?
 *
 * The shop's phone forwards what it receives, and other people can make that
 * phone receive things: anyone can text it "Rs 20.01 credited to your a/c",
 * a ledger app can text it "you received Rs 20.01" on a stranger's say-so, and
 * in Google Pay, PhonePe or Paytm anyone can send the shop a chat message.
 * If such a message could pay an order, printing would be free.
 *
 * So a message counts by itself only when nobody but the bank or the payment
 * company can have written it:
 *
 *   SMS            from a sender NAME the bank owns ("AX-SBIUPI"), never from a
 *                  phone number. Sender names are registered with the telecom
 *                  operators; the well-known banks are listed here, others are
 *                  added with UPI_SMS_SENDERS or confirmed once at the counter.
 *   notification   from a business UPI app (PhonePe Business, Paytm for
 *                  Business, Google Pay for Business, BharatPe). These have no
 *                  chat: only the payment company's servers write to them.
 *   counter        pasted by staff, who are signed in.
 *
 * Every other message is kept for staff to see, with the reason, and pays nothing.
 */
public final class AlertTrust {

    /** trusted: may pay an order by itself. senderKey: the bank sender name staff could confirm, or null. */
    public record Verdict(boolean trusted, String note, String senderKey) {}

    /** Business UPI apps: no person can post a notification into these. */
    static final Set<String> BUSINESS_APPS = Set.of(
            "com.phonepe.app.business",
            "com.paytm.business",
            "com.google.android.apps.nbu.paisa.merchant",
            "com.bharatpe.app");

    /** Sender names of Indian banks' account messages, without the operator prefix ("AX-SBIUPI" is "SBIUPI"). */
    static final Set<String> BANK_SENDERS = Set.of(
            "SBIUPI", "SBIINB", "SBIPSG", "SBIBNK", "CBSSBI", "ATMSBI",
            "HDFCBK", "HDFCBN", "ICICIB", "ICICIT", "AXISBK", "KOTAKB",
            "PNBSMS", "BOBSMS", "BOBTXN", "BOIIND", "CANBNK", "UNIONB",
            "IDFCFB", "INDUSB", "YESBNK", "FEDBNK", "IDBIBK", "IOBCHN",
            "CENTBK", "UCOBNK", "MAHABK", "INDBNK", "PSBANK", "AUBANK",
            "RBLBNK", "DBSBNK", "SIBSMS", "KVBANK", "TMBANK", "CUBANK",
            "KBLBNK", "JKBANK", "BANDHN", "EQUTAS", "UJJIVN", "SARBNK",
            "PAYTMB", "AIRBNK", "IPBMSG", "FINOBK");

    private static final Pattern PREFIX = Pattern.compile("^[A-Z]{2}-(.+)$");
    private static final Pattern SUFFIX = Pattern.compile("^(.+)-[A-Z]$");
    private static final Pattern HEADER = Pattern.compile("[A-Z0-9]{3,11}");
    private static final Pattern LETTERS = Pattern.compile("[A-Z]");

    private AlertTrust() {
    }

    /**
     * The sender name of an SMS without the operator's prefix and the kind
     * suffix: "AX-SBIUPI" and "JD-SBIUPI-S" are both "SBIUPI". Null for a phone
     * number, or anything else that is not a registered sender name.
     */
    public static String senderKey(String sender) {
        if (sender == null) return null;
        String s = sender.trim().toUpperCase(Locale.ROOT);
        Matcher m = PREFIX.matcher(s);
        if (m.matches()) s = m.group(1);
        m = SUFFIX.matcher(s);
        if (m.matches()) s = m.group(1);
        if (!HEADER.matcher(s).matches()) return null;      // "+91 98765 43210", "9876543210", anything odd
        int letters = 0;
        Matcher l = LETTERS.matcher(s);
        while (l.find()) letters++;
        return letters >= 3 ? s : null;                     // short codes and numbers are not bank names
    }

    /**
     * @param source       "sms", "notification:<app package>", "counter", or a forwarder's own word (read like an SMS)
     * @param sender       the SMS sender, or the notification's title
     * @param extraSenders bank sender names from UPI_SMS_SENDERS and those staff confirmed (already upper case keys)
     * @param extraApps    more app packages from UPI_TRUSTED_APPS
     */
    public static Verdict check(String source, String sender, Set<String> extraSenders, Set<String> extraApps) {
        String src = source == null ? "sms" : source.trim();
        if (src.equals("counter")) {
            return new Verdict(true, null, null);
        }
        if (src.startsWith("notification")) {
            String app = src.contains(":") ? src.substring(src.indexOf(':') + 1).trim() : "";
            if (BUSINESS_APPS.contains(app) || extraApps.contains(app)) return new Verdict(true, null, null);
            return new Verdict(false, "From an app where anyone can send the shop a message, so it does not count by "
                    + "itself. A business UPI app (PhonePe Business, Paytm for Business, Google Pay for Business, "
                    + "BharatPe) confirms payments automatically.", null);
        }
        String key = senderKey(sender);
        if (key == null) {
            return new Verdict(false, "Not sent under a bank's sender name (it came from a phone number). It does "
                    + "not count: anyone can text these words.", null);
        }
        if (BANK_SENDERS.contains(key) || extraSenders.contains(key)) return new Verdict(true, null, key);
        return new Verdict(false, "From " + key + ", which is not known as your bank's sender name. If this is "
                + "your bank, press \"This is our bank\": its messages then confirm payments by themselves.", key);
    }

    /** "SBIUPI, ax-hdfcbk" -> [SBIUPI, HDFCBK]. Entries that are not sender names are left out. */
    public static Set<String> senderKeys(String commaSeparated) {
        Set<String> out = new LinkedHashSet<>();
        if (commaSeparated == null) return out;
        for (String part : commaSeparated.split("[,;\\s]+")) {
            String key = senderKey(part);
            if (key != null) out.add(key);
        }
        return out;
    }

    /** "com.example.upi, com.other" -> the app packages. */
    public static Set<String> apps(String commaSeparated) {
        Set<String> out = new LinkedHashSet<>();
        if (commaSeparated == null) return out;
        for (String part : commaSeparated.split("[,;\\s]+")) {
            if (part.matches("[A-Za-z0-9_.]{3,100}")) out.add(part);
        }
        return out;
    }
}
