package edu.campus.agent.print;

import java.awt.geom.AffineTransform;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which way up a picture goes on paper.
 *
 * Phone photos are often stored sideways with a hidden "turn me" note (EXIF
 * orientation 1..8). Browsers obey that note, so the student sees the photo
 * upright; we obey it too. Then the student's own turn (0, 90, 180 or 270
 * degrees clockwise) is added, exactly as the website preview does it.
 *
 * The result is a transform of the unit square (how PDF draws an image) onto
 * itself: drawn through it, then stretched to the placed box, the picture
 * appears upright and turned, never distorted (the box has the turned
 * picture's own proportions).
 */
public final class ImageOrientation {

    private ImageOrientation() {
    }

    /** true = the picture looks sideways compared to how it is stored (width and height swap). */
    public static boolean swapsSides(int exifOrientation, int rotation) {
        boolean exifQuarter = exifOrientation >= 5 && exifOrientation <= 8;
        boolean userQuarter = ((rotation / 90) % 2) != 0;
        return exifQuarter ^ userQuarter;
    }

    /** Unit square -> unit square: first the camera's note, then the student's turn. */
    public static AffineTransform unitTransform(int exifOrientation, int rotation) {
        AffineTransform t = new AffineTransform();
        switch (exifOrientation) {
            case 2 -> t.preConcatenate(MIRROR_H);
            case 3 -> { t.preConcatenate(CW90); t.preConcatenate(CW90); }
            case 4 -> t.preConcatenate(MIRROR_V);
            case 5 -> { t.preConcatenate(MIRROR_H); t.preConcatenate(CW90); t.preConcatenate(CW90); t.preConcatenate(CW90); }
            case 6 -> t.preConcatenate(CW90);
            case 7 -> { t.preConcatenate(MIRROR_H); t.preConcatenate(CW90); }
            case 8 -> { t.preConcatenate(CW90); t.preConcatenate(CW90); t.preConcatenate(CW90); }
            default -> { }
        }
        int turns = (((rotation / 90) % 4) + 4) % 4;
        for (int i = 0; i < turns; i++) t.preConcatenate(CW90);
        return t;
    }

    /** A quarter turn clockwise about the centre: (u, v) -> (v, 1 - u). */
    private static final AffineTransform CW90 = new AffineTransform(0, -1, 1, 0, 0, 1);
    /** Left-right mirror: (u, v) -> (1 - u, v). */
    private static final AffineTransform MIRROR_H = new AffineTransform(-1, 0, 0, 1, 1, 0);
    /** Top-bottom mirror: (u, v) -> (u, 1 - v). */
    private static final AffineTransform MIRROR_V = new AffineTransform(1, 0, 0, -1, 0, 1);

    /**
     * Reads the EXIF orientation (1 = upright ... 8) from a JPEG. Returns 1 for
     * PNGs, for JPEGs without it, and for anything unexpected. The server reads
     * it the same way (backend FileInspector).
     */
    public static int exifOrientation(Path file) {
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
