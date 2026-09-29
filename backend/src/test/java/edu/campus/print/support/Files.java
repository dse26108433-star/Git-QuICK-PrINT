package edu.campus.print.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Test files made on the spot. */
public final class Files {

    private Files() {
    }

    public static byte[] pdf(int pages) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] png(int w, int h) {
        return image(w, h, "png");
    }

    /** A JPEG with an EXIF note "turn me" (orientation 1..8), as phone cameras write. */
    public static byte[] jpegWithOrientation(int w, int h, int orientation) {
        byte[] jpg = image(w, h, "jpg");
        byte[] exif = {
                (byte) 0xFF, (byte) 0xE1, 0, 34,                         // APP1, length 34
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8,                             // TIFF header, big-endian, IFD at 8
                0, 1,                                                     // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation, SHORT, 1
                0, 0, 0, 0                                                // no next IFD
        };
        byte[] out = new byte[jpg.length + exif.length];
        System.arraycopy(jpg, 0, out, 0, 2);                             // SOI
        System.arraycopy(exif, 0, out, 2, exif.length);
        System.arraycopy(jpg, 2, out, 2 + exif.length, jpg.length - 2);
        return out;
    }

    private static byte[] image(int w, int h, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
