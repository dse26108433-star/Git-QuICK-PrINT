package edu.campus.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campus.print.support.Docx;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The whole API against a real PostgreSQL, with storage in memory: a
 * student's multi-file order from upload to paper, the checks on settings,
 * printer features, the counter, collecting by showing the files (no pickup
 * code), and the one-file (Android) way.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiFlowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COUNTER = "counter-password-for-tests";
    private static String dbUrl;

    @Autowired MockMvc mvc;
    @Autowired FakeStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired edu.campus.print.orders.WordFiles wordFiles;

    private String pcId;
    private String pcSecret;
    private String agentToken;
    private String bwPrinter;
    private String colourPrinter;

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

    /** Every test starts with: one PC, a B/W duplex A3 stapling printer and a colour photo printer. */
    @BeforeEach
    void shop() throws Exception {
        jdbc.update("delete from document_previews");
        jdbc.update("delete from order_events");
        jdbc.update("delete from order_documents");
        jdbc.update("delete from orders");
        jdbc.update("delete from printers");
        jdbc.update("update shop_settings set price_bw_paise = 200, price_color_paise = 1000, "
                + "pricing = '{\"paperSizePercent\":{\"A3\":200},\"finishingPaise\":{\"STAPLE\":100}}'");
        JsonNode pc = counter(post("/api/v1/counter/pcs").content("{\"name\":\"Test PC\"}"));
        pcId = pc.path("agentId").asText();
        pcSecret = pc.path("agentSecret").asText();
        agentToken = null;

        ObjectNode bwCaps = caps(List.of("A4", "A3", "A5", "LETTER"), true, List.of("STAPLE_TOP_LEFT", "PUNCH_LEFT"),
                List.of(), false, false);
        bwPrinter = counter(post("/api/v1/counter/printers").content(JSON.writeValueAsString(Map.of(
                "name", "Printer 1 (B/W)", "windowsPrinterName", "Canon BW", "supportsColor", false,
                "acceptsBw", true, "agentId", pcId, "capabilities", bwCaps, "capabilitiesHash", "h1"))))
                .path("id").asText();
        ObjectNode colourCaps = caps(List.of("A4", "PHOTO_4X6"), false, List.of(), List.of("psk:PhotographicHighGloss"),
                true, true);
        colourPrinter = counter(post("/api/v1/counter/printers").content(JSON.writeValueAsString(Map.of(
                "name", "Printer 2 (Colour)", "windowsPrinterName", "Epson Colour", "supportsColor", true,
                "acceptsBw", false, "agentId", pcId, "capabilities", colourCaps, "capabilitiesHash", "h2",
                "offered", Map.of("paperSizes", List.of("A4", "PHOTO_4X6"),
                        "mediaTypes", List.of("psk:PhotographicHighGloss"))))))
                .path("id").asText();
    }

    // ------------------------------------------------------------------ the shop tells what printers can do

    @Test
    void theShopShowsOnlyWhatThePrintersCanDo() throws Exception {
        JsonNode shop = json(mvc.perform(get("/api/v1/shop")).andReturn());
        JsonNode printers = shop.path("printing").path("printers");
        assertThat(printers).hasSize(2);
        JsonNode bw = find(printers, "name", "Printer 1 (B/W)");
        assertThat(bw.path("duplex").asBoolean()).isTrue();
        // Default offer: the common sizes the printer has (Letter is not offered by default).
        assertThat(texts(bw.path("paperSizes"))).containsExactly("A4", "A3", "A5");
        assertThat(texts(bw.path("finishing"))).isEmpty();          // not until staff tick it after a test print
        JsonNode colour = find(printers, "name", "Printer 2 (Colour)");
        assertThat(colour.path("duplex").asBoolean()).isFalse();
        assertThat(colour.path("borderless").asBoolean()).isTrue();
        assertThat(texts(colour.path("mediaTypes"))).containsExactly("psk:PhotographicHighGloss");
        assertThat(shop.path("printing").path("mediaTypes").path("psk:PhotographicHighGloss").asText())
                .isEqualTo("Glossy photo");
        assertThat(texts(shop.path("printing").path("paperSizes"), "id")).containsExactly("A4", "A3", "A5", "PHOTO_4X6");

        // Staff tick the hole punch after a test print: students can choose it at once.
        counter(put("/api/v1/counter/printers/" + bwPrinter).content("{\"offered\":{\"finishing\":[\"PUNCH_LEFT\"]}}"));
        assertThat(texts(find(json(mvc.perform(get("/api/v1/shop")).andReturn()).path("printing").path("printers"),
                "name", "Printer 1 (B/W)").path("finishing"))).containsExactly("PUNCH_LEFT");

        // Staff stop offering two-sided on Printer 1: the website stops offering it at once.
        counter(put("/api/v1/counter/printers/" + bwPrinter).content("{\"offered\":{\"duplex\":false}}"));
        JsonNode after = json(mvc.perform(get("/api/v1/shop")).andReturn());
        assertThat(find(after.path("printing").path("printers"), "name", "Printer 1 (B/W)").path("duplex").asBoolean())
                .isFalse();
    }

    @Test
    void theStationKeepsPrinterFeaturesUpToDate() throws Exception {
        heartbeatPrinters();       // signs in
        ObjectNode newCaps = caps(List.of("A4"), false, List.of(), List.of(), false, false);
        agent(post("/agent/v1/printers/" + bwPrinter + "/capabilities")
                .content(JSON.writeValueAsString(Map.of("capabilities", newCaps, "hash", "h9"))));
        JsonNode config = find(heartbeatPrinters(), "id", bwPrinter);
        assertThat(config.path("capabilitiesHash").asText()).isEqualTo("h9");
        JsonNode shop = json(mvc.perform(get("/api/v1/shop")).andReturn());
        JsonNode bw = find(shop.path("printing").path("printers"), "name", "Printer 1 (B/W)");
        assertThat(bw.path("duplex").asBoolean()).isFalse();
        assertThat(texts(bw.path("paperSizes"))).containsExactly("A4");

        // Staff ask for a fresh look; the PC sees the request in its next heartbeat.
        counter(post("/api/v1/counter/printers/" + bwPrinter + "/rescan"));
        assertThat(find(heartbeatPrinters(), "id", bwPrinter).path("rescan").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ several files, from upload to paper

    @Test
    void aMultiFileOrderPrintsEachFileExactlyAsChosen() throws Exception {
        Order o = newOrder();
        String notes = o.add("Notes.pdf", "PDF", Files.pdf(10));
        String assignment = o.add("Assignment.pdf", "PDF", Files.pdf(8));
        String photo = o.add("Photo.jpg", "JPEG", Files.jpegWithOrientation(400, 300, 6));

        JsonNode photoView = o.uploaded(photo);
        assertThat(photoView.path("status").asText()).isEqualTo("READY");
        assertThat(photoView.path("image").path("widthPx").asInt()).isEqualTo(400);
        assertThat(photoView.path("image").path("exifOrientation").asInt()).isEqualTo(6);
        assertThat(o.uploaded(notes).path("pageCount").asInt()).isEqualTo(10);
        assertThat(o.uploaded(assignment).path("pageCount").asInt()).isEqualTo(8);

        JsonNode priced = o.review(Map.of(
                notes, Map.of("pages", "1-10", "duplex", "LONG_EDGE"),
                assignment, Map.of("pages", "7, 3", "copies", 2, "color", true),
                photo, Map.of("color", true, "paperSize", "A4", "scaling", "FIT")), notes, assignment, photo);
        assertThat(priced.path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        JsonNode docs = priced.path("documents");
        JsonNode n = docs.get(0), a = docs.get(1), p = docs.get(2);
        assertThat(n.path("settings").path("pages").isNull()).isTrue();          // 1-10 of 10 = every page
        assertThat(n.path("pagesText").asText()).isEqualTo("All 10 pages");
        assertThat(n.path("sheets").asInt()).isEqualTo(5);
        assertThat(n.path("amountPaise").asInt()).isEqualTo(10 * 200);
        assertThat(a.path("settings").path("pages").asText()).isEqualTo("3,7");
        assertThat(a.path("pagesText").asText()).isEqualTo("3, 7");
        assertThat(a.path("printPages").asInt()).isEqualTo(2);
        assertThat(a.path("amountPaise").asInt()).isEqualTo(2 * 2 * 1000);
        assertThat(p.path("pagesText").asText()).isEqualTo("Picture");
        assertThat(p.path("amountPaise").asInt()).isEqualTo(1000);
        assertThat(priced.path("amountPaise").asInt()).isEqualTo(2000 + 4000 + 1000);
        assertThat(priced.path("totalSheets").asInt()).isEqualTo(5 + 2 * 2 + 1);

        JsonNode paid = o.pay();
        assertThat(paid.path("status").asText()).isEqualTo("QUEUED");

        // The B/W printer can only take the B/W two-sided notes.
        JsonNode job1 = claim(bwPrinter);
        assertThat(job1.path("jobId").asText()).isEqualTo(notes);
        assertThat(job1.path("settings").path("duplex").asText()).isEqualTo("LONG_EDGE");
        assertThat(job1.path("paper").path("widthMm").asDouble()).isEqualTo(210.0);
        assertThat(job1.path("documentNumber").asInt()).isEqualTo(1);
        assertThat(job1.path("documentCount").asInt()).isEqualTo(3);
        assertThat(claim(bwPrinter)).isNull();

        // The colour printer takes the colour pages, exactly as the student saw them.
        JsonNode job2 = claim(colourPrinter);
        assertThat(job2.path("jobId").asText()).isEqualTo(assignment);
        assertThat(job2.path("settings").path("pages").asText()).isEqualTo("3,7");
        assertThat(job2.path("settings").path("copies").asInt()).isEqualTo(2);
        assertThat(job2.path("printPages").asInt()).isEqualTo(2);
        JsonNode job3 = claim(colourPrinter);
        assertThat(job3.path("jobId").asText()).isEqualTo(photo);
        assertThat(job3.path("image").path("exifOrientation").asInt()).isEqualTo(6);

        for (JsonNode job : List.of(job1, job2)) printed(job);
        assertThat(o.view().path("status").asText()).isEqualTo("PRINTING");
        assertThat(o.view().path("documentsDone").asInt()).isEqualTo(2);
        printed(job3);
        JsonNode done = o.view();
        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.path("documents").get(0).path("stage").asText()).isEqualTo("Printed on Printer 1 (B/W)");
        for (String path : o.uploads.values()) {
            assertThat(storage.has(path)).as("printed files are deleted").isFalse();
        }

        // The counter sees one order with three files and hands it over.
        JsonNode ready = counter(get("/api/v1/counter/orders?view=ready"));
        assertThat(ready).hasSize(1);
        assertThat(ready.get(0).path("documents")).hasSize(3);
        assertThat(ready.get(0).path("documents").get(0).path("settingsText").asText())
                .isEqualTo("B/W · A4 · two-sided");
        assertThat(ready.get(0).path("totalSheets").asInt()).isEqualTo(10);
        counter(post("/api/v1/counter/orders/" + o.id + "/collected"));
        assertThat(o.view().path("collected").asBoolean()).isTrue();
    }

    @Test
    void settingsNoPrinterCanDoAreRefusedBeforePayment() throws Exception {
        Order o = newOrder();
        String doc = o.add("Colour.pdf", "PDF", Files.pdf(3));
        o.uploaded(doc);
        MvcResult r = o.reviewRaw(Map.of(doc, Map.of("color", true, "duplex", "LONG_EDGE")), doc);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = json(r);
        assertThat(body.path("error").asText()).isEqualTo("CHECK_SETTINGS");
        assertThat(body.path("documents").get(0).path("id").asText()).isEqualTo(doc);
        assertThat(body.path("documents").get(0).path("message").asText())
                .isEqualTo("No single printer can do colour + two-sided together. Change one of these choices.");

        r = o.reviewRaw(Map.of(doc, Map.of("paperSize", "LEGAL")), doc);
        assertThat(json(r).path("documents").get(0).path("message").asText())
                .isEqualTo("No printer takes Legal (8.5 × 14 in) paper. Choose another paper size.");

        r = o.reviewRaw(Map.of(doc, Map.of("pages", "2-9")), doc);
        assertThat(json(r).path("documents").get(0).path("message").asText())
                .isEqualTo("Page 9 does not exist: this PDF has 3 pages.");

        r = o.reviewRaw(Map.of(doc, Map.of("pages", "2", "staple", "TOP_LEFT")), doc);
        assertThat(json(r).path("documents").get(0).path("message").asText())
                .startsWith("Stapling needs at least 2 sheets");

        // Nothing was saved: the order is still a draft.
        assertThat(o.view().path("status").asText()).isEqualTo("AWAITING_UPLOAD");
    }

    @Test
    void theSummaryShowsTheTidyChoiceThatWillPrint() throws Exception {
        Order o = newOrder();
        String doc = o.add("Book.pdf", "PDF", Files.pdf(12));
        o.uploaded(doc);
        JsonNode priced = o.review(Map.of(doc, Map.of("pages", "8, 1-3, 2", "pagesPerSheet", 2,
                "scaling", "ACTUAL", "copies", 1, "collate", false)), doc);
        JsonNode d = priced.path("documents").get(0);
        assertThat(d.path("settings").path("pages").asText()).isEqualTo("1-3,8");
        assertThat(d.path("pagesText").asText()).isEqualTo("1–3, 8");
        assertThat(d.path("printPages").asInt()).isEqualTo(4);
        assertThat(d.path("sides").asInt()).isEqualTo(2);
        assertThat(d.path("settings").path("scaling").asText()).isEqualTo("FIT");    // several per sheet: fitted
        assertThat(d.path("settings").path("collate").asBoolean()).isTrue();         // one copy
        assertThat(d.path("amountPaise").asInt()).isEqualTo(400);
    }

    @Test
    void draftsCanBeChangedUntilPaymentStarts() throws Exception {
        Order o = newOrder();
        String a = o.add("A.pdf", "PDF", Files.pdf(1));
        String b = o.add("B.png", "PNG", Files.png(20, 10));
        o.uploaded(a);
        o.uploaded(b);
        json(mvc.perform(key(delete("/api/v1/orders/" + o.id + "/documents/" + b), o)).andReturn());
        assertThat(o.view().path("documents")).hasSize(1);
        o.review(Map.of(a, Map.of()), a);

        // Priced: adding a file needs "edit" first.
        MvcResult r = mvc.perform(key(post("/api/v1/orders/" + o.id + "/documents"), o)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"C.pdf\",\"fileType\":\"PDF\",\"fileSizeBytes\":100}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        JsonNode edited = json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/edit"), o)).andReturn());
        assertThat(edited.path("status").asText()).isEqualTo("AWAITING_UPLOAD");
        String c = o.add("C.pdf", "PDF", Files.pdf(2));
        o.uploaded(c);
        JsonNode priced = o.review(Map.of(a, Map.of(), c, Map.of("copies", 3)), c, a);   // new print order
        assertThat(priced.path("documents").get(0).path("fileName").asText()).isEqualTo("C.pdf");
        assertThat(priced.path("amountPaise").asInt()).isEqualTo(3 * 2 * 200 + 200);
    }

    @Test
    void aFileThatCannotBePrintedIsRefusedAndMustBeRemoved() throws Exception {
        Order o = newOrder();
        String bad = o.add("broken.pdf", "PDF", "this is not a pdf".getBytes(StandardCharsets.UTF_8));
        String good = o.add("ok.pdf", "PDF", Files.pdf(1));
        JsonNode view = o.uploaded(bad);
        assertThat(view.path("status").asText()).isEqualTo("REJECTED");
        assertThat(view.path("problem").asText()).isEqualTo("Only PDF, Word (.docx), PNG and JPG files can be printed.");
        o.uploaded(good);
        MvcResult r = o.reviewRaw(Map.of(good, Map.of()), good);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r).path("documents").get(0).path("fileName").asText()).isEqualTo("broken.pdf");
    }

    @Test
    void theCounterCanCancelOneFileNoPrinterCanDo() throws Exception {
        Order o = newOrder();
        String a3 = o.add("Poster.pdf", "PDF", Files.pdf(1));
        String plain = o.add("Notes.pdf", "PDF", Files.pdf(1));
        o.uploaded(a3);
        o.uploaded(plain);
        o.review(Map.of(a3, Map.of("paperSize", "A3"), plain, Map.of()), a3, plain);
        o.pay();
        // The A3 printer is switched off after payment: nobody can print the poster today.
        counter(post("/api/v1/counter/printers/" + bwPrinter + "/enabled").content("{\"enabled\":false}"));
        JsonNode summary = counter(get("/api/v1/counter/summary"));
        assertThat(summary.path("noPrinter").asInt()).isEqualTo(2);
        JsonNode docs = counter(get("/api/v1/counter/orders?view=active")).get(0).path("documents");
        assertThat(docs.get(0).path("noPrinter").asBoolean()).isTrue();
        counter(post("/api/v1/counter/documents/" + a3 + "/cancel"));
        JsonNode view = o.view();
        assertThat(view.path("refundDuePaise").asInt()).isEqualTo(400);
        assertThat(view.path("message").asText()).contains("A refund of ₹4 is due");
    }

    // ------------------------------------------------------------------ collecting by showing the files (no pickup code)

    @Test
    void theStudentShowsTheFilesAtTheCounterAndStaffHandThemOver() throws Exception {
        Order o = newOrder();
        String notes = o.add("Notes.pdf", "PDF", Files.pdf(4));
        String photo = o.add("Photo.jpg", "JPEG", Files.jpegWithOrientation(400, 300, 1));
        o.uploaded(notes);
        o.uploaded(photo);
        o.review(Map.of(notes, Map.of(), photo, Map.of("color", true)), notes, photo);

        // Not paid: there is nothing to collect, and nobody shows up on the staff screen.
        MvcResult early = mvc.perform(key(post("/api/v1/orders/" + o.id + "/arrive"), o)).andReturn();
        assertThat(early.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(early).path("error").asText()).isEqualTo("NOT_PAID");

        JsonNode paid = o.pay();
        assertThat(paid.path("serverTime").asText()).isNotEmpty();
        assertThat(paid.path("arrivedAt").isNull()).isTrue();
        assertThat(counter(get("/api/v1/counter/summary")).path("atCounter")).isEmpty();

        // The Xerox PC prints both files and sends a picture of each first sheet.
        JsonNode job1 = claim(bwPrinter);
        JsonNode job2 = claim(colourPrinter);
        byte[] picture = Files.jpegWithOrientation(120, 170, 1);
        sendPreview(job1, picture, 200);
        printed(job1);
        printed(job2);
        sendPreview(job2, picture, 200);              // also taken just after the file printed

        JsonNode ready = o.view();
        assertThat(ready.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(ready.path("message").asText()).contains("show your files")
                .doesNotContain(ready.path("pickupCode").asText());
        assertThat(ready.path("documents").get(0).path("hasPreview").asBoolean()).isTrue();
        assertThat(ready.path("documents").get(0).path("printedAt").asText()).isNotEmpty();
        // The student can fetch the picture of their own file; nobody else can.
        MvcResult mine = mvc.perform(key(get("/api/v1/orders/" + o.id + "/documents/" + notes + "/preview"), o)).andReturn();
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(mine.getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(mine.getResponse().getContentAsByteArray()).isEqualTo(picture);
        Order other = newOrder();
        assertThat(mvc.perform(key(get("/api/v1/orders/" + o.id + "/documents/" + notes + "/preview"), other)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/counter/documents/" + notes + "/preview")).andReturn()
                .getResponse().getStatus()).isEqualTo(401);

        // At the counter the student taps "I'm at the counter": the order is on the staff screen, with its files.
        JsonNode here = json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/arrive"), o)).andReturn());
        assertThat(here.path("arrivedAt").asText()).isNotEmpty();
        JsonNode at = counter(get("/api/v1/counter/summary")).path("atCounter");
        assertThat(at).hasSize(1);
        assertThat(at.get(0).path("id").asText()).isEqualTo(o.id);
        assertThat(at.get(0).path("arrivedAt").asText()).isNotEmpty();
        assertThat(at.get(0).path("documents").get(0).path("hasPreview").asBoolean()).isTrue();
        assertThat(at.get(0).path("documents").get(0).path("printerName").asText()).isEqualTo("Printer 1 (B/W)");
        MvcResult pic = mvc.perform(get("/api/v1/counter/documents/" + notes + "/preview")
                .header("X-Counter-Password", COUNTER)).andReturn();
        assertThat(pic.getResponse().getContentAsByteArray()).isEqualTo(picture);

        // Someone with another order's key cannot put this order on the staff screen...
        assertThat(mvc.perform(key(post("/api/v1/orders/" + o.id + "/arrive"), other)).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        // ...and leaving that screen takes the order off it again.
        json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/arrive"), o).contentType(MediaType.APPLICATION_JSON)
                .content("{\"here\":false}")).andReturn());
        assertThat(counter(get("/api/v1/counter/summary")).path("atCounter")).isEmpty();
        json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/arrive"), o)).andReturn());

        // Staff hand the pages over: once. The phone shows "Collected", and the pictures are gone.
        JsonNode handed = counter(post("/api/v1/counter/orders/" + o.id + "/collected"));
        assertThat(handed.path("studentAtCounter").asBoolean()).isTrue();
        JsonNode done = o.view();
        assertThat(done.path("collected").asBoolean()).isTrue();
        assertThat(done.path("collectedAt").asText()).isNotEmpty();
        assertThat(done.path("stage").asText()).isEqualTo("Collected");
        assertThat(done.path("documents").get(0).path("hasPreview").asBoolean()).isFalse();
        assertThat(counter(get("/api/v1/counter/summary")).path("atCounter")).isEmpty();
        MvcResult again = mvc.perform(post("/api/v1/counter/orders/" + o.id + "/collected")
                .header("X-Counter-Password", COUNTER)).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(again).path("message").asText()).contains("already handed over");
        assertThat(jdbc.queryForObject("select count(*) from document_previews", Integer.class)).isZero();
        // Tapping again after that changes nothing.
        assertThat(json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/arrive"), o)).andReturn())
                .path("arrivedAt").isNull()).isTrue();
    }

    @Test
    void onlyThePcPrintingAFileCanSendItsPicture() throws Exception {
        Order o = newOrder();
        String doc = o.add("Notes.pdf", "PDF", Files.pdf(1));
        o.uploaded(doc);
        o.review(Map.of(doc, Map.of()), doc);
        o.pay();
        JsonNode job = claim(bwPrinter);
        byte[] picture = Files.jpegWithOrientation(120, 170, 1);
        String url = "/agent/v1/jobs/" + job.path("jobId").asText() + "/preview";

        // Another claim (an older try at this file, or a guess): refused.
        assertThat(mvc.perform(agentAuth(post(url).header("X-Claim-Token", UUID.randomUUID().toString())
                .contentType(MediaType.IMAGE_JPEG).content(picture))).andReturn().getResponse().getStatus()).isEqualTo(409);
        // Not signed in as a PC: refused.
        assertThat(mvc.perform(post(url).header("X-Claim-Token", job.path("claimToken").asText())
                .contentType(MediaType.IMAGE_JPEG).content(picture)).andReturn().getResponse().getStatus()).isEqualTo(401);
        // Not a picture, or far too big: refused.
        sendPreview(job, ("<html>" + "x".repeat(300) + "</html>").getBytes(StandardCharsets.UTF_8), 400);
        byte[] huge = new byte[400 * 1024];
        System.arraycopy(picture, 0, huge, 0, picture.length);
        sendPreview(job, huge, 400);
        assertThat(o.view().path("documents").get(0).path("hasPreview").asBoolean()).isFalse();
        sendPreview(job, picture, 200);
        assertThat(o.view().path("documents").get(0).path("hasPreview").asBoolean()).isTrue();
    }

    @Test
    void beingAtTheCounterWearsOffByItself() throws Exception {
        Order o = newOrder();
        String doc = o.add("Notes.pdf", "PDF", Files.pdf(1));
        o.uploaded(doc);
        o.review(Map.of(doc, Map.of()), doc);
        o.pay();
        json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/arrive"), o)).andReturn());
        assertThat(counter(get("/api/v1/counter/summary")).path("atCounter")).hasSize(1);
        // The student walked off without closing the screen: a few minutes later the order is off the staff screen.
        jdbc.update("update orders set arrived_at = now() - interval '10 minutes' where id = ?::uuid", o.id);
        assertThat(o.view().path("arrivedAt").isNull()).isTrue();
        assertThat(counter(get("/api/v1/counter/summary")).path("atCounter")).isEmpty();
        // Staff can still hand it over (found in the list); the answer says the phone was not at the counter.
        printed(claim(bwPrinter));
        assertThat(counter(post("/api/v1/counter/orders/" + o.id + "/collected")).path("studentAtCounter").asBoolean())
                .isFalse();
    }

    @Test
    void staffFindAnOrderByAFileNameWhenThePhoneIsOffline() throws Exception {
        Order a = newOrder();
        String da = a.add("Thermodynamics Notes.pdf", "PDF", Files.pdf(1));
        a.uploaded(da);
        a.review(Map.of(da, Map.of()), da);
        a.pay();
        Order b = newOrder();
        String db = b.add("Resume.pdf", "PDF", Files.pdf(1));
        b.uploaded(db);
        b.review(Map.of(db, Map.of()), db);
        b.pay();
        Order unpaid = newOrder();
        String du = unpaid.add("Thermo draft.pdf", "PDF", Files.pdf(1));
        unpaid.uploaded(du);
        unpaid.review(Map.of(du, Map.of()), du);

        JsonNode found = counter(get("/api/v1/counter/orders").param("q", "thermo"));
        assertThat(found).hasSize(1);                                       // paid orders only
        assertThat(found.get(0).path("id").asText()).isEqualTo(a.id);
        String number = b.view().path("pickupCode").asText();
        assertThat(counter(get("/api/v1/counter/orders").param("q", number.toLowerCase())).get(0).path("id").asText())
                .isEqualTo(b.id);
        assertThat(counter(get("/api/v1/counter/orders").param("q", "no such file"))).isEmpty();
        // A Station before 4.3 still looks an order up by its number.
        assertThat(counter(get("/api/v1/counter/orders").param("code", number)).get(0).path("id").asText()).isEqualTo(b.id);
    }

    @Test
    void aPaidOrderSaysAboutWhenItWillBeReady() throws Exception {
        Order o = newOrder();
        String doc = o.add("Notes.pdf", "PDF", Files.pdf(6));
        o.uploaded(doc);
        o.review(Map.of(doc, Map.of("copies", 2)), doc);
        // No printer is online yet: when it prints cannot be known.
        assertThat(o.pay().path("estimatedReadyAt").isNull()).isTrue();
        agent(post("/agent/v1/heartbeat").content("{\"agentVersion\":\"4.3.0\",\"hostName\":\"test\",\"printers\":["
                + "{\"printerId\":\"" + bwPrinter + "\",\"status\":\"READY\",\"detail\":\"\"}]}"));
        JsonNode waiting = o.view();
        Instant now = Instant.parse(waiting.path("serverTime").asText());
        Instant about = Instant.parse(waiting.path("estimatedReadyAt").asText());
        assertThat(Duration.between(now, about)).isBetween(Duration.ofSeconds(15), Duration.ofMinutes(5));
        // A longer queue ahead means later.
        Order behind = newOrder();
        String d2 = behind.add("More.pdf", "PDF", Files.pdf(6));
        behind.uploaded(d2);
        behind.review(Map.of(d2, Map.of()), d2);
        behind.pay();
        assertThat(Instant.parse(behind.view().path("estimatedReadyAt").asText())).isAfter(about);
        printed(claim(bwPrinter));
        JsonNode done = o.view();
        assertThat(done.path("estimatedReadyAt").isNull()).isTrue();
        assertThat(done.path("completedAt").asText()).isNotEmpty();
    }

    // ------------------------------------------------------------------ the counter password

    @Test
    void aSignedInCounterKeepsWorkingWhileWrongPasswordsMustWait() throws Exception {
        String token = counter(post("/api/v1/counter/session")).path("token").asText();
        RequestPostProcessor guesser = r -> {
            r.setRemoteAddr("203.0.113.9");
            return r;
        };
        for (int i = 0; i < 10; i++) {
            assertThat(mvc.perform(get("/api/v1/counter/summary").header("X-Counter-Password", "guess-number-" + i)
                    .with(guesser)).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        // That address has to wait now, even if the next guess were right...
        assertThat(mvc.perform(get("/api/v1/counter/summary").header("X-Counter-Password", COUNTER).with(guesser))
                .andReturn().getResponse().getStatus()).isEqualTo(429);
        // ...but a screen that signed in before is not disturbed, wherever it is.
        assertThat(mvc.perform(get("/api/v1/counter/summary").header("X-Counter-Session", token).with(guesser))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/v1/counter/summary").header("X-Counter-Session", token))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        // A made-up or changed token is nothing.
        assertThat(mvc.perform(get("/api/v1/counter/summary").header("X-Counter-Session", "1999999999.abc.def"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/counter/summary").header("X-Counter-Session", token + "x"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/counter/summary")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ one-file apps (Android)

    @Test
    void theOneFileWayStillWorks() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"old.pdf\",\"fileType\":\"PDF\",\"fileSizeBytes\":500,\"color\":false,"
                        + "\"copies\":2,\"pages\":\"2-3\"}")).andReturn();
        JsonNode c = json(created);
        Order o = new Order(c.path("orderId").asText(), c.path("accessKey").asText());
        storage.upload(c.path("uploadUrl").asText(), Files.pdf(5));
        JsonNode view = json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/uploaded"), o)).andReturn());
        assertThat(view.path("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(view.path("fileName").asText()).isEqualTo("old.pdf");
        assertThat(view.path("pages").asText()).isEqualTo("2-3");
        assertThat(view.path("printPages").asInt()).isEqualTo(2);
        assertThat(view.path("copies").asInt()).isEqualTo(2);
        assertThat(view.path("amountPaise").asInt()).isEqualTo(2 * 2 * 200);
        o.pay();

        // An older Station (before version 4) prints it with the old API.
        JsonNode old = agent(post("/agent/v1/orders/claim").content("{\"printerId\":\"" + bwPrinter + "\"}"));
        assertThat(old.path("orderId").asText()).isEqualTo(c.path("documentId").asText());
        assertThat(old.path("pages").asText()).isEqualTo("2-3");
        for (String s : List.of("DOWNLOADING", "SUBMITTED", "COMPLETED")) {
            agent(post("/agent/v1/orders/" + old.path("orderId").asText() + "/status")
                    .content("{\"claimToken\":\"" + old.path("claimToken").asText() + "\",\"status\":\"" + s + "\"}"));
        }
        assertThat(o.view().path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void theOneFileWayRefusesImpossiblePagesAsBefore() throws Exception {
        JsonNode c = json(mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"x.pdf\",\"fileType\":\"PDF\",\"fileSizeBytes\":500,\"copies\":1,"
                        + "\"pages\":\"4-9\"}")).andReturn());
        Order o = new Order(c.path("orderId").asText(), c.path("accessKey").asText());
        storage.upload(c.path("uploadUrl").asText(), Files.pdf(5));
        MvcResult r = mvc.perform(key(post("/api/v1/orders/" + o.id + "/uploaded"), o)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r).path("error").asText()).isEqualTo("BAD_PAGES");
        assertThat(o.view().path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void anOlderStationNeverGetsWorkItWouldPrintWrongly() throws Exception {
        Order o = newOrder();
        String doc = o.add("TwoSided.pdf", "PDF", Files.pdf(4));
        o.uploaded(doc);
        o.review(Map.of(doc, Map.of("duplex", "LONG_EDGE")), doc);
        o.pay();
        MvcResult r = mvc.perform(agentAuth(post("/agent/v1/orders/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"printerId\":\"" + bwPrinter + "\"}"))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        assertThat(claim(bwPrinter).path("jobId").asText()).isEqualTo(doc);
    }

    // ------------------------------------------------------------------ helpers

    // ------------------------------------------------------------------ two printers, one order, the same moment

    /**
     * Two files of one order print on two printers, and both printers report
     * at the same instant, again and again. Nothing may be lost to the two
     * waiting for each other (the database then gives one of them up).
     */
    @Test
    void twoPrintersFinishingFilesOfOneOrderAtTheSameMomentBothCount() throws Exception {
        java.util.concurrent.ExecutorService two = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            agentAuth(post("/agent/v1/heartbeat"));                        // sign the PC in once, before the threads
            for (int round = 0; round < 25; round++) {
                Order o = newOrder();
                String a = o.add("A.pdf", "PDF", Files.pdf(1));
                String b = o.add("B.png", "PNG", Files.png(20, 10));
                o.uploaded(a);
                o.uploaded(b);
                o.review(Map.of(a, Map.of(), b, Map.of("color", true)), a, b);      // A to the B/W printer, B to the colour one
                o.pay();
                JsonNode first = claim(bwPrinter);
                JsonNode second = claim(colourPrinter);
                assertThat(first).isNotNull();
                assertThat(second).isNotNull();
                for (String status : List.of("DOWNLOADING", "SUBMITTED", "COMPLETED")) {
                    java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
                    List<java.util.concurrent.Future<Integer>> answers = new java.util.ArrayList<>();
                    for (JsonNode job : List.of(first, second)) {
                        answers.add(two.submit(() -> {
                            together.await();
                            return mvc.perform(post("/agent/v1/jobs/" + job.path("jobId").asText() + "/status")
                                    .header("Authorization", "Bearer " + agentToken).contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"claimToken\":\"" + job.path("claimToken").asText() + "\",\"status\":\"" + status + "\"}"))
                                    .andReturn().getResponse().getStatus();
                        }));
                    }
                    for (java.util.concurrent.Future<Integer> f : answers) {
                        assertThat(f.get()).as("round " + round + ", " + status).isEqualTo(200);
                    }
                }
                assertThat(o.view().path("status").asText()).as("round " + round).isEqualTo("COMPLETED");
            }
        } finally {
            two.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ hundreds of screens asking "how is my order"

    @Test
    void theServerSaysHowSoonToAskAgain() throws Exception {
        Order o = newOrder();
        String a = o.add("A.pdf", "PDF", Files.pdf(1));
        o.uploaded(a);
        o.review(Map.of(a, Map.of()), a);
        assertThat(o.view().path("pollSeconds").asInt()).isEqualTo(3);        // about to pay: soon
        o.pay();
        assertThat(o.view().path("pollSeconds").asInt()).isEqualTo(6);        // waiting for a printer
        JsonNode job = claim(bwPrinter);                                       // a printer took it
        assertThat(o.view().path("pollSeconds").asInt()).isEqualTo(4);        // printing: it changes any moment
        printed(job);
        // ready, the student on the way: nothing changes until they are there, so seldom
        assertThat(o.view().path("pollSeconds").asInt()).isEqualTo(12);
        json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/arrive"), o).contentType(MediaType.APPLICATION_JSON)
                .content("{\"here\":true}")).andReturn());
        assertThat(o.view().path("pollSeconds").asInt()).isEqualTo(3);        // at the counter: "Collected" shows at once
        counter(post("/api/v1/counter/orders/" + o.id + "/collected"));
        assertThat(o.view().path("pollSeconds").asInt()).isZero();            // done: nothing more to ask
    }

    // ------------------------------------------------------------------ Word files

    /** The PC says with its heartbeat whether its Microsoft Word can make PDFs. */
    private void pcWithWord(boolean ready) throws Exception {
        agent(post("/agent/v1/heartbeat").content("{\"agentVersion\":\"4.3.0\",\"hostName\":\"test\",\"wordFiles\":"
                + ready + ",\"wordNote\":\"Microsoft Word 2013\"}"));
    }

    private JsonNode claimWord() throws Exception {
        MvcResult r = mvc.perform(agentAuth(post("/agent/v1/conversions/claim"))).andReturn();
        if (r.getResponse().getStatus() == 204) return null;
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private boolean wordFilesOffered() throws Exception {
        return json(mvc.perform(get("/api/v1/shop")).andReturn()).path("wordFiles").asBoolean();
    }

    @Test
    void aWordFileIsTurnedIntoPagesByTheXeroxPcAndThenPrintsLikeAnyPdf() throws Exception {
        jdbc.update("update agents set word_ready = false");
        Order o = newOrder();
        // no PC with Word: said before anything is uploaded
        assertThat(wordFilesOffered()).isFalse();
        MvcResult no = mvc.perform(key(post("/api/v1/orders/" + o.id + "/documents"), o).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"Report.docx\",\"fileType\":\"DOCX\",\"fileSizeBytes\":100}")).andReturn();
        assertThat(no.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(no).path("error").asText()).isEqualTo("WORD_NOT_AVAILABLE");
        assertThat(json(no).path("message").asText()).contains("Save the file as PDF");

        pcWithWord(true);
        assertThat(wordFilesOffered()).isTrue();
        byte[] docx = Docx.pages(3).bytes();
        String d = o.add("Report.docx", "DOCX", docx);
        String wordPath = o.uploads.get(d);
        assertThat(wordPath).endsWith(".docx");
        JsonNode waiting = o.uploaded(d);
        assertThat(waiting.path("status").asText()).isEqualTo("CONVERTING");
        assertThat(waiting.path("fileType").asText()).isEqualTo("DOCX");
        assertThat(waiting.path("stage").asText()).isEqualTo("Being prepared");
        assertThat(waiting.path("pageCount").isNull()).isTrue();
        // not yet: no price, no file to show
        assertThat(o.reviewRaw(Map.of(d, Map.of()), d).getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(key(get("/api/v1/orders/" + o.id + "/documents/" + d + "/file-url"), o)).andReturn()
                .getResponse().getStatus()).isEqualTo(409);

        // the Xerox PC takes it: exactly the file the server looked into, and a place for the PDF
        JsonNode job = claimWord();
        assertThat(job.path("documentId").asText()).isEqualTo(d);
        assertThat(job.path("fileName").asText()).isEqualTo("Report.docx");
        assertThat(job.path("sha256").asText()).isEqualTo(edu.campus.print.common.Secrets.sha256Hex(docx));
        assertThat(job.path("downloadUrl").asText()).endsWith(wordPath);
        assertThat(job.path("uploadUrl").asText()).endsWith(d + "-pages.pdf");
        assertThat(job.path("secondsAllowed").asInt()).isGreaterThan(30);
        assertThat(claimWord()).isNull();                         // one PC per file

        byte[] pages = Files.pdf(3);                              // what the PC's Word made of it
        storage.upload(job.path("uploadUrl").asText(), pages);
        assertThat(agent(post("/agent/v1/conversions/" + d + "/done")).path("status").asText()).isEqualTo("READY");

        // from here on it is a PDF with a Word file's name
        JsonNode doc = o.view().path("documents").get(0);
        assertThat(doc.path("status").asText()).isEqualTo("READY");
        assertThat(doc.path("fileType").asText()).isEqualTo("PDF");
        assertThat(doc.path("sourceType").asText()).isEqualTo("DOCX");
        assertThat(doc.path("fileName").asText()).isEqualTo("Report.docx");
        assertThat(doc.path("pageCount").asInt()).isEqualTo(3);
        assertThat(storage.has(wordPath)).isFalse();              // the Word file itself is gone
        String file = json(mvc.perform(key(get("/api/v1/orders/" + o.id + "/documents/" + d + "/file-url"), o)).andReturn())
                .path("url").asText();
        assertThat(file).endsWith(d + "-pages.pdf");

        // every setting a PDF has: pages 1-2, two copies, two-sided, stapled
        JsonNode priced = o.review(Map.of(d, Map.of("pages", "1-2", "copies", 2, "duplex", "LONG_EDGE")), d);
        assertThat(priced.path("documents").get(0).path("printPages").asInt()).isEqualTo(2);
        assertThat(priced.path("documents").get(0).path("sheets").asInt()).isEqualTo(1);
        assertThat(priced.path("amountPaise").asInt()).isEqualTo(2 * 2 * 200);
        o.pay();
        JsonNode print = claim(bwPrinter);
        assertThat(print.path("fileName").asText()).isEqualTo("Report.docx");
        assertThat(print.path("fileType").asText()).isEqualTo("PDF");
        assertThat(print.path("pageCount").asInt()).isEqualTo(3);
        assertThat(print.path("sha256").asText()).isEqualTo(edu.campus.print.common.Secrets.sha256Hex(pages));
        assertThat(print.path("settings").path("pages").asText()).isEqualTo("1-2");
        printed(print);
        assertThat(o.view().path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void aWordFileWithSomethingActiveNeverReachesTheXeroxPc() throws Exception {
        pcWithWord(true);
        Order o = newOrder();
        String macro = o.add("Macro.docx", "DOCX", Docx.plain().part("word/vbaProject.bin", new byte[]{1}).bytes());
        String dde = o.add("Dde.docx", "DOCX", Docx.plain().body(Docx.field(" DDEAUTO excel \"Book1.xls\" r1c1 ")).bytes());
        String old = o.add("Old.doc", "DOCX", join(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
                new byte[3000], "WordDocument".getBytes(StandardCharsets.UTF_16LE)));
        String zip = o.add("Sheet.xlsx", "DOCX", Docx.plain()
                .mainType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml").bytes());
        for (String d : List.of(macro, dde)) {
            JsonNode v = o.uploaded(d);
            assertThat(v.path("status").asText()).isEqualTo("REJECTED");
            assertThat(v.path("problem").asText()).contains("cannot be prepared automatically").contains("Save As");
        }
        assertThat(o.uploaded(old).path("problem").asText()).startsWith("This is an older kind of Word file (.doc)");
        assertThat(o.uploaded(zip).path("problem").asText()).contains("Save this file as PDF");
        assertThat(claimWord()).isNull();                         // nothing for the PC to open
        for (String d : List.of(macro, dde, old, zip)) assertThat(storage.has(o.uploads.get(d))).isFalse();   // the files are gone
    }

    @Test
    void aFileIsJudgedByWhatItIsNotWhatItIsCalled() throws Exception {
        jdbc.update("update agents set word_ready = false");
        Order o = newOrder();
        // called a PDF, really a Word file, and no PC with Word: refused, with the reason
        String d = o.add("Really-word.pdf", "PDF", Docx.plain().bytes());
        JsonNode v = o.uploaded(d);
        assertThat(v.path("status").asText()).isEqualTo("REJECTED");
        assertThat(v.path("problem").asText()).contains("not online right now");
        // with one: it is prepared like any Word file (its PDF goes to a place of its own)
        pcWithWord(true);
        String e = o.add("Also-word.pdf", "PDF", Docx.plain().bytes());
        assertThat(o.uploaded(e).path("status").asText()).isEqualTo("CONVERTING");
        JsonNode job = claimWord();
        assertThat(job.path("uploadUrl").asText()).endsWith(e + "-pages.pdf").doesNotEndWith(o.uploads.get(e));
        // called a Word file, really a PDF: simply a PDF
        String f = o.add("Really-pdf.docx", "DOCX", Files.pdf(2));
        JsonNode pdf = o.uploaded(f);
        assertThat(pdf.path("status").asText()).isEqualTo("READY");
        assertThat(pdf.path("fileType").asText()).isEqualTo("PDF");
    }

    @Test
    void aWordFileThePcCannotOpenIsRefusedWithAdvice() throws Exception {
        pcWithWord(true);
        Order o = newOrder();
        String d = o.add("Odd.docx", "DOCX", Docx.plain().bytes());
        o.uploaded(d);
        claimWord();
        // Word on that PC stopped working (not the file): back in line, once
        assertThat(agent(post("/agent/v1/conversions/" + d + "/failed").content("{\"code\":\"ENGINE\",\"message\":\"Word did not start\"}"))
                .path("status").asText()).isEqualTo("CONVERTING");
        assertThat(o.view().path("documents").get(0).path("status").asText()).isEqualTo("CONVERTING");
        assertThat(claimWord().path("documentId").asText()).isEqualTo(d);
        assertThat(agent(post("/agent/v1/conversions/" + d + "/failed").content("{\"code\":\"ENGINE\"}"))
                .path("status").asText()).isEqualTo("REJECTED");
        assertThat(o.view().path("documents").get(0).path("problem").asText()).contains("Try again in a few minutes");

        // the file itself: refused with the way that always works
        String e = o.add("Broken.docx", "DOCX", Docx.plain().bytes());
        o.uploaded(e);
        claimWord();
        agent(post("/agent/v1/conversions/" + e + "/failed").content("{\"code\":\"FAILED\",\"message\":\"C:\\\\secret\\\\path\"}"));
        JsonNode doc = find(o.view().path("documents"), "id", e);
        assertThat(doc.path("status").asText()).isEqualTo("REJECTED");
        assertThat(doc.path("problem").asText()).isEqualTo("Microsoft Word at the Xerox center could not open this file."
                + " In Word, choose File \u2192 Save As \u2192 PDF, and add the PDF.");     // nothing of the PC's own message
        assertThat(storage.has(o.uploads.get(e))).isFalse();

        // what comes back must really be a PDF
        String g = o.add("Fake.docx", "DOCX", Docx.plain().bytes());
        o.uploaded(g);
        JsonNode job = claimWord();
        storage.upload(job.path("uploadUrl").asText(), "not a pdf".getBytes(StandardCharsets.UTF_8));
        assertThat(agent(post("/agent/v1/conversions/" + g + "/done")).path("status").asText()).isEqualTo("REJECTED");
        assertThat(storage.has(o.uploads.get(g))).isFalse();
        assertThat(storage.has(job.path("uploadUrl").asText().substring(FakeStorage.UPLOAD.length()))).isFalse();
    }

    @Test
    void onlyThePcThatTookAWordFileCanFinishIt() throws Exception {
        pcWithWord(true);
        Order o = newOrder();
        String d = o.add("Mine.docx", "DOCX", Docx.plain().bytes());
        o.uploaded(d);
        JsonNode job = claimWord();
        String firstPc = agentToken;

        // another PC of the same center
        JsonNode pc2 = counter(post("/api/v1/counter/pcs").content("{\"name\":\"Second PC\"}"));
        String basic = Base64.getEncoder().encodeToString((pc2.path("agentId").asText() + ":" + pc2.path("agentSecret").asText())
                .getBytes(StandardCharsets.UTF_8));
        String other = json(mvc.perform(post("/agent/v1/token").header("Authorization", "Basic " + basic)).andReturn())
                .path("token").asText();
        storage.upload(job.path("uploadUrl").asText(), Files.pdf(1));
        assertThat(mvc.perform(post("/agent/v1/conversions/" + d + "/done").header("Authorization", "Bearer " + other))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(post("/agent/v1/conversions/" + d + "/failed").header("Authorization", "Bearer " + other)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"FAILED\"}")).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        // and nobody without a PC's sign-in at all
        assertThat(mvc.perform(post("/agent/v1/conversions/claim")).andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(o.view().path("documents").get(0).path("status").asText()).isEqualTo("CONVERTING");

        agentToken = firstPc;
        assertThat(agent(post("/agent/v1/conversions/" + d + "/done")).path("status").asText()).isEqualTo("READY");
        // a second "done" changes nothing
        assertThat(mvc.perform(agentAuth(post("/agent/v1/conversions/" + d + "/done"))).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        assertThat(o.view().path("documents").get(0).path("pageCount").asInt()).isEqualTo(1);
    }

    @Test
    void aWordFileNeverWaitsForEver() throws Exception {
        pcWithWord(true);
        Order o = newOrder();
        // several in line: each is told how many are before it
        String a = o.add("A.docx", "DOCX", Docx.plain().bytes());
        String b = o.add("B.docx", "DOCX", Docx.plain().bytes());
        String c = o.add("C.docx", "DOCX", Docx.plain().bytes());
        o.uploaded(a);
        Thread.sleep(15);
        o.uploaded(b);
        Thread.sleep(15);
        assertThat(o.uploaded(c).path("ahead").asInt()).isEqualTo(2);
        assertThat(find(o.view().path("documents"), "id", c).path("stage").asText()).isEqualTo("Being prepared \u2013 2 ahead");
        assertThat(wordFiles.expire()).isZero();                  // all is well: nothing is given up

        // the PC takes one and goes silent: after a while it is handed out again, and then given up
        assertThat(claimWord().path("documentId").asText()).isEqualTo(a);
        jdbc.update("update order_documents set convert_claimed_at = now() - interval '2 minutes' where id = ?::uuid", a);
        assertThat(claimWord().path("documentId").asText()).isEqualTo(a);         // the oldest first, again
        jdbc.update("update order_documents set convert_claimed_at = now() - interval '2 minutes' where id = ?::uuid", a);
        assertThat(claimWord().path("documentId").asText()).isEqualTo(b);         // a third time: no
        assertThat(wordFiles.expire()).isEqualTo(1);
        JsonNode gone = find(o.view().path("documents"), "id", a);
        assertThat(gone.path("status").asText()).isEqualTo("REJECTED");
        assertThat(gone.path("problem").asText()).contains("did not finish preparing");

        // ten minutes in line is the limit
        jdbc.update("update order_documents set convert_requested_at = now() - interval '11 minutes' where id = ?::uuid", c);
        assertThat(wordFiles.expire()).isEqualTo(1);
        assertThat(find(o.view().path("documents"), "id", c).path("status").asText()).isEqualTo("REJECTED");

        // the PC goes offline while a file waits: said as it is
        String e = o.add("E.docx", "DOCX", Docx.plain().bytes());
        o.uploaded(e);
        jdbc.update("update agents set last_seen_at = now() - interval '5 minutes'");
        jdbc.update("update order_documents set convert_requested_at = now() - interval '50 seconds' where id = ?::uuid", e);
        assertThat(wordFiles.expire()).isEqualTo(1);
        assertThat(find(o.view().path("documents"), "id", e).path("problem").asText()).contains("not online right now");
        assertThat(wordFilesOffered()).isFalse();

        // a removed file is nothing to prepare
        pcWithWord(true);
        String f = o.add("F.docx", "DOCX", Docx.plain().bytes());
        o.uploaded(f);
        json(mvc.perform(key(delete("/api/v1/orders/" + o.id + "/documents/" + f), o)).andReturn());
        // (b is still held by the PC from above)
        assertThat(claimWord()).isNull();
    }

    private static byte[] join(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private Order newOrder() throws Exception {
        JsonNode c = json(mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn());
        assertThat(c.path("uploadUrl").isNull()).isTrue();
        return new Order(c.path("orderId").asText(), c.path("accessKey").asText());
    }

    private final class Order {
        final String id;
        final String key;
        final Map<String, String> uploads = new HashMap<>();

        Order(String id, String key) {
            this.id = id;
            this.key = key;
        }

        String add(String name, String type, byte[] bytes) throws Exception {
            JsonNode t = json(mvc.perform(key(post("/api/v1/orders/" + id + "/documents"), this)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("fileName", name, "fileType", type,
                            "fileSizeBytes", bytes.length)))).andReturn());
            storage.upload(t.path("uploadUrl").asText(), bytes);
            String doc = t.path("document").path("id").asText();
            uploads.put(doc, t.path("uploadUrl").asText().substring(FakeStorage.UPLOAD.length()));
            return doc;
        }

        JsonNode uploaded(String doc) throws Exception {
            return json(mvc.perform(key(post("/api/v1/orders/" + id + "/documents/" + doc + "/uploaded"), this))
                    .andReturn());
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
            return mvc.perform(key(post("/api/v1/orders/" + id + "/review"), this)
                    .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
        }

        JsonNode pay() throws Exception {
            return json(mvc.perform(key(post("/api/v1/orders/" + id + "/payment/demo"), this)).andReturn());
        }

        JsonNode view() throws Exception {
            return json(mvc.perform(key(get("/api/v1/orders/" + id), this)).andReturn());
        }
    }

    private static MockHttpServletRequestBuilder key(MockHttpServletRequestBuilder b, Order o) {
        return b.header("X-Order-Key", o.key);
    }

    private JsonNode counter(MockHttpServletRequestBuilder b) throws Exception {
        MvcResult r = mvc.perform(b.header("X-Counter-Password", COUNTER).contentType(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
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

    private JsonNode heartbeatPrinters() throws Exception {
        return agent(post("/agent/v1/heartbeat").content("{\"agentVersion\":\"4.0.0\",\"hostName\":\"test\"}"))
                .path("printers");
    }

    private JsonNode claim(String printer) throws Exception {
        MvcResult r = mvc.perform(agentAuth(post("/agent/v1/jobs/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"printerId\":\"" + printer + "\"}"))).andReturn();
        if (r.getResponse().getStatus() == 204) return null;
        return json(r);
    }

    /** The Xerox PC sends the picture of a file's first sheet. */
    private void sendPreview(JsonNode job, byte[] jpeg, int expectedStatus) throws Exception {
        MvcResult r = mvc.perform(agentAuth(post("/agent/v1/jobs/" + job.path("jobId").asText() + "/preview")
                .header("X-Claim-Token", job.path("claimToken").asText())
                .contentType(MediaType.IMAGE_JPEG).content(jpeg))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expectedStatus);
    }

    private void printed(JsonNode job) throws Exception {
        for (String s : List.of("DOWNLOADING", "SUBMITTED", "COMPLETED")) {
            agent(post("/agent/v1/jobs/" + job.path("jobId").asText() + "/status")
                    .content("{\"claimToken\":\"" + job.path("claimToken").asText() + "\",\"status\":\"" + s + "\"}"));
        }
    }

    private static ObjectNode caps(List<String> sizes, boolean duplex, List<String> finishing, List<String> media,
                                   boolean borderless, boolean high) {
        ObjectNode c = JSON.createObjectNode();
        c.put("schema", 1);
        ArrayNode ps = c.putArray("paperSizes");
        for (String s : sizes) ps.addObject().put("id", s).putArray("marginsMm").add(4).add(4).add(4).add(4);
        c.put("duplex", duplex);
        ArrayNode f = c.putArray("finishing");
        finishing.forEach(f::add);
        ArrayNode m = c.putArray("mediaTypes");
        for (String id : media) m.addObject().put("id", id).put("name", "Glossy photo");
        c.put("borderless", borderless);
        c.put("highQuality", high);
        c.put("maxCopies", 999);
        return c;
    }

    private static JsonNode find(JsonNode list, String field, String value) {
        for (JsonNode n : list) if (value.equals(n.path(field).asText())) return n;
        throw new AssertionError("No " + field + "=" + value + " in " + list);
    }

    private static List<String> texts(JsonNode list) {
        return texts(list, null);
    }

    private static List<String> texts(JsonNode list, String field) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode n : list) out.add(field == null ? n.asText() : n.path(field).asText());
        return out;
    }

    private static JsonNode json(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() ? JSON.nullNode() : JSON.readTree(s);
    }
}
