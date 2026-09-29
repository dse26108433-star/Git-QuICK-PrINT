package edu.campus.print.printing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * How one document is printed, after the server has checked and tidied the
 * student's choices (see SettingsChecker). Stored as order_documents.settings,
 * shown back to the student before payment, and sent as it is to the Xerox
 * PC, which prints exactly this: nothing is decided anywhere else.
 *
 * @param copies        1 .. shop limit
 * @param color         colour or black & white
 * @param pages         PDF pages to print, tidy form "3,7,10-12"; null = all (pictures: null)
 * @param duplex        ONE_SIDED, LONG_EDGE (flip on long edge) or SHORT_EDGE
 * @param paperSize     a PaperSize id, e.g. "A4"
 * @param orientation   AUTO (follows each page), PORTRAIT or LANDSCAPE
 * @param scaling       FIT (to the page, inside the margins), ACTUAL (100 %), CUSTOM (scalePercent),
 *                      FILL (pictures only: cover the page inside the margins, trimming edges)
 * @param scalePercent  10 .. 400, used with CUSTOM (else 100)
 * @param pagesPerSheet 1, 2, 4, 6, 9 or 16 (PDFs); pages are then fitted into their cells
 * @param marginMm      white border in mm; 0 = borderless (needs a printer that can)
 * @param rotation      pictures: 0, 90, 180 or 270 degrees clockwise (PDFs: 0)
 * @param center        pictures: centre on the paper (true) or start at the top-left margin
 * @param collate       copies come out as 1-2-3, 1-2-3 (true) or 1-1, 2-2, 3-3 (false)
 * @param staple        null or a position: TOP_LEFT, DUAL_LEFT, ... (see Finishing)
 * @param punch         null or an edge: LEFT, TOP, RIGHT, BOTTOM
 * @param bind          null or an edge: LEFT, TOP, RIGHT, BOTTOM
 * @param mediaType     null (the printer's usual paper) or a paper type id the printer reported
 * @param quality       STANDARD or HIGH
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrintSettings(
        int copies,
        boolean color,
        String pages,
        String duplex,
        String paperSize,
        String orientation,
        String scaling,
        int scalePercent,
        int pagesPerSheet,
        int marginMm,
        int rotation,
        boolean center,
        boolean collate,
        String staple,
        String punch,
        String bind,
        String mediaType,
        String quality
) {
    public static final int DEFAULT_MARGIN_MM = 5;

    /** What a document starts with. */
    public static PrintSettings defaults() {
        return new PrintSettings(1, false, null, "ONE_SIDED", "A4", "AUTO", "FIT", 100, 1,
                DEFAULT_MARGIN_MM, 0, true, true, null, null, null, null, "STANDARD");
    }

    public boolean twoSided() {
        return !"ONE_SIDED".equals(duplex);
    }

    public boolean borderless() {
        return marginMm == 0;
    }
}
