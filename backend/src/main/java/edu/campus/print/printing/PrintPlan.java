package edu.campus.print.printing;

/**
 * How much paper one copy of a document takes.
 *
 *   printPages  pages chosen (a picture is 1)
 *   sides       printed sides: pages / pages-per-sheet, rounded up
 *   sheets      sheets of paper: sides, or sides / 2 rounded up when two-sided
 *
 * The price is per printed side. Same arithmetic in web/js/print-core.js
 * (spec/cases/pricing.json keeps them equal).
 */
public record PrintPlan(int printPages, int sides, int sheets) {

    public static PrintPlan of(int printPages, PrintSettings s) {
        int perSheet = Math.max(1, s.pagesPerSheet());
        int sides = (printPages + perSheet - 1) / perSheet;
        int sheets = s.twoSided() ? (sides + 1) / 2 : sides;
        return new PrintPlan(printPages, sides, sheets);
    }
}
