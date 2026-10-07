package edu.campus.print.config;

import edu.campus.print.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The server brings its database up to date by itself when it starts
 * (DatabaseSetup): a new one, an old one, one that is fine already, and what
 * happens when it cannot.
 */
class DatabaseSetupTest {

    @Test
    void theCopyInsideTheServerIsDbSetupSql() {
        // backend/src/main/resources/db/setup.sql must be db/setup.sql, letter for letter (copy it after every change)
        assertThat(DatabaseSetup.script()).isEqualTo(TestDb.setupSql().replace("\r\n", "\n"));
        assertThat(DatabaseSetup.versionOf(DatabaseSetup.script())).isEqualTo("5.2");
    }

    @Test
    void aNewDatabaseGetsEverythingAndTheNextStartChangesNothing() {
        DataSource ds = TestDb.dataSource(TestDb.freshDatabase());          // as a new Supabase project: nothing of ours
        JdbcTemplate db = new JdbcTemplate(ds);
        assertThat(db.queryForObject("select to_regclass('public.orders') is null", Boolean.class)).isTrue();

        assertThat(DatabaseSetup.bringUpToDate(ds)).isEqualTo(DatabaseSetup.Outcome.UPDATED);
        for (String table : new String[] {"orders", "order_documents", "shop_settings", "staff_accounts", "document_previews"}) {
            assertThat(db.queryForObject("select to_regclass('public." + table + "') is not null", Boolean.class)).as(table).isTrue();
        }
        assertThat(db.queryForObject("select version from schema_version", String.class)).isEqualTo("5.2");
        assertThat(db.queryForObject("select checksum from schema_version", String.class)).hasSize(64);
        // nobody but the server reaches the tables (Supabase would show them to the whole internet otherwise)
        assertThat(db.queryForObject("select count(*) from pg_tables where schemaname = 'public' and not rowsecurity",
                Integer.class)).isZero();

        Timestamp first = db.queryForObject("select applied_at from schema_version", Timestamp.class);
        assertThat(DatabaseSetup.bringUpToDate(ds)).isEqualTo(DatabaseSetup.Outcome.UP_TO_DATE);
        assertThat(db.queryForObject("select applied_at from schema_version", Timestamp.class)).isEqualTo(first);

        // a newer server has a newer file: it runs again
        db.update("update schema_version set checksum = 'what an older server left'");
        assertThat(DatabaseSetup.bringUpToDate(ds)).isEqualTo(DatabaseSetup.Outcome.UPDATED);
        assertThat(DatabaseSetup.bringUpToDate(ds)).isEqualTo(DatabaseSetup.Outcome.UP_TO_DATE);
    }

    @Test
    void anOlderDatabaseIsUpgradedAndKeepsItsOrders() {
        DataSource ds = TestDb.dataSource(TestDb.freshDatabase());
        TestDb.run(ds, TestDb.resource("/db/setup-v3.sql"));                 // the one-file version, long before this file
        JdbcTemplate db = new JdbcTemplate(ds);
        UUID order = UUID.randomUUID();
        db.update("insert into orders (id, pickup_code, access_key_hash, status, file_name, file_type, storage_path, "
                + "page_count, copies, amount_paise, paid_at) values (?, 'OLD11', 'hash', 'COMPLETED', 'old.pdf', 'PDF', ?, 3, 1, 600, now())",
                order, "orders/" + order + ".pdf");
        db.update("update shop_settings set center_name = 'The Old Shop', price_bw_paise = 300");

        assertThat(DatabaseSetup.bringUpToDate(ds)).isEqualTo(DatabaseSetup.Outcome.UPDATED);

        assertThat(db.queryForObject("select status from orders where id = ?", String.class, order)).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("select file_name from order_documents where order_id = ?", String.class, order)).isEqualTo("old.pdf");
        assertThat(db.queryForObject("select center_name from shop_settings", String.class)).isEqualTo("The Old Shop");
        assertThat(db.queryForObject("select price_bw_paise from shop_settings", Integer.class)).isEqualTo(300);
        assertThat(db.queryForObject("select staff_monthly_pages from shop_settings", Integer.class)).isEqualTo(1000);
        assertThat(db.queryForObject("select staff_id is null from orders where id = ?", Boolean.class, order)).isTrue();
    }

    @Test
    void whenItCannotBeDoneNothingChangesAndTheServerSaysWhy() {
        DataSource ds = TestDb.dataSource(TestDb.freshDatabase());
        JdbcTemplate db = new JdbcTemplate(ds);
        db.execute("create table app_users (id int)");                       // a database of the very first version
        assertThatThrownBy(() -> DatabaseSetup.bringUpToDate(ds))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reset.sql")
                .hasMessageContaining("nothing was changed");
        // all or nothing: not one of our tables was made
        assertThat(db.queryForObject("select to_regclass('public.orders') is null and to_regclass('public.schema_version') is null",
                Boolean.class)).isTrue();
    }

    @Test
    void aFileRunByHandIsEnoughWhenTheServerMayNotChangeTables() {
        String url = TestDb.freshDatabase();
        DataSource owner = TestDb.dataSource(url);
        JdbcTemplate db = new JdbcTemplate(owner);
        // A database user that may read and write every row, but owns nothing and may not change tables.
        String role = "limited_" + Long.toHexString(System.nanoTime());
        db.execute("create role " + role + " login bypassrls");
        db.execute("revoke create on schema public from public");
        org.postgresql.ds.PGSimpleDataSource limited = new org.postgresql.ds.PGSimpleDataSource();
        limited.setUrl(url);
        limited.setUser(role);

        // Nothing was run by hand: such a user cannot set the database up, and the server says so.
        assertThatThrownBy(() -> DatabaseSetup.bringUpToDate(limited))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DB_AUTO_SETUP");

        // Somebody runs db/setup.sql of this version in the SQL editor: now the server starts.
        TestDb.run(owner, TestDb.setupSql());
        db.execute("grant usage on schema public to " + role);
        db.execute("grant select, insert, update, delete on all tables in schema public to " + role);
        assertThat(DatabaseSetup.bringUpToDate(limited)).isEqualTo(DatabaseSetup.Outcome.DONE_BY_HAND);

        // But an older file run by hand is not this version: the server refuses to run on it.
        db.update("update schema_version set version = '4.0'");
        assertThatThrownBy(() -> DatabaseSetup.bringUpToDate(limited)).isInstanceOf(IllegalStateException.class);
    }
}
