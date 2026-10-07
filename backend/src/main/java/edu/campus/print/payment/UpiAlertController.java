package edu.campus.print.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.print.common.ApiException;
import edu.campus.print.common.ClientIp;
import edu.campus.print.common.Secrets;
import edu.campus.print.config.PaymentProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XeoGo Pay's bank connection: the Xerox center's phone (the one that gets
 * the bank's SMS for the UPI account) forwards each "money received" SMS or
 * UPI-app notification here, and the order it proves is paid at once.
 *
 *   POST /api/v1/payments/upi/alerts
 *   header  X-Alert-Token: <UPI_ALERT_TOKEN>   (or ?token=..., for apps without headers)
 *   body    {"from": "AX-SBIUPI", "text": "...credited by Rs.10.03 ... Ref 627312345678", "sentStamp": 1790000000000,
 *            "source": "sms" | "notification:com.phonepe.app.business", "device": "Shop phone"}
 *           Also accepted: message / body / msg / content / sms for the text; sender / address for the
 *           sender; timestamp / receivedStamp for the time; or the plain text as the whole body.
 *
 *   POST /api/v1/payments/upi/heartbeat   {"device", "version", "sms", "notifications"}
 *           the Verifier phone is alive: while it is, payments confirm by themselves.
 *
 * The XeoGo Pay Verifier app (android/verifier) does both. Anything else that
 * can send an HTTP POST works too (an SMS forwarder app, an iPhone Shortcuts
 * automation). Only credit messages are kept; OTPs and every other SMS are
 * dropped unread.
 *
 * Not every message is believed: other people can make the shop's phone
 * receive an SMS or a chat message that says "Rs 20.01 received". Only an SMS
 * from the bank's own sender name, or a notification of a business UPI app,
 * pays an order by itself (AlertTrust); the rest waits for staff.
 */
@RestController
@RequestMapping("/api/v1/payments/upi")
public class UpiAlertController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FAILURES_PER_10_MIN = 20;
    private static final List<String> TEXT_FIELDS = List.of("text", "message", "body", "msg", "content", "sms");
    private static final List<String> SENDER_FIELDS = List.of("from", "sender", "address", "title", "app");
    private static final List<String> TIME_FIELDS = List.of("sentStamp", "receivedStamp", "timestamp", "time", "date");
    private static final java.util.regex.Pattern SAFE = java.util.regex.Pattern.compile("[A-Za-z0-9 ._:-]{1,60}");

    private final PaymentProperties props;
    private final PaymentGateway gateway;
    private final UpiLedger ledger;
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private volatile long windowStart = Instant.now().getEpochSecond();

    public UpiAlertController(PaymentProperties props, PaymentGateway gateway, UpiLedger ledger) {
        this.props = props;
        this.gateway = gateway;
        this.ledger = ledger;
    }

    @PostMapping("/alerts")
    public Map<String, Object> alert(@RequestHeader(value = "X-Alert-Token", required = false) String header,
                                     @RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestParam(value = "token", required = false) String query,
                                     @RequestBody(required = false) String body,
                                     HttpServletRequest req) {
        if (!(gateway instanceof UpiGateway upi) || !props.upiAlertsOn()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ALERTS_OFF",
                    "Bank messages are not switched on. Set PAYMENT_MODE=upi and UPI_ALERT_TOKEN (24+ characters).");
        }
        checkToken(firstNonBlank(header, bearer(authorization), query), ClientIp.of(req));

        String text = body == null ? "" : body;
        String sender = null;
        String stamp = null;
        String source = "sms";
        String device = null;
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode n = JSON.readTree(trimmed);
                text = field(n, TEXT_FIELDS);
                sender = field(n, SENDER_FIELDS);
                stamp = field(n, TIME_FIELDS);
                source = safe(field(n, List.of("source")), "sms");
                device = safe(field(n, List.of("device")), null);
            } catch (Exception e) {
                throw ApiException.badRequest("BAD_ALERT", "The message is not valid JSON.");
            }
        }
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("BAD_ALERT", "No message text. Send {\"text\": \"...\"}.");
        }
        // The phone retrying the same SMS must not count twice; without its time, 10-minute windows.
        String dedupe = stamp != null ? stamp : String.valueOf(Instant.now().getEpochSecond() / 600);
        UpiLedger.Received r = ledger.receive(text, sender, source, dedupe, upi.windowMinutes(), device);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("kind", r.parsed().kind().name());
        out.put("stored", r.stored());
        out.put("duplicate", r.duplicate());
        out.put("amountPaise", r.parsed().amountPaise());
        out.put("references", r.parsed().refs());
        out.put("paidOrder", r.alert() == null ? null : r.alert().pickupCode());
        out.put("matchMethod", r.alert() == null ? null : r.alert().matchMethod());
        out.put("trusted", r.alert() == null || r.alert().trusted());
        out.put("note", r.alert() == null ? null : r.alert().trustNote());
        return out;
    }

    /** The XeoGo Pay Verifier phone is alive, and what it may read. */
    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestHeader(value = "X-Alert-Token", required = false) String header,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody(required = false) JsonNode body, HttpServletRequest req) {
        if (!(gateway instanceof UpiGateway upi) || !props.upiAlertsOn()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ALERTS_OFF",
                    "Bank messages are not switched on. Set PAYMENT_MODE=upi and UPI_ALERT_TOKEN (24+ characters).");
        }
        checkToken(firstNonBlank(header, bearer(authorization)), ClientIp.of(req));
        String device = safe(body == null ? null : field(body, List.of("device")), "Verifier phone");
        String version = safe(body == null ? null : field(body, List.of("version")), null);
        boolean sms = body != null && body.path("sms").asBoolean(false);
        boolean notifications = body != null && body.path("notifications").asBoolean(false);
        ledger.heartbeat(device, version, sms, notifications);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("payee", upi.payee().vpa());
        out.put("autoConfirm", true);
        return out;
    }

    private static String safe(String v, String fallback) {
        if (v == null || v.isBlank()) return fallback;
        String t = v.trim();
        if (t.length() > 60) t = t.substring(0, 60);
        return SAFE.matcher(t).matches() ? t : fallback;
    }

    private void checkToken(String given, String ip) {
        long now = Instant.now().getEpochSecond();
        if (now - windowStart > 600) {
            failures.clear();
            windowStart = now;
        }
        int[] count = failures.computeIfAbsent(ip, k -> new int[1]);
        if (count[0] >= MAX_FAILURES_PER_10_MIN) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many wrong tokens. Wait 10 minutes.");
        }
        if (!Secrets.sameText(props.upiAlertToken().trim(), given == null ? null : given.trim())) {
            synchronized (count) {
                count[0]++;
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_TOKEN", "Wrong alert token.");
        }
    }

    private static String field(JsonNode n, List<String> names) {
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static String bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return authorization.substring(7);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
