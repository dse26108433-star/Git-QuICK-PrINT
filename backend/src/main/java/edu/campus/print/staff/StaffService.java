package edu.campus.print.staff;

import edu.campus.print.common.ApiException;
import edu.campus.print.config.StaffProperties;
import edu.campus.print.domain.ShopSettings;
import edu.campus.print.domain.StaffAccount;
import edu.campus.print.repo.ShopSettingsRepository;
import edu.campus.print.repo.StaffAccountRepository;
import edu.campus.print.security.StaffSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Free printing for college staff.
 *
 * The Xerox center makes a staff ID on its own screen: a username and a
 * password, nothing else. The password is made here, shown once, and only its
 * hash is kept. The staff member signs in on the staff website or the staff
 * app and prints without paying, up to a number of pages each month (the
 * shop's usual number, or the ID's own). The month starts again on the 1st.
 *
 * What makes it hold:
 *   - passwords are made by the server (10 random characters), so none is
 *     weak, and 8 wrong ones in a row make that ID wait 15 minutes;
 *   - a new password, or switching the ID off, signs every device out at once
 *     (StaffSessions);
 *   - the month's pages are checked and the order is sent to print in ONE
 *     database step, with the ID's row locked (staff_print in db/setup.sql),
 *     so nothing can slip under the limit;
 *   - a staff order is never paid and an ordinary order is never free: the
 *     database keeps the two apart (mark_order_paid).
 */
@Service
public class StaffService {

    private static final Logger log = LoggerFactory.getLogger(StaffService.class);

    static final int MAX_WRONG_PASSWORDS = 8;
    static final int WAIT_MINUTES = 15;
    static final int MAX_ACCOUNTS = 5000;
    public static final int MAX_MONTHLY_PAGES = 100_000;

    /** No 0/o, 1/l/i: a password is read from a slip of paper and typed once. */
    private static final char[] PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final int PASSWORD_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,29}");
    private static final Set<String> TITLES = Set.of("dr", "mr", "mrs", "ms", "miss", "prof", "shri", "smt", "sir", "madam");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final StaffAccountRepository accounts;
    private final ShopSettingsRepository settings;
    private final StaffSessions sessions;
    private final StaffProperties props;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final String dummyHash;
    private final String site;

    public StaffService(StaffAccountRepository accounts, ShopSettingsRepository settings, StaffSessions sessions,
                        StaffProperties props, PasswordEncoder encoder, JdbcTemplate jdbc,
                        @Value("${campus.cors.allowed-origins:}") String webOrigins) {
        this.accounts = accounts;
        this.settings = settings;
        this.sessions = sessions;
        this.props = props;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.dummyHash = encoder.encode(newPassword());
        this.site = staffSite(webOrigins);
    }

    /**
     * The staff website's address, for the slip the Xerox center hands out: the
     * website this server is opened from (WEB_ORIGINS), the https one if there
     * are several. Null when that is not known.
     */
    static String staffSite(String webOrigins) {
        String found = null;
        for (String o : (webOrigins == null ? "" : webOrigins).split(",")) {
            String origin = o.trim();
            if (!origin.matches("https?://[A-Za-z0-9.:\\[\\]-]{1,200}")) continue;
            if (origin.startsWith("https://")) return origin + "/staff.html";
            if (found == null) found = origin + "/staff.html";
        }
        return found;
    }

    // ------------------------------------------------------------------ what the staff member sees

    /** Who is signed in and how many free pages are left this month. resetsOn: the day they start again (ISO date). */
    public record StaffView(String username, String name, int monthlyPages, int usedPages, int leftPages,
                            String month, String resetsOn, boolean colorAllowed, String centerName,
                            Instant serverTime) {}

    public record Login(String token, long expiresInSeconds, StaffView staff) {}

    /** One line of "your prints", the same on every device signed in with the ID. */
    public record StaffOrder(UUID orderId, String pickupCode, String status, String stage, String name, int documents,
                             Integer pages, Instant createdAt, Instant paidAt, Instant completedAt,
                             Instant collectedAt) {}

    /** What staff_print (db/setup.sql) answered. */
    public record PrintResult(String result, int pages, int used, int limit) {}

    /** The month whose free pages are being counted, in the shop's time. */
    public record Month(Instant start, String label, LocalDate next) {}

    public Month month() {
        LocalDate first = LocalDate.now(props.zone()).withDayOfMonth(1);
        return new Month(first.atStartOfDay(props.zone()).toInstant(), first.format(MONTH), first.plusMonths(1));
    }

    /**
     * Username and password, nothing else. Every failure reads the same, so
     * nothing here tells whether a username exists.
     */
    public Login login(String username, String password) {
        String u = username == null ? "" : username.trim();
        String p = normalPassword(password);
        StaffAccount a = u.isEmpty() || u.length() > 60 || p.length() > 60 ? null : accounts.findLive(u).orElse(null);
        boolean waiting = a != null && a.getLockedUntil() != null && a.getLockedUntil().isAfter(Instant.now());
        // The same work whether the ID exists, waits or not: how long the answer takes tells nothing either.
        boolean right = encoder.matches(p, a == null || waiting ? dummyHash : a.getPasswordHash()) && a != null && !waiting;
        if (!right) {
            if (a != null && !waiting) accounts.wrongPassword(a.getId(), MAX_WRONG_PASSWORDS, WAIT_MINUTES);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_LOGIN", "Wrong username or password. After "
                    + MAX_WRONG_PASSWORDS + " wrong tries an ID has to wait " + WAIT_MINUTES + " minutes. "
                    + "Forgot it? The Xerox center gives you a new password.");
        }
        if (!a.isActive()) throw off();
        accounts.signedIn(a.getId());
        log.info("Staff {} signed in", a.getUsername());
        return new Login(sessions.issue(a), sessions.ttlSeconds(), view(a));
    }

    /**
     * The staff member a sign-in token belongs to. 401 when the token is not
     * (or no longer) good: made up, run out, or made with a password that was
     * replaced since. 403 when the ID is switched off.
     */
    public StaffAccount session(String token) {
        StaffSessions.Claims c = token == null ? null : sessions.read(token.trim()).orElse(null);
        StaffAccount a = c == null ? null : accounts.findById(c.staffId()).orElse(null);
        if (a == null || a.getRemovedAt() != null || a.getPasswordSetAt().toEpochMilli() != c.passwordStamp()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "STAFF_SIGNED_OUT", "Please sign in again with your staff ID.");
        }
        if (!a.isActive()) throw off();
        return a;
    }

    /** No header: not a staff request (null). A header that is not good is refused, never ignored. */
    public StaffAccount optionalSession(String header) {
        return header == null || header.isBlank() ? null : session(header);
    }

    /** A fresh token once the old one is a week old, so a device in use never has to sign in again. */
    public String renewed(String token, StaffAccount a) {
        Instant expires = sessions.read(token.trim()).map(StaffSessions.Claims::expiresAt).orElse(Instant.MIN);
        long age = sessions.ttlSeconds() - Math.max(0, expires.getEpochSecond() - Instant.now().getEpochSecond());
        return age > 7 * 24 * 3600L ? sessions.issue(a) : null;
    }

    public long sessionSeconds() {
        return sessions.ttlSeconds();
    }

    public StaffView view(StaffAccount a) {
        ShopSettings s = settings.current();
        Month m = month();
        int limit = limitOf(a, s);
        int used = accounts.pagesUsed(a.getId(), m.start());
        return new StaffView(a.getUsername(), a.getName(), limit, used, Math.max(0, limit - used), m.label(),
                m.next().toString(), s.isStaffColor(), s.getCenterName(), Instant.now());
    }

    private static int limitOf(StaffAccount a, ShopSettings s) {
        return a.getMonthlyPages() != null ? a.getMonthlyPages() : s.getStaffMonthlyPages();
    }

    /** May staff print colour and on special paper for free? */
    public boolean colorAllowed() {
        return settings.current().isStaffColor();
    }

    /** Checks the month's free pages and sends the order to print, in one database step. */
    public PrintResult print(UUID orderId, StaffAccount a) {
        Map<String, Object> row = jdbc.queryForMap("select * from staff_print(?, ?, ?)", orderId, a.getId(),
                month().start().atOffset(ZoneOffset.UTC));
        return new PrintResult((String) row.get("out_result"), ((Number) row.get("out_pages")).intValue(),
                ((Number) row.get("out_used")).intValue(), ((Number) row.get("out_limit")).intValue());
    }

    /** What this ID sent to print in the last 45 days, newest first. */
    public List<StaffOrder> orders(StaffAccount a) {
        List<StaffOrder> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select o.id, o.pickup_code, o.status, o.created_at, o.paid_at, o.completed_at, o.collected_at,
                       o.staff_pages, coalesce(o.document_count, 1) as documents,
                       (select d.file_name from order_documents d where d.order_id = o.id
                         order by d.position limit 1) as first_file
                  from orders o
                 where o.staff_id = ? and o.paid_at is not null and o.paid_at > now() - interval '45 days'
                 order by o.paid_at desc
                 limit 30
                """, a.getId())) {
            String status = (String) r.get("status");
            Instant collected = instant(r.get("collected_at"));
            int documents = ((Number) r.get("documents")).intValue();
            String first = r.get("first_file") == null ? "Order" : (String) r.get("first_file");
            out.add(new StaffOrder((UUID) r.get("id"), (String) r.get("pickup_code"), status, stage(status, collected),
                    documents > 1 ? first + " + " + (documents - 1) + " more" : first, documents,
                    r.get("staff_pages") == null ? null : ((Number) r.get("staff_pages")).intValue(),
                    instant(r.get("created_at")), instant(r.get("paid_at")), instant(r.get("completed_at")), collected));
        }
        return out;
    }

    private static String stage(String status, Instant collected) {
        if (collected != null) return "Collected";
        return switch (status) {
            case "QUEUED" -> "Waiting for a printer";
            case "PRINTING" -> "Printing";
            case "COMPLETED" -> "Ready to collect";
            case "FAILED" -> "Problem – ask at the counter";
            case "CANCELLED" -> "Cancelled";
            default -> status;
        };
    }

    private static Instant instant(Object o) {
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime t) return t.toInstant();
        return o instanceof Instant i ? i : null;
    }

    private static ApiException off() {
        return new ApiException(HttpStatus.FORBIDDEN, "STAFF_OFF",
                "This staff ID is switched off. Ask at the Xerox center.");
    }

    // ------------------------------------------------------------------ what the Xerox center does

    /**
     * One staff ID on the Xerox center's list. ownMonthlyPages: null when it has the shop's usual number.
     * waiting: too many wrong passwords just now (a new password ends the wait).
     */
    public record AccountView(UUID id, String username, String name, boolean active, Integer ownMonthlyPages,
                              int monthlyPages, int usedPages, int leftPages, Instant lastPrintAt,
                              Instant lastLoginAt, Instant createdAt, boolean waiting) {}

    /** password: shown to the Xerox center now and never again. */
    public record NewPassword(AccountView account, String password) {}

    /** site: where staff sign in (the staff website), or null when the server does not know its website. */
    public record Overview(int monthlyPages, boolean colorAllowed, String month, String resetsOn,
                           List<AccountView> accounts, String site) {}

    public Overview overview() {
        ShopSettings s = settings.current();
        Month m = month();
        Map<UUID, Integer> used = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select o.staff_id, coalesce(sum(d.sides * coalesce((d.settings ->> 'copies')::int, 1)), 0)::int as used
                  from orders o
                  join order_documents d on d.order_id = o.id
                 where o.staff_id is not null and o.paid_at >= ?
                   and d.status in ('QUEUED', 'CLAIMED', 'DOWNLOADING', 'SUBMITTED', 'COMPLETED')
                 group by o.staff_id
                """, m.start().atOffset(ZoneOffset.UTC))) {
            used.put((UUID) r.get("staff_id"), ((Number) r.get("used")).intValue());
        }
        Map<UUID, Instant> last = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select staff_id, max(paid_at) as last_print from orders
                 where staff_id is not null and paid_at is not null
                 group by staff_id
                """)) {
            last.put((UUID) r.get("staff_id"), instant(r.get("last_print")));
        }
        List<AccountView> list = accounts.findAllLive().stream()
                .map(a -> describe(a, s, used.getOrDefault(a.getId(), 0), last.get(a.getId()))).toList();
        return new Overview(s.getStaffMonthlyPages(), s.isStaffColor(), m.label(), m.next().toString(), list, site);
    }

    private static AccountView describe(StaffAccount a, ShopSettings s, int used, Instant lastPrint) {
        int limit = limitOf(a, s);
        return new AccountView(a.getId(), a.getUsername(), a.getName(), a.isActive(), a.getMonthlyPages(), limit, used,
                Math.max(0, limit - used), lastPrint, a.getLastLoginAt(), a.getCreatedAt(),
                a.getLockedUntil() != null && a.getLockedUntil().isAfter(Instant.now()));
    }

    private AccountView describe(StaffAccount a) {
        return describe(a, settings.current(), accounts.pagesUsed(a.getId(), month().start()), null);
    }

    /**
     * A new staff ID. username: what the Xerox center typed, or empty to make
     * one from the name ("Prof. Rakesh Sharma" becomes "rakesh.sharma").
     */
    public NewPassword create(String name, String username, Integer monthlyPages) {
        String n = cleanName(name);
        String u = username == null || username.isBlank() ? freeUsername(n) : checkUsername(username);
        Integer pages = checkPages(monthlyPages);
        if (accounts.findLive(u).isPresent()) throw taken(u);
        if (accounts.countLive() >= MAX_ACCOUNTS) {
            throw ApiException.conflict("TOO_MANY_STAFF", "There are " + MAX_ACCOUNTS + " staff IDs already. Remove "
                    + "the ones no longer used.");
        }
        String password = newPassword();
        StaffAccount a = StaffAccount.create(u, n, encoder.encode(password), pages);
        try {
            accounts.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            throw taken(u);                       // two screens made the same username at the same moment
        }
        log.warn("Counter made staff ID {} ({})", u, n);
        return new NewPassword(describe(a, settings.current(), 0, null), pretty(password));
    }

    /** A new password for an ID (the old one stops working, every device is signed out, any wait is over). */
    public NewPassword resetPassword(UUID id) {
        StaffAccount a = live(id);
        String password = newPassword();
        a.setPassword(encoder.encode(password));
        accounts.saveAndFlush(a);
        jdbc.update("update staff_accounts set failed_logins = 0, locked_until = null where id = ?", id);
        log.warn("Counter made a new password for staff ID {}", a.getUsername());
        return new NewPassword(describe(live(id)), pretty(password));
    }

    /**
     * name: a new name. monthlyPages: this ID's own number of free pages;
     * usualPages = true: back to the shop's usual number. active: switch the
     * ID off (cannot sign in or print; signed out everywhere) or on again.
     */
    public AccountView update(UUID id, String name, Integer monthlyPages, Boolean usualPages, Boolean active) {
        StaffAccount a = live(id);
        if (name != null) a.setName(cleanName(name));
        if (Boolean.TRUE.equals(usualPages)) a.setMonthlyPages(null);
        else if (monthlyPages != null) a.setMonthlyPages(checkPages(monthlyPages));
        if (active != null && active != a.isActive()) {
            a.setActive(active);
            log.warn("Counter switched staff ID {} {}", a.getUsername(), active ? "on" : "off");
        }
        accounts.saveAndFlush(a);
        return describe(a);
    }

    /** Takes an ID off the list for good. What it already sent to print still prints; its username is free again. */
    public void remove(UUID id) {
        StaffAccount a = live(id);
        a.setActive(false);
        a.setRemovedAt(Instant.now());
        accounts.saveAndFlush(a);
        log.warn("Counter removed staff ID {} ({})", a.getUsername(), a.getName());
    }

    /** The shop's usual number of free pages per month, and whether colour is part of it. */
    public void saveSettings(Integer monthlyPages, Boolean colorAllowed) {
        ShopSettings s = settings.current();
        if (monthlyPages != null) s.setStaffMonthlyPages(checkPages(monthlyPages));
        if (colorAllowed != null) s.setStaffColor(colorAllowed);
        s.setUpdatedAt(Instant.now());
        settings.save(s);
        log.warn("Staff printing: {} free pages a month, colour {}", s.getStaffMonthlyPages(),
                s.isStaffColor() ? "allowed" : "not allowed");
    }

    /** The names of these staff IDs, for the counter's order list (removed ones too). */
    public Map<UUID, String> names(Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        accounts.findAllById(ids).forEach(a -> out.put(a.getId(), a.getName()));
        return out;
    }

    private StaffAccount live(UUID id) {
        return accounts.findById(id).filter(a -> a.getRemovedAt() == null)
                .orElseThrow(() -> ApiException.notFound("That staff ID"));
    }

    // ------------------------------------------------------------------ names, usernames, passwords

    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (n.length() < 2 || n.length() > 60) {
            throw ApiException.badRequest("BAD_STAFF_NAME", "Type the staff member's name (2 to 60 letters).");
        }
        return n;
    }

    static String checkUsername(String typed) {
        String u = typed.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(u).matches()) {
            throw ApiException.badRequest("BAD_USERNAME", "A username has 3 to 30 small letters or digits "
                    + "(dots, dashes and underscores are fine inside), for example rakesh.sharma.");
        }
        return u;
    }

    private static Integer checkPages(Integer pages) {
        if (pages == null) return null;
        if (pages < 0 || pages > MAX_MONTHLY_PAGES) {
            throw ApiException.badRequest("BAD_PAGES", "Free pages per month must be between 0 and "
                    + MAX_MONTHLY_PAGES + ".");
        }
        return pages;
    }

    private static ApiException taken(String username) {
        return ApiException.conflict("USERNAME_TAKEN", "The username \"" + username + "\" is already used. "
                + "Choose another one.");
    }

    /** "Prof. Rakesh Sharma" -> rakesh.sharma; if taken, rakesh.sharma2, rakesh.sharma3... */
    private String freeUsername(String name) {
        String base = usernameFrom(name);
        String candidate = base;
        for (int i = 2; i < 1000; i++) {
            if (accounts.findLive(candidate).isEmpty()) return candidate;
            candidate = base + i;
        }
        throw ApiException.conflict("USERNAME_TAKEN", "Type a username for this staff member.");
    }

    static String usernameFrom(String name) {
        List<String> words = Arrays.stream(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split(" "))
                .filter(w -> !w.isEmpty() && !TITLES.contains(w)).toList();
        String base = words.isEmpty() ? "staff"
                : words.size() == 1 ? words.get(0) : words.get(0) + "." + words.get(words.size() - 1);
        if (base.length() > 24) base = base.substring(0, 24).replaceAll("[._-]+$", "");
        if (base.length() < 3) base = "staff." + base;
        return base;
    }

    static String newPassword() {
        char[] c = new char[PASSWORD_LENGTH];
        for (int i = 0; i < c.length; i++) c[i] = PASSWORD_ALPHABET[RANDOM.nextInt(PASSWORD_ALPHABET.length)];
        return new String(c);
    }

    /** As it is written down: two groups of five. */
    static String pretty(String password) {
        return password.substring(0, 5) + "-" + password.substring(5);
    }

    /** As it is checked: capitals, spaces and the dash do not matter. */
    static String normalPassword(String typed) {
        return typed == null ? "" : typed.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
    }
}
