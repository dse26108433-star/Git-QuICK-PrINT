package edu.campus.print.orders;

import edu.campus.print.domain.DocumentStatus;
import edu.campus.print.domain.OrderDocument;
import edu.campus.print.domain.PrintOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * About when a paid order will be ready, so the student can walk over at the
 * right time: what is ahead of it in the queue, how many sheets it has itself,
 * and how fast the printers have really been lately (learned from the last
 * documents printed, not a number someone typed in).
 *
 * It is an estimate: the apps show it as "about 10:47".
 */
@Component
public class ReadyEstimate {

    static final double DEFAULT_SECONDS_PER_SHEET = 4;
    /** Fetching the file, laying out the sheets and handing them to Windows, per document. */
    static final double SECONDS_PER_DOCUMENT = 12;

    private final JdbcTemplate jdbc;
    private volatile double secondsPerSheet = DEFAULT_SECONDS_PER_SHEET;
    private volatile long learnedAt;

    public ReadyEstimate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Empty when it cannot be known: nothing left to print, or no printer is
     * online (the files then wait until one is back).
     *
     * @param printersOnline printers that are switched on and ready right now
     */
    public Optional<Instant> forOrder(PrintOrder o, List<OrderDocument> docs, int printersOnline) {
        if (o.getPaidAt() == null || printersOnline <= 0) return Optional.empty();
        int mySheets = 0, myDocs = 0;
        for (OrderDocument d : docs) {
            if (d.getStatus() != DocumentStatus.QUEUED && !DocumentStatus.AT_PRINTER.contains(d.getStatus())) continue;
            myDocs++;
            mySheets += sheets(d);
        }
        if (myDocs == 0) return Optional.empty();
        long[] ahead = jdbc.queryForObject("""
                select coalesce(sum(greatest(1, coalesce(d.sheets, 1))
                                    * greatest(1, coalesce((d.settings ->> 'copies')::int, 1))), 0), count(*)
                  from order_documents d join orders x on x.id = d.order_id
                 where d.status in ('QUEUED', 'CLAIMED', 'DOWNLOADING', 'SUBMITTED')
                   and x.status in ('QUEUED', 'PRINTING') and x.id <> ? and x.paid_at < ?
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)},
                o.getId(), java.sql.Timestamp.from(o.getPaidAt()));
        double seconds = ((ahead[0] + mySheets) * secondsPerSheet() + (ahead[1] + myDocs) * SECONDS_PER_DOCUMENT)
                / printersOnline;
        return Optional.of(Instant.now().plusSeconds(Math.max(15, Math.round(seconds))));
    }

    private static int sheets(OrderDocument d) {
        int copies = d.getSettings() == null ? 1 : Math.max(1, d.getSettings().copies());
        return Math.max(1, d.getSheets() == null ? 1 : d.getSheets()) * copies;
    }

    /** Seconds one sheet took lately (the middle value of the last 40 documents), looked up once a minute. */
    double secondsPerSheet() {
        long now = System.currentTimeMillis();
        if (now - learnedAt > 60_000) {
            learnedAt = now;
            try {
                Double v = jdbc.queryForObject("""
                        select percentile_cont(0.5) within group (order by per_sheet)
                          from (select extract(epoch from (completed_at - submitted_at))
                                       / (greatest(1, sheets) * greatest(1, coalesce((settings ->> 'copies')::int, 1)))
                                       as per_sheet
                                  from order_documents
                                 where status = 'COMPLETED' and submitted_at is not null
                                   and completed_at > submitted_at and sheets is not null
                                 order by completed_at desc
                                 limit 40) recent
                        """, Double.class);
                secondsPerSheet = v == null ? DEFAULT_SECONDS_PER_SHEET : Math.max(1.5, Math.min(20, v));
            } catch (RuntimeException e) {
                secondsPerSheet = DEFAULT_SECONDS_PER_SHEET;
            }
        }
        return secondsPerSheet;
    }
}
