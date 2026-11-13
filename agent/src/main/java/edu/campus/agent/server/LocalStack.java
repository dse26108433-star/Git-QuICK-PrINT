package edu.campus.agent.server;

import edu.campus.agent.station.StationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * "This PC is the XeoGo server."
 *
 * The Station starts, watches and stops three programs that come with it:
 *
 *   1. the database      PostgreSQL, data in ...\CampusPrint\server\pgdata
 *   2. the print service the same server program that can run in the cloud,
 *                        here with the files on this PC's disk
 *   3. the tunnel        cloudflared: gives this PC its https address on the
 *                        internet without opening anything in the router
 *
 * Nobody has to start anything: the Station starts with Windows, and this
 * class brings the three up in that order, however long it takes (a first
 * start, or a start after the power was cut, takes longer). While the
 * Station runs it looks every few seconds: whatever stopped is started
 * again. Switching the PC off in the evening is fine: the database is built
 * to survive that, and tidies itself the next morning.
 *
 * If the database cannot be started three times in a row (a damaged disk
 * area, a full disk at the wrong moment), the damaged data is put aside
 * (never deleted) and the latest backup is put in its place, so the shop
 * opens anyway. See Backups.
 *
 * Everything listens on 127.0.0.1 only: nothing on the network can reach the
 * database or the print service directly. Phones come in through the tunnel.
 */
public final class LocalStack {

    private static final Logger log = LoggerFactory.getLogger(LocalStack.class);
    static final String DB_USER = "xeogo";
    static final String DB_NAME = "xeogo";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** One of the three programs, as the screen shows it. */
    public record Part(String state, String detail) {
        static final Part OFF = new Part("off", "");

        static Part starting(String detail) {
            return new Part("starting", detail);
        }

        static Part running(String detail) {
            return new Part("running", detail);
        }

        static Part problem(String detail) {
            return new Part("problem", detail);
        }

        boolean isRunning() {
            return "running".equals(state);
        }
    }

    private final ServerSettings cfg;
    private final Path home;
    private final Path data;
    private final Path pgdata;
    private final Path logs;
    private final Backups backups;
    private final KeepAwake awake = new KeepAwake();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private volatile boolean wanted;
    private Thread thread;
    private Process service;
    private Process tunnel;

    private volatile Part database = Part.OFF;
    private volatile Part serviceState = Part.OFF;
    private volatile Part tunnelState = Part.OFF;
    private volatile String repaired;                 // "The database was repaired from the backup of ..."
    private volatile Boolean publicOk;
    private volatile String publicNote = "";
    private volatile Instant publicAt;

    private int databaseFailures;
    private int serviceFailures;
    private long serviceRetryAt;
    private long serviceStoppedAt;                 // when this Station last stopped its own print service
    private long serviceStartedAt;
    private long tunnelStartedAt;
    private long tunnelRetryAt;
    private long nextPublicCheck;
    private boolean databaseChecked;                  // the database inside the server exists (asked once per start)
    private volatile boolean restartService;
    private volatile boolean restartTunnel;

    public LocalStack(ServerSettings cfg) {
        this.cfg = cfg;
        this.home = home();
        this.data = dataDir();
        this.pgdata = data.resolve("pgdata");
        this.logs = StationConfig.dir().resolve("logs");
        this.backups = new Backups(cfg, this);
    }

    // ------------------------------------------------------------------ where things are

    /**
     * The folder with the programs: pgsql\bin\postgres.exe, print-server.jar,
     * cloudflared.exe. Installed: "server" next to the Station's own program.
     * -Dxeogo.server.home=... (or XEOGO_SERVER_HOME) points somewhere else, for tests.
     */
    public static Path home() {
        String set = System.getProperty("xeogo.server.home", "");
        if (set.isBlank()) set = System.getenv("XEOGO_SERVER_HOME") == null ? "" : System.getenv("XEOGO_SERVER_HOME");
        if (!set.isBlank()) return Path.of(set.trim());
        String launcher = System.getProperty("jpackage.app-path", "");
        if (!launcher.isBlank()) return Path.of(launcher).toAbsolutePath().getParent().resolve("server");
        return Path.of("server").toAbsolutePath();
    }

    /** Did this copy of the Station come with the server programs? */
    public static boolean available() {
        Path h = home();
        return Files.isRegularFile(h.resolve("pgsql").resolve("bin").resolve(exe("postgres")))
                && Files.isRegularFile(h.resolve("print-server.jar"));
    }

    /**
     * The database, the files and the backups. Next to the Station's settings;
     * PostgreSQL does not like folder names with letters outside plain English,
     * so for a Windows user called, say, "ज़ेरॉक्स" it is C:\ProgramData\XeoGo instead.
     */
    public static Path dataDir() {
        Path usual = StationConfig.dir().resolve("server");
        boolean plain = usual.toString().chars().allMatch(c -> c < 128);
        if (plain) return usual;
        String shared = System.getenv("ProgramData");
        return shared == null || shared.isBlank() ? usual : Path.of(shared, "XeoGo", "server");
    }

    public Path data() {
        return data;
    }

    /** No database has been made on this PC yet (so one can still be brought in from a backup or the cloud). */
    public boolean isFresh() {
        return !Files.isRegularFile(pgdata.resolve("PG_VERSION"));
    }

    public Path logsDir() {
        return logs;
    }

    Path logs() {
        return logs;
    }

    public Path filesDir() {
        return data.resolve("files");
    }

    public Path backupsDir() {
        return data.resolve("backups");
    }

    Path tool(String name) {
        return home.resolve("pgsql").resolve("bin").resolve(exe(name));
    }

    private static String exe(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    /** What the database tools need to sign in. */
    Map<String, String> dbEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PGPASSWORD", cfg.dbPassword);
        env.put("PGCLIENTENCODING", "UTF8");
        env.put("PGCONNECT_TIMEOUT", "10");
        return env;
    }

    List<String> dbTool(String name, String... args) {
        List<String> c = new ArrayList<>(List.of(tool(name).toString(), "-h", "127.0.0.1", "-p",
                String.valueOf(cfg.dbPort), "-U", DB_USER));
        c.addAll(List.of(args));
        return c;
    }

    // ------------------------------------------------------------------ start and stop

    public synchronized void start() {
        if (thread != null && thread.isAlive()) return;
        wanted = true;
        awake.on();
        thread = new Thread(this::supervise, "server");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isWanted() {
        return wanted;
    }

    /** Stops the tunnel, the print service and the database, in that order. Quick: Windows may be shutting down. */
    public synchronized void stop() {
        if (!wanted && thread == null) return;
        wanted = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        try {
            if (t != null) t.join(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        awake.off();
        Proc.stop(tunnel, 2);
        tunnel = null;
        tunnelState = Part.OFF;
        Proc.stop(service, 3);
        service = null;
        serviceState = Part.OFF;
        if (Files.exists(pgdata.resolve("postmaster.pid"))) {
            Proc.Result r = Proc.run(List.of(tool("pg_ctl").toString(), "stop", "-D", pgdata.toString(), "-m", "fast",
                    "-w", "-t", "25", "-s"), null, null, 30);
            if (!r.ok()) log.warn("The database did not stop cleanly ({}); it tidies itself at the next start", r.tail());
        }
        database = Part.OFF;
        log.info("The server on this PC is stopped");
    }

    /** Settings changed: the print service (payment, websites) or the tunnel (token) takes them at once. */
    public void applySettings(boolean serviceChanged, boolean tunnelChanged) {
        if (serviceChanged) restartService = true;
        if (tunnelChanged) restartTunnel = true;
        nextPublicCheck = 0;
        Thread t = thread;
        if (t != null) t.interrupt();                  // look now, not in a few seconds
    }

    // ------------------------------------------------------------------ the loop

    private void supervise() {
        log.info("This PC is the XeoGo server: programs in {}, data in {}", home, data);
        while (wanted) {
            long pause = 3000;
            try {
                if (cfg.damaged) {
                    database = Part.problem("The server's settings file (server.json) cannot be read. "
                            + "Restore it from a backup, or set this PC up again.");
                    pause = 30_000;
                } else if (lookAfterDatabase()) {
                    if (lookAfterService()) {
                        lookAfterTunnel();
                        checkPublicAddress();
                        backups.maybeRun();
                        keepFirst();
                        awake.on();                    // (started again if it ever ended)
                        pause = 8000;
                    }
                }
            } catch (RuntimeException e) {
                log.error("Looking after the server failed; trying again", e);
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException e) {
                // woken up: settings changed, or stopping
            }
        }
    }

    // ------------------------------------------------------------------ 1. the database

    /** True when the database answers. Starts it (and makes it, the first time) when it does not. */
    private boolean lookAfterDatabase() {
        int ready = Proc.run(List.of(tool("pg_isready").toString(), "-h", "127.0.0.1", "-p", String.valueOf(cfg.dbPort),
                "-t", "3", "-q"), null, null, 10).exit();
        if (ready == 0) {
            if (!databaseChecked) {
                if (!ensureDatabaseInside()) return false;
                databaseChecked = true;
                databaseFailures = 0;
                try {
                    // also when it was already running (the Station was restarted, not the PC)
                    comeFirst(Long.parseLong(Files.readAllLines(pgdata.resolve("postmaster.pid")).get(0).trim()));
                } catch (IOException | RuntimeException e) {
                    // not essential
                }
            }
            if (!database.isRunning()) log.info("Database: running");
            database = Part.running("");
            return true;
        }
        databaseChecked = false;
        if (ready == 1) {
            // it is there but not taking questions yet: starting, or tidying up after the PC was switched off
            database = Part.starting("The database is getting ready (after a power cut this can take a few minutes).");
            return false;
        }
        startDatabase();
        return false;
    }

    private void startDatabase() {
        try {
            Files.createDirectories(data);
            Files.createDirectories(logs);
            Files.createDirectories(filesDir());
            if (!Files.isRegularFile(pgdata.resolve("PG_VERSION"))) {
                if (!makeDatabase()) return;
            }
            writeDatabaseSettings();
        } catch (IOException e) {
            database = Part.problem("Cannot write to " + data + ": " + e.getMessage());
            return;
        }
        // Still running from before (the Station was restarted, not the PC)? Then it only needs a moment.
        int status = Proc.run(List.of(tool("pg_ctl").toString(), "status", "-D", pgdata.toString()), null, null, 15).exit();
        if (status == 0) {
            database = Part.starting("The database is getting ready.");
            return;
        }
        database = Part.starting("Starting the database…");
        Path startLog = logs.resolve("database-start.log");
        Proc.Result r = Proc.run(List.of(tool("pg_ctl").toString(), "start", "-D", pgdata.toString(), "-w", "-t", "180",
                "-s", "-l", startLog.toString()), null, null, 200);
        if (r.ok()) {
            log.info("Database: started");
            try {
                // first line of postmaster.pid: the database's process number
                comeFirst(Long.parseLong(Files.readAllLines(pgdata.resolve("postmaster.pid")).get(0).trim()));
            } catch (IOException | RuntimeException e) {
                // not essential
            }
            return;
        }
        databaseFailures++;
        String why = firstLine(r.tail() + "\n" + Proc.tailOf(startLog, 400));
        log.error("The database did not start (try {}): {}", databaseFailures, r.tail() + " | " + Proc.tailOf(startLog, 800));
        if (portTaken(cfg.dbPort)) {
            database = Part.problem("Another program on this PC uses port " + cfg.dbPort + ", which the database needs.");
            return;
        }
        if (databaseFailures >= 3) {
            repairDatabase(why);
        } else {
            database = Part.problem("The database did not start (" + why + "). Trying again…");
        }
    }

    /** The very first start: make an empty database with a password only the Station knows. */
    private boolean makeDatabase() throws IOException {
        if (Files.isDirectory(pgdata)) {
            try (Stream<Path> inside = Files.list(pgdata)) {
                if (inside.findAny().isPresent()) {
                    // Something half-made is there (the PC lost power during the first start): never build on it.
                    Path aside = data.resolve("pgdata.unfinished-" + LocalDateTime.now().format(STAMP));
                    Files.move(pgdata, aside);
                    log.warn("An unfinished database folder was put aside: {}", aside);
                }
            }
        }
        database = Part.starting("Making the database (only the first time, about a minute)…");
        log.info("Making the database in {}", pgdata);
        Path pw = ServerSettings.writeSecretFile(data, "pw.tmp", cfg.dbPassword);
        try {
            Proc.Result r = Proc.run(List.of(tool("initdb").toString(), "-D", pgdata.toString(), "-U", DB_USER,
                    "--pwfile=" + pw, "--auth=scram-sha-256", "-E", "UTF8", "--locale=C", "--no-instructions"),
                    Map.of("TZ", "UTC"), null, 300);
            if (!r.ok()) {
                log.error("Making the database failed: {}", r.tail());
                database = Part.problem("The database could not be made: " + firstLine(r.tail()));
                return false;
            }
        } finally {
            Files.deleteIfExists(pw);
        }
        Path conf = pgdata.resolve("postgresql.conf");
        Files.writeString(conf, Files.readString(conf) + "\n# XeoGo's own settings (written by the Station at every start)\n"
                + "include_if_exists = 'xeogo.conf'\n");
        return true;
    }

    /** Our settings, written fresh at every start (so a new version of the Station can change them). */
    private void writeDatabaseSettings() throws IOException {
        long ramMb = Machine.memoryMb();
        String logDir = logs.toString().replace('\\', '/');
        String conf = """
                # Written by XeoGo Station at every start. Changes made here are lost.
                listen_addresses = '127.0.0.1'
                port = %d
                max_connections = 80
                shared_buffers = %dMB
                password_encryption = scram-sha-256
                timezone = 'UTC'
                # log files: one per weekday, each replaced a week later
                logging_collector = on
                log_directory = '%s'
                log_filename = 'database-%%a.log'
                log_truncate_on_rotation = on
                log_rotation_age = 1d
                log_rotation_size = 0
                log_min_messages = warning
                log_line_prefix = '%%m [%%p] '
                log_checkpoints = off
                # a PC that is switched off every evening: keep what was confirmed, always
                fsync = on
                synchronous_commit = on
                full_page_writes = on
                """.formatted(cfg.dbPort, ramMb <= 4500 ? 128 : 256, logDir);
        Files.writeString(pgdata.resolve("xeogo.conf"), conf);
        writeAccessRules(false);
    }

    /** Who may sign in to the database: programs on this PC, with the password. open: without (only to set a lost password). */
    private void writeAccessRules(boolean open) throws IOException {
        String how = open ? "trust" : "scram-sha-256";
        Files.writeString(pgdata.resolve("pg_hba.conf"), "# Written by XeoGo Station: only programs on this PC.\n"
                + "host all all 127.0.0.1/32 " + how + "\nhost all all ::1/128 " + how + "\n");
    }

    /** The database server runs: make sure our database exists in it, and put a waiting backup into it. */
    private boolean ensureDatabaseInside() {
        Proc.Result has = psql("postgres", "select 1 from pg_database where datname = '" + DB_NAME + "'");
        if (!has.ok() && has.output() != null && has.output().contains("password authentication failed")) {
            if (!resetDatabasePassword()) return false;
            has = psql("postgres", "select 1 from pg_database where datname = '" + DB_NAME + "'");
        }
        if (!has.ok()) {
            database = Part.problem("The database does not answer: " + firstLine(has.tail()));
            return false;
        }
        boolean fresh = !has.output().strip().equals("1");
        if (fresh) {
            Proc.Result made = Proc.run(dbTool("createdb", "-E", "UTF8", "-T", "template0", DB_NAME), dbEnv(), null, 60);
            if (!made.ok()) {
                database = Part.problem("The database could not be made: " + firstLine(made.tail()));
                return false;
            }
        }
        // A backup waiting to be put back (this PC was set up from a backup, or the database was repaired).
        if (backups.restoreWaiting(fresh)) repaired = backups.lastRestoreNote();
        return true;
    }

    /**
     * The Station's password no longer opens the database (server.json was lost
     * or replaced, the data was kept). The data is ours: let this PC in without
     * a password for a moment, set the password again, and close it again.
     */
    private boolean resetDatabasePassword() {
        log.warn("The database does not accept the Station's password: setting it again");
        try {
            writeAccessRules(true);
            Proc.run(List.of(tool("pg_ctl").toString(), "reload", "-D", pgdata.toString(), "-s"), null, null, 20);
            Proc.Result r = Proc.run(dbTool("psql", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-tAc",
                    "alter user " + DB_USER + " password '" + cfg.dbPassword.replace("'", "''") + "'"), Map.of(), null, 30);
            if (!r.ok()) {
                database = Part.problem("The database's password could not be set again: " + firstLine(r.tail()));
                return false;
            }
            return true;
        } catch (IOException e) {
            database = Part.problem("The database's password could not be set again: " + e.getMessage());
            return false;
        } finally {
            try {
                writeAccessRules(false);
            } catch (IOException e) {
                log.error("Could not close the database's sign-in rules again", e);
            }
            Proc.run(List.of(tool("pg_ctl").toString(), "reload", "-D", pgdata.toString(), "-s"), null, null, 20);
        }
    }

    Proc.Result psql(String db, String sql) {
        return Proc.run(dbTool("psql", "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql), dbEnv(), null, 60);
    }

    /**
     * The database would not start three times. Its folder is put aside (kept,
     * for someone who wants to look into it) and a new, empty one is made; the
     * latest backup is put into it at the next look.
     */
    private void repairDatabase(String why) {
        Path newest = backups.latest();
        if (newest == null && Files.isRegularFile(pgdata.resolve("PG_VERSION"))) {
            // No backup to fall back on: never throw the only copy away. Keep trying, and say so plainly.
            database = Part.problem("The database does not start (" + why + ") and there is no backup yet. "
                    + "Restart this PC. If it stays like this, ask for help: nothing has been deleted.");
            databaseFailures = 0;
            return;
        }
        try {
            Proc.run(List.of(tool("pg_ctl").toString(), "stop", "-D", pgdata.toString(), "-m", "immediate", "-s"),
                    null, null, 30);
            Path aside = data.resolve("pgdata.damaged-" + LocalDateTime.now().format(STAMP));
            Files.move(pgdata, aside);
            log.error("The database did not start three times ({}). Its folder was put aside as {} and the backup {} "
                    + "is being put back.", why, aside, newest == null ? "(none)" : newest.getFileName());
            backups.restoreAtNextStart(newest);
            databaseFailures = 0;
            database = Part.starting("The database was damaged: putting the latest backup back…");
        } catch (IOException e) {
            database = Part.problem("The database does not start (" + why + "), and its folder cannot be moved: "
                    + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 2. the print service

    private boolean lookAfterService() {
        if (restartService) {
            restartService = false;
            if (service != null) {
                log.info("Print service: restarting with the new settings");
                Proc.stop(service, 8);
                serviceStoppedAt = System.currentTimeMillis();
                service = null;
            }
            serviceFailures = 0;
            serviceRetryAt = 0;
        }
        boolean alive = service != null && service.isAlive();
        boolean healthy = alive && healthy();
        if (healthy) {
            if (!serviceState.isRunning()) {
                log.info("Print service: running on {}", cfg.localUrl());
                nextKeepFirst = System.currentTimeMillis() + 20_000;       // its database connections are there now
            }
            serviceState = Part.running("");
            serviceFailures = 0;
            return true;
        }
        if (alive) {
            long since = System.currentTimeMillis() - serviceStartedAt;
            if (since < 300_000) {
                serviceState = Part.starting(since < 60_000 ? "Starting the print service…"
                        : "Starting the print service (bringing the database up to date)…");
                return false;
            }
            // five minutes and still no answer: something is stuck
            log.error("The print service does not answer after five minutes: starting it again. {}",
                    Proc.tailOf(logs.resolve("server-start.log"), 800));
            Proc.stop(service, 5);
            serviceStoppedAt = System.currentTimeMillis();
            service = null;
        } else if (service != null) {
            serviceFailures++;
            String why = startProblem();
            log.error("The print service stopped (try {}): {}", serviceFailures, why);
            serviceState = Part.problem("The print service stopped: " + why + " Starting it again…");
            service = null;
            serviceRetryAt = System.currentTimeMillis() + Math.min(60_000, 5000L * serviceFailures);
        }
        if (System.currentTimeMillis() < serviceRetryAt) return false;
        startService();
        return false;
    }

    private void startService() {
        if (portTaken(cfg.httpPort)) {
            // Our own print service from before the Station was restarted ends by itself within seconds, and one this
            // Station has just stopped (to restart it with new settings) can hold the port a moment longer.
            serviceState = healthy() || System.currentTimeMillis() - serviceStoppedAt < 60_000
                    ? Part.starting("Waiting for the previous print service to end…")
                    : Part.problem("Another program on this PC uses port " + cfg.httpPort + ", which the print service needs.");
            serviceRetryAt = System.currentTimeMillis() + 4000;
            return;
        }
        long ramMb = Machine.memoryMb();
        int heap = cfg.serverMemoryMb > 0 ? cfg.serverMemoryMb : (int) Math.max(768, Math.min(2048, ramMb / 6));
        int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
        Path java = Path.of(System.getProperty("java.home"), "bin", exe("java"));
        List<String> cmd = List.of(java.toString(), "-Xms128m", "-Xmx" + heap + "m", "-XX:+ExitOnOutOfMemoryError",
                "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-jar", home.resolve("print-server.jar").toString());

        Map<String, String> env = new LinkedHashMap<>();
        env.put("PORT", String.valueOf(cfg.httpPort));
        env.put("BIND_ADDRESS", "127.0.0.1");
        env.put("DB_URL", "jdbc:postgresql://127.0.0.1:" + cfg.dbPort + "/" + DB_NAME);
        env.put("DB_USER", DB_USER);
        env.put("DB_PASSWORD", cfg.dbPassword);
        env.put("DB_PLAIN_POSTGRES", "true");
        env.put("DB_POOL_SIZE", "24");
        env.put("HTTP_THREADS", "300");
        env.put("STORAGE_MODE", "local");
        env.put("STORAGE_DIR", filesDir().toString());
        env.put("PUBLIC_URL", nz(cfg.publicUrl));
        env.put("CHECK_MEMORY_MB", String.valueOf(Math.max(128, heap / 3)));
        env.put("MAX_PARALLEL_CHECKS", String.valueOf(Math.min(6, cores)));
        env.put("SHOP_CACHE_MILLIS", "2000");
        env.put("COUNTER_PASSWORD", nz(cfg.counterPassword));
        env.put("AGENT_TOKEN_SECRET", cfg.tokenSecret);
        env.put("PAYMENT_MODE", nz(cfg.paymentMode));
        env.put("UPI_ID", nz(cfg.upiId));
        env.put("UPI_NAME", nz(cfg.upiName));
        env.put("UPI_MERCHANT_CODE", nz(cfg.upiMerchantCode));
        env.put("UPI_ALERT_TOKEN", nz(cfg.upiAlertToken));
        env.put("UPI_SMS_SENDERS", nz(cfg.upiSmsSenders));
        env.put("UPI_TRUSTED_APPS", nz(cfg.upiTrustedApps));
        env.put("RAZORPAY_KEY_ID", nz(cfg.razorpayKeyId));
        env.put("RAZORPAY_KEY_SECRET", nz(cfg.razorpayKeySecret));
        env.put("WEB_ORIGINS", nz(cfg.webOrigins).isBlank() ? "*" : cfg.webOrigins.trim());
        env.put("SHOP_TIME_ZONE", nz(cfg.timeZone).isBlank() ? "Asia/Kolkata" : cfg.timeZone.trim());
        // its log: ...\logs\server.log, a new file every day or 10 MB, two weeks kept
        env.put("LOGGING_FILE_NAME", logs.resolve("server.log").toString());
        env.put("LOGGING_LOGBACK_ROLLINGPOLICY_MAX_FILE_SIZE", "10MB");
        env.put("LOGGING_LOGBACK_ROLLINGPOLICY_MAX_HISTORY", "14");
        env.put("LOGGING_LOGBACK_ROLLINGPOLICY_TOTAL_SIZE_CAP", "200MB");
        env.put("LOGGING_THRESHOLD_CONSOLE", "WARN");
        // the print service ends by itself when the Station is gone (it must never be left running alone)
        env.put("XEOGO_PARENT_PID", String.valueOf(ProcessHandle.current().pid()));
        try {
            Files.createDirectories(data);
            service = Proc.start(cmd, env, data, logs.resolve("server-start.log"));
            serviceStartedAt = System.currentTimeMillis();
            comeFirst(service.pid());
            serviceState = Part.starting("Starting the print service…");
            log.info("Print service: starting ({} MB of memory)", heap);
        } catch (IOException e) {
            serviceFailures++;
            serviceRetryAt = System.currentTimeMillis() + Math.min(60_000, 5000L * serviceFailures);
            serviceState = Part.problem("The print service could not be started: " + e.getMessage());
            log.error("The print service could not be started", e);
        }
    }

    /** Why the print service ended, in words: from the last lines it wrote. */
    private String startProblem() {
        String tail = Proc.tailOf(logs.resolve("server-start.log"), 3000);
        if (tail.contains("PAYMENT_MODE is")) return firstLine(tail.substring(tail.indexOf("PAYMENT_MODE is")));
        if (tail.contains("COUNTER_PASSWORD")) return "the counter password is missing or too short.";
        if (tail.contains("The database could not be brought up to date")) {
            return firstLine(tail.substring(tail.indexOf("The database could not be brought up to date")));
        }
        if (tail.contains("OutOfMemoryError")) return "it ran out of memory.";
        if (tail.contains("Address already in use") || tail.contains("Port " + cfg.httpPort)) {
            return "port " + cfg.httpPort + " is used by another program.";
        }
        String line = firstLine(tail.isBlank() ? "no message" : lastErrorLine(tail));
        return line.length() > 240 ? line.substring(0, 240) + "…" : line;
    }

    private static String lastErrorLine(String tail) {
        String[] lines = tail.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].contains("ERROR") || lines[i].contains("Exception") || lines[i].contains("Caused by")) return lines[i].strip();
        }
        return lines[lines.length - 1].strip();
    }

    public boolean healthy() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(cfg.localUrl() + "/actuator/health"))
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"UP\"");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------ 3. the tunnel to the internet

    private void lookAfterTunnel() {
        if (restartTunnel) {
            restartTunnel = false;
            Proc.stop(tunnel, 3);
            tunnel = null;
            tunnelRetryAt = 0;
        }
        if (!cfg.hasTunnel()) {
            if (tunnel != null) {
                Proc.stop(tunnel, 3);
                tunnel = null;
            }
            tunnelState = new Part("off", "No internet address is set up yet: only this PC can reach the server.");
            return;
        }
        Path program = home.resolve(exe("cloudflared"));
        if (!Files.isRegularFile(program)) {
            tunnelState = Part.problem("The tunnel program is missing from this installation. Install XeoGo Station again.");
            return;
        }
        if (tunnel == null || !tunnel.isAlive()) {
            if (tunnel != null) {
                String why = tunnelProblem();
                log.warn("The tunnel stopped: {}", why);
                tunnelState = Part.problem(why);
                tunnel = null;
                tunnelRetryAt = System.currentTimeMillis() + 15_000;
                return;
            }
            if (System.currentTimeMillis() < tunnelRetryAt) return;
            try {
                tunnel = Proc.start(List.of(program.toString(), "tunnel", "--no-autoupdate", "--metrics",
                                "127.0.0.1:" + cfg.tunnelStatusPort, "run"),
                        Map.of("TUNNEL_TOKEN", cfg.tunnelToken.trim()), data, logs.resolve("tunnel.log"));
                tunnelStartedAt = System.currentTimeMillis();
                tunnelState = Part.starting("Connecting this PC to the internet…");
                log.info("Tunnel: starting");
            } catch (IOException e) {
                tunnelState = Part.problem("The tunnel could not be started: " + e.getMessage());
                tunnelRetryAt = System.currentTimeMillis() + 30_000;
            }
            return;
        }
        if (tunnelReady()) {
            if (!tunnelState.isRunning()) log.info("Tunnel: connected");
            tunnelState = Part.running("");
        } else if (System.currentTimeMillis() - tunnelStartedAt > 45_000) {
            tunnelState = Part.problem(tunnelProblem());
        } else {
            tunnelState = Part.starting("Connecting this PC to the internet…");
        }
    }

    private boolean tunnelReady() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + cfg.tunnelStatusPort + "/ready"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** What the tunnel program complained about, in words someone at the PC can act on. */
    private String tunnelProblem() {
        String t = Proc.tailOf(logs.resolve("tunnel.log"), 4000).toLowerCase();
        if (t.contains("provided tunnel token is not valid") || t.contains("invalid tunnel secret")
                || t.contains("unauthorized") || t.contains("token is not valid")) {
            return "Cloudflare does not accept the tunnel token. Copy it again from the Cloudflare page of your tunnel.";
        }
        if (t.contains("tunnel not found") || t.contains("tunnel has been deleted")) {
            return "This tunnel no longer exists at Cloudflare. Make a new one and paste its token.";
        }
        if (t.contains("i/o timeout") || t.contains("failed to dial") || t.contains("no recent network activity")
                || t.contains("connection refused") || t.contains("network is unreachable")) {
            return "This PC cannot reach Cloudflare. Check the internet; if the internet works, the network blocks "
                    + "outgoing port 7844: ask whoever runs it to allow that, or use another connection.";
        }
        if (t.contains("no such host") || t.contains("lookup ")) {
            return "No internet (names cannot be looked up). It connects by itself when the internet is back.";
        }
        return "Not connected to the internet yet. It keeps trying by itself.";
    }

    /** Asks the public address, from outside in, whether students can really reach this PC. Once a minute. */
    private void checkPublicAddress() {
        String url = nz(cfg.publicUrl).trim().replaceAll("/+$", "");
        if (url.isEmpty()) {
            publicOk = null;
            publicNote = "";
            return;
        }
        if (System.currentTimeMillis() < nextPublicCheck) return;
        nextPublicCheck = System.currentTimeMillis() + 60_000;
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url + "/api/v1/shop"))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (r.statusCode() == 200 && r.body().contains("\"centerName\"")) {
                publicOk = true;
                publicNote = "Students can reach this PC (answered in " + (ms < 1000 ? ms + " ms" : (ms / 100) / 10.0 + " s") + ").";
            } else {
                publicOk = false;
                publicNote = r.statusCode() == 530 || r.statusCode() == 502 || r.statusCode() == 1033
                        ? "The address exists, but it does not lead to this PC yet (answer " + r.statusCode()
                          + "). In Cloudflare, the tunnel's public hostname must point to http://localhost:" + cfg.httpPort + "."
                        : "The address answered " + r.statusCode() + ", not XeoGo. Check the address and the tunnel's "
                          + "public hostname (service: http://localhost:" + cfg.httpPort + ").";
            }
        } catch (IllegalArgumentException e) {
            publicOk = false;
            publicNote = "That is not a web address. It looks like https://print.yourcollege.in";
        } catch (IOException e) {
            publicOk = false;
            publicNote = "The address cannot be reached from here (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    + "). It is tried again every minute.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        publicAt = Instant.now();
    }

    // ------------------------------------------------------------------ for the screens

    public boolean serviceRunning() {
        return serviceState.isRunning();
    }

    public Backups backups() {
        return backups;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled);
        m.put("running", wanted);
        m.put("database", part(database));
        m.put("service", part(serviceState));
        m.put("tunnel", part(tunnelState));
        boolean up = database.isRunning() && serviceState.isRunning();
        m.put("ready", up);
        String stage;
        if (!wanted) stage = "Stopped";
        else if (up) stage = "Running";
        else if ("problem".equals(database.state())) stage = database.detail();
        else if (!database.isRunning()) stage = database.detail().isBlank() ? "Starting the database…" : database.detail();
        else if ("problem".equals(serviceState.state())) stage = serviceState.detail();
        else stage = serviceState.detail().isBlank() ? "Starting the print service…" : serviceState.detail();
        m.put("stage", stage);
        m.put("localUrl", cfg.localUrl());
        m.put("httpPort", cfg.httpPort);
        Map<String, Object> pub = new LinkedHashMap<>();
        pub.put("url", nz(cfg.publicUrl));
        pub.put("ok", publicOk);
        pub.put("detail", publicNote);
        pub.put("checkedAt", publicAt == null ? null : publicAt.toString());
        m.put("public", pub);
        m.put("backup", backups.status());
        m.put("repaired", repaired);
        m.put("staysAwake", awake.isOn());
        m.put("dataFolder", data.toString());
        try {
            m.put("freeGb", Files.getFileStore(Files.exists(data) ? data : data.getRoot()).getUsableSpace() / (1024 * 1024 * 1024));
        } catch (IOException | RuntimeException e) {
            m.put("freeGb", null);
        }
        return m;
    }

    public void forgetRepairNote() {
        repaired = null;
    }

    private static Map<String, Object> part(Part p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", p.state());
        m.put("detail", p.detail());
        return m;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Students' phones come first. Printing a big, picture-heavy document
     * keeps the processor busy (Windows and the printer driver turn every page
     * into dots); the database and the print service get to go before that, so
     * an upload or "how is my order" is answered at once all the same. Windows
     * "above normal" priority: no administrator needed for our own programs.
     */
    private static void comeFirst(long pid) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Thread t = new Thread(() -> {
            Proc.Result r = Proc.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "(Get-Process -Id " + pid + ").PriorityClass = 'AboveNormal'"), null, null, 30);
            if (!r.ok()) log.debug("Could not raise the priority of process {}: {}", pid, r.tail());
        }, "priority");
        t.setDaemon(true);
        t.start();
    }

    private long nextKeepFirst;

    /**
     * The database is many small programs: one for every connection of the
     * print service, made anew every half hour, and Windows starts each of
     * them at normal priority whatever the first one has. So every few minutes
     * all of them (and the print service) are put first again. One short
     * PowerShell call; it only touches programs in XeoGo's own folder.
     */
    private void keepFirst() {
        if (System.currentTimeMillis() < nextKeepFirst) return;
        nextKeepFirst = System.currentTimeMillis() + 5 * 60_000L;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Process s = service;
        long servicePid = s == null ? -1 : s.pid();
        String folder = home.resolve("pgsql").toString().replace("'", "''");
        Thread t = new Thread(() -> Proc.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "Get-Process postgres, java -ErrorAction SilentlyContinue | Where-Object { $_.Id -eq " + servicePid
                + " -or ($_.Path -and $_.Path.StartsWith('" + folder + "', [StringComparison]::OrdinalIgnoreCase)) } | "
                + "ForEach-Object { try { if ($_.PriorityClass -ne 'AboveNormal') { $_.PriorityClass = 'AboveNormal' } } catch { } }"),
                null, null, 60), "priority");
        t.setDaemon(true);
        t.start();
    }

    /** Is something listening on this port of this PC? */
    static boolean portTaken(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 700);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (!l.isEmpty()) return l;
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Moves a folder; over a slow disk the plain move is enough (same drive). */
    static void moveAside(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
