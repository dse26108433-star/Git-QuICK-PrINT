package edu.campus.print.payment;

import edu.campus.print.common.Secrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CampusPay's books: which order was offered which amount, who said "I have
 * paid", which bank messages arrived and which order each one paid.
 *
 * The rules themselves live in the database (upi_* functions in db/setup.sql)
 * and all take one lock, so they hold even with two backends running. This
 * class only calls them.
 */
@Component
public class UpiLedger {

    private static final Logger log = LoggerFactory.getLogger(UpiLedger.class);

    private final JdbcTemplate jdbc;

    public UpiLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What upi_start_payment fixed for an order. */
    public record Started(int amountPaise, String reference, Instant startedAt, int tagPaise) {}

    /** A bank message as it was stored, and the order it paid (if any). */
    public record Alert(UUID id, Instant receivedAt, String source, String sender, String message, Integer amountPaise,
                        List<String> refs, UUID orderId, String pickupCode, Instant matchedAt, String matchMethod) {}

    /** The answer to a forwarded or pasted message. stored = false: not a payment, so it was not kept. */
    public record Received(boolean stored, boolean duplicate, BankAlertParser.Parsed parsed, Alert alert) {}

    public Optional<Started> start(UUID orderId, String reference, int windowMinutes) {
        List<Started> rows = jdbc.query("select * from upi_start_payment(?, ?, ?)",
                (rs, i) -> new Started(rs.getInt("out_amount_paise"), rs.getString("out_reference"),
                        instant(rs, "out_started_at"), rs.getInt("out_tag_paise")),
                orderId, reference, windowMinutes);
        return rows.stream().findFirst();
    }

    /** "OK", "NOT_OPEN", "NOT_STARTED" or "REF_USED". */
    public String claim(UUID orderId, String reference) {
        return jdbc.queryForObject("select upi_claim_payment(?, ?)", String.class, orderId, reference);
    }

    /** Pays the order if a bank message that arrived earlier proves it. The payment id, if paid now. */
    public Optional<String> matchOrder(UUID orderId) {
        return Optional.ofNullable(jdbc.queryForObject("select upi_match_order(?)", String.class, orderId));
    }

    /** Staff found the money. "OK", "NOT_OPEN" or "REF_USED". */
    public String approve(UUID orderId, String reference) {
        return jdbc.queryForObject("select upi_approve_payment(?, ?)", String.class, orderId, reference);
    }

    /** Staff could not find the money: the student is told why and can try again. */
    public boolean reject(UUID orderId, String note) {
        return jdbc.update("""
                update orders set payment_claim_ref = null, payment_claimed_at = null, payment_note = ?
                 where id = ? and status = 'AWAITING_PAYMENT' and payment_provider = 'upi'
                """, note, orderId) == 1;
    }

    /**
     * A message from the bank (forwarded SMS, app notification, or pasted by
     * staff). Only "money received" messages are kept; anything else (OTPs,
     * money going out, other SMS) is dropped without being stored.
     * dedupeKey: the same message forwarded twice (the phone retrying) is kept once.
     */
    public Received receive(String text, String sender, String source, String dedupeKey, int windowMinutes) {
        return receive(text, sender, source, dedupeKey, windowMinutes, null);
    }

    /** device: the CampusPay Verifier phone that forwarded it (its "last message" time is kept). */
    public Received receive(String text, String sender, String source, String dedupeKey, int windowMinutes, String device) {
        if (device != null) {
            boolean bySms = "sms".equals(source);
            boolean byNotification = source != null && source.startsWith("notification");
            jdbc.update("""
                    insert into payment_verifiers (device, sms, notifications, last_seen_at, last_alert_at)
                    values (?, ?, ?, now(), now())
                    on conflict (device) do update
                       set last_seen_at = now(), last_alert_at = now(),
                           sms = payment_verifiers.sms or excluded.sms,
                           notifications = payment_verifiers.notifications or excluded.notifications
                    """, device, bySms, byNotification);
        }
        BankAlertParser.Parsed p = BankAlertParser.parse(text);
        if (!p.isCredit()) {
            return new Received(false, false, p, null);
        }
        String who = sender == null ? "" : sender.trim();
        if (who.length() > 60) who = who.substring(0, 60);
        String hash = Secrets.sha256Hex(source + "|" + who + "|" + text.trim() + "|" + dedupeKey);
        String refs = "{" + String.join(",", p.refs()) + "}";          // 12-digit numbers only
        List<UUID> inserted = jdbc.query("""
                insert into payment_alerts (source, sender, message, message_hash, kind, amount_paise, refs)
                values (?, ?, ?, ?, 'CREDIT', ?, ?::text[])
                on conflict (message_hash) do nothing
                returning id
                """, (rs, i) -> rs.getObject(1, UUID.class), source, who.isEmpty() ? null : who, p.maskedText(),
                hash, p.amountPaise(), refs);
        if (inserted.isEmpty()) {
            Alert old = jdbc.query(ALERTS + " where a.message_hash = ?", this::alert, hash).stream().findFirst()
                    .orElse(null);
            return new Received(true, true, p, old);
        }
        UUID id = inserted.get(0);
        UUID paid = jdbc.queryForObject("select upi_match_alert(?, ?)", UUID.class, id, windowMinutes);
        Alert a = jdbc.query(ALERTS + " where a.id = ?", this::alert, id).get(0);
        if (paid != null) {
            log.info("CampusPay: {} received, order {} paid ({})", rupees(p.amountPaise()), a.pickupCode(),
                    a.matchMethod());
        } else {
            log.info("CampusPay: {} received from {}, no order matched yet", rupees(p.amountPaise()), source);
        }
        return new Received(true, false, p, a);
    }

    /** A CampusPay Verifier phone. sms / notifications: what it is allowed to read. */
    public record Verifier(String device, String appVersion, boolean sms, boolean notifications, Instant lastSeenAt,
                           Instant lastAlertAt) {}

    /** The Verifier phone says it is alive (every few minutes, and when its app opens). */
    public void heartbeat(String device, String appVersion, boolean sms, boolean notifications) {
        jdbc.update("""
                insert into payment_verifiers (device, app_version, sms, notifications, last_seen_at)
                values (?, ?, ?, ?, now())
                on conflict (device) do update
                   set app_version = excluded.app_version, sms = excluded.sms,
                       notifications = excluded.notifications, last_seen_at = now()
                """, device, appVersion, sms, notifications);
    }

    public List<Verifier> verifiers() {
        return jdbc.query("select * from payment_verifiers order by last_seen_at desc limit 10", (rs, i) -> new Verifier(
                rs.getString("device"), rs.getString("app_version"), rs.getBoolean("sms"), rs.getBoolean("notifications"),
                instant(rs, "last_seen_at"), instant(rs, "last_alert_at")));
    }

    /** A Verifier phone was heard from within this many minutes: payments confirm by themselves. */
    public boolean verifierAlive(int minutes) {
        Boolean alive = jdbc.queryForObject("""
                select exists (select 1 from payment_verifiers
                                where last_seen_at > now() - make_interval(mins => ?))
                """, Boolean.class, minutes);
        return Boolean.TRUE.equals(alive);
    }

    /** The latest money-received messages, newest first. */
    public List<Alert> recentAlerts(int limit) {
        return jdbc.query(ALERTS + " order by a.received_at desc limit ?", this::alert, limit);
    }

    /** Messages not yet linked to an order, for this amount (hints for staff). */
    public List<Alert> unmatchedFor(int amountPaise, Instant since) {
        return jdbc.query(ALERTS + " where a.order_id is null and a.amount_paise = ? and a.received_at >= ?"
                + " order by a.received_at", this::alert, amountPaise, Timestamp.from(since));
    }

    public long toCheckCount() {
        Long n = jdbc.queryForObject("""
                select count(*) from orders
                 where status = 'AWAITING_PAYMENT' and payment_provider = 'upi' and payment_claimed_at is not null
                """, Long.class);
        return n == null ? 0 : n;
    }

    public Map<String, Object> todayTotals() {
        Map<String, Object> m = new LinkedHashMap<>();
        jdbc.query("""
                select count(*) as n, coalesce(sum(amount_paise), 0) as paise,
                       count(*) filter (where payment_verified_by = 'bank-alert') as auto
                  from orders
                 where payment_provider = 'upi' and paid_at > date_trunc('day', now())
                """, rs -> {
            m.put("paidOrders", rs.getInt("n"));
            m.put("paidPaise", rs.getLong("paise"));
            m.put("confirmedByBank", rs.getInt("auto"));
        });
        return m;
    }

    private static final String ALERTS = """
            select a.id, a.received_at, a.source, a.sender, a.message, a.amount_paise, a.refs, a.order_id,
                   o.pickup_code, a.matched_at, a.match_method
              from payment_alerts a left join orders o on o.id = a.order_id
            """;

    private Alert alert(ResultSet rs, int i) throws SQLException {
        Array arr = rs.getArray("refs");
        List<String> refs = new ArrayList<>();
        if (arr != null) {
            for (Object o : (Object[]) arr.getArray()) refs.add(String.valueOf(o));
        }
        int paise = rs.getInt("amount_paise");
        Integer amount = rs.wasNull() ? null : paise;
        return new Alert(rs.getObject("id", UUID.class), instant(rs, "received_at"), rs.getString("source"),
                rs.getString("sender"), rs.getString("message"), amount, refs,
                rs.getObject("order_id", UUID.class), rs.getString("pickup_code"), instant(rs, "matched_at"),
                rs.getString("match_method"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static String rupees(Integer paise) {
        if (paise == null) return "-";
        return "Rs " + UpiPayee.amountText(paise);
    }
}
