package edu.campus.print;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.print.support.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * db/setup.sql against a real PostgreSQL: the rules, the state machines and
 * the print queue, and the upgrade of a database made by the one-file version.
 */
class DatabaseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdbcTemplate db;
    private UUID agent;

    @BeforeEach
    void freshDatabase() {
        DataSource ds = TestDb.dataSource(TestDb.freshDatabase());
        TestDb.run(ds, TestDb.setupSql());
        db = new JdbcTemplate(ds);
        agent = db.queryForObject("select agent_id from enroll_agent('Test PC')", UUID.class);
    }

    @Test
    void setupRunsTwiceWithoutHarm() {
        TestDb.run((DataSource) db.getDataSource(), TestDb.setupSql());
        assertThat(db.queryForObject("select count(*) from shop_settings", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select pricing->'paperSizePercent'->>'A3' from shop_settings", String.class))
                .isEqualTo("200");
    }

    // ------------------------------------------------------------------ printer_can_do = spec

    @Test
    void printerRuleMatchesTheSharedCases() throws Exception {
        JsonNode spec = JSON.readTree(Path.of("..", "spec", "cases", "printer-rules.json").toFile());
        for (JsonNode c : spec.path("cases")) {
            JsonNode p = spec.path("printers").path(c.path("printer").asText());
            Boolean can = db.queryForObject("select printer_can_do(cast(? as jsonb), ?, ?, cast(? as jsonb))",
                    Boolean.class, p.path("features").isNull() ? null : p.path("features").toString(),
                    p.path("color").asBoolean(), p.path("bw").asBoolean(), c.path("req").toString());
            assertThat(can).as(c.toString()).isEqualTo(c.path("can").asBoolean());
        }
    }

    // ------------------------------------------------------------------ the print queue

    @Test
    void aPrinterOnlyGetsDocumentsItCanReallyPrint() {
        UUID plain = printer("Plain B/W", false, true, null);
        UUID duplex = printer("Duplex A3", false, true,
                "{\"paperSizes\":[\"A4\",\"A3\"],\"duplex\":true,\"finishing\":[\"STAPLE_TOP_LEFT\"]}");
        UUID colour = printer("Colour", true, true, "{\"paperSizes\":[\"A4\"]}");

        UUID twoSided = paidOrder(req(false, "A4", "LONG_EDGE", "[]"));        // oldest
        UUID a3 = paidOrder(req(false, "A3", "ONE_SIDED", "[]"));
        UUID plainDoc = paidOrder(req(false, "A4", "ONE_SIDED", "[]"));
        UUID col = paidOrder(req(true, "A4", "ONE_SIDED", "[]"));

        // The plain printer skips the two-sided and A3 documents, although they were paid first.
        assertThat(claim(plain)).isEqualTo(plainDoc);
        // The colour printer takes colour work first.
        assertThat(claim(colour)).isEqualTo(col);
        // The duplex printer takes the oldest document it can do.
        assertThat(claim(duplex)).isEqualTo(twoSided);
        assertThat(claim(duplex)).isEqualTo(a3);
        assertThat(claim(plain)).isNull();
    }

    @Test
    void oneOrderStaysOnOnePrinterWhenThereIsOtherWork() {
        UUID p1 = printer("One", false, true, null);
        UUID p2 = printer("Two", false, true, null);
        UUID order = order();
        UUID d1 = document(order, 1, req(false, "A4", "ONE_SIDED", "[]"));
        UUID d2 = document(order, 2, req(false, "A4", "ONE_SIDED", "[]"));
        pay(order);
        UUID other = paidOrder(req(false, "A4", "ONE_SIDED", "[]"));

        assertThat(claim(p1)).isEqualTo(d1);
        // Printer Two leaves the order Printer One is working on, and takes other work.
        assertThat(claim(p2)).isEqualTo(other);
        // Printer One carries on with its order.
        assertThat(claim(p1)).isEqualTo(d2);
    }

    @Test
    void theOrderFollowsItsDocuments() {
        UUID p = printer("One", false, true, null);
        UUID order = order();
        UUID d1 = document(order, 1, req(false, "A4", "ONE_SIDED", "[]"));
        UUID d2 = document(order, 2, req(false, "A4", "ONE_SIDED", "[]"));
        pay(order);
        assertThat(orderStatus(order)).isEqualTo("QUEUED");

        assertThat(claim(p)).isEqualTo(d1);
        assertThat(orderStatus(order)).isEqualTo("PRINTING");
        agentSays(d1, "DOWNLOADING");
        agentSays(d1, "SUBMITTED");
        agentSays(d1, "COMPLETED");
        assertThat(orderStatus(order)).isEqualTo("PRINTING");          // one done, one waiting

        assertThat(claim(p)).isEqualTo(d2);
        agentSays(d2, "DOWNLOADING");
        agentSays(d2, "FAILED");
        assertThat(orderStatus(order)).isEqualTo("FAILED");            // nothing left to do, one failed

        // Staff: "Print again" -> back in the queue, then printed.
        db.update("update order_documents set status = 'QUEUED', attempts = 0, claim_token = null where id = ?", d2);
        assertThat(orderStatus(order)).isEqualTo("PRINTING");
        assertThat(claim(p)).isEqualTo(d2);
        agentSays(d2, "DOWNLOADING");
        agentSays(d2, "SUBMITTED");
        agentSays(d2, "COMPLETED");
        assertThat(orderStatus(order)).isEqualTo("COMPLETED");
        assertThat(db.queryForObject("select completed_at is not null from orders where id = ?", Boolean.class, order))
                .isTrue();
    }

    @Test
    void cancellingEveryDocumentCancelsThePaidOrder() {
        UUID order = order();
        UUID d1 = document(order, 1, req(false, "A4", "ONE_SIDED", "[]"));
        pay(order);
        db.update("update order_documents set status = 'CANCELLED' where id = ?", d1);
        assertThat(orderStatus(order)).isEqualTo("CANCELLED");
    }

    @Test
    void impossibleChangesAreRefused() {
        UUID order = order();
        UUID d1 = document(order, 1, req(false, "A4", "ONE_SIDED", "[]"));
        assertThatThrownBy(() -> db.update("update order_documents set status = 'COMPLETED' where id = ?", d1))
                .hasMessageContaining("Illegal document transition");
        assertThatThrownBy(() -> db.update("update orders set status = 'QUEUED' where id = ?", order))
                .hasMessageContaining("Illegal order transition");
        pay(order);
        // Back to editing is only possible before payment.
        assertThatThrownBy(() -> db.update("update orders set status = 'AWAITING_UPLOAD' where id = ?", order))
                .hasMessageContaining("Illegal order transition");
    }

    @Test
    void anUnpaidFailedOrderIsNotTouchedByItsDocuments() {
        UUID order = order();
        UUID d1 = document(order, 1, null);
        db.update("update order_documents set status = 'REJECTED' where id = ?", d1);
        db.update("update orders set status = 'FAILED' where id = ?", order);
        db.update("update order_documents set status = 'CANCELLED' where id = ?", d1);
        assertThat(orderStatus(order)).isEqualTo("FAILED");
    }

    @Test
    void anOlderStationOnlyGetsPlainA4Documents() {
        UUID p = printer("Duplex", false, true, "{\"paperSizes\":[\"A4\"],\"duplex\":true}");
        UUID fancy = order();
        document(fancy, 1, req(false, "A4", "LONG_EDGE", "[]"));
        pay(fancy);
        UUID plainOrder = order();
        UUID plainDoc = document(plainOrder, 1, req(false, "A4", "ONE_SIDED", "[]"));
        db.update("update order_documents set legacy_ok = true where id = ?", plainDoc);
        pay(plainOrder);

        UUID got = db.query("select id from claim_next_job(?, ?, 300, true)", rs -> rs.next()
                ? (UUID) rs.getObject(1) : null, agent, p);
        assertThat(got).isEqualTo(plainDoc);
    }

    @Test
    void recoveryNeverReprintsByItself() {
        UUID p = printer("One", false, true, null);
        UUID a = paidOrder(req(false, "A4", "ONE_SIDED", "[]"));
        UUID b = paidOrder(req(false, "A4", "ONE_SIDED", "[]"));
        assertThat(claim(p)).isEqualTo(a);
        agentSays(a, "DOWNLOADING");
        agentSays(a, "SUBMITTED");
        assertThat(claim(p)).isEqualTo(b);
        db.update("update order_documents set lease_expires_at = now() - interval '1 minute'");
        db.queryForList("select * from reap_expired_leases()");
        assertThat(docStatus(a)).isEqualTo("FAILED");                  // may be on paper: staff check
        assertThat(docStatus(b)).isEqualTo("QUEUED");                  // nothing printed: back in the queue
        assertThat(db.queryForObject("select error_code from order_documents where id = ?", String.class, a))
                .isEqualTo("AGENT_LOST_AFTER_SUBMIT");
    }

    // ------------------------------------------------------------------ upgrade from the one-file version

    @Test
    void upgradesADatabaseFromTheOneFileVersion() {
        DataSource ds = TestDb.dataSource(TestDb.freshDatabase());
        TestDb.run(ds, TestDb.resource("/db/setup-v3.sql"));
        JdbcTemplate old = new JdbcTemplate(ds);
        UUID pc = old.queryForObject("select agent_id from enroll_agent('Old PC')", UUID.class);
        UUID printer = old.queryForObject("insert into printers (name, windows_printer_name, supports_color, agent_id) "
                + "values ('P1', 'Canon', false, ?) returning id", UUID.class, pc);
        UUID token = UUID.randomUUID();
        UUID printing = legacyOrder(old, "SUBMITTED", true, printer, pc, token, "5-9");
        UUID queued = legacyOrder(old, "QUEUED", true, null, null, null, null);
        UUID done = legacyOrder(old, "COMPLETED", true, printer, pc, UUID.randomUUID(), null);
        UUID unpaid = legacyOrder(old, "AWAITING_PAYMENT", false, null, null, null, null);
        UUID refused = legacyOrder(old, "FAILED", false, null, null, null, null);
        UUID expired = legacyOrder(old, "EXPIRED", false, null, null, null, null);

        TestDb.run(ds, TestDb.setupSql());
        TestDb.run(ds, TestDb.setupSql());          // and again: nothing changes twice

        JdbcTemplate now = new JdbcTemplate(ds);
        assertThat(now.queryForObject("select count(*) from order_documents", Integer.class)).isEqualTo(6);
        Map<String, Object> d = now.queryForMap("select * from order_documents where id = ?", printing);
        assertThat(d.get("order_id")).isEqualTo(printing);
        assertThat(d.get("status")).isEqualTo("SUBMITTED");
        assertThat(d.get("claim_token")).isEqualTo(token);
        assertThat(now.queryForObject("select settings->>'pages' from order_documents where id = ?", String.class,
                printing)).isEqualTo("5-9");
        assertThat(status(now, "orders", printing)).isEqualTo("PRINTING");
        assertThat(status(now, "order_documents", queued)).isEqualTo("QUEUED");
        assertThat(status(now, "order_documents", done)).isEqualTo("COMPLETED");
        assertThat(status(now, "order_documents", unpaid)).isEqualTo("READY");
        assertThat(status(now, "order_documents", refused)).isEqualTo("REJECTED");
        assertThat(status(now, "order_documents", expired)).isEqualTo("CANCELLED");

        // The Station that was printing the old order reports back with its old id and token.
        int n = now.update("update order_documents set status = 'COMPLETED', completed_at = now() "
                + "where id = ? and claim_token = ? and status = 'SUBMITTED'", printing, token);
        assertThat(n).isEqualTo(1);
        assertThat(status(now, "orders", printing)).isEqualTo("COMPLETED");
    }

    private static UUID legacyOrder(JdbcTemplate old, String status, boolean paid, UUID printer, UUID pc,
                                    UUID token, String pages) {
        UUID id = UUID.randomUUID();
        old.update("insert into orders (id, pickup_code, access_key_hash, status, file_name, file_type, storage_path, "
                        + "file_size_bytes, page_count, page_ranges, print_pages, color, copies, amount_paise, paid_at, "
                        + "printer_id, agent_id, claim_token, lease_expires_at) values (?, ?, 'x', ?, 'old.pdf', 'PDF', ?, "
                        + "1000, 12, ?, 5, false, 2, 2000, ?, ?, ?, ?, now() + interval '5 minutes')",
                id, id.toString().substring(0, 5).toUpperCase(), status, "orders/" + id + ".pdf", pages,
                paid ? new java.sql.Timestamp(System.currentTimeMillis()) : null, printer, pc, token);
        return id;
    }

    private static String status(JdbcTemplate t, String table, UUID id) {
        return t.queryForObject("select status from " + table + " where id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ helpers

    private UUID printer(String name, boolean color, boolean bw, String effective) {
        return db.queryForObject("insert into printers (name, windows_printer_name, supports_color, accepts_bw, agent_id, "
                + "effective) values (?, ?, ?, ?, ?, cast(? as jsonb)) returning id", UUID.class,
                name, name, color, bw, agent, effective);
    }

    private static String req(boolean color, String paper, String duplex, String finishing) {
        return "{\"color\":" + color + ",\"paperSize\":\"" + paper + "\",\"duplex\":\"" + duplex
                + "\",\"finishing\":" + finishing + "}";
    }

    private UUID order() {
        UUID id = UUID.randomUUID();
        db.update("insert into orders (id, pickup_code, access_key_hash) values (?, ?, 'x')", id,
                id.toString().substring(0, 6));
        return id;
    }

    private UUID document(UUID order, int position, String requirements) {
        UUID id = UUID.randomUUID();
        db.update("insert into order_documents (id, order_id, position, status, file_name, file_type, storage_path, "
                        + "requirements) values (?, ?, ?, 'READY', 'f.pdf', 'PDF', ?, cast(? as jsonb))",
                id, order, position, "orders/" + order + "/" + id + ".pdf", requirements);
        return id;
    }

    private void pay(UUID order) {
        db.update("update orders set status = 'AWAITING_PAYMENT' where id = ?", order);
        assertThat(db.queryForObject("select mark_order_paid(?, 'demo', 'p1')", Integer.class, order)).isEqualTo(1);
        sleepAMoment();                // paid_at orders the queue
    }

    private UUID paidOrder(String requirements) {
        UUID o = order();
        UUID d = document(o, 1, requirements);
        pay(o);
        return d;
    }

    private UUID claim(UUID printer) {
        List<UUID> got = db.query("select id from claim_next_job(?, ?, 300, false)",
                (rs, i) -> (UUID) rs.getObject(1), agent, printer);
        return got.isEmpty() ? null : got.get(0);
    }

    private void agentSays(UUID doc, String status) {
        db.update("update order_documents set status = ? where id = ?", status, doc);
    }

    private String orderStatus(UUID order) {
        return db.queryForObject("select status from orders where id = ?", String.class, order);
    }

    private String docStatus(UUID doc) {
        return db.queryForObject("select status from order_documents where id = ?", String.class, doc);
    }

    private static void sleepAMoment() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
