package edu.campus.print.payment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a bank's "money received" message: an SMS from the bank, or a
 * notification from a UPI business app. Every bank words it differently, e.g.
 *
 *   "Dear UPI user A/C X1234 credited by Rs.10.03 on 29Sep26 trf from RAHUL Refno 627312345678 -SBI"
 *   "Money Received - INR 10.03 in HDFC Bank A/c xx1234 on 29-09-26 from VPA rahul@okaxis (UPI 627312345678)"
 *   "Received Rs.10.03 in your Kotak Bank a/c XX1234 from rahul@ybl on 29-09-26. UPI Ref:627312345678."
 *
 * so it looks for what they all have: a credit word, an amount, and the
 * 12-digit UPI reference number (UTR / RRN). A message that says money went
 * OUT, an OTP, or a payment request is never read as a payment.
 *
 * Nothing here decides an order is paid: XeoGo Pay only pays an order when
 * this amount matches it exactly (see upi_match_alert in db/setup.sql).
 */
public final class BankAlertParser {

    public enum Kind { CREDIT, DEBIT, OTHER }

    /** amountPaise and refs are empty unless kind is CREDIT or DEBIT. */
    public record Parsed(Kind kind, Integer amountPaise, List<String> refs, String maskedText) {
        public boolean isCredit() {
            return kind == Kind.CREDIT && amountPaise != null && amountPaise > 0;
        }
    }

    private static final Pattern IGNORE = Pattern.compile(
            "\\b(otp|one[ -]?time[ -]?password|verification code|requested|request of|collect request"
            + "|is requesting|has requested|payment request|mandate|autopay|e-?mandate|failed|declined|reversed"
            + "|will be credited|to be credited)\\b");
    private static final Pattern CREDIT = Pattern.compile(
            "\\b(credited|credit of|received|deposited|added to|paid you|sent you|cr\\.?)(?![a-z])");
    private static final Pattern DEBIT = Pattern.compile(
            "\\b(debited|debit of|dr\\.?|withdrawn|spent|sent|paid to|paid rs|paid inr|transferred to|deducted"
            + "|purchase)(?![a-z])");
    /** Rs 10 · Rs.10.03 · INR 1,250.00 · ₹10.03 · Rs:10 · rupees 10 */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:(?<![a-z])(?:rs\\.?|inr|rupees)|₹)\\s*[:.]?\\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern BALANCE_BEFORE = Pattern.compile(
            "(bal|balance|avl|available|limit|total due|min due)[^0-9]{0,14}$");
    private static final Pattern REF = Pattern.compile("(?<![0-9+])([0-9]{12})(?![0-9])");
    private static final int MAX_TEXT = 600;

    private BankAlertParser() {
    }

    public static Parsed parse(String raw) {
        String text = clean(raw);
        String lower = text.toLowerCase(Locale.ROOT);
        String masked = maskBalances(text);
        if (lower.isEmpty() || IGNORE.matcher(lower).find()) {
            return new Parsed(Kind.OTHER, null, List.of(), masked);
        }
        Matcher credit = CREDIT.matcher(lower);
        Matcher debit = DEBIT.matcher(lower);
        int c = credit.find() ? credit.start() : -1;
        int d = debit.find() ? debit.start() : -1;
        Kind kind;
        if (c < 0 && d < 0) {
            kind = Kind.OTHER;
        } else if (c >= 0 && (d < 0 || c <= d)) {
            kind = Kind.CREDIT;           // "A/c credited by Rs 10 ... debited from payer" is still money in
        } else {
            kind = Kind.DEBIT;            // "Rs 10 debited from your a/c ... credited to rahul@ybl" is money out
        }
        Integer amount = amount(lower);
        if (kind == Kind.OTHER || amount == null) {
            return new Parsed(kind, amount, List.of(), masked);
        }
        return new Parsed(kind, amount, refs(text), masked);
    }

    /** The first amount that is not a balance or a limit, in paise. */
    static Integer amount(String lower) {
        Matcher m = AMOUNT.matcher(lower);
        while (m.find()) {
            String before = lower.substring(Math.max(0, m.start() - 24), m.start());
            if (BALANCE_BEFORE.matcher(before).find()) continue;
            try {
                BigDecimal rupees = new BigDecimal(m.group(1).replace(",", ""));
                BigDecimal paise = rupees.movePointRight(2);
                if (paise.signum() <= 0 || paise.compareTo(BigDecimal.valueOf(100_000_000L)) > 0) continue;
                return paise.intValueExact();
            } catch (ArithmeticException | NumberFormatException e) {
                // not an amount after all
            }
        }
        return null;
    }

    /** Every 12-digit number: UPI references (UTR / RRN) are 12 digits. */
    static List<String> refs(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = REF.matcher(text);
        while (m.find()) out.add(m.group(1));
        return new ArrayList<>(out);
    }

    /** Hides the account balance: the counter screen shows these messages to staff. */
    static String maskBalances(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.length() != text.length()) return text;
        Matcher m = AMOUNT.matcher(lower);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String before = lower.substring(Math.max(0, m.start() - 24), m.start());
            if (!BALANCE_BEFORE.matcher(before).find()) continue;
            out.append(text, last, m.start(1)).append("***");
            last = m.end(1);
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private static String clean(String raw) {
        if (raw == null) return "";
        String s = raw.replace(' ', ' ').replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}
