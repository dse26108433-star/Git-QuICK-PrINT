package edu.campus.print.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real PostgreSQL 17 for the tests, started once per test run. Each caller
 * gets its own empty database, set up like a fresh Supabase project.
 */
public final class TestDb {

    private static EmbeddedPostgres pg;
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private TestDb() {
    }

    public static synchronized EmbeddedPostgres server() {
        if (pg == null) {
            try {
                pg = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the test database", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                }
            }));
        }
        return pg;
    }

    /** A new, empty database with the Supabase basics. Returns its JDBC URL. */
    public static String freshDatabase() {
        String name = "t" + COUNTER.incrementAndGet() + "_" + System.nanoTime() % 100000;
        try (Connection c = server().getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + name);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String url = server().getJdbcUrl("postgres", name);
        run(dataSource(url), resource("/db/supabase-stub.sql"));
        return url;
    }

    public static DataSource dataSource(String url) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser("postgres");
        return ds;
    }

    public static void run(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("SQL failed: " + e.getMessage(), e);
        }
    }

    /** db/setup.sql exactly as Xerox centers run it (-Dcampusprint.root = the project folder, default ".."). */
    public static String setupSql() {
        return file(Path.of(System.getProperty("campusprint.root", ".."), "db", "setup.sql"));
    }

    public static String file(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String resource(String name) {
        try (InputStream in = TestDb.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing test resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
