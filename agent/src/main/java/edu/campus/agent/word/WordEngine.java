package edu.campus.agent.word;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Microsoft Word installed on this PC, used to turn students' Word files
 * into PDFs: the file then looks exactly as it would if the student had
 * brought it on a pen drive and the shop had opened it in this Word.
 *
 * Word is driven through a small helper (word-worker.ps1, run by Windows
 * PowerShell): a second, hidden Word that belongs to the Station alone, with
 * alerts and macros off. It is started when the first file comes, kept while
 * files keep coming (so each one takes about a second), and closed after a
 * short quiet time. Nothing here can hang the Station: every step has a time
 * limit, and a Word that stops answering is ended and replaced.
 *
 * Whether it works on this PC is not guessed but tried: check() turns a small
 * test file into a PDF. Only then does the Station tell the server that Word
 * files can be taken here.
 */
public class WordEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WordEngine.class);

    /** code: PASSWORD, FAILED (it is the file), TOO_SLOW, or ENGINE (Word on this PC, not the file). */
    public record Result(boolean ok, int pages, String code, String message) {
        static Result done(int pages) {
            return new Result(true, pages, null, null);
        }

        static Result problem(String code, String message) {
            return new Result(false, 0, code, message);
        }
    }

    /** ready: a test file was turned into a PDF here. note: which Word it is, or why it is not ready. */
    public record State(boolean ready, String note) {}

    private static final String EOF = "\u0000EOF";
    private static final Duration START_LIMIT = Duration.ofSeconds(75);    // Word's first start on a slow PC
    private static final Duration IDLE_QUIT = Duration.ofSeconds(20);      // then the hidden Word is closed
    private static final int FILES_PER_WORD = 40;                          // then a fresh Word is started

    private final Path dir;
    private final ScheduledExecutorService ticker;
    private volatile State state = new State(false, "Not checked yet");

    private Process worker;
    private BufferedWriter toWorker;
    private BlockingQueue<String> fromWorker;
    private long wordPid;
    private String wordVersion = "";
    private Instant lastUse = Instant.now();
    private int filesDone;
    private long sequence;
    private boolean closed;

    /** dir: a folder of the Station's own, for the helper, its log and the files being turned. */
    public WordEngine(Path dir) {
        this.dir = dir;
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "word-watch");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
    }

    public State state() {
        return state;
    }

    // ------------------------------------------------------------------ the test

    /** Turns a one-page test file into a PDF. Sets (and returns) whether Word files can be taken on this PC. */
    public synchronized State check() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return state = new State(false, "Word files need a Windows PC with Microsoft Word.");
        }
        Path in = dir.resolve("check.docx");
        Path out = dir.resolve("check.pdf");
        try {
            Files.createDirectories(dir);
            Files.write(in, testDocument());
            Files.deleteIfExists(out);
            Result r = convert(in, out, Duration.ofSeconds(90));
            if (!r.ok()) {
                return state = new State(false, explain(r));
            }
            if (!looksLikePdf(out)) {
                return state = new State(false, "Microsoft Word wrote no usable PDF on this PC.");
            }
            log.info("Word files: ready ({})", wordName());
            return state = new State(true, wordName());
        } catch (IOException e) {
            return state = new State(false, "The Station's folder cannot be written: " + e.getMessage());
        } finally {
            quietDelete(in);
            quietDelete(out);
        }
    }

    private String explain(Result r) {
        String m = r.message() == null ? "" : r.message();
        if (m.contains("80040154") || m.toLowerCase().contains("class not registered")
                || m.toLowerCase().contains("cannot create") || m.toLowerCase().contains("retrieving the com class factory")) {
            return "Microsoft Word is not installed on this PC.";
        }
        if ("TOO_SLOW".equals(r.code()) || m.contains("did not answer")) {
            return "Microsoft Word did not answer. Open Word once by hand: it may be asking something "
                    + "(activation, or a question at its first start).";
        }
        if (m.contains("PowerShell")) return m;
        return "Microsoft Word could not save a PDF on this PC" + (m.isBlank() ? "." : ": " + shorten(m, 160));
    }

    private String wordName() {
        String v = wordVersion == null ? "" : wordVersion;
        String year = v.startsWith("16.") ? "2016 or newer" : v.startsWith("15.") ? "2013" : v.startsWith("14.") ? "2010"
                : v.startsWith("12.") ? "2007" : v.isBlank() ? "" : "version " + v;
        return ("Microsoft Word " + year).trim();
    }

    // ------------------------------------------------------------------ turning one file

    /**
     * Turns the Word file `in` into the PDF `out`. Never throws and never
     * takes longer than `limit` (plus the time Word needs to start, the first
     * time).
     */
    public synchronized Result convert(Path in, Path out, Duration limit) {
        if (closed) return Result.problem("ENGINE", "The Station is closing.");
        try {
            ensureWorker();
        } catch (IOException e) {
            endWorker();
            return Result.problem("ENGINE", e.getMessage());
        }
        String id = Long.toString(++sequence);
        try {
            say("CONVERT " + id + " " + b64(in.toAbsolutePath().toString()) + " " + b64(out.toAbsolutePath().toString()));
        } catch (IOException e) {
            endWorker();
            return Result.problem("ENGINE", "Microsoft Word's helper stopped.");
        }
        long until = System.nanoTime() + limit.toNanos();
        while (true) {
            String line = hear(Duration.ofNanos(Math.max(1, until - System.nanoTime())));
            if (line == null) {                               // too slow: this Word is ended, the next file gets a new one
                endWorker();
                return Result.problem("TOO_SLOW", "Microsoft Word did not finish this file in " + limit.toSeconds() + " seconds.");
            }
            if (line.equals(EOF)) {
                endWorker();
                return Result.problem("ENGINE", "Microsoft Word's helper stopped.");
            }
            String[] p = line.split(" ");
            if (p.length >= 3 && p[0].equals("OK") && p[1].equals(id)) {
                lastUse = Instant.now();
                if (++filesDone >= FILES_PER_WORD) quitWorker();
                return Result.done(number(p[2]));
            }
            if (p.length >= 3 && p[0].equals("ERR") && p[1].equals(id)) {
                lastUse = Instant.now();
                String message = p.length >= 4 ? unb64(p[3]) : "";
                if (p[2].equals("ENGINE")) endWorker();
                return Result.problem(p[2], message);
            }
            // anything else (a late PONG): not for us
        }
    }

    // ------------------------------------------------------------------ the helper process

    private void ensureWorker() throws IOException {
        if (worker != null && worker.isAlive()) return;
        endWorker();
        Files.createDirectories(dir);
        Path script = dir.resolve("word-worker.ps1");
        try (InputStream in = WordEngine.class.getResourceAsStream("/word/word-worker.ps1")) {
            if (in == null) throw new IOException("The Station's Word helper is missing from the program.");
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        Path shell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(shell)) throw new IOException("Windows PowerShell was not found on this PC.");
        ProcessBuilder pb = new ProcessBuilder(List.of(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", script.toString()));
        pb.directory(dir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("word-helper.log").toFile()));
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("Windows PowerShell could not be started: " + e.getMessage());
        }
        worker = p;
        fromWorker = new LinkedBlockingQueue<>();
        toWorker = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        BlockingQueue<String> queue = fromWorker;
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) queue.add(line.strip());
            } catch (IOException ignored) {
                // the helper ended
            }
            queue.add(EOF);
        }, "word-helper");
        reader.setDaemon(true);
        reader.start();

        String first = hear(START_LIMIT);
        if (first == null) {
            endWorker();
            throw new IOException("Microsoft Word did not answer.");
        }
        if (first.startsWith("READY ")) {
            String[] parts = first.split(" ");
            wordVersion = parts.length > 1 ? parts[1] : "";
            wordPid = parts.length > 2 ? number(parts[2]) : 0;
            filesDone = 0;
            lastUse = Instant.now();
            return;
        }
        String why = first.startsWith("NOWORD ") ? unb64(first.substring(7))
                : "Windows PowerShell did not run the Station's Word helper" + (first.equals(EOF) ? "." : ": " + shorten(first, 120));
        endWorker();
        throw new IOException(why);
    }

    /** Twice a second while there is no work: is the hidden Word still well, and is it still needed? */
    private synchronized void tick() {
        try {
            if (worker == null) return;
            if (!worker.isAlive()) {
                endWorker();
                return;
            }
            if (Duration.between(lastUse, Instant.now()).compareTo(IDLE_QUIT) > 0) {
                quitWorker();
                return;
            }
            say("PING");
            while (true) {
                String line = hear(Duration.ofSeconds(8));
                if (line == null || line.equals(EOF)) {       // it does not answer: end it, the next file starts a new one
                    log.warn("The hidden Microsoft Word stopped answering; it is ended and started again when needed");
                    endWorker();
                    return;
                }
                if (line.equals("PONG")) return;
            }
        } catch (IOException | RuntimeException e) {
            endWorker();
        }
    }

    /** "Close Word and end", politely; by force if that takes too long. */
    private void quitWorker() {
        Process p = worker;
        if (p == null) return;
        try {
            say("QUIT");
            if (p.waitFor(10, TimeUnit.SECONDS)) {
                worker = null;
                toWorker = null;
                fromWorker = null;
                wordPid = 0;
                return;
            }
        } catch (IOException e) {
            // it is gone already, or stuck: by force
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        endWorker();
    }

    /** Ends the helper and its hidden Word, whatever state they are in. */
    private void endWorker() {
        Process p = worker;
        worker = null;
        toWorker = null;
        fromWorker = null;
        long word = wordPid;
        wordPid = 0;
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        if (word > 0) endHiddenWord(word);
    }

    /**
     * Ends the Word this engine started, by its process id, and only if that
     * process is a Word (the id could belong to something else by now).
     * Never the shop's own Word: that one has another id. And never a Word
     * that shows a window: a file somebody opened by hand may have landed in
     * the hidden Word (Windows does that when no other Word is open); then it
     * is theirs and stays.
     */
    private static void endHiddenWord(long pid) {
        try {
            if (showsAWindow(pid)) {
                log.warn("The Word the Station started ({}) shows a document somebody opened: it is left open", pid);
                return;
            }
            Path taskkill = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "taskkill.exe");
            Process k = new ProcessBuilder(taskkill.toString(), "/PID", Long.toString(pid), "/F", "/FI", "IMAGENAME eq WINWORD.EXE")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            k.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException e) {
            log.debug("Could not end the hidden Word {}: {}", pid, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True when that process has a window with a title (a document is open for somebody to see). */
    private static boolean showsAWindow(long pid) {
        try {
            Path shell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                    "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            Process p = new ProcessBuilder(shell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
                    "$p = Get-Process -Id " + pid + " -ErrorAction SilentlyContinue; if ($p -and $p.MainWindowTitle) { 'WINDOW' } else { 'NONE' }")
                    .redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor(15, TimeUnit.SECONDS);
            return out.contains("WINDOW");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void say(String line) throws IOException {
        BufferedWriter w = toWorker;
        if (w == null) throw new IOException("no helper");
        w.write(line);
        w.write("\n");
        w.flush();
    }

    /** The helper's next line; null when none came in time. */
    private String hear(Duration limit) {
        BlockingQueue<String> q = fromWorker;
        if (q == null) return EOF;
        try {
            return q.poll(Math.max(1, limit.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EOF;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        ticker.shutdownNow();
        quitWorker();
    }

    // ------------------------------------------------------------------ small things

    /** The smallest Word file there is: one page that says what it is for. */
    static byte[] testDocument() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument."
                    + "wordprocessingml.document.main+xml\"/></Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                    + "officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            put(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + "<w:p><w:r><w:t>XeoGo Station: this page checks that Microsoft Word can make PDFs on this PC.</w:t></w:r></w:p>"
                    + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr></w:body></w:document>");
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /** A file that starts the way every PDF starts, and is not empty. */
    public static boolean looksLikePdf(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(5);
            return head.length == 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-';
        } catch (IOException e) {
            return false;
        }
    }

    private static void quietDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // a leftover test file harms nobody
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        try {
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8).strip();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String shorten(String s, int n) {
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= n ? one : one.substring(0, n) + "\u2026";
    }

    /** For tests: the folder the helper works in. */
    Path dir() {
        return dir;
    }

    /** For tests and the Station's screen: the process id of the hidden Word, or 0 when none runs. */
    synchronized long hiddenWord() {
        return worker != null && worker.isAlive() ? wordPid : 0;
    }
}
