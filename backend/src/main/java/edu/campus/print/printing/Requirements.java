package edu.campus.print.printing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * What a printer must be able to do to print a document, worked out from its
 * settings. Stored as order_documents.requirements; the print queue
 * (claim_next_job -> printer_can_do in db/setup.sql) gives the document only
 * to a printer whose features include all of it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Requirements(
        boolean color,
        String paperSize,
        String duplex,
        List<String> finishing,
        String mediaType,
        boolean borderless,
        String quality
) {
    public static Requirements of(PrintSettings s) {
        List<String> finishing = new ArrayList<>();
        if (s.staple() != null) finishing.add(Finishing.id("STAPLE", s.staple()));
        if (s.punch() != null) finishing.add(Finishing.id("PUNCH", s.punch()));
        if (s.bind() != null) finishing.add(Finishing.id("BIND", s.bind()));
        return new Requirements(s.color(), s.paperSize(), s.duplex(), List.copyOf(finishing), s.mediaType(),
                s.borderless(), s.quality());
    }
}
