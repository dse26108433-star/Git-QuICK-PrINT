package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turns a PNG or JPG into a one-page A4 PDF, so pictures print through exactly
 * the same path as PDFs. The picture is centred and fitted to the page with a
 * small margin; wide pictures get a landscape page.
 *
 * Phone photos are often stored sideways with a hidden "rotate me" note (EXIF
 * orientation). We apply that note, so a photo of notes prints upright,
 * exactly as it looked on the student's screen.
 */
public final class ImageToPdf {

    private static final float MARGIN = 18f;    // quarter inch

    private ImageToPdf() {
    }

    public static PDDocument convert(Path image) throws Exception {
        PDDocument doc = new PDDocument();
        try {
            PDImageXObject img = PDImageXObject.createFromFileByContent(image.toFile(), doc);
            int orientation = exifOrientation(image);
            boolean quarterTurn = orientation >= 5 && orientation <= 8;

            // Size as it should LOOK (after rotation).
            float lookW = quarterTurn ? img.getHeight() : img.getWidth();
            float lookH = quarterTurn ? img.getWidth() : img.getHeight();

            PDRectangle a4 = PDRectangle.A4;
            PDRectangle box = lookW > lookH ? new PDRectangle(a4.getHeight(), a4.getWidth()) : a4;
            PDPage page = new PDPage(box);
            doc.addPage(page);

            float scale = Math.min((box.getWidth() - 2 * MARGIN) / lookW, (box.getHeight() - 2 * MARGIN) / lookH);
            float dw = lookW * scale;                    // drawn size on paper
            float dh = lookH * scale;
            float x0 = (box.getWidth() - dw) / 2;
            float y0 = (box.getHeight() - dh) / 2;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.saveGraphicsState();
                switch (orientation) {
                    case 3, 4 -> {                                   // upside down
                        cs.transform(Matrix.getTranslateInstance(x0 + dw, y0 + dh));
                        cs.transform(Matrix.getRotateInstance(Math.PI, 0, 0));
                        cs.drawImage(img, 0, 0, dw, dh);
                    }
                    case 5, 6 -> {                                   // needs a quarter turn clockwise
                        cs.transform(Matrix.getTranslateInstance(x0, y0 + dh));
                        cs.transform(Matrix.getRotateInstance(-Math.PI / 2, 0, 0));
                        cs.drawImage(img, 0, 0, dh, dw);
                    }
                    case 7, 8 -> {                                   // needs a quarter turn anticlockwise
                        cs.transform(Matrix.getTranslateInstance(x0 + dw, y0));
                        cs.transform(Matrix.getRotateInstance(Math.PI / 2, 0, 0));
                        cs.drawImage(img, 0, 0, dh, dw);
                    }
                    default -> cs.drawImage(img, x0, y0, dw, dh);   // already upright
                }
                cs.restoreGraphicsState();
            }
            return doc;
        } catch (Exception e) {
            doc.close();
            throw new IllegalStateException("The picture could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the EXIF orientation (1 = upright ... 8) from a JPEG. Returns 1 for
     * PNGs, for JPEGs without it, and for anything unexpected.
     */
    static int exifOrientation(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(256 * 1024);
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
            int p = 2;
            while (p + 4 < b.length && (b[p] & 0xFF) == 0xFF) {
                int marker = b[p + 1] & 0xFF;
                int len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                if (marker == 0xDA || len < 2) break;                  // image data starts: no EXIF
                int seg = p + 4;
                if (marker == 0xE1 && seg + 14 < b.length
                        && b[seg] == 'E' && b[seg + 1] == 'x' && b[seg + 2] == 'i' && b[seg + 3] == 'f') {
                    int tiff = seg + 6;
                    boolean le = b[tiff] == 'I';
                    int ifd = tiff + (int) read(b, tiff + 4, 4, le);
                    int count = (int) read(b, ifd, 2, le);
                    for (int i = 0; i < count; i++) {
                        int entry = ifd + 2 + i * 12;
                        if (entry + 12 > b.length) break;
                        if (read(b, entry, 2, le) == 0x0112) {
                            int v = (int) read(b, entry + 8, 2, le);
                            return v >= 1 && v <= 8 ? v : 1;
                        }
                    }
                    return 1;
                }
                p += 2 + len;
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    private static long read(byte[] b, int off, int n, boolean littleEndian) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            int x = b[off + i] & 0xFF;
            v = littleEndian ? v | ((long) x << (8 * i)) : (v << 8) | x;
        }
        return v;
    }
}
