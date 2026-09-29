package edu.campus.agent.print;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Where every page goes on the paper: the one piece of arithmetic behind
 * "what you see is what you get". The student's website draws its print
 * preview with the same rules (web/js/print-core.js, function layout), and
 * spec/cases/layout.json checks both give the same numbers.
 *
 * All sizes are in points (1/72 inch); y counts up from the bottom of the
 * sheet, as in PDF. A page's size is its size "as seen": after its own
 * rotation, and for pictures after the camera's and the student's turn.
 *
 * One page per sheet:
 *   sheet orientation  PORTRAIT / LANDSCAPE, or AUTO = the page's own shape (each page its own)
 *   FIT     the page as large as possible inside the margins, never cut
 *   FILL    (pictures) covers the area inside the margins; the edges that stick out are cut
 *   ACTUAL  100 %;  CUSTOM  scalePercent
 *   the page is centred (PDFs always; pictures unless "center" is off: then top-left at the margins)
 *
 * Several pages per sheet (2, 4, 6, 9, 16): the grid and sheet orientation
 * that show the first page largest; cells in reading order (left to right,
 * top to bottom), 3 mm apart; each page fitted and centred in its cell.
 */
public final class Layout {

    public static final double MM = 72 / 25.4;
    public static final double GAP = 3 * MM;

    private static final Map<Integer, int[][]> GRIDS = Map.of(
            2, new int[][]{{1, 2}, {2, 1}},
            4, new int[][]{{2, 2}},
            6, new int[][]{{2, 3}, {3, 2}},
            9, new int[][]{{3, 3}},
            16, new int[][]{{4, 4}});

    private Layout() {
    }

    /** A page (or picture) size as seen. */
    public record Size(double w, double h) {}

    /** Where one page is drawn: its whole "as seen" box scaled into x, y, w, h. */
    public record Cell(int page, double x, double y, double w, double h) {}

    public record Rect(double x, double y, double w, double h) {}

    /** One side of paper. clip: what may be painted (null = the whole sheet). */
    public record Sheet(double w, double h, List<Cell> cells, Rect clip) {
        public boolean landscape() {
            return w > h;
        }
    }

    public record Options(double paperW, double paperH, String orientation, double marginPt, String scaling,
                          int scalePercent, int pagesPerSheet, boolean center) {}

    public static List<Sheet> sheets(List<Size> pages, Options o) {
        List<Sheet> out = new ArrayList<>();
        if (pages.isEmpty()) return out;
        double shortSide = Math.min(o.paperW(), o.paperH());
        double longSide = Math.max(o.paperW(), o.paperH());
        double m = o.marginPt();
        int n = Math.max(1, o.pagesPerSheet());

        if (n == 1) {
            for (int i = 0; i < pages.size(); i++) {
                Size p = pages.get(i);
                boolean landscape = "LANDSCAPE".equals(o.orientation())
                        || ("AUTO".equals(o.orientation()) && p.w() > p.h());
                double sw = landscape ? longSide : shortSide;
                double sh = landscape ? shortSide : longSide;
                double aw = sw - 2 * m;
                double ah = sh - 2 * m;
                double s = switch (o.scaling()) {
                    case "FILL" -> Math.max(aw / p.w(), ah / p.h());
                    case "ACTUAL" -> 1;
                    case "CUSTOM" -> o.scalePercent() / 100.0;
                    default -> Math.min(aw / p.w(), ah / p.h());
                };
                double w = p.w() * s;
                double h = p.h() * s;
                double x = o.center() ? (sw - w) / 2 : m;
                double y = o.center() ? (sh - h) / 2 : sh - m - h;
                Rect clip = "FILL".equals(o.scaling()) ? new Rect(m, m, aw, ah) : null;
                out.add(new Sheet(sw, sh, List.of(new Cell(i, x, y, w, h)), clip));
            }
            return out;
        }

        int[][] grids = GRIDS.get(n);
        if (grids == null) throw new IllegalArgumentException("Pages per sheet must be 1, 2, 4, 6, 9 or 16");
        Size first = pages.get(0);
        boolean[] orientations = "AUTO".equals(o.orientation()) ? new boolean[]{false, true}
                : new boolean[]{"LANDSCAPE".equals(o.orientation())};
        double bestScale = -1, sw = 0, sh = 0, cw = 0, ch = 0;
        int cols = 1;
        for (boolean landscape : orientations) {
            double w = landscape ? longSide : shortSide;
            double h = landscape ? shortSide : longSide;
            for (int[] g : grids) {
                double cellW = (w - 2 * m - (g[0] - 1) * GAP) / g[0];
                double cellH = (h - 2 * m - (g[1] - 1) * GAP) / g[1];
                double s = Math.min(cellW / first.w(), cellH / first.h());
                if (s > bestScale + 1e-9) {
                    bestScale = s;
                    sw = w;
                    sh = h;
                    cw = cellW;
                    ch = cellH;
                    cols = g[0];
                }
            }
        }
        for (int start = 0; start < pages.size(); start += n) {
            List<Cell> cells = new ArrayList<>();
            for (int j = 0; j < n && start + j < pages.size(); j++) {
                Size p = pages.get(start + j);
                int col = j % cols;
                int row = j / cols;
                double cellX = m + col * (cw + GAP);
                double cellY = sh - m - row * (ch + GAP) - ch;
                double s = Math.min(cw / p.w(), ch / p.h());
                double w = p.w() * s;
                double h = p.h() * s;
                cells.add(new Cell(start + j, cellX + (cw - w) / 2, cellY + (ch - h) / 2, w, h));
            }
            out.add(new Sheet(sw, sh, List.copyOf(cells), null));
        }
        return out;
    }
}
