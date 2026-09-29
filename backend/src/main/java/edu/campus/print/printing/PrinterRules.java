package edu.campus.print.printing;

import java.util.ArrayList;
import java.util.List;

/**
 * Can this printer print a document with these requirements?
 *
 * The same rule lives in three places, kept identical by
 * spec/cases/printer-rules.json:
 *   - here, to check a student's settings before payment;
 *   - printer_can_do() in db/setup.sql, which the print queue uses;
 *   - web/js/print-core.js, which shows students only what is possible.
 */
public final class PrinterRules {

    private PrinterRules() {
    }

    /** One printer as the rule sees it. */
    public record Candidate(String name, boolean color, boolean bw, PrinterFeatures features) {
        public Candidate {
            if (features == null) features = PrinterFeatures.PLAIN_A4;
        }
    }

    public static boolean canDo(Candidate p, Requirements r) {
        PrinterFeatures f = p.features();
        if (!(r.color() ? p.color() : p.bw())) return false;
        if (r.paperSize() != null && !f.paperSizes().contains(r.paperSize())) return false;
        if (r.duplex() != null && !"ONE_SIDED".equals(r.duplex()) && !f.duplex()) return false;
        if (r.finishing() != null && !f.finishing().containsAll(r.finishing())) return false;
        if (r.mediaType() != null && !f.mediaTypes().contains(r.mediaType())) return false;
        if (r.borderless() && !f.borderless()) return false;
        return r.quality() == null || "STANDARD".equals(r.quality()) || f.highQuality();
    }

    public static boolean anyCanDo(List<Candidate> printers, Requirements r) {
        return printers.stream().anyMatch(p -> canDo(p, r));
    }

    /**
     * Why no printer can do it, in words a student can act on. Looks for a
     * single choice nobody can do first, then says it is the combination.
     */
    public static String whyNot(List<Candidate> printers, Requirements r, PrintSettings s) {
        if (printers.isEmpty()) {
            return "No printer is taking orders right now. Please try again later.";
        }
        Requirements plain = new Requirements(false, null, null, List.of(), null, false, null);
        List<String> choices = new ArrayList<>();
        if (r.color()) {
            if (!anyCanDo(printers, new Requirements(true, null, null, List.of(), null, false, null))) {
                return "Colour printing is not available. Choose black & white.";
            }
            choices.add("colour");
        } else if (!anyCanDo(printers, plain)) {
            return "Black & white printing is not available. Choose colour.";
        }
        if (r.paperSize() != null) {
            if (!anyCanDo(printers, only(r.color(), r.paperSize(), null, List.of(), null, false, null))
                    && !anyCanDo(printers, only(!r.color(), r.paperSize(), null, List.of(), null, false, null))) {
                return "No printer takes " + paperLabel(r.paperSize()) + " paper. Choose another paper size.";
            }
            if (!"A4".equals(r.paperSize())) choices.add(paperLabel(r.paperSize()));
        }
        if (s.twoSided()) {
            if (printers.stream().noneMatch(p -> p.features().duplex())) {
                return "Two-sided printing is not available. Choose one-sided.";
            }
            choices.add("two-sided");
        }
        for (String f : r.finishing()) {
            if (printers.stream().noneMatch(p -> p.features().finishing().contains(f))) {
                return Finishing.LABELS.getOrDefault(f, f) + " is not available.";
            }
            choices.add(Finishing.LABELS.getOrDefault(f, f).toLowerCase());
        }
        if (r.mediaType() != null) {
            if (printers.stream().noneMatch(p -> p.features().mediaTypes().contains(r.mediaType()))) {
                return "That paper type is not available. Choose the usual paper.";
            }
            choices.add("that paper type");
        }
        if (r.borderless()) {
            if (printers.stream().noneMatch(p -> p.features().borderless())) {
                return "Borderless printing is not available. Choose a margin.";
            }
            choices.add("borderless");
        }
        if ("HIGH".equals(r.quality())) {
            if (printers.stream().noneMatch(p -> p.features().highQuality())) {
                return "High quality is not available. Choose standard quality.";
            }
            choices.add("high quality");
        }
        return "No single printer can do " + String.join(" + ", choices)
                + " together. Change one of these choices.";
    }

    private static Requirements only(boolean color, String paper, String duplex, List<String> finishing,
                                     String media, boolean borderless, String quality) {
        return new Requirements(color, paper, duplex, finishing, media, borderless, quality);
    }

    private static String paperLabel(String id) {
        return PaperSize.byId(id).map(PaperSize::label).orElse(id);
    }
}
