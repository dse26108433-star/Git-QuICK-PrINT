package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/**
 * Prints the pickup code small in the bottom-right corner of the FIRST page,
 * instead of spending a whole extra sheet on a cover page:
 *
 *     [ Pickup K7M4X · 3 pages × 2 ]
 *
 * Every copy starts with that page, so staff can see where each order (and
 * each copy) begins in the printer tray, and how many sheets to count.
 *
 * The student's layout is kept: if that corner of the page is blank (almost
 * always - documents have a bottom margin) the label goes into the margin and
 * nothing else changes. Only if something is already printed there (a page
 * number, a full-page photo) is the first page shrunk by about 4 % to make a
 * clean white strip for the label. The label stays well inside the area every
 * printer can reach (printers cannot print the outer 3-5 mm of the paper).
 *
 * Only the document in memory is changed, never the student's file.
 */
public final class PickupCodeStamp {

    /** Distances in points (1/72 inch), measured on the page as the student sees it. */
    private static final float EDGE_RIGHT = 24f;     // 8.5 mm from the right edge
    private static final float BASELINE = 22f;       // text baseline, 7.8 mm from the bottom edge
    private static final float BOX_BOTTOM = 17f;     // 6 mm: clear of any printer's margin
    private static final float BOX_HEIGHT = 16f;
    private static final float PAD = 5f;
    private static final float STRIP = 38f;          // white strip made when the corner is not blank
    private static final float CODE_SIZE = 11f;
    private static final float TEXT_SIZE = 7.5f;

    /** What happened, for the log. */
    public enum Result { IN_MARGIN, PAGE_SHRUNK }

    private PickupCodeStamp() {
    }

    public static Result apply(PDDocument doc, PrintStrategy.Settings s) throws Exception {
        // One font object per document: several printers stamp at the same time.
        PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        int pages = doc.getNumberOfPages();
        PDPage page = doc.getPage(0);
        View v = View.of(page);

        String before = "Pickup ";
        String code = s.pickupCode();
        String after = "  ·  " + pages + (pages == 1 ? " page" : " pages")
                + (s.copies() > 1 ? " × " + s.copies() : "");
        float wBefore = width(regular, TEXT_SIZE, before);
        float wCode = width(bold, CODE_SIZE, code);
        float wAfter = width(regular, TEXT_SIZE, after);
        float boxW = PAD + wBefore + wCode + wAfter + PAD;
        float boxX = v.width - EDGE_RIGHT - boxW;

        boolean blank = cornerIsBlank(doc, v, boxX - 4, BOX_BOTTOM - 4, boxW + 8, BOX_HEIGHT + 10);
        if (!blank) {
            makeStrip(doc, page, v);
        }

        try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
            cs.saveGraphicsState();
            cs.transform(new Matrix(v.toPage));

            // White background, so the label reads cleanly even on a light page colour.
            cs.setNonStrokingColor(Color.WHITE);
            cs.addRect(boxX, BOX_BOTTOM, boxW, BOX_HEIGHT);
            cs.fill();
            cs.setStrokingColor(Color.BLACK);
            cs.setLineWidth(0.6f);
            cs.addRect(boxX, BOX_BOTTOM, boxW, BOX_HEIGHT);
            cs.stroke();

            cs.setNonStrokingColor(Color.BLACK);
            cs.beginText();
            cs.newLineAtOffset(boxX + PAD, BASELINE);
            cs.setFont(regular, TEXT_SIZE);
            cs.showText(before);
            cs.setFont(bold, CODE_SIZE);
            cs.showText(code);
            cs.setFont(regular, TEXT_SIZE);
            cs.showText(after);
            cs.endText();
            cs.restoreGraphicsState();
        }
        return blank ? Result.IN_MARGIN : Result.PAGE_SHRUNK;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The page "as the student sees it": width/height after the page's own
     * rotation, and the transform from those coordinates to the PDF's own
     * (possibly rotated, possibly offset) coordinates.
     */
    private record View(float width, float height, AffineTransform toPage) {

        static View of(PDPage page) {
            PDRectangle box = page.getCropBox();
            float llx = box.getLowerLeftX(), lly = box.getLowerLeftY();
            float urx = box.getUpperRightX(), ury = box.getUpperRightY();
            int rotation = ((page.getRotation() % 360) + 360) % 360;
            // Viewers turn the page clockwise by /Rotate; these map a point
            // (x from the left, y up from the bottom, as seen) back onto the page.
            return switch (rotation) {
                case 90 -> new View(box.getHeight(), box.getWidth(), new AffineTransform(0, 1, -1, 0, urx, lly));
                case 180 -> new View(box.getWidth(), box.getHeight(), new AffineTransform(-1, 0, 0, -1, urx, ury));
                case 270 -> new View(box.getHeight(), box.getWidth(), new AffineTransform(0, -1, 1, 0, llx, ury));
                default -> new View(box.getWidth(), box.getHeight(), new AffineTransform(1, 0, 0, 1, llx, lly));
            };
        }
    }

    /**
     * Draws the first page small (to 1/72 inch precision) and looks for ink
     * where the label would go. Any doubt counts as "not blank", which only
     * means the page gets the white strip.
     */
    private static boolean cornerIsBlank(PDDocument doc, View v, float x, float y, float w, float h) {
        try {
            BufferedImage img = new PDFRenderer(doc).renderImage(0, 1f, ImageType.GRAY);
            double sx = img.getWidth() / (double) v.width;
            double sy = img.getHeight() / (double) v.height;
            int x0 = clamp((int) Math.floor(x * sx), img.getWidth());
            int x1 = clamp((int) Math.ceil((x + w) * sx), img.getWidth());
            int y0 = clamp((int) Math.floor(img.getHeight() - (y + h) * sy), img.getHeight());   // image rows go down
            int y1 = clamp((int) Math.ceil(img.getHeight() - y * sy), img.getHeight());
            int total = 0, ink = 0;
            var raster = img.getRaster();
            for (int row = y0; row < y1; row++) {
                for (int col = x0; col < x1; col++) {
                    total++;
                    if (raster.getSample(col, row, 0) < 225) ink++;
                }
            }
            // A few stray pixels (scanner dust) do not count; a page number does.
            return total > 0 && ink <= total * 0.0025;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }

    /**
     * Shrinks the first page's content (and its filled-in form fields) evenly,
     * keeping it centred and at the top, to free a white strip at the bottom.
     */
    private static void makeStrip(PDDocument doc, PDPage page, View v) throws Exception {
        float strip = Math.min(STRIP, v.height * 0.15f);
        double scale = (v.height - strip) / v.height;
        AffineTransform seen = new AffineTransform();
        seen.translate(v.width * (1 - scale) / 2, strip);
        seen.scale(scale, scale);
        // Same change expressed in the page's own coordinates: to view, shrink, back.
        AffineTransform onPage = new AffineTransform(v.toPage);
        onPage.concatenate(seen);
        onPage.concatenate(v.toPage.createInverse());

        try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.PREPEND, true)) {
            cs.saveGraphicsState();
            cs.transform(new Matrix(onPage));
        }
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, AppendMode.APPEND, true)) {
            cs.restoreGraphicsState();
        }
        // Filled-in form fields and other annotations are drawn separately
        // from the page content, so move them the same way.
        for (PDAnnotation a : page.getAnnotations()) {
            PDRectangle r = a.getRectangle();
            if (r == null) continue;
            Point2D p1 = onPage.transform(new Point2D.Float(r.getLowerLeftX(), r.getLowerLeftY()), null);
            Point2D p2 = onPage.transform(new Point2D.Float(r.getUpperRightX(), r.getUpperRightY()), null);
            float lx = (float) Math.min(p1.getX(), p2.getX()), ly = (float) Math.min(p1.getY(), p2.getY());
            float ux = (float) Math.max(p1.getX(), p2.getX()), uy = (float) Math.max(p1.getY(), p2.getY());
            a.setRectangle(new PDRectangle(lx, ly, ux - lx, uy - ly));
        }
    }

    private static float width(PDFont font, float size, String text) throws Exception {
        return font.getStringWidth(text) / 1000f * size;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }
}
