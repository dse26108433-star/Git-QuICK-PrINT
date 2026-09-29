package edu.campus.print.printing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import edu.campus.print.common.ApiException;
import edu.campus.print.domain.FileType;
import edu.campus.print.orders.PageRanges;

import java.util.List;
import java.util.Set;

/**
 * Turns what a student chose for one document into settings the server
 * vouches for. Everything is checked against the real file (page count) and
 * the real printers (what they can do): what passes here is exactly what the
 * student sees on the summary and exactly what the Xerox PC prints.
 *
 * Choices that do not apply are tidied, never guessed: pictures have no page
 * list or pages-per-sheet, PDFs are always centred, several pages per sheet
 * are always fitted into their cells, one copy is always "collated". Choices
 * that cannot be printed are refused with words the student can act on.
 */
public final class SettingsChecker {

    public static final Set<Integer> PAGES_PER_SHEET = Set.of(1, 2, 4, 6, 9, 16);
    public static final int MAX_MARGIN_MM = 40;

    /** Facts about the file, from the server's own check of it. */
    public record Facts(FileType type, int pageCount) {}

    public record Limits(int maxCopies, int maxPages) {}

    public record Checked(PrintSettings settings, Requirements requirements, PrintPlan plan, boolean legacyOk) {}

    /** A choice that cannot be printed. The message is for the student. */
    public static class Problem extends RuntimeException {
        public Problem(String message) {
            super(message);
        }
    }

    private SettingsChecker() {
    }

    public static Checked check(JsonNode raw, Facts facts, List<PrinterRules.Candidate> printers, Limits limits) {
        JsonNode in = raw == null || raw.isNull() ? JsonNodeFactory.instance.objectNode() : raw;
        if (!in.isObject()) throw new Problem("The print settings could not be read.");
        boolean pdf = facts.type() == FileType.PDF;

        int copies = integer(in, "copies", 1);
        if (copies < 1 || copies > limits.maxCopies()) {
            throw new Problem("Choose between 1 and " + limits.maxCopies() + " copies.");
        }
        boolean color = bool(in, "color", false);

        String pages = null;
        int printPages = 1;
        if (pdf) {
            PageRanges.Selection sel;
            try {
                sel = PageRanges.resolve(text(in, "pages"), facts.pageCount());
            } catch (ApiException e) {
                throw new Problem(e.getMessage());
            }
            pages = sel.spec();
            printPages = sel.count();
            if (printPages > limits.maxPages()) {
                throw new Problem((pages == null ? "This file has " : "You chose ") + printPages
                        + " pages. Up to " + limits.maxPages() + " pages can be printed from one document: "
                        + "choose the pages you need.");
            }
        }

        String duplex = choice(in, "duplex", "ONE_SIDED", Set.of("ONE_SIDED", "LONG_EDGE", "SHORT_EDGE"), "sides");
        String paper = text(in, "paperSize");
        if (paper == null) paper = "A4";
        if (!PaperSize.known(paper)) throw new Problem("Unknown paper size " + paper + ".");
        String orientation = choice(in, "orientation", "AUTO", Set.of("AUTO", "PORTRAIT", "LANDSCAPE"), "orientation");

        int perSheet = pdf ? integer(in, "pagesPerSheet", 1) : 1;
        if (!PAGES_PER_SHEET.contains(perSheet)) {
            throw new Problem("Pages per sheet must be 1, 2, 4, 6, 9 or 16.");
        }
        String scaling = choice(in, "scaling", "FIT", pdf ? Set.of("FIT", "ACTUAL", "CUSTOM")
                : Set.of("FIT", "FILL", "ACTUAL", "CUSTOM"), "scaling");
        int scalePercent = 100;
        if (perSheet > 1) {
            scaling = "FIT";                        // pages are fitted into their cells
        } else if ("CUSTOM".equals(scaling)) {
            scalePercent = integer(in, "scalePercent", 100);
            if (scalePercent < 10 || scalePercent > 400) throw new Problem("Scale must be between 10 % and 400 %.");
        }

        int margin = (int) Math.round(number(in, "marginMm", PrintSettings.DEFAULT_MARGIN_MM));
        if (margin < 0 || margin > MAX_MARGIN_MM) {
            throw new Problem("Margins must be between 0 and " + MAX_MARGIN_MM + " mm.");
        }

        int rotation = 0;
        boolean center = true;
        if (!pdf) {
            rotation = integer(in, "rotation", 0);
            rotation = ((rotation % 360) + 360) % 360;
            if (rotation % 90 != 0) throw new Problem("Pictures can be turned by 90, 180 or 270 degrees.");
            center = bool(in, "center", true);
        }

        String staple = finishing(in, "staple", "STAPLE");
        String punch = finishing(in, "punch", "PUNCH");
        String bind = finishing(in, "bind", "BIND");
        boolean collate = copies == 1 || staple != null || bind != null || bool(in, "collate", true);

        String media = text(in, "mediaType");
        if (media != null && media.length() > 200) throw new Problem("Unknown paper type.");
        String quality = choice(in, "quality", "STANDARD", Set.of("STANDARD", "HIGH"), "quality");

        PrintSettings s = new PrintSettings(copies, color, pages, duplex, paper, orientation, scaling, scalePercent,
                perSheet, margin, rotation, center, collate, staple, punch, bind, media, quality);
        PrintPlan plan = PrintPlan.of(printPages, s);
        if ((staple != null || bind != null) && plan.sheets() < 2) {
            throw new Problem((staple != null ? "Stapling" : "Binding") + " needs at least 2 sheets of paper; "
                    + "this document prints on 1 sheet.");
        }

        Requirements req = Requirements.of(s);
        if (!PrinterRules.anyCanDo(printers, req)) {
            throw new Problem(PrinterRules.whyNot(printers, req, s));
        }
        return new Checked(s, req, plan, legacyOk(s));
    }

    /**
     * An older Station (before version 4) prints A4, one-sided, fitted, one
     * page per sheet, with nothing else. Only documents like that may go to it.
     */
    static boolean legacyOk(PrintSettings s) {
        return "A4".equals(s.paperSize()) && !s.twoSided() && s.pagesPerSheet() == 1 && "FIT".equals(s.scaling())
                && "AUTO".equals(s.orientation()) && s.staple() == null && s.punch() == null && s.bind() == null
                && s.mediaType() == null && !s.borderless() && "STANDARD".equals(s.quality()) && s.rotation() == 0
                && s.center() && s.collate();
    }

    // ------------------------------------------------------------------ reading the student's choices

    private static String text(JsonNode in, String field) {
        JsonNode v = in.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw new Problem("The setting \"" + field + "\" could not be read.");
        String t = v.asText().trim();
        return t.isEmpty() ? null : t;
    }

    private static int integer(JsonNode in, String field, int fallback) {
        JsonNode v = in.get(field);
        if (v == null || v.isNull()) return fallback;
        if (!v.isNumber() || v.asDouble() != Math.rint(v.asDouble())) {
            throw new Problem("The setting \"" + field + "\" must be a whole number.");
        }
        return v.asInt();
    }

    private static double number(JsonNode in, String field, double fallback) {
        JsonNode v = in.get(field);
        if (v == null || v.isNull()) return fallback;
        if (!v.isNumber()) throw new Problem("The setting \"" + field + "\" must be a number.");
        return v.asDouble();
    }

    private static boolean bool(JsonNode in, String field, boolean fallback) {
        JsonNode v = in.get(field);
        if (v == null || v.isNull()) return fallback;
        if (!v.isBoolean()) throw new Problem("The setting \"" + field + "\" must be true or false.");
        return v.asBoolean();
    }

    private static String choice(JsonNode in, String field, String fallback, Set<String> allowed, String words) {
        String v = text(in, field);
        if (v == null) return fallback;
        if (!allowed.contains(v)) throw new Problem("Unknown " + words + " \"" + v + "\".");
        return v;
    }

    private static String finishing(JsonNode in, String field, String group) {
        String v = text(in, field);
        if (v == null || "NONE".equals(v)) return null;
        if (!Finishing.known(group + "_" + v)) throw new Problem("Unknown " + field + " position \"" + v + "\".");
        return v;
    }
}
