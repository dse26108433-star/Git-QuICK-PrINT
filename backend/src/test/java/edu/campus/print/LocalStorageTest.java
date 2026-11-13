package edu.campus.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campus.print.schedule.Housekeeping;
import edu.campus.print.storage.FileStorage;
import edu.campus.print.storage.LocalFileStorage;
import edu.campus.print.support.Files;
import edu.campus.print.support.TestDb;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The server as it runs on the Xerox center's own PC: an ordinary PostgreSQL
 * (nothing of Supabase's in it) and the files in a folder on the disk.
 *
 * Phones send files in pieces; a connection that drops carries on from where
 * it stopped. Everything else (checking, pricing, the print queue, the Xerox
 * PC fetching a file) is the same code as with cloud storage.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocalStorageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COUNTER = "counter-pass-123";
    private static final int PIECE = 150;                  // tiny pieces, so even a small test PDF is several
    private static String dbUrl;
    private static Path folder;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage storage;
    @Autowired Housekeeping housekeeping;

    private String pcId;
    private String pcSecret;
    private String agentToken;
    private String printer;

    @BeforeAll
    static void database() throws Exception {
        dbUrl = TestDb.plainDatabase();                     // empty: the server sets it up by itself
        folder = java.nio.file.Files.createTempDirectory("xeogo-files-test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> dbUrl);
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
        r.add("campus.database.plain-postgres", () -> "true");
        r.add("campus.storage.mode", () -> "local");
        r.add("campus.storage.dir", () -> folder.toString());
        r.add("campus.storage.chunk-bytes", () -> String.valueOf(PIECE));
        r.add("campus.storage.min-free-mb", () -> "1");
        r.add("campus.shop.shop-cache-millis", () -> "0");
        r.add("campus.payment.mode", () -> "demo");
        r.add("campus.counter.password", () -> COUNTER);
        r.add("campus.agent.token-secret", () -> "a-test-token-secret-that-is-long-enough-123");
        r.add("campus.cors.allowed-origins", () -> "http://localhost:3000");
    }

    @BeforeEach
    void shop() throws Exception {
        jdbc.update("delete from document_previews");
        jdbc.update("delete from order_events");
        jdbc.update("delete from order_documents");
        jdbc.update("delete from orders");
        jdbc.update("delete from printers");
        JsonNode pc = counter(post("/api/v1/counter/pcs").content("{\"name\":\"Test PC\"}"));
        pcId = pc.path("agentId").asText();
        pcSecret = pc.path("agentSecret").asText();
        agentToken = null;
        ObjectNode caps = JSON.createObjectNode();
        caps.put("schema", 1);
        caps.putArray("paperSizes").addObject().put("id", "A4").putArray("marginsMm").add(4).add(4).add(4).add(4);
        caps.put("duplex", true);
        caps.putArray("finishing");
        caps.putArray("mediaTypes");
        caps.put("borderless", false);
        caps.put("highQuality", false);
        caps.put("maxCopies", 999);
        printer = counter(post("/api/v1/counter/printers").content(JSON.writeValueAsString(Map.of(
                "name", "Printer 1", "windowsPrinterName", "Canon BW", "supportsColor", false,
                "acceptsBw", true, "agentId", pcId, "capabilities", caps, "capabilitiesHash", "h1"))))
                .path("id").asText();
    }

    // ------------------------------------------------------------------ the whole way, with real files on the disk

    @Test
    void theServerSetsUpAnOrdinaryPostgresByItselfAndKeepsFilesOnTheDisk() throws Exception {
        assertThat(storage).isInstanceOf(LocalFileStorage.class);
        JsonNode shop = json(mvc.perform(get("/api/v1/shop")).andReturn());
        assertThat(shop.path("maxDocuments").asInt()).isEqualTo(25);
        assertThat(jdbc.queryForObject("select version from schema_version where id = 1", String.class)).isNotBlank();
    }

    @Test
    void aFileSentInPiecesIsCheckedPricedFetchedByThePcAndThenDeleted() throws Exception {
        byte[] pdf = Files.pdf(7);
        assertThat(pdf.length).isGreaterThan(3 * PIECE);             // really several pieces

        Order o = newOrder();
        JsonNode ticket = o.ticket("Notes.pdf", "PDF", pdf.length);
        assertThat(ticket.path("uploadChunkBytes").asInt()).isEqualTo(PIECE);
        String link = ticket.path("uploadUrl").asText();
        assertThat(link).contains("/api/v1/files/up/");
        String doc = ticket.path("document").path("id").asText();

        // before anything arrived: nothing to check
        MvcResult early = mvc.perform(key(post("/api/v1/orders/" + o.id + "/documents/" + doc + "/uploaded"), o)).andReturn();
        assertThat(early.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(early).path("error").asText()).isEqualTo("UPLOAD_MISSING");

        JsonNode last = sendInPieces(link, pdf, 0);
        assertThat(last.path("complete").asBoolean()).isTrue();
        assertThat(last.path("received").asLong()).isEqualTo(pdf.length);
        Path onDisk = folder.resolve(storagePath(doc));
        assertThat(java.nio.file.Files.readAllBytes(onDisk)).isEqualTo(pdf);
        assertThat(java.nio.file.Files.exists(Path.of(onDisk + ".part"))).isFalse();

        JsonNode checked = o.uploaded(doc);
        assertThat(checked.path("status").asText()).isEqualTo("READY");
        assertThat(checked.path("pageCount").asInt()).isEqualTo(7);

        // the device lost its connection after the last byte and asks for a link again: told where the file stands
        MvcResult again = mvc.perform(key(post("/api/v1/orders/" + o.id + "/documents/" + doc + "/upload-url"), o)).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(again).path("error").asText()).isEqualTo("ALREADY_UPLOADED");
        assertThat(json(again).path("document").path("status").asText()).isEqualTo("READY");
        assertThat(json(again).path("document").path("pageCount").asInt()).isEqualTo(7);

        // the student's own device reads the file back (after a reload)
        String mine = json(mvc.perform(key(get("/api/v1/orders/" + o.id + "/documents/" + doc + "/file-url"), o)).andReturn())
                .path("url").asText();
        assertThat(mvc.perform(get(URI.create(mine))).andReturn().getResponse().getContentAsByteArray()).isEqualTo(pdf);

        assertThat(o.review(doc).path("amountPaise").asInt()).isGreaterThan(0);
        assertThat(o.pay().path("status").asText()).isEqualTo("QUEUED");

        // the Xerox PC takes it and fetches exactly the file that was checked
        JsonNode job = claim();
        assertThat(job.path("jobId").asText()).isEqualTo(doc);
        String fetch = agent(get("/agent/v1/jobs/" + doc + "/download-url")
                .header("X-Claim-Token", job.path("claimToken").asText())).path("url").asText();
        assertThat(fetch).contains("/api/v1/files/dl/");
        MvcResult file = mvc.perform(get(URI.create(fetch))).andReturn();
        assertThat(file.getResponse().getStatus()).isEqualTo(200);
        assertThat(file.getResponse().getContentAsByteArray()).isEqualTo(pdf);
        assertThat(file.getResponse().getContentType()).isEqualTo("application/pdf");

        for (String s : List.of("DOWNLOADING", "SUBMITTED", "COMPLETED")) {
            agent(post("/agent/v1/jobs/" + doc + "/status")
                    .content("{\"claimToken\":\"" + job.path("claimToken").asText() + "\",\"status\":\"" + s + "\"}"));
        }
        assertThat(o.view().path("status").asText()).isEqualTo("COMPLETED");
        assertThat(java.nio.file.Files.exists(onDisk)).as("a printed file is deleted from the disk").isFalse();
        assertThat(java.nio.file.Files.exists(onDisk.getParent())).as("and the order's empty folder with it").isFalse();
    }

    // ------------------------------------------------------------------ a connection that drops

    @Test
    void anUploadThatStoppedCarriesOnFromWhereItStopped() throws Exception {
        byte[] pdf = Files.pdf(9);
        Order o = newOrder();
        JsonNode ticket = o.ticket("Big.pdf", "PDF", pdf.length);
        String link = ticket.path("uploadUrl").asText();
        String doc = ticket.path("document").path("id").asText();

        // the first piece arrives, then the connection is gone
        assertThat(json(piece(link, pdf, 0, PIECE)).path("received").asLong()).isEqualTo(PIECE);

        // a device that skips ahead is told where the file really stands, and nothing is written
        MvcResult gap = piece(link, pdf, 2 * PIECE, PIECE);
        assertThat(gap.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(gap).path("received").asLong()).isEqualTo(PIECE);

        // "how much have you got?"
        JsonNode state = json(mvc.perform(get(URI.create(link))).andReturn());
        assertThat(state.path("received").asLong()).isEqualTo(PIECE);
        assertThat(state.path("complete").asBoolean()).isFalse();

        // not yet a file as far as the order is concerned
        MvcResult early = mvc.perform(key(post("/api/v1/orders/" + o.id + "/documents/" + doc + "/uploaded"), o)).andReturn();
        assertThat(json(early).path("error").asText()).isEqualTo("UPLOAD_MISSING");

        // a piece whose answer was lost is sent again: harmless
        assertThat(json(piece(link, pdf, 0, PIECE)).path("received").asLong()).isEqualTo(PIECE);

        // a new link for the same file (the app's "Try again") carries on with the same half-sent file
        String link2 = json(mvc.perform(key(post("/api/v1/orders/" + o.id + "/documents/" + doc + "/upload-url"), o)).andReturn())
                .path("uploadUrl").asText();
        assertThat(link2).isNotEqualTo("").contains("/api/v1/files/up/");
        assertThat(json(mvc.perform(get(URI.create(link2))).andReturn()).path("received").asLong()).isEqualTo(PIECE);
        JsonNode last = sendInPieces(link2, pdf, PIECE);
        assertThat(last.path("complete").asBoolean()).isTrue();

        // the very last piece again (its answer was lost): still whole, still the same file
        int lastStart = (pdf.length - 1) / PIECE * PIECE;
        JsonNode repeat = json(piece(link2, pdf, lastStart, pdf.length - lastStart));
        assertThat(repeat.path("complete").asBoolean()).isTrue();
        assertThat(java.nio.file.Files.readAllBytes(folder.resolve(storagePath(doc)))).isEqualTo(pdf);

        assertThat(o.uploaded(doc).path("pageCount").asInt()).isEqualTo(9);
    }

    @Test
    void aWholeFileInOneRequestStillWorks() throws Exception {
        // the Xerox PC's printing part (a Word file turned into pages) and older apps send a file in one go
        byte[] pdf = Files.pdf(3);
        Order o = newOrder();
        JsonNode ticket = o.ticket("One.pdf", "PDF", pdf.length);
        MvcResult r = mvc.perform(put(URI.create(ticket.path("uploadUrl").asText()))
                .contentType(MediaType.APPLICATION_PDF).content(pdf)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(r).path("complete").asBoolean()).isTrue();
        assertThat(o.uploaded(ticket.path("document").path("id").asText()).path("pageCount").asInt()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ what must never get in

    @Test
    void onlyAGenuineLinkForThatUseOpensAFile() throws Exception {
        byte[] pdf = Files.pdf(2);
        Order o = newOrder();
        JsonNode ticket = o.ticket("A.pdf", "PDF", pdf.length);
        String link = ticket.path("uploadUrl").asText();
        String doc = ticket.path("document").path("id").asText();
        sendInPieces(link, pdf, 0);
        o.uploaded(doc);
        String read = json(mvc.perform(key(get("/api/v1/orders/" + o.id + "/documents/" + doc + "/file-url"), o)).andReturn())
                .path("url").asText();

        // a made-up ticket, and a genuine one with one letter changed
        assertThat(mvc.perform(get("/api/v1/files/dl/abc.def")).andReturn().getResponse().getStatus()).isEqualTo(403);
        String forged = read.substring(0, read.length() - 2) + (read.endsWith("A") ? "BB" : "AA");
        assertThat(mvc.perform(get(URI.create(forged))).andReturn().getResponse().getStatus()).isEqualTo(403);

        // a ticket to send is not a ticket to read, nor the other way round
        String token = link.substring(link.lastIndexOf('/') + 1);
        assertThat(mvc.perform(get("/api/v1/files/dl/" + token)).andReturn().getResponse().getStatus()).isEqualTo(403);
        String readToken = read.substring(read.lastIndexOf('/') + 1);
        assertThat(mvc.perform(put("/api/v1/files/up/" + readToken).content(pdf)).andReturn().getResponse().getStatus())
                .isEqualTo(403);

        // a ticket that points outside the files folder is refused even if someone could sign it
        LocalFileStorage local = (LocalFileStorage) storage;
        for (String bad : List.of("../secrets.txt", "orders/../../x", "C:/Windows/x", "/etc/passwd", "orders\\x")) {
            try {
                local.resolve(bad);
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // good
            }
        }
    }

    @Test
    void nothingBiggerThanTheLimitIsEverWritten() throws Exception {
        Order o = newOrder();
        JsonNode ticket = o.ticket("Huge.pdf", "PDF", 1000);
        String link = ticket.path("uploadUrl").asText();
        String doc = ticket.path("document").path("id").asText();
        long tooBig = 60L * 1024 * 1024;

        // "this is piece one of a 60 MB file"
        MvcResult r = mvc.perform(put(URI.create(link)).header("Content-Range", "bytes 0-999/" + tooBig)
                .content(new byte[1000])).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(413);
        assertThat(json(r).path("error").asText()).isEqualTo("FILE_TOO_LARGE");

        // a piece that is longer, or shorter, than it says
        assertThat(mvc.perform(put(URI.create(link)).header("Content-Range", "bytes 0-999/5000")
                .content(new byte[1500])).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(put(URI.create(link)).header("Content-Range", "bytes 0-999/5000")
                .content(new byte[400])).andReturn().getResponse().getStatus()).isEqualTo(400);
        // nonsense
        assertThat(mvc.perform(put(URI.create(link)).header("Content-Range", "bytes 900-100/5000")
                .content(new byte[10])).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(put(URI.create(link)).header("Content-Range", "pieces 1 of 5")
                .content(new byte[10])).andReturn().getResponse().getStatus()).isEqualTo(400);

        assertThat(java.nio.file.Files.exists(folder.resolve(storagePath(doc)))).isFalse();
        Path part = Path.of(folder.resolve(storagePath(doc)) + ".part");
        assertThat(java.nio.file.Files.exists(part) ? java.nio.file.Files.size(part) : 0).isLessThanOrEqualTo(1000);
    }

    // ------------------------------------------------------------------ tidying up

    @Test
    void removingAFileOrCancellingTheOrderRemovesItFromTheDisk() throws Exception {
        byte[] pdf = Files.pdf(4);
        Order o = newOrder();
        JsonNode a = o.ticket("A.pdf", "PDF", pdf.length);
        JsonNode b = o.ticket("B.pdf", "PDF", pdf.length);
        sendInPieces(a.path("uploadUrl").asText(), pdf, 0);
        piece(b.path("uploadUrl").asText(), pdf, 0, PIECE);          // B is only half sent
        String docA = a.path("document").path("id").asText();
        String docB = b.path("document").path("id").asText();
        Path fileA = folder.resolve(storagePath(docA));
        Path partB = Path.of(folder.resolve(storagePath(docB)) + ".part");
        assertThat(java.nio.file.Files.exists(fileA)).isTrue();
        assertThat(java.nio.file.Files.exists(partB)).isTrue();

        mvc.perform(key(delete("/api/v1/orders/" + o.id + "/documents/" + docB), o)).andReturn();
        assertThat(java.nio.file.Files.exists(partB)).as("a half-sent file goes with its document").isFalse();

        mvc.perform(key(post("/api/v1/orders/" + o.id + "/cancel"), o)).andReturn();
        assertThat(java.nio.file.Files.exists(fileA)).isFalse();
    }

    @Test
    void leftOverFilesAreSweptButNothingAnOrderStillNeeds() throws Exception {
        byte[] pdf = Files.pdf(2);
        Order o = newOrder();
        JsonNode t = o.ticket("Keep.pdf", "PDF", pdf.length);
        sendInPieces(t.path("uploadUrl").asText(), pdf, 0);
        String doc = t.path("document").path("id").asText();
        o.uploaded(doc);
        Path kept = folder.resolve(storagePath(doc));

        // a folder of an order that no longer exists, from last week (the PC lost power while deleting)
        Path orphanDir = folder.resolve("orders").resolve(java.util.UUID.randomUUID().toString());
        java.nio.file.Files.createDirectories(orphanDir);
        Path orphan = orphanDir.resolve("x.pdf");
        java.nio.file.Files.write(orphan, pdf);
        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000);
        java.nio.file.Files.setLastModifiedTime(orphan, old);
        java.nio.file.Files.setLastModifiedTime(orphanDir, old);
        // the same, but made a minute ago: could be an upload that is just starting
        Path youngDir = folder.resolve("orders").resolve(java.util.UUID.randomUUID().toString());
        java.nio.file.Files.createDirectories(youngDir);
        Path young = youngDir.resolve("y.pdf");
        java.nio.file.Files.write(young, pdf);
        // the order's own folder is old too, but the order still needs its file
        java.nio.file.Files.setLastModifiedTime(kept.getParent(), old);

        housekeeping.sweepFiles();

        assertThat(java.nio.file.Files.exists(orphan)).isFalse();
        assertThat(java.nio.file.Files.exists(orphanDir)).isFalse();
        assertThat(java.nio.file.Files.exists(young)).isTrue();
        assertThat(java.nio.file.Files.exists(kept)).as("a file an order still needs is never swept").isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private String storagePath(String doc) {
        return jdbc.queryForObject("select storage_path from order_documents where id = ?::uuid", String.class, doc);
    }

    /** Sends the file from this byte on, piece by piece, as the website and the apps do. Returns the last answer. */
    private JsonNode sendInPieces(String link, byte[] bytes, int from) throws Exception {
        JsonNode last = null;
        for (int start = from; start < bytes.length; start += PIECE) {
            MvcResult r = piece(link, bytes, start, Math.min(PIECE, bytes.length - start));
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            last = json(r);
            assertThat(last.path("received").asLong()).isEqualTo(Math.min(bytes.length, start + PIECE));
        }
        return last;
    }

    private MvcResult piece(String link, byte[] bytes, int start, int length) throws Exception {
        return mvc.perform(put(URI.create(link)).contentType(MediaType.APPLICATION_PDF)
                .header("Content-Range", "bytes " + start + "-" + (start + length - 1) + "/" + bytes.length)
                .content(Arrays.copyOfRange(bytes, start, start + length))).andReturn();
    }

    private Order newOrder() throws Exception {
        JsonNode c = json(mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn());
        return new Order(c.path("orderId").asText(), c.path("accessKey").asText());
    }

    private final class Order {
        final String id;
        final String key;

        Order(String id, String key) {
            this.id = id;
            this.key = key;
        }

        JsonNode ticket(String name, String type, int size) throws Exception {
            MvcResult r = mvc.perform(key(post("/api/v1/orders/" + id + "/documents"), this)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("fileName", name, "fileType", type, "fileSizeBytes", size))))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json(r);
        }

        JsonNode uploaded(String doc) throws Exception {
            MvcResult r = mvc.perform(key(post("/api/v1/orders/" + id + "/documents/" + doc + "/uploaded"), this)).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json(r);
        }

        JsonNode review(String... docs) throws Exception {
            ArrayNode list = JSON.createArrayNode();
            for (String d : docs) list.addObject().put("id", d).set("settings", JSON.createObjectNode());
            ObjectNode body = JSON.createObjectNode();
            body.set("documents", list);
            MvcResult r = mvc.perform(key(post("/api/v1/orders/" + id + "/review"), this)
                    .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
            return json(r);
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
        MvcResult r = mvc.perform(b.header("X-Counter-Password", COUNTER).contentType(MediaType.APPLICATION_JSON)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private MockHttpServletRequestBuilder agentAuth(MockHttpServletRequestBuilder b) throws Exception {
        if (agentToken == null) {
            String basic = Base64.getEncoder().encodeToString((pcId + ":" + pcSecret).getBytes(StandardCharsets.UTF_8));
            agentToken = json(mvc.perform(post("/agent/v1/token").header("Authorization", "Basic " + basic)).andReturn())
                    .path("token").asText();
        }
        return b.header("Authorization", "Bearer " + agentToken);
    }

    private JsonNode agent(MockHttpServletRequestBuilder b) throws Exception {
        MvcResult r = mvc.perform(agentAuth(b.contentType(MediaType.APPLICATION_JSON))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private JsonNode claim() throws Exception {
        agent(post("/agent/v1/heartbeat").content("{\"agentVersion\":\"4.4.0\",\"hostName\":\"test\"}"));
        MvcResult r = mvc.perform(agentAuth(post("/agent/v1/jobs/claim").contentType(MediaType.APPLICATION_JSON)
                .content("{\"printerId\":\"" + printer + "\"}"))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json(r);
    }

    private static JsonNode json(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() ? JSON.nullNode() : JSON.readTree(s);
    }
}
