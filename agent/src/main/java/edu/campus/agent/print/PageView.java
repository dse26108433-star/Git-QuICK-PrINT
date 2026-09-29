package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;

/**
 * A PDF page "as seen": width and height after the page's own rotation
 * (/Rotate), and the transform from those coordinates (x from the left, y up
 * from the bottom, as seen) to the PDF's own, possibly rotated and offset,
 * page coordinates. Viewers, PDF.js in the website and printers all show
 * pages this way.
 */
public record PageView(float width, float height, AffineTransform toPage) {

    public static PageView of(PDPage page) {
        PDRectangle box = page.getCropBox();
        float llx = box.getLowerLeftX(), lly = box.getLowerLeftY();
        float urx = box.getUpperRightX(), ury = box.getUpperRightY();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        // Viewers turn the page clockwise by /Rotate; these map a point
        // (x from the left, y up from the bottom, as seen) back onto the page.
        return switch (rotation) {
            case 90 -> new PageView(box.getHeight(), box.getWidth(), new AffineTransform(0, 1, -1, 0, urx, lly));
            case 180 -> new PageView(box.getWidth(), box.getHeight(), new AffineTransform(-1, 0, 0, -1, urx, ury));
            case 270 -> new PageView(box.getHeight(), box.getWidth(), new AffineTransform(0, -1, 1, 0, llx, ury));
            default -> new PageView(box.getWidth(), box.getHeight(), new AffineTransform(1, 0, 0, 1, llx, lly));
        };
    }

    /** From the PDF's own page coordinates to "as seen". */
    public AffineTransform fromPage() {
        try {
            return toPage.createInverse();
        } catch (NoninvertibleTransformException e) {
            throw new IllegalStateException(e);       // rotations and moves always invert
        }
    }
}
