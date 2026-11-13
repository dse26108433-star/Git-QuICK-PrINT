package edu.campus.agent.server;

import edu.campus.agent.station.StationConfig;

import java.util.Map;

/**
 * Runs only the server part (database + print service + tunnel) in a console
 * window, without the Station's screens and without printing. For looking
 * into a problem, and for load tests:
 *
 *   java -Dxeogo.server.home=C:\...\server -cp print-agent.jar edu.campus.agent.server.ServerMain
 *
 * It uses the same settings and data as the Station (server.json). Ctrl+C stops it.
 * --demo: with no settings yet, make them with test payments and the counter
 * password "counter-pass-123" (never for a real shop).
 */
public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("LOG_DIR", StationConfig.dir().resolve("logs").toString());
        if (!LocalStack.available()) {
            System.err.println("The server programs were not found in " + LocalStack.home()
                    + " (pgsql\\bin\\postgres.exe, print-server.jar). Set -Dxeogo.server.home=...");
            System.exit(2);
        }
        ServerSettings cfg = ServerSettings.load();
        if (cfg.damaged) {
            System.err.println("server.json cannot be read.");
            System.exit(2);
        }
        boolean demo = java.util.List.of(args).contains("--demo");
        if (cfg.counterPassword.isBlank()) {
            if (!demo) {
                System.err.println("This PC is not set up as a server yet. Set it up in XeoGo Station, or pass --demo for a test.");
                System.exit(2);
            }
            cfg.counterPassword = "counter-pass-123";
            cfg.paymentMode = "demo";
        }
        cfg.enabled = true;
        if (cfg.ensureSecrets() || demo) cfg.save();

        // --once: start, wait until everything runs, say how it went, stop again (for checks and tests).
        // --backup-now: with --once, also make a backup (and send it to the cloud, if that is set up).
        boolean once = java.util.List.of(args).contains("--once");
        boolean backupNow = java.util.List.of(args).contains("--backup-now");

        LocalStack stack = new LocalStack(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(stack::stop, "stop-server"));
        long started = System.currentTimeMillis();
        stack.start();
        String last = "";
        while (true) {
            Map<String, Object> s = stack.status();
            String line = s.get("stage") + " | database " + ((Map<?, ?>) s.get("database")).get("state")
                    + " | service " + ((Map<?, ?>) s.get("service")).get("state")
                    + " | tunnel " + ((Map<?, ?>) s.get("tunnel")).get("state");
            if (!line.equals(last)) {
                System.out.println(java.time.LocalTime.now().withNano(0) + "  " + line);
                last = line;
            }
            if (once && Boolean.TRUE.equals(s.get("ready"))) {
                System.out.println("READY after " + (System.currentTimeMillis() - started) / 1000 + " s");
                if (s.get("repaired") != null) System.out.println("NOTE: " + s.get("repaired"));
                // what is in the database, in one line (so a restore can be seen to have brought the shop back)
                System.out.println("DATA: " + stack.psql(LocalStack.DB_NAME, "select 'orders ' || (select count(*) from orders)"
                        + " || ', files ' || (select count(*) from order_documents)"
                        + " || ', printers ' || (select count(*) from printers)"
                        + " || ', PCs ' || (select count(*) from agents)"
                        + " || ', shop: ' || (select center_name from shop_settings limit 1)").output().strip());
                if (backupNow) {
                    try {
                        System.out.println("BACKUP: " + stack.backups().runNow());
                    } catch (RuntimeException e) {
                        System.out.println("BACKUP FAILED: " + e.getMessage());
                    }
                    System.out.println("BACKUP STATE: " + stack.backups().status());
                }
                stack.stop();
                System.exit(0);
            }
            if (once && System.currentTimeMillis() - started > 20 * 60_000L) {
                System.out.println("NOT READY after 20 minutes: " + line);
                stack.stop();
                System.exit(1);
            }
            Thread.sleep(1000);
        }
    }
}
