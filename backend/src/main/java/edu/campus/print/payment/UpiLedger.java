package edu.campus.print.payment;

import edu.campus.print.common.Secrets;
import edu.campus.print.config.PaymentProperties;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * XeoGo Pay's books: which order was offered which amount, who said "I have
 * paid", which bank messages arrived and which order each one paid.
 *
 * The rules themselves live in the database (upi_* functions in db/setup.sql)
 * and all take one lock, so they hold even with two backends running. This
 * class only calls them, and decides which messages can be believed without
 * a person looking (AlertTrust): the others are kept for staff and pay nothing.
 */
@Component
public class UpiLedger {

    private static final Logger log = LoggerFactory.getLogger(UpiLedger.class);

    private final JdbcTemplate jdbc;
    private final Set<String> configuredSenders;
    private final Set<String> configuredApps;

    public UpiLedger(JdbcTemplate jdbc, PaymentProperties props) {
        this.jdbc = jdbc;
        this.configuredSenders = AlertTrust.senderKeys(props.upiSmsSenders());
        this.configuredApps = AlertTrust.apps(props.upiTrustedApps());
    }

    /** What upi_start_payment fixed for an order. */
    public record Started(int amountPaise, String reference, Instant startedAt, int tagPaise) {}

    /**
     * A bank message as it was stored, and the order it paid (if any).
     * trusted: it may pay an order by itself; trustNote: why not.
     * senderKey: the bank sender name staff can confirm ("This is our bank"), or null.
     */
    public record Alert(UUID id, Instant receivedAt, String source, String sender, String message, Integer amountPaise,
                        List<String> refs, UUID orderId, String pickupCode, Instant matchedAt, String matchMethod,
                        boolean trusted, String trustNote, String senderKey) {}

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

    /** device: the XeoGo Pay Verifier phone that forwarded it (its "last message" time is kept). */
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
        AlertTrust.Verdict trust = AlertTrust.check(source, who, knownSenders(), configuredApps);
        List<UUID> inserted = jdbc.query("""
                insert into payment_alerts (source, sender, message, message_hash, kind, amount_paise, refs,
                                            trusted, trust_note)
                values (?, ?, ?, ?, 'CREDIT', ?, ?::text[], ?, ?)
                on conflict (message_hash) do nothing
                returning id
                """, (rs, i) -> rs.getObject(1, UUID.class), source, who.isEmpty() ? null : who, p.maskedText(),
                hash, p.amountPaise(), refs, trust.trusted(), trust.note());
        if (inserted.isEmpty()) {
            Alert old = jdbc.query(ALERTS + " where a.message_hash = ?", this::alert, hash).stream().findFirst()
                    .orElse(null);
            return new Received(true, true, p, old);
        }
        UUID id = inserted.get(0);
        UUID paid = jdbc.queryForObject("select upi_match_alert(?, ?)", UUID.class, id, windowMinutes);
        Alert a = jdbc.query(ALERTS + " where a.id = ?", this::alert, id).get(0);
        if (paid != null) {
            log.info("XeoGo Pay: {} received, order {} paid ({})", rupees(p.amountPaise()), a.pickupCode(),
                    a.matchMethod());
        } else if (!trust.trusted()) {
            log.warn("XeoGo Pay: a message about {} from {} ({}) is NOT counted: {}", rupees(p.amountPaise()), source,
                    who.isEmpty() ? "no sender" : who, trust.note());
        } else {
            log.info("XeoGo Pay: {} received from {}, no order matched yet", rupees(p.amountPaise()), source);
        }
        return new Received(true, false, p, a);
    }

    // ------------------------------------------------------------------ which bank senders are believed

    /** Sender names from UPI_SMS_SENDERS and those staff confirmed at the counter. */
    private Set<String> knownSenders() {
        Set<String> all = new LinkedHashSet<>(configuredSenders);
        all.addAll(jdbc.queryForList("select sender from payment_senders", String.class));
        return all;
    }

    /** The bank sender names staff confirmed, newest first. */
    public List<String> confirmedSenders() {
        return jdbc.queryForList("select sender from payment_senders order by added_at desc", String.class);
    }

    /**
     * Staff say "this is our bank" about one message. Its sender name is
     * believed from now on, and messages from it that were waiting are read
     * again: each pays the order it proves. Empty: that message has no sender
     * name that can be believed (it came from a phone number, or from an app).
     */
    public Optional<String> trustSenderOf(UUID alertId, int windowMinutes) {
        List<Alert> found = jdbc.query(ALERTS + " where a.id = ?", this::alert, alertId);
        if (found.isEmpty() || found.get(0).source().startsWith("notification")) return Optional.empty();
        String key = AlertTrust.senderKey(found.get(0).sender());
        if (key == null) return Optional.empty();
        jdbc.update("insert into payment_senders (sender) values (?) on conflict (sender) do nothing", key);
        log.warn("Counter confirmed the bank SMS sender {}: its messages now confirm payments by themselves", key);
        List<Alert> waiting = jdbc.query(ALERTS + """
                 where not a.trusted and a.order_id is null and a.kind = 'CREDIT'
                   and a.received_at > now() - interval '3 days' and a.source not like 'notification%'
                 order by a.received_at
                """, this::alert);
        for (Alert w : waiting) {
            if (!key.equals(AlertTrust.senderKey(w.sender()))) continue;
            jdbc.update("update payment_alerts set trusted = true, trust_note = null where id = ?", w.id());
            UUID paid = jdbc.queryForObject("select upi_match_alert(?, ?)", UUID.class, w.id(), windowMinutes);
            if (paid != null) log.info("XeoGo Pay: {} from {} now counted, an order was paid", rupees(w.amountPaise()), key);
        }
        return Optional.of(key);
    }

    /** Staff take a sender name back. Messages already counted stay as they are. */
    public boolean forgetSender(String sender) {
        String key = AlertTrust.senderKey(sender);
        return key != null && jdbc.update("delete from payment_senders where sender = ?", key) == 1;
    }

    /** A XeoGo Pay Verifier phone. sms / notifications: what it is allowed to read. */
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
                   o.pickup_code, a.matched_at, a.match_method, a.trusted, a.trust_note
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
        String source = rs.getString("source");
        boolean trusted = rs.getBoolean("trusted");
        // Only an SMS with a sender NAME can be confirmed by staff; a phone number or an app never.
        String senderKey = trusted || source == null || source.startsWith("notification") || source.equals("counter")
                ? null : AlertTrust.senderKey(rs.getString("sender"));
        return new Alert(rs.getObject("id", UUID.class), instant(rs, "received_at"), source,
                rs.getString("sender"), rs.getString("message"), amount, refs,
                rs.getObject("order_id", UUID.class), rs.getString("pickup_code"), instant(rs, "matched_at"),
                rs.getString("match_method"), trusted, rs.getString("trust_note"), senderKey);
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
