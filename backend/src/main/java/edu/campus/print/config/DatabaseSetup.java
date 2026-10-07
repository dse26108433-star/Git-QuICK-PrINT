package edu.campus.print.config;

import edu.campus.print.common.Secrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brings the database up to date when the server starts, by itself.
 *
 * db/setup.sql owns the tables and the rules, and is written to be run again
 * and again: a new database gets everything, an older one gets what is new,
 * and nothing that is already there is lost. A copy of that file travels
 * inside the server (resources/db/setup.sql). At start the server compares it
 * with what the database was last brought up to (table schema_version) and,
 * when it differs, runs it: in ONE transaction, so it either all happens or
 * nothing changes.
 *
 * So putting a new version online is only: push the code. Nobody has to
 * remember to run a file in Supabase first.
 *
 * If it cannot be done (for example the database user may not change tables)
 * the server does not start, and says why. A host like Render then keeps the
 * previous version running, so the shop stays open. DB_AUTO_SETUP=false
 * switches this off (then run db/setup.sql by hand before every update).
 */
@Component
public class DatabaseSetup {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSetup.class);
    private static final String SCRIPT = "/db/setup.sql";
    /** deadlock, lock not available, serialization failure, cancelled, connection lost: worth trying again */
    private static final Set<String> TRY_AGAIN = Set.of("40P01", "55P03", "40001", "57014");
    private static final Pattern VERSION = Pattern.compile("insert into schema_version \\(id, version\\) values \\(1, '([^']+)'\\)");

    /** What happened at start: for the log, and for tests. */
    public enum Outcome { SWITCHED_OFF, UP_TO_DATE, UPDATED, DONE_BY_HAND }

    private final Outcome outcome;

    public DatabaseSetup(DataSource dataSource, @Value("${campus.database.auto-setup:true}") boolean enabled) {
        this.outcome = enabled ? bringUpToDate(dataSource) : Outcome.SWITCHED_OFF;
        if (!enabled) log.warn("DB_AUTO_SETUP=false: the database is not checked. Run db/setup.sql by hand before every update.");
    }

    public Outcome outcome() {
        return outcome;
    }

    /** JPA (and so every repository) waits until the database is up to date. */
    @Configuration(proxyBeanMethods = false)
    static class JpaWaits extends EntityManagerFactoryDependsOnPostProcessor {
        JpaWaits() {
            super(DatabaseSetup.class);
        }
    }

    static Outcome bringUpToDate(DataSource dataSource) {
        String script = script();
        String checksum = Secrets.sha256Hex(script);
        SQLException last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try (Connection c = dataSource.getConnection()) {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    Outcome o = run(c, script, checksum);
                    c.commit();
                    return o;
                } catch (SQLException e) {
                    c.rollback();
                    // The database user may not change tables, but somebody already ran this very version by hand.
                    if ("42501".equals(e.getSQLState()) && doneByHand(c, script)) {
                        c.rollback();
                        log.warn("The database user may not change tables ({}), but db/setup.sql of this version was "
                                + "already run by hand: carrying on.", oneLine(e));
                        return Outcome.DONE_BY_HAND;
                    }
                    throw e;
                } finally {
                    c.setAutoCommit(auto);
                }
            } catch (SQLException e) {
                last = e;
                boolean again = e.getSQLState() != null && (TRY_AGAIN.contains(e.getSQLState()) || e.getSQLState().startsWith("08"));
                if (!again || attempt == 4) break;
                log.warn("Bringing the database up to date did not go through ({}); trying again", oneLine(e));
                try {
                    Thread.sleep(attempt * 2500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("The database could not be brought up to date, so the server does not start "
                + "(nothing was changed): " + (last == null ? "interrupted" : oneLine(last)) + "  --  Fix that, or run "
                + "db/setup.sql in the Supabase SQL editor and start again. To start without this check set "
                + "DB_AUTO_SETUP=false.", last);
    }

    private static Outcome run(Connection c, String script, String checksum) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("set local lock_timeout = '25s'");
            // Two servers starting at the same moment: one after the other.
            s.execute("select pg_advisory_xact_lock(hashtext('campusprint.setup'))");
            if (checksum.equals(stored(c, "checksum"))) {
                log.info("Database: up to date");
                return Outcome.UP_TO_DATE;
            }
            log.info("Database: bringing it up to date (db/setup.sql)...");
            s.execute(script);
        }
        try (PreparedStatement p = c.prepareStatement(
                "update schema_version set checksum = ?, applied_at = now() where id = 1")) {
            p.setString(1, checksum);
            if (p.executeUpdate() != 1) throw new SQLException("db/setup.sql did not leave its version behind", "XX000");
        }
        log.info("Database: up to date now (version {})", versionOf(script));
        return Outcome.UPDATED;
    }

    /** One value of the schema_version row, or null when the table or the row is not there yet. */
    private static String stored(Connection c, String column) throws SQLException {
        try (Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("select to_regclass('public.schema_version') is not null")) {
                r.next();
                if (!r.getBoolean(1)) return null;
            }
            try (ResultSet r = s.executeQuery("select " + column + " from schema_version where id = 1")) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    private static boolean doneByHand(Connection c, String script) {
        try {
            String version = versionOf(script);
            return version != null && version.equals(stored(c, "version"));
        } catch (SQLException e) {
            return false;
        }
    }

    /** The version db/setup.sql writes into schema_version when it has run. */
    static String versionOf(String script) {
        Matcher m = VERSION.matcher(script);
        return m.find() ? m.group(1) : null;
    }

    static String script() {
        try (InputStream in = DatabaseSetup.class.getResourceAsStream(SCRIPT)) {
            if (in == null) throw new IllegalStateException("The server was built without " + SCRIPT);
            // Line ends as in the file a person would run by hand, whatever the build computer made of them.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SCRIPT, e);
        }
    }

    private static String oneLine(SQLException e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.replaceAll("\\s+", " ").trim() + (e.getSQLState() == null ? "" : " [" + e.getSQLState() + "]");
    }
}
