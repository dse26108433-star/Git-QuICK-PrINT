package edu.campus.print.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The small pictures of each document's first printed sheet (table
 * document_previews). The Xerox PC makes one while it prints; the counter
 * shows it next to the order, and the student's phone shows it when it has
 * no copy of its own. So staff hand the pages to the student whose phone
 * shows the same sheet: nobody needs a pickup code.
 *
 * Kept in the database, not in file storage: they are small, and they must
 * disappear together with their order.
 */
@Component
public class PreviewStore {

    /** A JPEG of a sheet at screen size is 30 to 120 KB. */
    public static final int MAX_BYTES = 200 * 1024;

    private final JdbcTemplate jdbc;

    public PreviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True for bytes that start like a JPEG picture and are not too big. */
    public static boolean acceptable(byte[] b) {
        return b != null && b.length > 100 && b.length <= MAX_BYTES
                && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    /** Keeps the picture of one document (a newer one replaces the older: "Print again"). */
    public void put(UUID documentId, UUID orderId, byte[] jpeg) {
        jdbc.update("""
                insert into document_previews (document_id, order_id, image) values (?, ?, ?)
                on conflict (document_id) do update set image = excluded.image, created_at = now()
                """, documentId, orderId, jpeg);
    }

    public Optional<byte[]> get(UUID documentId) {
        List<byte[]> rows = jdbc.query("select image from document_previews where document_id = ?",
                (rs, i) -> rs.getBytes(1), documentId);
        return rows.stream().findFirst();
    }

    /** Which documents of these orders have a picture. */
    public Set<UUID> documentsWithPreview(Collection<UUID> orderIds) {
        Set<UUID> out = new HashSet<>();
        if (orderIds.isEmpty()) return out;
        StringBuilder ids = new StringBuilder("{");
        for (UUID id : orderIds) ids.append(ids.length() > 1 ? "," : "").append(id);
        jdbc.query("select document_id from document_previews where order_id = any (?::uuid[])",
                rs -> { out.add(rs.getObject(1, UUID.class)); }, ids.append('}').toString());
        return out;
    }

    /** The order was handed over: its pictures are not needed any more. */
    public int deleteForOrder(UUID orderId) {
        return jdbc.update("delete from document_previews where order_id = ?", orderId);
    }

    /** Pictures of orders handed over a while ago, and any picture older than a week. */
    public int cleanUp() {
        return jdbc.update("""
                delete from document_previews p
                 using orders o
                 where o.id = p.order_id
                   and (o.collected_at < now() - interval '10 minutes'
                        or o.status = 'CANCELLED'
                        or p.created_at < now() - interval '7 days')
                """);
    }
}
