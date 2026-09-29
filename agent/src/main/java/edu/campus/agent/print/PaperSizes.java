package edu.campus.agent.print;

import javax.print.PrintService;
import javax.print.attribute.standard.Media;
import javax.print.attribute.standard.MediaSize;
import javax.print.attribute.standard.MediaSizeName;
import java.util.List;
import java.util.Optional;

/**
 * The paper sizes Campus Print knows (same list as the server's PaperSize),
 * and how to find each among what a printer's driver offers. Matched by size
 * (within 2 mm), never by name: drivers name the same paper differently.
 */
public final class PaperSizes {

    public record Size(String id, double widthMm, double heightMm) {}

    public static final List<Size> ALL = List.of(
            new Size("A4", 210, 297),
            new Size("A3", 297, 420),
            new Size("A5", 148, 210),
            new Size("A6", 105, 148),
            new Size("LEGAL", 215.9, 355.6),
            new Size("FOLIO", 215.9, 330.2),
            new Size("F4", 210, 330),
            new Size("LETTER", 215.9, 279.4),
            new Size("B4", 257, 364),
            new Size("B5", 182, 257),
            new Size("TABLOID", 279.4, 431.8),
            new Size("EXECUTIVE", 184.15, 266.7),
            new Size("STATEMENT", 139.7, 215.9),
            new Size("PHOTO_4X6", 101.6, 152.4),
            new Size("PHOTO_5X7", 127, 177.8),
            new Size("PHOTO_8X10", 203.2, 254));

    private static final double TOLERANCE_MM = 2.0;

    private PaperSizes() {
    }

    /** The known paper this size is (either way round), if any. */
    public static Optional<Size> match(double widthMm, double heightMm) {
        double a = Math.min(widthMm, heightMm), b = Math.max(widthMm, heightMm);
        for (Size s : ALL) {
            if (Math.abs(s.widthMm - a) <= TOLERANCE_MM && Math.abs(s.heightMm - b) <= TOLERANCE_MM) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** The driver's own name for this paper size on this printer, to ask Windows for it. */
    public static Optional<MediaSizeName> mediaFor(PrintService service, double widthMm, double heightMm) {
        Object values = service.getSupportedAttributeValues(Media.class, null, null);
        if (!(values instanceof Media[] media)) return Optional.empty();
        MediaSizeName best = null;
        double bestError = Double.MAX_VALUE;
        double a = Math.min(widthMm, heightMm), b = Math.max(widthMm, heightMm);
        for (Media m : media) {
            if (!(m instanceof MediaSizeName name)) continue;
            MediaSize size = MediaSize.getMediaSizeForName(name);
            if (size == null) continue;
            double w = size.getX(MediaSize.MM), h = size.getY(MediaSize.MM);
            double error = Math.abs(Math.min(w, h) - a) + Math.abs(Math.max(w, h) - b);
            if (error < bestError) {
                bestError = error;
                best = name;
            }
        }
        return bestError <= 2 * TOLERANCE_MM ? Optional.of(best) : Optional.empty();
    }
}
