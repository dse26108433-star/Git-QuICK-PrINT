package edu.campus.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A load test that does what students really do, against a server that is
 * really running: nothing is simulated inside the server.
 *
 * Each "student" is its own connection to the server and goes the whole way:
 * opens the shop, starts an order, sends 1 to 3 real files (PDFs with real
 * pages and pictures, JPGs, PNGs: from a few KB up to just under 50 MB) at
 * the speed of a phone connection, lets the server check and price them,
 * pays (the server must be in demo payment mode), and then asks "how is my
 * order" the way the apps do until every file is printed. Printing is done
 * by the real XeoGo Station on the same server, on the printers it has.
 *
 * What it reports is what it measured: how many orders got through, how long
 * each step took (middle, 90 %, 99 %, slowest), every kind of error, how
 * fast files went up, and how busy the computer was. It says which computer
 * it ran on. It does not claim anything it did not measure.
 *
 *   java ... edu.campus.demo.LoadTest --url http://127.0.0.1:47810 --students 300 --arrive 180
 *        [--files D:\loadfiles] [--huge 6] [--min-mbps 2] [--max-mbps 30] [--wait 3600] [--report out.json]
 *        [--no-print]   stop after paying (no Station attached)
 */
public final class LoadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------ settings

    private final String base;
    private final int students;
    private final int arriveSeconds;
    private final Path filesDir;
    private final int hugeFiles;
    private final double minMbps, maxMbps;
    private final int waitSeconds;
    private final boolean print;
    private final Path report;
    private final long seed;
    private final int maxPrintPages;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20)).build();

    // ------------------------------------------------------------------ what is measured

    private final Map<String, ConcurrentLinkedQueue<Double>> times = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();
    private final AtomicInteger ordersStarted = new AtomicInteger();
    private final AtomicInteger ordersPaid = new AtomicInteger();
    private final AtomicInteger ordersPrinted = new AtomicInteger();
    private final AtomicInteger ordersFailed = new AtomicInteger();
    private final AtomicInteger ordersGaveUp = new AtomicInteger();
    private final AtomicInteger filesSent = new AtomicInteger();
    private final AtomicInteger filesPrinted = new AtomicInteger();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicInteger piecesRetried = new AtomicInteger();
    private final AtomicInteger busyAnswers = new AtomicInteger();
    private final AtomicInteger sheetsPrinted = new AtomicInteger();
    private final ConcurrentLinkedQueue<Double> cpu = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Double> freeMemMb = new ConcurrentLinkedQueue<>();
    private final AtomicInteger uploadingNow = new AtomicInteger();
    private final AtomicInteger uploadingPeak = new AtomicInteger();
    private final AtomicInteger waitingNow = new AtomicInteger();
    private final AtomicInteger waitingPeak = new AtomicInteger();

    private record TestFile(Path path, String name, String type, long size, int pages, String kind) {}

    private List<TestFile> small, medium, large, huge, pictures;
    private JsonNode shop;

    private LoadTest(Map<String, String> a) {
        this.base = a.getOrDefault("url", "http://127.0.0.1:47810").replaceAll("/+$", "");
        this.students = Integer.parseInt(a.getOrDefault("students", "100"));
        this.arriveSeconds = Integer.parseInt(a.getOrDefault("arrive", "120"));
        this.filesDir = Path.of(a.getOrDefault("files", "loadtest-files"));
        this.hugeFiles = Integer.parseInt(a.getOrDefault("huge", "4"));
        this.minMbps = Double.parseDouble(a.getOrDefault("min-mbps", "2"));
        this.maxMbps = Double.parseDouble(a.getOrDefault("max-mbps", "30"));
        this.waitSeconds = Integer.parseInt(a.getOrDefault("wait", "3600"));
        this.print = !a.containsKey("no-print");
        this.report = a.containsKey("report") ? Path.of(a.get("report")) : null;
        this.seed = Long.parseLong(a.getOrDefault("seed", "20261111"));
        this.maxPrintPages = Integer.parseInt(a.getOrDefault("max-print-pages", "0"));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String k = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) a.put(k, args[++i]);
            else a.put(k, "true");
        }
        new LoadTest(a).run();
    }

    // ------------------------------------------------------------------ the run

    private void run() throws Exception {
        say("XeoGo load test");
        say("  server:   " + base);
        say("  students: " + students + ", arriving within " + arriveSeconds + " s");
        say("  this computer: " + System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "?") + ", "
                + Runtime.getRuntime().availableProcessors() + " processors, " + totalMemoryMb() + " MB of memory");

        shop = get("/api/v1/shop", null, "shop");
        if (!"demo".equals(shop.path("paymentMode").asText())) {
            throw new IllegalStateException("The server must be in test payment mode (demo) for a load test: no real "
                    + "money can be paid by a program. It is \"" + shop.path("paymentMode").asText() + "\".");
        }
        boolean printersOnline = shop.path("bwOnline").asBoolean() || shop.path("colorOnline").asBoolean();
        say("  printers: " + shop.path("printing").path("printers").size() + " (" + (printersOnline ? "online" : "OFFLINE") + ")"
                + ", files up to " + shop.path("maxFileSizeBytes").asLong() / 1048576 + " MB");
        if (print && !printersOnline) {
            throw new IllegalStateException("No printer is online: start XeoGo Station, or pass --no-print.");
        }
        prepareFiles();

        Random random = new Random(seed);
        // who gets a huge file: exactly hugeFiles of the students, spread over the run
        List<Integer> hugeOwners = new ArrayList<>();
        for (int i = 0; i < students; i++) hugeOwners.add(i);
        Collections.shuffle(hugeOwners, random);
        hugeOwners = hugeOwners.subList(0, Math.min(hugeFiles, students));

        Thread sampler = new Thread(this::sample, "sampler");
        sampler.setDaemon(true);
        sampler.start();

        long start = System.currentTimeMillis();
        CountDownLatch done = new CountDownLatch(students);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < students; i++) {
                final int n = i;
                final long arrival = (long) (random.nextDouble() * arriveSeconds * 1000);
                final long studentSeed = random.nextLong();
                final boolean getsHuge = hugeOwners.contains(i);
                pool.submit(() -> {
                    try {
                        sleep(arrival);
                        student(n, new Random(studentSeed), getsHuge);
                    } catch (Throwable t) {
                        error("student crashed: " + t, null);
                    } finally {
                        done.countDown();
                    }
                });
            }
            long lastSaid = 0;
            while (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                long now = System.currentTimeMillis();
                if (now - lastSaid >= 20_000) {
                    lastSaid = now;
                    say(String.format("  %4d s | started %d | paid %d | printed %d | failed %d | sending now %d | waiting for paper %d | errors %d",
                            (now - start) / 1000, ordersStarted.get(), ordersPaid.get(), ordersPrinted.get(),
                            ordersFailed.get() + ordersGaveUp.get(), uploadingNow.get(), waitingNow.get(), totalErrors()));
                }
            }
        }
        double seconds = (System.currentTimeMillis() - start) / 1000.0;
        report(seconds);
    }

    // ------------------------------------------------------------------ one student

    private void student(int n, Random r, boolean getsHuge) throws Exception {
        double mbps = minMbps + r.nextDouble() * (maxMbps - minMbps);
        get("/api/v1/shop", null, "shop");
        JsonNode order = send("POST", "/api/v1/orders", "{}", null, "start order");
        if (order == null) return;
        String id = order.path("orderId").asText();
        String key = order.path("accessKey").asText();
        ordersStarted.incrementAndGet();

        int count = r.nextDouble() < 0.60 ? 1 : r.nextDouble() < 0.65 ? 2 : 3;
        List<TestFile> mine = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double x = r.nextDouble();
            List<TestFile> from = (i == 0 && getsHuge) ? huge : x < 0.50 ? small : x < 0.72 ? pictures : x < 0.93 ? medium : large;
            mine.add(from.get(r.nextInt(from.size())));
        }
        ArrayNode choices = JSON.createArrayNode();
        boolean colourPrinter = shop.path("colorAvailable").asBoolean();
        boolean bwPrinter = shop.path("bwAvailable").asBoolean();
        int filesInOrder = 0;
        for (TestFile f : mine) {
            String doc = sendFile(id, key, f, mbps, r);
            if (doc == null) continue;
            filesInOrder++;
            ObjectNode s = JSON.createObjectNode();
            boolean colour = colourPrinter && (!bwPrinter || r.nextDouble() < 0.15);
            s.put("color", colour);
            s.put("copies", r.nextDouble() < 0.9 ? 1 : 2);
            if (maxPrintPages > 0 && f.pages() > maxPrintPages) {
                // --max-print-pages: every student prints only a few pages, so that a run with hundreds of orders
                // is not mostly waiting for paper (the printers' speed is not what such a run measures)
                int from = 1 + r.nextInt(f.pages() - maxPrintPages + 1);
                s.put("pages", maxPrintPages == 1 ? String.valueOf(from) : from + "-" + (from + maxPrintPages - 1));
            } else if (f.pages() > 20) {
                // a long document: half of the students print a part of it, half all of it (up to the shop's limit)
                int max = shop.path("maxPages").asInt(300);
                if (r.nextBoolean() || f.pages() > max) {
                    int from = 1 + r.nextInt(Math.max(1, f.pages() - 30));
                    int to = Math.min(f.pages(), from + 4 + r.nextInt(26));
                    s.put("pages", from + "-" + to);
                }
            }
            choices.addObject().put("id", doc).set("settings", s);
        }
        if (filesInOrder == 0) {
            ordersFailed.incrementAndGet();
            return;
        }
        ObjectNode review = JSON.createObjectNode();
        review.set("documents", choices);
        JsonNode priced = send("POST", "/api/v1/orders/" + id + "/review", review.toString(), key, "review and price");
        if (priced == null) {
            ordersFailed.incrementAndGet();
            return;
        }
        JsonNode paid = send("POST", "/api/v1/orders/" + id + "/payment/demo", null, key, "pay");
        if (paid == null) {
            ordersFailed.incrementAndGet();
            return;
        }
        ordersPaid.incrementAndGet();
        if (!print) return;

        // "how is my order?": asked as often as the server says, like the apps
        long paidAt = System.currentTimeMillis();
        int now = waitingNow.incrementAndGet();
        waitingPeak.accumulateAndGet(now, Math::max);
        try {
            while (true) {
                JsonNode v = get("/api/v1/orders/" + id, key, "how is my order");
                if (v != null) {
                    String status = v.path("status").asText();
                    if (status.equals("COMPLETED")) {
                        ordersPrinted.incrementAndGet();
                        filesPrinted.addAndGet(v.path("documentsDone").asInt());
                        sheetsPrinted.addAndGet(v.path("totalSheets").asInt());
                        time("paid -> every file printed", (System.currentTimeMillis() - paidAt));
                        return;
                    }
                    if (status.equals("FAILED") || status.equals("CANCELLED")) {
                        ordersFailed.incrementAndGet();
                        error("order ended as " + status, v.path("message").asText());
                        return;
                    }
                }
                if (System.currentTimeMillis() - paidAt > waitSeconds * 1000L) {
                    ordersGaveUp.incrementAndGet();
                    error("still not printed after " + waitSeconds + " s", null);
                    return;
                }
                int poll = v == null ? 5 : Math.max(2, v.path("pollSeconds").asInt(5));
                sleep(poll * 1000L);
            }
        } finally {
            waitingNow.decrementAndGet();
        }
    }

    /** Adds one file to the order, sends it in pieces at this student's speed, and has the server check it. */
    private String sendFile(String orderId, String key, TestFile f, double mbps, Random r) throws Exception {
        ObjectNode add = JSON.createObjectNode();
        add.put("fileName", f.name());
        add.put("fileType", f.type());
        add.put("fileSizeBytes", f.size());
        JsonNode ticket = send("POST", "/api/v1/orders/" + orderId + "/documents", add.toString(), key, "add a file");
        if (ticket == null) return null;
        String doc = ticket.path("document").path("id").asText();
        String link = ticket.path("uploadUrl").asText();
        int piece = ticket.path("uploadChunkBytes").asInt(0);
        String contentType = ticket.path("uploadContentType").asText("application/octet-stream");

        int now = uploadingNow.incrementAndGet();
        uploadingPeak.accumulateAndGet(now, Math::max);
        long t0 = System.nanoTime();
        try (RandomAccessFile in = new RandomAccessFile(f.path().toFile(), "r")) {
            long total = f.size();
            long at = 0;
            int tries = 0;
            while (at < total) {
                int n = (int) Math.min(piece > 0 ? piece : total, total - at);
                byte[] bytes = new byte[n];
                in.seek(at);
                in.readFully(bytes);
                long sendStart = System.nanoTime();
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(link)).timeout(Duration.ofMinutes(5))
                        .header("Content-Type", contentType).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
                if (piece > 0) b.header("Content-Range", "bytes " + at + "-" + (at + n - 1) + "/" + total);
                HttpResponse<String> res;
                try {
                    res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    if (++tries > 8) {
                        error("upload gave up after 8 tries", e.toString());
                        return null;
                    }
                    piecesRetried.incrementAndGet();
                    sleep(1000L * tries);
                    at = received(link, at);
                    continue;
                }
                if (res.statusCode() == 200) {
                    at += n;
                    tries = 0;
                    // a phone connection: this piece would have taken bytes*8/mbps seconds
                    long should = (long) (n * 8.0 / (mbps * 1_000_000) * 1000);
                    long took = (System.nanoTime() - sendStart) / 1_000_000;
                    if (should > took) sleep(should - took);
                } else if (res.statusCode() == 409 && res.body().contains("received")) {
                    at = JSON.readTree(res.body()).path("received").asLong(at);       // carry on where the server is
                    piecesRetried.incrementAndGet();
                } else if (res.statusCode() == 503) {
                    busyAnswers.incrementAndGet();
                    if (++tries > 30) {
                        error("upload: server busy for too long", res.body());
                        return null;
                    }
                    sleep(1500 + r.nextInt(2000));
                } else {
                    error("upload answered " + res.statusCode(), res.body());
                    return null;
                }
            }
        } finally {
            uploadingNow.decrementAndGet();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        bytesSent.addAndGet(f.size());
        time("upload of one file (s)", seconds * 1000);
        time("upload " + f.kind(), seconds * 1000);

        // the server's check: pages counted, damaged files refused. "Busy" means in line: ask again.
        for (int tries = 0; ; tries++) {
            long c0 = System.nanoTime();
            HttpResponse<String> res = raw("POST", "/api/v1/orders/" + orderId + "/documents/" + doc + "/uploaded", null, key);
            if (res == null) {
                if (tries > 5) return null;
                sleep(2000);
                continue;
            }
            if (res.statusCode() == 200) {
                time("server checks the file", (System.nanoTime() - c0) / 1e6);
                time("check " + f.kind(), (System.nanoTime() - c0) / 1e6);
                JsonNode v = JSON.readTree(res.body());
                if (!"READY".equals(v.path("status").asText())) {
                    error("file not accepted: " + v.path("status").asText(), v.path("problem").asText());
                    return null;
                }
                if (f.type().equals("PDF") && v.path("pageCount").asInt() != f.pages()) {
                    error("WRONG PAGE COUNT", f.name() + ": server says " + v.path("pageCount").asInt() + ", file has " + f.pages());
                }
                filesSent.incrementAndGet();
                return doc;
            }
            if (res.statusCode() == 503 && tries < 60) {
                busyAnswers.incrementAndGet();
                sleep(3000 + r.nextInt(3000));
                continue;
            }
            error("check answered " + res.statusCode(), res.body());
            return null;
        }
    }

    private long received(String link, long fallback) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(link)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) return JSON.readTree(res.body()).path("received").asLong(fallback);
        } catch (Exception e) {
            // asked again with the next try
        }
        return fallback;
    }

    // ------------------------------------------------------------------ http

    private JsonNode get(String path, String key, String what) throws Exception {
        return send("GET", path, null, key, what);
    }

    /** One call; its time is recorded under "what". Null (and an error counted) when it did not work. */
    private JsonNode send(String method, String path, String body, String key, String what) throws Exception {
        for (int tries = 0; ; tries++) {
            long t0 = System.nanoTime();
            HttpResponse<String> res = raw(method, path, body, key);
            if (res == null) {
                if (tries >= 3) return null;
                sleep(1500L * (tries + 1));
                continue;
            }
            time(what, (System.nanoTime() - t0) / 1e6);
            if (res.statusCode() / 100 == 2) return res.body().isEmpty() ? JSON.nullNode() : JSON.readTree(res.body());
            if (res.statusCode() == 503 && tries < 20) {            // "busy, try again": what the apps do
                busyAnswers.incrementAndGet();
                sleep(2000);
                continue;
            }
            error(what + " answered " + res.statusCode(), res.body());
            return null;
        }
    }

    private HttpResponse<String> raw(String method, String path, String body, String key) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120));
            if (key != null) b.header("X-Order-Key", key);
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            error("no answer: " + e.getClass().getSimpleName(), method + " " + path.replaceAll("[0-9a-f-]{36}", "{id}") + " " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ------------------------------------------------------------------ the test files

    /** Real files, made once and kept: PDFs with real pages (text and pictures), JPGs and PNGs. */
    private void prepareFiles() throws IOException {
        Files.createDirectories(filesDir);
        Random r = new Random(7);
        small = new ArrayList<>();
        medium = new ArrayList<>();
        large = new ArrayList<>();
        huge = new ArrayList<>();
        pictures = new ArrayList<>();
        long max = shop.path("maxFileSizeBytes").asLong(50L * 1024 * 1024);
        say("  test files in " + filesDir.toAbsolutePath() + " (made once, then reused):");
        int[] smallPages = {1, 1, 2, 2, 3, 4, 5, 6, 8, 10, 12, 16};
        for (int i = 0; i < smallPages.length; i++) small.add(pdf("notes-" + (i + 1) + ".pdf", smallPages[i], 0, 0, r, "small PDF (text)"));
        int[][] mediumSpec = {{8, 250}, {12, 300}, {20, 200}, {24, 350}, {30, 150}, {40, 200}, {16, 500}, {60, 120}};
        for (int i = 0; i < mediumSpec.length; i++) {
            medium.add(pdf("assignment-" + (i + 1) + ".pdf", mediumSpec[i][0], mediumSpec[i][1], 0, r, "medium PDF (1-10 MB)"));
        }
        large.add(pdf("lab-manual-1.pdf", 60, 300, 0, r, "large PDF (10-30 MB)"));
        large.add(pdf("lab-manual-2.pdf", 90, 260, 0, r, "large PDF (10-30 MB)"));
        large.add(pdf("scanned-book-1.pdf", 45, 520, 0, r, "large PDF (10-30 MB)"));
        // just under the limit: what "up to 50 MB" really means
        huge.add(pdf("scanned-book-50mb.pdf", 120, 0, max - 60_000, r, "huge PDF (just under 50 MB)"));
        huge.add(pdf("project-report-49mb.pdf", 220, 0, max - 1_000_000, r, "huge PDF (just under 50 MB)"));
        pictures.add(picture("photo-1.jpg", 1600, 1200, "jpg", r));
        pictures.add(picture("photo-2.jpg", 3000, 2000, "jpg", r));
        pictures.add(picture("photo-3.jpg", 4000, 3000, "jpg", r));
        pictures.add(picture("scan-1.png", 1240, 1754, "png", r));
        pictures.add(picture("id-card.png", 800, 500, "png", r));
        Map<String, long[]> byKind = new TreeMap<>();
        for (List<TestFile> list : List.of(small, medium, large, huge, pictures)) {
            for (TestFile f : list) {
                long[] v = byKind.computeIfAbsent(f.kind(), k -> new long[]{0, Long.MAX_VALUE, 0});
                v[0]++;
                v[1] = Math.min(v[1], f.size());
                v[2] = Math.max(v[2], f.size());
            }
        }
        byKind.forEach((k, v) -> say(String.format("    %-24s %2d files, %s to %s", k, v[0], mb(v[1]), mb(v[2]))));
    }

    /**
     * A PDF with this many pages. picturesKb > 0: each page carries a picture of
     * about that size (like a scanned or photographed page). exactBytes > 0:
     * the pictures are sized so the whole file comes out at about that size.
     */
    private TestFile pdf(String name, int pages, int pictureKb, long exactBytes, Random r, String kind) throws IOException {
        Path p = filesDir.resolve(name);
        if (Files.exists(p) && exactBytes > 0 && Files.size(p) < exactBytes * 0.97) Files.delete(p);   // from an older run: too small
        if (!Files.exists(p)) {
            long perPage = exactBytes > 0 ? (exactBytes - 4000L * pages) / pages : pictureKb * 1024L;
            writePdf(p, name, pages, perPage, r);
            // "about that size" must really be that size: measure, and make it again until it is (a little under).
            for (int i = 0; exactBytes > 0 && i < 6; i++) {
                long size = Files.size(p);
                if (size <= exactBytes && size >= exactBytes * 0.985) break;
                perPage = (long) (perPage * ((exactBytes * 0.993) / size));
                Files.delete(p);
                writePdf(p, name, pages, perPage, r);
            }
            if (exactBytes > 0 && Files.size(p) > exactBytes) {
                Files.delete(p);
                throw new IOException(name + " came out too big");
            }
        }
        return new TestFile(p, name, "PDF", Files.size(p), pages, kind);
    }

    private static void writePdf(Path p, String name, int pages, long perPage, Random r) throws IOException {
        {
            try (PDDocument doc = new PDDocument()) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (int i = 1; i <= pages; i++) {
                    PDPage page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    PDImageXObject image = perPage > 2000 ? noise(doc, perPage, r) : null;
                    try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                        c.beginText();
                        c.setFont(font, 14);
                        c.newLineAtOffset(56, 780);
                        c.showText(name + "  -  page " + i + " of " + pages);
                        c.endText();
                        c.setFont(font, 10);
                        for (int line = 0; line < (image == null ? 46 : 6); line++) {
                            c.beginText();
                            c.newLineAtOffset(56, 750 - line * 15);
                            c.showText("Line " + (line + 1) + ": the quick brown fox jumps over the lazy dog. XeoGo load test, page " + i + ".");
                            c.endText();
                        }
                        if (image != null) c.drawImage(image, 56, 80, 483, 560);
                    }
                }
                doc.save(p.toFile());
            }
        }
    }

    /** A picture that takes about this many bytes as a JPEG (random dots do not compress: the size is real). */
    private static PDImageXObject noise(PDDocument doc, long bytes, Random r) throws IOException {
        // at quality 0.9 random dots take roughly 1.15 bytes per pixel
        int pixels = (int) Math.max(400, bytes / 1.15);
        int w = (int) Math.max(20, Math.sqrt(pixels * 0.86));
        int h = Math.max(20, pixels / w);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) row[x] = r.nextInt(0x1000000);
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return JPEGFactory.createFromImage(doc, img, 0.9f);
    }

    private TestFile picture(String name, int w, int h, String format, Random r) throws IOException {
        Path p = filesDir.resolve(name);
        if (!Files.exists(p)) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            // a "photo": soft colour areas with some grain, so it is neither tiny nor absurd
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int g = r.nextInt(48);
                    int red = (x * 255 / w + g) & 0xFF, green = (y * 255 / h + g) & 0xFF, blue = ((x + y) * 255 / (w + h) + g) & 0xFF;
                    img.setRGB(x, y, (red << 16) | (green << 8) | blue);
                }
            }
            ImageIO.write(img, format, p.toFile());
        }
        return new TestFile(p, name, format.equals("png") ? "PNG" : "JPEG", Files.size(p), 1, "picture (JPG / PNG)");
    }

    // ------------------------------------------------------------------ measuring and reporting

    private void time(String what, double millis) {
        times.computeIfAbsent(what, k -> new ConcurrentLinkedQueue<>()).add(millis);
    }

    private void error(String kind, String detail) {
        errors.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
        if (errorSamples.size() < 25) {
            String d = detail == null ? "" : detail.replaceAll("\\s+", " ");
            errorSamples.add(kind + (d.isEmpty() ? "" : " | " + (d.length() > 220 ? d.substring(0, 220) : d)));
        }
    }

    private int totalErrors() {
        return errors.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    private void sample() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        while (true) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            if (os instanceof com.sun.management.OperatingSystemMXBean m) {
                double load = m.getCpuLoad();
                if (load >= 0) cpu.add(load * 100);
                freeMemMb.add(m.getFreeMemorySize() / 1048576.0);
            }
        }
    }

    private static long totalMemoryMb() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.OperatingSystemMXBean m ? m.getTotalMemorySize() / 1048576 : -1;
    }

    private void report(double seconds) throws IOException {
        say("");
        say("================================ RESULT ================================");
        say(String.format("Ran for %.0f s (%.1f min).  Students: %d, arriving within %d s.", seconds, seconds / 60, students, arriveSeconds));
        say(String.format("Orders: started %d | paid %d | %s", ordersStarted.get(), ordersPaid.get(),
                print ? "every file printed " + ordersPrinted.get() + " | failed " + ordersFailed.get() + " | not printed in time " + ordersGaveUp.get()
                      : "(printing not part of this run) | failed before paying " + ordersFailed.get()));
        say(String.format("Files:  sent and accepted %d (%s in all)%s", filesSent.get(), mb(bytesSent.get()),
                print ? " | printed " + filesPrinted.get() + " (" + sheetsPrinted.get() + " sheets)" : ""));
        say(String.format("At the busiest moment: %d files being sent at once, %d students waiting for their paper.",
                uploadingPeak.get(), waitingPeak.get()));
        say(String.format("Pieces sent again after a hiccup: %d.  \"Busy, try again\" answers: %d.", piecesRetried.get(), busyAnswers.get()));
        say("");
        say(String.format("%-34s %7s %9s %9s %9s %9s", "How long each step took", "times", "middle", "90%", "99%", "slowest"));
        ObjectNode out = JSON.createObjectNode();
        ObjectNode steps = out.putObject("steps");
        for (String k : new TreeMap<>(times).keySet()) {
            double[] v = times.get(k).stream().mapToDouble(Double::doubleValue).sorted().toArray();
            if (v.length == 0) continue;
            say(String.format("%-34s %7d %9s %9s %9s %9s", k, v.length, dur(pct(v, 50)), dur(pct(v, 90)), dur(pct(v, 99)), dur(v[v.length - 1])));
            ObjectNode s = steps.putObject(k);
            s.put("count", v.length);
            s.put("p50ms", Math.round(pct(v, 50)));
            s.put("p90ms", Math.round(pct(v, 90)));
            s.put("p99ms", Math.round(pct(v, 99)));
            s.put("maxMs", Math.round(v[v.length - 1]));
        }
        say("");
        double[] c = cpu.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double[] f = freeMemMb.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (c.length > 0) {
            say(String.format("This computer during the run (server, database, Station AND this test on the same machine): "
                    + "processor average %.0f %%, highest %.0f %%; least free memory %.0f MB of %d MB.",
                    Arrays.stream(c).average().orElse(0), c[c.length - 1], f.length == 0 ? -1 : f[0], totalMemoryMb()));
        }
        say("");
        if (errors.isEmpty()) {
            say("Errors: none.");
        } else {
            say("Errors: " + totalErrors());
            new TreeMap<>(errors).forEach((k, v) -> say("  " + v.get() + " x " + k));
            say("First examples:");
            errorSamples.forEach(e -> say("  - " + e));
        }
        say("========================================================================");
        if (report != null) {
            out.put("seconds", seconds);
            out.put("students", students);
            out.put("arriveSeconds", arriveSeconds);
            out.put("ordersStarted", ordersStarted.get());
            out.put("ordersPaid", ordersPaid.get());
            out.put("ordersPrinted", ordersPrinted.get());
            out.put("ordersFailed", ordersFailed.get());
            out.put("ordersNotPrintedInTime", ordersGaveUp.get());
            out.put("filesSent", filesSent.get());
            out.put("filesPrinted", filesPrinted.get());
            out.put("sheetsPrinted", sheetsPrinted.get());
            out.put("bytesSent", bytesSent.get());
            out.put("piecesRetried", piecesRetried.get());
            out.put("busyAnswers", busyAnswers.get());
            out.put("uploadingPeak", uploadingPeak.get());
            out.put("waitingPeak", waitingPeak.get());
            out.put("cpuAverage", c.length == 0 ? -1 : Arrays.stream(c).average().orElse(0));
            out.put("cpuMax", c.length == 0 ? -1 : c[c.length - 1]);
            out.put("leastFreeMemoryMb", f.length == 0 ? -1 : f[0]);
            ObjectNode e = out.putObject("errors");
            errors.forEach((k, v) -> e.put(k, v.get()));
            Files.writeString(report, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        }
    }

    private static double pct(double[] sorted, int p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1 < 0 ? 0 : (int) Math.ceil(p / 100.0 * sorted.length) - 1)];
    }

    private static String dur(double ms) {
        if (ms < 1000) return Math.round(ms) + " ms";
        if (ms < 120_000) return String.format("%.1f s", ms / 1000);
        return String.format("%.1f min", ms / 60000);
    }

    private static String mb(long bytes) {
        return bytes < 1048576 ? (bytes / 1024) + " KB" : String.format("%.1f MB", bytes / 1048576.0);
    }

    private static void sleep(long ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }

    private static void say(String s) {
        System.out.println(s);
    }
}
