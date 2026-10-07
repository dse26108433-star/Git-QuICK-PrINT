package edu.campus.print.printing;

import java.util.List;
import java.util.Optional;

/**
 * The paper sizes XeoGo knows. A printer's driver may report many
 * more (envelopes, odd photo sizes); only these can be offered to students,
 * because the whole chain (website preview, price, printing) knows them.
 *
 * The Station matches what a driver reports to these by size (within 2 mm),
 * whatever the driver calls them. The list order is the order students see.
 */
public record PaperSize(String id, String label, double widthMm, double heightMm) {

    public static final List<PaperSize> ALL = List.of(
            new PaperSize("A4", "A4", 210, 297),
            new PaperSize("A3", "A3", 297, 420),
            new PaperSize("A5", "A5", 148, 210),
            new PaperSize("A6", "A6", 105, 148),
            new PaperSize("LEGAL", "Legal (8.5 × 14 in)", 215.9, 355.6),
            new PaperSize("FOLIO", "Folio / FS (8.5 × 13 in)", 215.9, 330.2),
            new PaperSize("F4", "F4 (210 × 330 mm)", 210, 330),
            new PaperSize("LETTER", "Letter (8.5 × 11 in)", 215.9, 279.4),
            new PaperSize("B4", "B4 (JIS)", 257, 364),
            new PaperSize("B5", "B5 (JIS)", 182, 257),
            new PaperSize("TABLOID", "Tabloid (11 × 17 in)", 279.4, 431.8),
            new PaperSize("EXECUTIVE", "Executive", 184.15, 266.7),
            new PaperSize("STATEMENT", "Statement", 139.7, 215.9),
            new PaperSize("PHOTO_4X6", "Photo 4 × 6 in", 101.6, 152.4),
            new PaperSize("PHOTO_5X7", "Photo 5 × 7 in", 127, 177.8),
            new PaperSize("PHOTO_8X10", "Photo 8 × 10 in", 203.2, 254));

    /** Offered to students when staff have not chosen otherwise (and the printer has them). */
    public static final List<String> DEFAULT_OFFERED = List.of("A4", "A3", "A5", "LEGAL", "FOLIO");

    public static Optional<PaperSize> byId(String id) {
        if (id == null) return Optional.empty();
        return ALL.stream().filter(p -> p.id.equals(id)).findFirst();
    }

    public static boolean known(String id) {
        return byId(id).isPresent();
    }

    /** Position in ALL, for sorting (unknown sizes last). */
    public static int rank(String id) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).id.equals(id)) return i;
        }
        return Integer.MAX_VALUE;
    }
}
