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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The whole API against a real PostgreSQL, with storage in memory: a
 * student's multi-file order from upload to paper, the checks on settings,
 * printer features, the counter, and the one-file (Android) way.
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
        r.add("campus.supabase.service-key", () -> "test-key");
        r.add("campus.payment.mode", () -> "demo");
        r.add("campus.counter.password", () -> COUNTER);
        r.add("campus.agent.token-secret", () -> "a-test-token-secret-that-is-long-enough-123");
        r.add("campus.cors.allowed-origins", () -> "http://localhost:3000");
    }

    /** Every test starts with: one PC, a B/W duplex A3 stapling printer and a colour photo printer. */
    @BeforeEach
    void shop() throws Exception {
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
        assertThat(view.path("problem").asText()).isEqualTo("Only PDF, PNG and JPG files can be printed.");
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
