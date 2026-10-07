package edu.campus.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campus.print.support.FakeStorage;
import edu.campus.print.support.Files;
import edu.campus.print.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Free printing for college staff, against a real PostgreSQL: the Xerox
 * center makes a staff ID (a username and a password, nothing else), the
 * staff member signs in and prints without paying up to a number of pages a
 * month, and collects by showing the files like everyone else. And every way
 * around it that we could think of.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffPrintingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COUNTER = "counter-password-for-tests";
    private static final AtomicInteger ADDRESS = new AtomicInteger(10);
    private static String dbUrl;

    @Autowired MockMvc mvc;
    @Autowired FakeStorage storage;
    @Autowired JdbcTemplate jdbc;

    private String pcId;
    private String pcSecret;
    private String agentToken;
    private String bwPrinter;
    private String colourPrinter;
    /** This test's own network address: sign-ins are counted per address. */
    private RequestPostProcessor here;

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        FakeStorage fakeStorage() {
            return new FakeStorage();
        }
    }

    @BeforeAll
    static void database() {
        dbUrl = TestDb.freshDatabase();
        TestDb.run(TestDb.dataSource(dbUrl), TestDb.setupSql());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> dbUrl);
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("campus.supabase.url", () -> "https://storage.test");
        r.add("campus.shop.shop-cache-millis", () -> "0");
        r.add("campus.supabase.service-key", () -> "test-key");
        r.add("campus.payment.mode", () -> "demo");
        r.add("campus.counter.password", () -> COUNTER);
        r.add("campus.agent.token-secret", () -> "a-test-token-secret-that-is-long-enough-123");
        r.add("campus.cors.allowed-origins", () -> "http://localhost:3000");
    }

    /** Every test starts with: no staff IDs, 1000 free pages a month, colour not free, and two printers. */
    @BeforeEach
    void shop() throws Exception {
        jdbc.update("delete from document_previews");
        jdbc.update("delete from order_events");
        jdbc.update("delete from order_documents");
        jdbc.update("delete from orders");
        jdbc.update("delete from staff_accounts");
        jdbc.update("delete from printers");
        jdbc.update("update shop_settings set price_bw_paise = 200, price_color_paise = 1000, "
                + "staff_monthly_pages = 1000, staff_color = false");
        String ip = "198.51.100." + ADDRESS.incrementAndGet();
        here = r -> {
            r.setRemoteAddr(ip);
            return r;
        };
        JsonNode pc = counter(post("/api/v1/counter/pcs").content("{\"name\":\"Test PC\"}"));
        pcId = pc.path("agentId").asText();
        pcSecret = pc.path("agentSecret").asText();
        agentToken = null;
        bwPrinter = counter(post("/api/v1/counter/printers").content(JSON.writeValueAsString(Map.of(
                "name", "Printer 1 (B/W)", "windowsPrinterName", "Canon BW", "supportsColor", false,
                "acceptsBw", true, "agentId", pcId, "capabilities", caps(true, "psk:CardStock"),
                "capabilitiesHash", "h1",
                "offered", Map.of("mediaTypes", List.of("psk:CardStock")))))).path("id").asText();
        colourPrinter = counter(post("/api/v1/counter/printers").content(JSON.writeValueAsString(Map.of(
                "name", "Printer 2 (Colour)", "windowsPrinterName", "Epson Colour", "supportsColor", true,
                "acceptsBw", false, "agentId", pcId, "capabilities", caps(false, null),
                "capabilitiesHash", "h2")))).path("id").asText();
    }

    // ------------------------------------------------------------------ the Xerox center makes the IDs

    @Test
    void theXeroxCenterMakesAStaffIdWithAUsernameAndAPasswordOnly() throws Exception {
        // Only the Xerox center can: not without its sign-in.
        assertThat(mvc.perform(post("/api/v1/counter/staff").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Somebody Else\"}")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/counter/staff")).andReturn().getResponse().getStatus()).isEqualTo(401);

        JsonNode made = counter(post("/api/v1/counter/staff").content("{\"name\":\"Prof. Rakesh Sharma\"}"));
        assertThat(made.path("account").path("username").asText()).isEqualTo("rakesh.sharma");
        assertThat(made.path("account").path("name").asText()).isEqualTo("Prof. Rakesh Sharma");
        assertThat(made.path("account").path("monthlyPages").asInt()).isEqualTo(1000);
        String password = made.path("password").asText();
        assertThat(password).matches("[a-hjkmnp-z2-9]{5}-[a-hjkmnp-z2-9]{5}");     // made by the server: never a weak one

        // Only the hash is kept.
        String hash = jdbc.queryForObject("select password_hash from staff_accounts where username = 'rakesh.sharma'",
                String.class);
        assertThat(hash).startsWith("$2").doesNotContain(password.replace("-", ""));

        // A second Rakesh Sharma gets a username of his own; a typed one must be free and tidy.
        assertThat(counter(post("/api/v1/counter/staff").content("{\"name\":\"Rakesh Sharma\"}"))
                .path("account").path("username").asText()).isEqualTo("rakesh.sharma2");
        MvcResult taken = counterRaw(post("/api/v1/counter/staff").content("{\"name\":\"R S\",\"username\":\"Rakesh.Sharma\"}"));
        assertThat(taken.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(taken).path("error").asText()).isEqualTo("USERNAME_TAKEN");
        for (String bad : List.of("ab", "has space", "x".repeat(31), "-dash.first", "ünïcode", "a/b/c")) {
            MvcResult r = counterRaw(post("/api/v1/counter/staff").content(JSON.writeValueAsString(
                    Map.of("name", "Some One", "username", bad))));
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(json(r).path("error").asText()).as(bad).isEqualTo("BAD_USERNAME");
        }
        assertThat(json(counterRaw(post("/api/v1/counter/staff").content("{\"name\":\" \"}"))).path("error").asText())
                .isEqualTo("BAD_STAFF_NAME");
        assertThat(counter(post("/api/v1/counter/staff").content("{\"name\":\"Dr. Li\",\"username\":\"li.physics\",\"monthlyPages\":800}"))
                .path("account").path("monthlyPages").asInt()).isEqualTo(800);

        JsonNode list = counter(get("/api/v1/counter/staff"));
        assertThat(list.path("monthlyPages").asInt()).isEqualTo(1000);
        assertThat(list.path("colorAllowed").asBoolean()).isFalse();
        assertThat(texts(list.path("accounts"), "username")).containsExactly("li.physics", "rakesh.sharma", "rakesh.sharma2");
        // The list never carries a password or a hash.
        assertThat(list.toString()).doesNotContain("assword").doesNotContain("$2");
    }

    @Test
    void signingInNeedsTheRightPasswordAndTellsNothingElse() throws Exception {
        Staff s = newStaff("Anita Desai");
        MvcResult wrong = login(s.username, "aaaaa-aaaaa");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(wrong).path("error").asText()).isEqualTo("BAD_LOGIN");
        MvcResult nobody = login("no.such.person", "aaaaa-aaaaa");
        assertThat(nobody.getResponse().getStatus()).isEqualTo(401);
        // The same words for a wrong password and for a username that does not exist.
        assertThat(json(nobody).path("message").asText()).isEqualTo(json(wrong).path("message").asText());
        assertThat(login(s.username, "").getResponse().getStatus()).isEqualTo(401);
        assertThat(login("", s.password).getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/staff/login").with(here)).andReturn().getResponse().getStatus()).isEqualTo(401);

        // Capitals, a missing dash and spaces do not matter (it is typed once, from a slip of paper).
        MvcResult ok = login("  " + s.username.toUpperCase() + " ", s.password.toUpperCase().replace("-", " "));
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        JsonNode in = json(ok);
        String token = in.path("token").asText();
        assertThat(token).isNotEmpty();
        assertThat(in.path("staff").path("name").asText()).isEqualTo("Anita Desai");
        assertThat(in.path("staff").path("monthlyPages").asInt()).isEqualTo(1000);
        assertThat(in.path("staff").path("usedPages").asInt()).isZero();
        assertThat(in.path("staff").path("leftPages").asInt()).isEqualTo(1000);
        assertThat(in.path("staff").path("colorAllowed").asBoolean()).isFalse();
        LocalDate next = LocalDate.now(ZoneId.of("Asia/Kolkata")).withDayOfMonth(1).plusMonths(1);
        assertThat(in.path("staff").path("resetsOn").asText()).isEqualTo(next.toString());
        assertThat(in.toString()).doesNotContain("$2");

        JsonNode me = json(mvc.perform(get("/api/v1/staff/me").header("X-Staff-Session", token)).andReturn());
        assertThat(me.path("staff").path("username").asText()).isEqualTo(s.username);
        assertThat(me.path("token").isNull()).isTrue();                 // a new token only once this one is a week old

        // No token, a made-up one, or one somebody changed: signed out.
        String other = newStaff("Other Person").id;
        String[] part = token.split("\\.");
        List<String> forged = List.of("", "abc", token + "x", token.substring(0, token.length() - 2) + "AA",
                other + "." + part[1] + "." + part[2] + "." + part[3] + "." + part[4],
                part[0] + "." + part[1] + ".9999999999." + part[3] + "." + part[4]);
        for (String t : forged) {
            MvcResult r = mvc.perform(get("/api/v1/staff/me").header("X-Staff-Session", t)).andReturn();
            assertThat(r.getResponse().getStatus()).as(t).isEqualTo(401);
            assertThat(json(r).path("error").asText()).as(t).isEqualTo("STAFF_SIGNED_OUT");
        }
        assertThat(mvc.perform(get("/api/v1/staff/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/staff/orders")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ printing for free

    @Test
    void aStaffOrderIsFreeCountedAndCollectedLikeAnyOther() throws Exception {
        Staff s = signIn(newStaff("Meera Nair"));
        Order o = newOrder(s);
        String a = o.add("Timetable.pdf", "PDF", Files.pdf(6));
        String b = o.add("Notice.png", "PNG", Files.png(40, 30));
        o.uploaded(a);
        o.uploaded(b);
        JsonNode reviewed = o.review(Map.of(a, Map.of("copies", 3, "duplex", "LONG_EDGE"), b, Map.of()), a, b);
        assertThat(reviewed.path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(reviewed.path("stage").asText()).isEqualTo("Ready to print");
        assertThat(reviewed.path("amountPaise").asInt()).isZero();
        assertThat(reviewed.path("freePages").asInt()).isEqualTo(6 * 3 + 1);       // printed sides x copies
        assertThat(texts(reviewed.path("documents"), "amountPaise")).containsExactly("0", "0");

        // No payment of any kind can touch a staff order: not from the app...
        for (String path : List.of("/payment", "/payment/demo", "/payment/claim")) {
            MvcResult r = mvc.perform(s.as(post("/api/v1/orders/" + o.id + path))).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(409);
            assertThat(json(r).path("error").asText()).as(path).isEqualTo("STAFF_ORDER");
        }
        MvcResult confirm = mvc.perform(s.as(post("/api/v1/orders/" + o.id + "/payment/confirm"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"paymentId\":\"p\",\"signature\":\"s\"}")).andReturn();
        assertThat(json(confirm).path("error").asText()).isEqualTo("STAFF_ORDER");
        // ...and not in the database, whoever asks: the one gate into the print queue keeps the two kinds apart.
        for (String provider : List.of("demo", "free", "upi", "razorpay")) {
            assertThat(jdbc.queryForObject("select mark_order_paid(?::uuid, ?, 'x')", Integer.class, o.id, provider))
                    .as(provider).isZero();
        }
        assertThat(jdbc.queryForObject("select upi_approve_payment(?::uuid, null)", String.class, o.id)).isEqualTo("NOT_OPEN");
        assertThat(jdbc.queryForObject("select count(*) from upi_start_payment(?::uuid, 'CPTEST', 60)", Integer.class, o.id)).isZero();
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");

        // "Print": free, and counted.
        JsonNode sent = o.print();
        assertThat(sent.path("status").asText()).isEqualTo("QUEUED");
        assertThat(sent.path("stage").asText()).isEqualTo("Sent – waiting for a printer");
        assertThat(sent.path("payment").path("provider").asText()).isEqualTo("staff");
        assertThat(sent.path("paidAt").asText()).isNotEmpty();
        assertThat(sent.path("freePages").asInt()).isEqualTo(19);
        assertThat(s.me().path("usedPages").asInt()).isEqualTo(19);
        assertThat(s.me().path("leftPages").asInt()).isEqualTo(981);
        // Pressed twice: nothing happens twice.
        assertThat(o.print().path("status").asText()).isEqualTo("QUEUED");
        assertThat(s.me().path("usedPages").asInt()).isEqualTo(19);

        // The counter sees whose order it is, and that nothing was paid.
        JsonNode row = find(counter(get("/api/v1/counter/orders?view=active")), "id", o.id);
        assertThat(row.path("staffName").asText()).isEqualTo("Meera Nair");
        assertThat(row.path("staffPages").asInt()).isEqualTo(19);
        assertThat(row.path("amountPaise").asInt()).isZero();
        assertThat(row.path("paymentProvider").asText()).isEqualTo("staff");

        printed(claim(bwPrinter));
        printed(claim(bwPrinter));
        JsonNode ready = o.view();
        assertThat(ready.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(ready.path("stage").asText()).isEqualTo("Ready – collect at the counter");

        // At the counter, on a phone that is only signed in with the ID (it never had this order's key).
        JsonNode there = json(mvc.perform(post("/api/v1/orders/" + o.id + "/arrive").header("X-Staff-Session", s.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"here\":true}")).andReturn());
        assertThat(there.path("arrivedAt").asText()).isNotEmpty();
        JsonNode atCounter = counter(get("/api/v1/counter/summary")).path("atCounter");
        assertThat(atCounter).hasSize(1);
        assertThat(atCounter.get(0).path("id").asText()).isEqualTo(o.id);
        assertThat(atCounter.get(0).path("staffName").asText()).isEqualTo("Meera Nair");
        counter(post("/api/v1/counter/orders/" + o.id + "/collected"));
        assertThat(o.view().path("collected").asBoolean()).isTrue();

        // "Your prints", on any device signed in with the ID.
        JsonNode mine = json(mvc.perform(get("/api/v1/staff/orders").header("X-Staff-Session", s.token)).andReturn());
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("orderId").asText()).isEqualTo(o.id);
        assertThat(mine.get(0).path("name").asText()).isEqualTo("Timetable.pdf + 1 more");
        assertThat(mine.get(0).path("pages").asInt()).isEqualTo(19);
        assertThat(mine.get(0).path("stage").asText()).isEqualTo("Collected");
    }

    @Test
    void theMonthsFreePagesAreAHardLimit() throws Exception {
        Staff s = signIn(newStaff("Limit Tester"));
        counter(put("/api/v1/counter/staff/" + s.id).content("{\"monthlyPages\":10}"));
        assertThat(s.me().path("monthlyPages").asInt()).isEqualTo(10);

        reviewed(s, 8).print();
        Order second = reviewed(s, 5);
        MvcResult over = second.printRaw();
        assertThat(over.getResponse().getStatus()).isEqualTo(409);
        JsonNode why = json(over);
        assertThat(why.path("error").asText()).isEqualTo("OVER_LIMIT");
        assertThat(why.path("pages").asInt()).isEqualTo(5);
        assertThat(why.path("used").asInt()).isEqualTo(8);
        assertThat(why.path("limit").asInt()).isEqualTo(10);
        assertThat(why.path("left").asInt()).isEqualTo(2);
        assertThat(why.path("message").asText()).contains("only 2 of your 10 free pages");
        // Nothing went to the printers.
        assertThat(second.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("select count(*) from order_documents where order_id = ?::uuid and status = 'QUEUED'",
                Integer.class, second.id)).isZero();
        assertThat(claimIsFor(bwPrinter, second.id)).isFalse();

        // Fewer pages fit: change the order, review, print.
        json(mvc.perform(s.as(post("/api/v1/orders/" + second.id + "/edit"))).andReturn());
        second.review(Map.of(second.doc, Map.of("pages", "1-2")), second.doc);
        assertThat(second.print().path("status").asText()).isIn("QUEUED", "PRINTING");
        assertThat(s.me().path("leftPages").asInt()).isZero();

        // Nothing left: not even one page.
        Order third = reviewed(s, 1);
        assertThat(json(third.printRaw()).path("message").asText()).contains("none of your 10 free pages");

        // The Xerox center puts this ID back on the shop's usual number...
        counter(put("/api/v1/counter/staff/" + s.id).content("{\"usualPages\":true}"));
        assertThat(s.me().path("monthlyPages").asInt()).isEqualTo(1000);
        third.print();
        // ...and changes the usual number for everyone who has no number of their own.
        counter(put("/api/v1/counter/staff/settings").content("{\"monthlyPages\":800}"));
        assertThat(s.me().path("monthlyPages").asInt()).isEqualTo(800);
        assertThat(s.me().path("usedPages").asInt()).isEqualTo(11);
        assertThat(json(counterRaw(put("/api/v1/counter/staff/settings").content("{\"monthlyPages\":-1}")))
                .path("error").asText()).isEqualTo("BAD_PAGES");
        assertThat(json(counterRaw(put("/api/v1/counter/staff/" + s.id).content("{\"monthlyPages\":100001}")))
                .path("error").asText()).isEqualTo("BAD_PAGES");
    }

    @Test
    void atTheSameMomentNothingSlipsUnderTheLimitAndNothingPrintsTwice() throws Exception {
        Staff s = signIn(newStaff("Quick Fingers"));
        counter(put("/api/v1/counter/staff/" + s.id).content("{\"monthlyPages\":10}"));
        // Two orders of 6 pages: each fits alone, both together do not.
        Order a = reviewed(s, 6);
        Order b = reviewed(s, 6);
        List<Integer> answers = together(() -> a.printRaw().getResponse().getStatus(),
                () -> b.printRaw().getResponse().getStatus());
        assertThat(answers).containsExactlyInAnyOrder(200, 409);
        assertThat(s.me().path("usedPages").asInt()).isEqualTo(6);

        // One order, "Print" pressed on two devices at once: it prints once and is counted once.
        Order c = reviewed(s, 2);
        assertThat(together(() -> c.printRaw().getResponse().getStatus(), () -> c.printRaw().getResponse().getStatus()))
                .containsExactly(200, 200);
        assertThat(s.me().path("usedPages").asInt()).isEqualTo(8);
        assertThat(jdbc.queryForObject("select count(*) from order_events where order_id = ?::uuid and to_status = 'QUEUED' "
                + "and document_id is null", Integer.class, c.id)).isEqualTo(1);
    }

    @Test
    void colourAndSpecialPaperAreNotFreeUnlessTheXeroxCenterSaysSo() throws Exception {
        Staff s = signIn(newStaff("Colour Fan"));
        Order o = newOrder(s);
        o.doc = o.add("Poster.pdf", "PDF", Files.pdf(1));
        o.uploaded(o.doc);
        MvcResult colour = o.reviewRaw(Map.of(o.doc, Map.of("color", true)), o.doc);
        assertThat(colour.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(colour).path("error").asText()).isEqualTo("CHECK_SETTINGS");
        assertThat(json(colour).path("documents").get(0).path("message").asText()).contains("black & white");
        MvcResult card = o.reviewRaw(Map.of(o.doc, Map.of("mediaType", "psk:CardStock")), o.doc);
        assertThat(card.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(card).path("documents").get(0).path("message").asText()).contains("plain paper");
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_UPLOAD");

        counter(put("/api/v1/counter/staff/settings").content("{\"colorAllowed\":true}"));
        assertThat(s.me().path("colorAllowed").asBoolean()).isTrue();
        assertThat(o.review(Map.of(o.doc, Map.of("color", true)), o.doc).path("freePages").asInt()).isEqualTo(1);

        // Switched off again before "Print" is pressed: the database refuses it at that moment too.
        counter(put("/api/v1/counter/staff/settings").content("{\"colorAllowed\":false}"));
        MvcResult refused = o.printRaw();
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(refused).path("error").asText()).isEqualTo("STAFF_NO_COLOR");
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        counter(put("/api/v1/counter/staff/settings").content("{\"colorAllowed\":true}"));
        assertThat(o.print().path("status").asText()).isEqualTo("QUEUED");
        assertThat(claimIsFor(colourPrinter, o.id)).isTrue();
    }

    @Test
    void onlyPagesThatReallyPrintAreCountedAndTheMonthStartsAgain() throws Exception {
        Staff s = signIn(newStaff("Fair Count"));
        Order o = newOrder(s);
        String a = o.add("A.pdf", "PDF", Files.pdf(4));
        String b = o.add("B.pdf", "PDF", Files.pdf(10));
        o.uploaded(a);
        o.uploaded(b);
        o.review(Map.of(a, Map.of(), b, Map.of("pagesPerSheet", 2)), a, b);          // 4 + 5 printed sides
        o.print();
        assertThat(s.me().path("usedPages").asInt()).isEqualTo(9);

        // The Xerox center cancels one file at the counter: no paper came out, so its pages come back.
        counter(post("/api/v1/counter/documents/" + b + "/cancel"));
        assertThat(s.me().path("usedPages").asInt()).isEqualTo(4);
        JsonNode view = o.view();
        assertThat(view.path("refundDuePaise").isNull()).isTrue();                   // nothing was paid: no refund
        assertThat(find(view.path("documents"), "id", b).path("stage").asText()).isEqualTo("Cancelled at the counter");
        assertThat(view.path("message").asText("")).doesNotContain("efund");

        // A file that failed to print does not count either, until it is printed again.
        JsonNode job = claim(bwPrinter);
        agent(post("/agent/v1/jobs/" + job.path("jobId").asText() + "/status").content("{\"claimToken\":\""
                + job.path("claimToken").asText() + "\",\"status\":\"FAILED\",\"errorCode\":\"PRINTER_ERROR\","
                + "\"message\":\"Paper jam\"}"));
        assertThat(s.me().path("usedPages").asInt()).isZero();
        assertThat(o.view().path("message").asText()).doesNotContain("payment");
        counter(post("/api/v1/counter/orders/" + o.id + "/print-again"));
        assertThat(s.me().path("usedPages").asInt()).isEqualTo(4);

        // Next month: the count starts again by itself.
        jdbc.update("update orders set paid_at = paid_at - interval '32 days' where id = ?::uuid", o.id);
        assertThat(s.me().path("usedPages").asInt()).isZero();
        assertThat(s.me().path("leftPages").asInt()).isEqualTo(1000);
    }

    // ------------------------------------------------------------------ who may see and print what

    @Test
    void aStaffOrderAnswersToItsStaffIdAndToNobodyElse() throws Exception {
        Staff s = signIn(newStaff("Owner One"));
        Staff other = signIn(newStaff("Owner Two"));
        JsonNode created = json(mvc.perform(s.as(post("/api/v1/orders")).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn());
        String id = created.path("orderId").asText();
        String key = created.path("accessKey").asText();

        // The order's key alone is not enough (a staff order is not tied to a device)...
        assertThat(mvc.perform(get("/api/v1/orders/" + id).header("X-Order-Key", key)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        // ...nor is somebody else's staff ID, with or without the key...
        assertThat(mvc.perform(other.as(get("/api/v1/orders/" + id)).header("X-Order-Key", key)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        // ...nor nothing at all.
        assertThat(mvc.perform(get("/api/v1/orders/" + id)).andReturn().getResponse().getStatus()).isEqualTo(404);
        // Its own ID is, from any device.
        assertThat(mvc.perform(s.as(get("/api/v1/orders/" + id))).andReturn().getResponse().getStatus()).isEqualTo(200);
        // A sign-in that is not good is refused, never quietly treated as "a student".
        assertThat(mvc.perform(get("/api/v1/orders/" + id).header("X-Order-Key", key).header("X-Staff-Session", "nonsense"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/orders").header("X-Staff-Session", "nonsense")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(401);

        // An ordinary (student) order can never be printed for free: not by a staff member, with or without its key...
        JsonNode student = json(mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn());
        String sid = student.path("orderId").asText();
        String skey = student.path("accessKey").asText();
        JsonNode ticket = json(mvc.perform(post("/api/v1/orders/" + sid + "/documents").header("X-Order-Key", skey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"S.pdf\",\"fileType\":\"PDF\",\"fileSizeBytes\":500}")).andReturn());
        String doc = ticket.path("document").path("id").asText();
        storage.upload(ticket.path("uploadUrl").asText(), Files.pdf(2));
        json(mvc.perform(post("/api/v1/orders/" + sid + "/documents/" + doc + "/uploaded").header("X-Order-Key", skey)).andReturn());
        JsonNode priced = json(mvc.perform(post("/api/v1/orders/" + sid + "/review").header("X-Order-Key", skey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"documents\":[{\"id\":\"" + doc + "\",\"settings\":{}}]}")).andReturn());
        assertThat(priced.path("amountPaise").asInt()).isEqualTo(400);
        assertThat(priced.path("freePages").isNull()).isTrue();
        assertThat(mvc.perform(s.as(post("/api/v1/orders/" + sid + "/staff-print")).header("X-Order-Key", skey))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        // ...not without signing in...
        assertThat(mvc.perform(post("/api/v1/orders/" + sid + "/staff-print").header("X-Order-Key", skey))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        // ...and not in the database either.
        assertThat(jdbc.queryForObject("select mark_order_paid(?::uuid, 'staff', 'x')", Integer.class, sid)).isZero();
        assertThat(jdbc.queryForObject("select out_result from staff_print(?::uuid, ?::uuid, now() - interval '1 day')",
                String.class, sid, s.id)).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, sid))
                .isEqualTo("AWAITING_PAYMENT");

        // One staff member cannot spend another's pages.
        Order mine = reviewed(s, 3);
        assertThat(mvc.perform(other.as(post("/api/v1/orders/" + mine.id + "/staff-print"))).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select out_result from staff_print(?::uuid, ?::uuid, now() - interval '1 day')",
                String.class, mine.id, other.id)).isEqualTo("NOT_FOUND");
        assertThat(other.me().path("usedPages").asInt()).isZero();
        assertThat(s.me().path("usedPages").asInt()).isZero();
    }

    @Test
    void aNewPasswordOrSwitchingOffSignsEveryDeviceOutAtOnce() throws Exception {
        Staff s = signIn(newStaff("Lost Phone"));
        Order o = reviewed(s, 2);
        String oldToken = s.token;
        String oldPassword = s.password;

        // The Xerox center makes a new password: the old one and every device signed in with it stop working.
        JsonNode fresh = counter(post("/api/v1/counter/staff/" + s.id + "/password"));
        assertThat(fresh.path("password").asText()).isNotEqualTo(oldPassword).matches("[a-z2-9]{5}-[a-z2-9]{5}");
        MvcResult out = mvc.perform(get("/api/v1/staff/me").header("X-Staff-Session", oldToken)).andReturn();
        assertThat(out.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(out).path("error").asText()).isEqualTo("STAFF_SIGNED_OUT");
        assertThat(mvc.perform(post("/api/v1/orders/" + o.id + "/staff-print").header("X-Staff-Session", oldToken))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/orders/" + o.id).header("X-Staff-Session", oldToken))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(login(s.username, oldPassword).getResponse().getStatus()).isEqualTo(401);
        s.password = fresh.path("password").asText();
        signIn(s);
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_PAYMENT");   // the order is still theirs

        // Switched off: no printing, no signing in; nothing is deleted.
        counter(put("/api/v1/counter/staff/" + s.id).content("{\"active\":false}"));
        MvcResult off = mvc.perform(s.as(post("/api/v1/orders/" + o.id + "/staff-print"))).andReturn();
        assertThat(off.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(off).path("error").asText()).isEqualTo("STAFF_OFF");
        assertThat(json(login(s.username, s.password)).path("error").asText()).isEqualTo("STAFF_OFF");
        assertThat(login(s.username, "wrong-wrong").getResponse().getStatus()).isEqualTo(401);     // only the owner learns it is off
        // Even straight in the database a switched-off ID prints nothing.
        assertThat(jdbc.queryForObject("select out_result from staff_print(?::uuid, ?::uuid, now() - interval '1 day')",
                String.class, o.id, s.id)).isEqualTo("OFF");
        counter(put("/api/v1/counter/staff/" + s.id).content("{\"active\":true}"));
        assertThat(o.print().path("status").asText()).isEqualTo("QUEUED");

        // Removed: gone from the list, cannot sign in, its username is free again; what it sent still prints.
        counter(delete("/api/v1/counter/staff/" + s.id));
        assertThat(texts(counter(get("/api/v1/counter/staff")).path("accounts"), "username")).doesNotContain(s.username);
        assertThat(login(s.username, s.password).getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/staff/me").header("X-Staff-Session", s.token)).andReturn()
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(claimIsFor(bwPrinter, o.id)).isTrue();
        assertThat(find(counter(get("/api/v1/counter/orders?view=all")), "id", o.id).path("staffName").asText())
                .isEqualTo("Lost Phone");
        assertThat(counter(post("/api/v1/counter/staff").content("{\"name\":\"New Person\",\"username\":\"" + s.username + "\"}"))
                .path("account").path("username").asText()).isEqualTo(s.username);
        assertThat(counterRaw(post("/api/v1/counter/staff/" + s.id + "/password")).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void wrongPasswordsMakeAnIdWaitAndAnAddressToo() throws Exception {
        Staff s = newStaff("Guessed At");
        for (int i = 0; i < 8; i++) {
            assertThat(login(s.username, "guess-" + i).getResponse().getStatus()).isEqualTo(401);
        }
        // The ID waits now: even the right password is refused, in the same words as a wrong one.
        MvcResult waiting = login(s.username, s.password);
        assertThat(waiting.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(waiting).path("error").asText()).isEqualTo("BAD_LOGIN");
        assertThat(find(counter(get("/api/v1/counter/staff")).path("accounts"), "username", s.username)
                .path("waiting").asBoolean()).isTrue();
        // Guesses while it waits do not make the wait longer.
        login(s.username, "another-guess");
        Integer failed = jdbc.queryForObject("select failed_logins from staff_accounts where id = ?::uuid", Integer.class, s.id);
        assertThat(failed).isEqualTo(8);

        // When the wait is over, the right password works, and the count starts again.
        jdbc.update("update staff_accounts set locked_until = now() - interval '1 second' where id = ?::uuid", s.id);
        assertThat(login(s.username, s.password).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select failed_logins from staff_accounts where id = ?::uuid", Integer.class, s.id)).isZero();

        // The Xerox center's new password also ends a wait.
        for (int i = 0; i < 8; i++) login(s.username, "guess-" + i);
        s.password = counter(post("/api/v1/counter/staff/" + s.id + "/password")).path("password").asText();
        assertThat(login(s.username, s.password).getResponse().getStatus()).isEqualTo(200);

        // One network address gets 30 sign-in tries in 10 minutes, then it has to wait (whatever username it tries).
        RequestPostProcessor script = r -> {
            r.setRemoteAddr("203.0.113.77");
            return r;
        };
        int refused = 0;
        for (int i = 0; i < 35; i++) {
            int status = mvc.perform(post("/api/v1/staff/login").with(script).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"user" + i + "\",\"password\":\"x\"}")).andReturn().getResponse().getStatus();
            if (status == 429) refused++;
            else assertThat(status).isEqualTo(401);
        }
        assertThat(refused).isEqualTo(5);
        assertThat(login(s.username, s.password).getResponse().getStatus()).isEqualTo(200);      // others are not disturbed
    }

    // ------------------------------------------------------------------ helpers

    private final class Staff {
        final String id;
        final String username;
        String password;
        String token;

        Staff(String id, String username, String password) {
            this.id = id;
            this.username = username;
            this.password = password;
        }

        MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b) {
            return b.header("X-Staff-Session", token);
        }

        JsonNode me() throws Exception {
            MvcResult r = mvc.perform(as(get("/api/v1/staff/me"))).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json(r).path("staff");
        }
    }

    private Staff newStaff(String name) throws Exception {
        JsonNode made = counter(post("/api/v1/counter/staff").content(JSON.writeValueAsString(Map.of("name", name))));
        return new Staff(made.path("account").path("id").asText(), made.path("account").path("username").asText(),
                made.path("password").asText());
    }

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/staff/login").with(here).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("username", username, "password", password)))).andReturn();
    }

    private Staff signIn(Staff s) throws Exception {
        MvcResult r = login(s.username, s.password);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        s.token = json(r).path("token").asText();
        return s;
    }

    private final class Order {
        final String id;
        final Staff staff;
        String doc;

        Order(String id, Staff staff) {
            this.id = id;
            this.staff = staff;
        }

        String add(String name, String type, byte[] bytes) throws Exception {
            JsonNode t = json(mvc.perform(staff.as(post("/api/v1/orders/" + id + "/documents"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("fileName", name, "fileType", type,
                            "fileSizeBytes", bytes.length)))).andReturn());
            storage.upload(t.path("uploadUrl").asText(), bytes);
            return t.path("document").path("id").asText();
        }

        JsonNode uploaded(String d) throws Exception {
            return json(mvc.perform(staff.as(post("/api/v1/orders/" + id + "/documents/" + d + "/uploaded"))).andReturn());
        }

        JsonNode review(Map<String, Map<String, Object>> settings, String... order) throws Exception {
            MvcResult r = reviewRaw(settings, order);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json(r);
        }

        MvcResult reviewRaw(Map<String, Map<String, Object>> settings, String... order) throws Exception {
            ArrayNode docs = JSON.createArrayNode();
            for (String d : order) {
                docs.addObject().put("id", d).set("settings", JSON.valueToTree(settings.getOrDefault(d, Map.of())));
            }
            ObjectNode body = JSON.createObjectNode();
            body.set("documents", docs);
            return mvc.perform(staff.as(post("/api/v1/orders/" + id + "/review"))
                    .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
        }

        MvcResult printRaw() throws Exception {
            return mvc.perform(staff.as(post("/api/v1/orders/" + id + "/staff-print"))).andReturn();
        }

        JsonNode print() throws Exception {
            MvcResult r = printRaw();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json(r);
        }

        JsonNode view() throws Exception {
            return json(mvc.perform(staff.as(get("/api/v1/orders/" + id))).andReturn());
        }
    }

    private Order newOrder(Staff s) throws Exception {
        MvcResult r = mvc.perform(s.as(post("/api/v1/orders")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return new Order(json(r).path("orderId").asText(), s);
    }

    /** A staff order with one PDF of this many pages, reviewed: ready for "Print". */
    private Order reviewed(Staff s, int pages) throws Exception {
        Order o = newOrder(s);
        o.doc = o.add("Pages" + pages + ".pdf", "PDF", Files.pdf(pages));
        o.uploaded(o.doc);
        assertThat(o.review(Map.of(o.doc, Map.of()), o.doc).path("freePages").asInt()).isEqualTo(pages);
        return o;
    }

    /** Both at the same moment, on two threads. The answers in the order given. */
    private static List<Integer> together(Callable<Integer> first, Callable<Integer> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> c : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return c.call();
                }));
            }
            go.countDown();
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Does the next job this printer gets belong to that order? (It then stays claimed.) */
    private boolean claimIsFor(String printer, String orderId) throws Exception {
        JsonNode job = claim(printer);
        if (job == null) return false;
        String owner = jdbc.queryForObject("select order_id::text from order_documents where id = ?::uuid", String.class,
                job.path("jobId").asText());
        return orderId.equals(owner);
    }

    private JsonNode counter(MockHttpServletRequestBuilder b) throws Exception {
        MvcResult r = counterRaw(b);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private MvcResult counterRaw(MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("X-Counter-Password", COUNTER).contentType(MediaType.APPLICATION_JSON)).andReturn();
    }

    private MockHttpServletRequestBuilder agentAuth(MockHttpServletRequestBuilder b) throws Exception {
        if (agentToken == null) {
            String basic = Base64.getEncoder().encodeToString((pcId + ":" + pcSecret).getBytes(StandardCharsets.UTF_8));
            agentToken = json(mvc.perform(post("/agent/v1/token").header("Authorization", "Basic " + basic))
                    .andReturn()).path("token").asText();
        }
        return b.header("Authorization", "Bearer " + agentToken);
    }

    private JsonNode agent(MockHttpServletRequestBuilder b) throws Exception {
        MvcResult r = mvc.perform(agentAuth(b.contentType(MediaType.APPLICATION_JSON))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private JsonNode claim(String printer) throws Exception {
        MvcResult r = mvc.perform(agentAuth(post("/agent/v1/jobs/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"printerId\":\"" + printer + "\"}"))).andReturn();
        if (r.getResponse().getStatus() == 204) return null;
        return json(r);
    }

    private void printed(JsonNode job) throws Exception {
        for (String s : List.of("DOWNLOADING", "SUBMITTED", "COMPLETED")) {
            agent(post("/agent/v1/jobs/" + job.path("jobId").asText() + "/status")
                    .content("{\"claimToken\":\"" + job.path("claimToken").asText() + "\",\"status\":\"" + s + "\"}"));
        }
    }

    /** A4/A3/A5, with or without two-sided printing and one special paper type. */
    private static ObjectNode caps(boolean duplex, String media) {
        ObjectNode c = JSON.createObjectNode();
        c.put("schema", 1);
        ArrayNode ps = c.putArray("paperSizes");
        for (String s : List.of("A4", "A3", "A5")) ps.addObject().put("id", s).putArray("marginsMm").add(4).add(4).add(4).add(4);
        c.put("duplex", duplex);
        c.putArray("finishing");
        ArrayNode m = c.putArray("mediaTypes");
        if (media != null) m.addObject().put("id", media).put("name", "Card");
        c.put("borderless", false);
        c.put("highQuality", false);
        c.put("maxCopies", 999);
        return c;
    }

    private static JsonNode find(JsonNode list, String field, String value) {
        for (JsonNode n : list) if (value.equals(n.path(field).asText())) return n;
        throw new AssertionError("No " + field + "=" + value + " in " + list);
    }

    private static List<String> texts(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : list) out.add(n.path(field).asText());
        return out;
    }

    private static JsonNode json(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() ? JSON.nullNode() : JSON.readTree(s);
    }
}
