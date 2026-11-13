package edu.campus.agent.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies of the database, so a broken disk or a stolen PC does not take the
 * shop's orders, staff IDs and settings with it.
 *
 *   - on this PC: every half hour while the server runs, into
 *     ...\server\backups (the last 30, and one per day for 45 days);
 *   - in the cloud (optional): each of them is also sent to the shop's own
 *     Supabase project, bucket "xeogo-backups": the latest, and one per day
 *     for 30 days. That is the "database connected to the cloud": the PC
 *     works on its own, fast database, and the cloud always holds a copy
 *     that is at most half an hour old.
 *
 * Putting a backup back happens by itself in two cases: the database was
 * damaged (LocalStack.repairDatabase), or this PC was set up with "bring my
 * data from a backup / from the cloud". It is done before the print service
 * starts, into a fresh database; the damaged one is kept aside, never deleted.
 *
 * The students' files (PDFs waiting to be printed) are not in the backup:
 * they are short-lived and can be large. Orders that were paid but not
 * printed stay in the list after a restore, marked for the counter.
 *
 * Made with PostgreSQL's own pg_dump and pg_restore, which come with the
 * Station.
 */
public final class Backups {

    private static final Logger log = LoggerFactory.getLogger(Backups.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUCKET = "xeogo-backups";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("d MMM, HH:mm");
    private static final int KEEP_LATEST = 30;
    private static final int KEEP_DAYS = 45;
    private static final int KEEP_CLOUD_DAYS = 30;

    private final ServerSettings cfg;
    private final LocalStack stack;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private volatile long nextRun = System.currentTimeMillis() + 4 * 60_000L;
    private volatile Instant lastAt;
    private volatile long lastBytes;
    private volatile String lastProblem;
    private volatile Instant cloudAt;
    private volatile String cloudProblem;
    private volatile String restoreNote;
    private boolean bucketChecked;

    Backups(ServerSettings cfg, LocalStack stack) {
        this.cfg = cfg;
        this.stack = stack;
        Path newest = latest();
        if (newest != null) {
            try {
                lastAt = Files.getLastModifiedTime(newest).toInstant();
                lastBytes = Files.size(newest);
            } catch (IOException ignored) {
                // shown as "no backup yet" until the next one
            }
        }
    }

    // ------------------------------------------------------------------ making backups

    /** Called every few seconds while the server runs: makes a backup when one is due. */
    void maybeRun() {
        if (System.currentTimeMillis() < nextRun) return;
        try {
            runNow();
            nextRun = System.currentTimeMillis() + 30 * 60_000L;
        } catch (RuntimeException e) {
            nextRun = System.currentTimeMillis() + 5 * 60_000L;       // try again soon
        }
    }

    /** Makes a backup now. Returns what to tell the person who asked. */
    public synchronized String runNow() {
        Path dir = stack.backupsDir();
        Path tmp = dir.resolve("backup.tmp");
        try {
            Files.createDirectories(dir);
            Files.deleteIfExists(tmp);
            Proc.Result r = Proc.run(stack.dbTool("pg_dump", "-d", LocalStack.DB_NAME, "-Fc", "-Z", "6", "--no-owner",
                    "--no-privileges", "--exclude-table-data=public.document_previews", "-f", tmp.toString()),
                    stack.dbEnv(), null, 600);
            if (!r.ok() || !Files.isRegularFile(tmp) || Files.size(tmp) < 200) {
                throw new IOException(r.timedOut() ? "it took too long" : LocalStack.firstLine(r.tail()));
            }
            Path file = dir.resolve("xeogo-" + LocalDateTime.now().format(STAMP) + ".dump");
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            lastAt = Instant.now();
            lastBytes = Files.size(file);
            lastProblem = null;
            tidy(dir);
            log.info("Backup made: {} ({} KB)", file.getFileName(), lastBytes / 1024);
            if (cfg.hasCloud()) sendToCloud(file);
            return describe();
        } catch (IOException e) {
            lastProblem = "The backup could not be made: " + e.getMessage();
            log.error(lastProblem);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // overwritten next time
            }
            throw new IllegalStateException(lastProblem);
        }
    }

    /** Keeps the newest 30, and the last of each day for 45 days. */
    private void tidy(Path dir) throws IOException {
        List<Path> all = list(dir);                       // newest first
        Set<Path> keep = new HashSet<>(all.subList(0, Math.min(KEEP_LATEST, all.size())));
        Set<String> days = new HashSet<>();
        String oldest = LocalDate.now().minusDays(KEEP_DAYS).format(DateTimeFormatter.BASIC_ISO_DATE);
        for (Path p : all) {
            String day = dayOf(p);
            if (day.compareTo(oldest) >= 0 && days.add(day)) keep.add(p);
        }
        for (Path p : all) {
            if (!keep.contains(p)) Files.deleteIfExists(p);
        }
    }

    private static List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("xeogo-\\d{8}-\\d{6}\\.dump"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String dayOf(Path p) {
        return p.getFileName().toString().substring(6, 14);
    }

    /** The newest backup on this PC, or null. */
    public Path latest() {
        List<Path> all = list(stack.backupsDir());
        return all.isEmpty() ? null : all.get(0);
    }

    // ------------------------------------------------------------------ the copy in the cloud

    private void sendToCloud(Path file) {
        try {
            ensureBucket();
            upload("latest.dump", file);
            upload("daily/" + LocalDate.now() + ".dump", file);
            byte[] note = JSON.writeValueAsBytes(Map.of("at", Instant.now().toString(), "bytes", Files.size(file),
                    "file", file.getFileName().toString()));
            send(HttpRequest.newBuilder(storage("/object/" + BUCKET + "/latest.json"))
                    .header("Content-Type", "application/json").header("x-upsert", "true")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(note)), 60);
            cloudAt = Instant.now();
            cloudProblem = null;
            removeOldCloudCopies();
            log.info("Backup copied to the cloud");
        } catch (IOException e) {
            cloudProblem = e.getMessage();
            log.warn("The backup could not be copied to the cloud: {}", e.getMessage());
        }
    }

    private void ensureBucket() throws IOException {
        if (bucketChecked) return;
        HttpResponse<String> r = send(HttpRequest.newBuilder(storage("/bucket"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"" + BUCKET + "\",\"name\":\"" + BUCKET
                        + "\",\"public\":false}")), 30);
        // 200: made. 400/409 "already exists": fine.
        if (r.statusCode() / 100 != 2 && !r.body().toLowerCase().contains("exist")) {
            throw new IOException(cloudError(r));
        }
        bucketChecked = true;
    }

    private void upload(String name, Path file) throws IOException {
        HttpResponse<String> r = send(HttpRequest.newBuilder(storage("/object/" + BUCKET + "/" + name))
                .header("Content-Type", "application/octet-stream").header("x-upsert", "true")
                .POST(HttpRequest.BodyPublishers.ofFile(file)), 600);
        if (r.statusCode() / 100 != 2) throw new IOException(cloudError(r));
    }

    private void removeOldCloudCopies() {
        try {
            HttpResponse<String> r = send(HttpRequest.newBuilder(storage("/object/list/" + BUCKET))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"prefix\":\"daily/\",\"limit\":1000}")), 30);
            if (r.statusCode() != 200) return;
            String oldest = LocalDate.now().minusDays(KEEP_CLOUD_DAYS).toString();
            List<String> old = new ArrayList<>();
            for (JsonNode n : JSON.readTree(r.body())) {
                String name = n.path("name").asText("");
                if (name.matches("\\d{4}-\\d{2}-\\d{2}\\.dump") && name.substring(0, 10).compareTo(oldest) < 0) {
                    old.add("daily/" + name);
                }
            }
            if (old.isEmpty()) return;
            send(HttpRequest.newBuilder(storage("/object/" + BUCKET)).header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("prefixes", old)))), 30);
        } catch (IOException e) {
            log.debug("Old cloud copies were not removed: {}", e.getMessage());
        }
    }

    /** Fetches the latest backup from the cloud into the backups folder. Null when there is none. */
    public Path fetchFromCloud() throws IOException {
        if (!cfg.hasCloud()) return null;
        Path dir = stack.backupsDir();
        Files.createDirectories(dir);
        Path tmp = dir.resolve("cloud.tmp");
        HttpRequest req = auth(HttpRequest.newBuilder(storage("/object/authenticated/" + BUCKET + "/latest.dump")))
                .timeout(Duration.ofMinutes(10)).GET().build();
        try {
            HttpResponse<Path> r = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (r.statusCode() == 404 || r.statusCode() == 400) {
                Files.deleteIfExists(tmp);
                return null;
            }
            if (r.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IOException("the cloud answered " + r.statusCode());
            }
            Path file = dir.resolve("xeogo-" + LocalDateTime.now().format(STAMP) + ".dump");
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            // The file's date is when the backup was MADE (the cloud keeps that next to it), not when it was
            // fetched: "put back from the backup of ..." must say how old the data is.
            try {
                HttpResponse<String> note = send(HttpRequest.newBuilder(storage("/object/authenticated/" + BUCKET + "/latest.json")).GET(), 30);
                if (note.statusCode() == 200) {
                    Instant made = Instant.parse(JSON.readTree(note.body()).path("at").asText());
                    Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(made));
                }
            } catch (IOException | RuntimeException e) {
                // then it keeps the time it was fetched
            }
            return file;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /** Checks the cloud settings by making (or finding) the bucket. Null: fine; else what is wrong. */
    public String checkCloud() {
        if (!cfg.hasCloud()) return null;
        try {
            bucketChecked = false;
            ensureBucket();
            return null;
        } catch (IOException | RuntimeException e) {
            return "The cloud copy cannot be set up: " + e.getMessage();
        }
    }

    private URI storage(String path) {
        return URI.create(cfg.cloudUrl.trim().replaceAll("/+$", "") + "/storage/v1" + path);
    }

    /** New Supabase secret keys (sb_secret_...) travel only as apikey; legacy service_role keys also as Bearer. */
    private HttpRequest.Builder auth(HttpRequest.Builder b) {
        String key = cfg.cloudKey.trim();
        b.header("apikey", key);
        if (!key.startsWith("sb_")) b.header("Authorization", "Bearer " + key);
        return b;
    }

    private HttpResponse<String> send(HttpRequest.Builder b, int seconds) throws IOException {
        try {
            return http.send(auth(b).timeout(Duration.ofSeconds(seconds)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IllegalArgumentException e) {
            throw new IOException("the cloud address is not a web address");
        }
    }

    private static String cloudError(HttpResponse<String> r) {
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            return "the cloud key was refused (it must be the secret key of the project, not the public one)";
        }
        if (r.statusCode() == 402) return "the cloud project is over its free limits";
        if (r.statusCode() == 540 || r.body().toLowerCase().contains("paused")) {
            return "the cloud project is paused: open supabase.com and press Restore project";
        }
        String body = r.body() == null ? "" : r.body().strip();
        return "the cloud answered " + r.statusCode() + (body.isEmpty() ? "" : " " + (body.length() > 160 ? body.substring(0, 160) : body));
    }

    // ------------------------------------------------------------------ putting a backup back

    private Path marker() {
        return stack.data().resolve("restore-this.txt");
    }

    /**
     * Puts this backup back at the next start of the database (before the print
     * service starts). null: the latest one in the cloud, if there is one.
     */
    public void restoreAtNextStart(Path dump) throws IOException {
        Files.createDirectories(stack.data());
        Files.writeString(marker(), dump == null ? "cloud" : dump.toAbsolutePath().toString());
    }

    /** Somebody asked for a backup (or the cloud database) to be brought in? */
    public boolean restorePending() {
        return Files.isRegularFile(marker());
    }

    /**
     * Called when the database server runs and before the print service
     * starts. If a backup is waiting, the database is replaced by it.
     * fresh: the database was just made (nothing in it to lose).
     */
    boolean restoreWaiting(boolean fresh) {
        Path marker = marker();
        if (!Files.isRegularFile(marker)) return false;
        String what;
        try {
            what = Files.readString(marker).strip();
        } catch (IOException e) {
            return false;
        }
        try {
            Path dump;
            if (what.equals("cloud")) {
                dump = fetchFromCloud();
                if (dump == null) {
                    log.warn("There is no backup in the cloud to put back: starting with an empty database");
                    restoreNote = "There was no backup in the cloud: this PC starts with an empty shop.";
                    Files.deleteIfExists(marker);
                    return true;
                }
            } else {
                dump = Path.of(what);
            }
            if (!Files.isRegularFile(dump)) {
                log.error("The backup to put back is not there: {}", dump);
                Files.deleteIfExists(marker);
                return false;
            }
            boolean imported = dump.getFileName().toString().startsWith("import-");
            log.warn("Putting the backup {} back into the database", dump.getFileName());
            recreate();
            if (imported) {
                // A copy of the cloud database holds only the shop's own part: give it what Supabase brings along.
                stack.psql(LocalStack.DB_NAME, "create schema if not exists extensions; "
                        + "create extension if not exists pgcrypto with schema extensions");
            }
            Proc.Result r = Proc.run(stack.dbTool("pg_restore", "-d", LocalStack.DB_NAME, "--no-owner", "--no-privileges",
                    dump.toString()), stack.dbEnv(), null, 1800);
            Proc.Result check = stack.psql(LocalStack.DB_NAME, "select count(*) from shop_settings");
            boolean usable = check.ok() && !check.output().strip().isEmpty();
            if (!usable) {
                // Not a usable backup: never leave half of one behind.
                log.error("The backup could not be put back ({}). Starting with an empty database instead.", r.tail());
                recreate();
                Files.move(marker, marker.resolveSibling("restore-failed.txt"), StandardCopyOption.REPLACE_EXISTING);
                restoreNote = "The backup " + dump.getFileName() + " could not be put back. This PC started with an "
                        + "empty shop; the backup files are still in the backups folder.";
                return true;
            }
            if (!r.ok()) log.warn("The backup was put back with warnings: {}", r.tail());
            Files.deleteIfExists(marker);
            String when = CLOCK.format(LocalDateTime.ofInstant(Files.getLastModifiedTime(dump).toInstant(), ZoneId.systemDefault()));
            restoreNote = imported ? "The shop's data was brought over from the cloud database (" + when + ")."
                    : "The database was put back from the backup of " + when + "."
                      + (fresh ? "" : " Orders made after that time are not in it.");
            log.warn(restoreNote);
            return true;
        } catch (IOException e) {
            log.error("Putting the backup back failed: {}", e.getMessage());
            restoreNote = "The backup could not be put back: " + e.getMessage();
            return false;
        }
    }

    /** An empty database in place of the present one. */
    private void recreate() throws IOException {
        Proc.run(stack.dbTool("dropdb", "--if-exists", "--force", LocalStack.DB_NAME), stack.dbEnv(), null, 120);
        Proc.Result made = Proc.run(stack.dbTool("createdb", "-E", "UTF8", "-T", "template0", LocalStack.DB_NAME),
                stack.dbEnv(), null, 120);
        if (!made.ok()) throw new IOException(LocalStack.firstLine(made.tail()));
    }

    String lastRestoreNote() {
        return restoreNote;
    }

    /**
     * Copies the shop's data out of the cloud database (the Supabase project
     * the cloud server used) into a file here, to be put into this PC's
     * database at its next start. address: the JDBC address or host the cloud
     * server used (DB_URL), with its user and password.
     */
    public Path copyFromCloudDatabase(String address, String user, String password) throws IOException {
        CloudDb db = CloudDb.parse(address);
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            throw new IOException("Type the database user and password of the cloud database.");
        }
        Path dir = stack.backupsDir();
        Files.createDirectories(dir);
        Path tmp = dir.resolve("import.tmp");
        Files.deleteIfExists(tmp);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PGPASSWORD", password);
        env.put("PGSSLMODE", db.ssl());          // "require" unless the address itself says otherwise
        env.put("PGCONNECT_TIMEOUT", "20");
        Proc.Result r = Proc.run(List.of(stack.tool("pg_dump").toString(), "-h", db.host(), "-p", String.valueOf(db.port()),
                "-U", user.trim(), "-d", db.name(), "--schema=public", "-Fc", "--no-owner", "--no-privileges",
                "--exclude-table-data=public.document_previews", "-f", tmp.toString()), env, null, 900);
        if (!r.ok() || !Files.isRegularFile(tmp) || Files.size(tmp) < 200) {
            Files.deleteIfExists(tmp);
            String why = r.tail();
            if (why.contains("password authentication failed")) why = "the user or password is not right.";
            else if (why.contains("could not translate host name") || why.contains("Unknown host")) why = "that address was not found. Check it, and the internet.";
            else if (why.contains("timeout") || why.contains("Connection refused")) why = "the cloud database did not answer (is the project paused?).";
            else if (why.contains("server version mismatch")) why = "it runs a newer PostgreSQL than this Station can copy from. Start with an empty shop, or use a newer Station.";
            else why = LocalStack.firstLine(why);
            throw new IOException("The cloud database could not be copied: " + why);
        }
        Path file = dir.resolve("import-" + LocalDateTime.now().format(STAMP) + ".dump");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        log.info("The cloud database was copied to {} ({} KB)", file.getFileName(), Files.size(file) / 1024);
        return file;
    }

    /**
     * host, port and database name out of "jdbc:postgresql://host:5432/postgres?sslmode=require" or "host".
     * ssl: the connection is encrypted ("require", as Supabase wants it) unless the address says sslmode=disable
     * (a database on the same network, or a test).
     */
    record CloudDb(String host, int port, String name, String ssl) {
        static CloudDb parse(String address) throws IOException {
            String a = address == null ? "" : address.trim();
            if (a.isEmpty()) throw new IOException("Type the address of the cloud database (DB_URL).");
            a = a.replaceFirst("(?i)^jdbc:", "").replaceFirst("(?i)^postgres(ql)?://", "");
            String ssl = "require";
            int q = a.indexOf('?');
            if (q >= 0) {
                if (a.substring(q).toLowerCase().contains("sslmode=disable")) ssl = "disable";
                a = a.substring(0, q);
            }
            int at = a.lastIndexOf('@');
            if (at >= 0) a = a.substring(at + 1);                  // user:password@host
            String name = "postgres";
            int slash = a.indexOf('/');
            if (slash >= 0) {
                if (slash + 1 < a.length()) name = a.substring(slash + 1);
                a = a.substring(0, slash);
            }
            int port = 5432;
            int colon = a.lastIndexOf(':');
            if (colon > 0) {
                try {
                    port = Integer.parseInt(a.substring(colon + 1));
                } catch (NumberFormatException e) {
                    throw new IOException("That database address is not valid.");
                }
                a = a.substring(0, colon);
            }
            if (a.isBlank() || !a.matches("[A-Za-z0-9.-]+") || !name.matches("[A-Za-z0-9_-]+")) {
                throw new IOException("That database address is not valid.");
            }
            return new CloudDb(a, port, name, ssl);
        }
    }

    // ------------------------------------------------------------------ for the screens

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastAt", lastAt == null ? null : lastAt.toString());
        m.put("lastKb", lastBytes / 1024);
        m.put("problem", lastProblem);
        m.put("count", list(stack.backupsDir()).size());
        m.put("folder", stack.backupsDir().toString());
        m.put("cloud", cfg.hasCloud());
        m.put("cloudAt", cloudAt == null ? null : cloudAt.toString());
        m.put("cloudProblem", cloudProblem);
        m.put("detail", describe());
        return m;
    }

    private String describe() {
        if (lastAt == null) return lastProblem != null ? lastProblem : "No backup yet: the first one is made a few minutes after the start.";
        String when = CLOCK.format(LocalDateTime.ofInstant(lastAt, ZoneId.systemDefault()));
        String here = "Last backup: " + when + ".";
        if (lastProblem != null) return here + " " + lastProblem;
        if (!cfg.hasCloud()) return here + " Kept on this PC only: add the cloud copy so a broken disk loses nothing.";
        if (cloudProblem != null) return here + " The copy in the cloud did not work: " + cloudProblem + ".";
        if (cloudAt == null) return here + " The copy in the cloud is made with the next backup.";
        return here + " A copy is in the cloud.";
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
