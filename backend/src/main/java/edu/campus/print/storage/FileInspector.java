package edu.campus.print.storage;

import edu.campus.print.common.Secrets;
import edu.campus.print.domain.FileType;
import edu.campus.print.printing.ImageInfo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

/**
 * Looks inside an uploaded file BEFORE the student pays:
 * what it really is (not what it is called), whether it opens, and how many
 * pages it has, because the price depends on the page count. For pictures it
 * also reads the size, the dots per inch and the phone camera's "turn me"
 * note, so the website preview and the print agree on how it lies on paper.
 * A Word file is only looked into for safety here (DocxInspector); its pages
 * are counted once the Xerox PC has turned it into a PDF.
 */
@Component
public class FileInspector {

    public record Result(FileType type, int pages, String sha256, ImageInfo image, String problemCode, String problem) {
        public boolean ok() {
            return problemCode == null;
        }

        static Result problem(String code, String message) {
            return new Result(null, 0, null, null, code, message);
        }
    }

    public Result inspect(byte[] bytes) {
        if (bytes.length == 0) {
            return Result.problem("EMPTY_FILE", "The file is empty.");
        }
        if (DocxInspector.looksLikeOldOffice(bytes)) {         // an old .doc, or a file locked with a password
            DocxInspector.Verdict v = DocxInspector.oldOffice(bytes);
            return Result.problem(v.code(), v.message());
        }
        FileType type = FileType.detect(bytes);
        if (type == null) {
            return Result.problem("NOT_SUPPORTED", DocxInspector.KINDS);
        }
        String sha = Secrets.sha256Hex(bytes);
        if (type == FileType.DOCX) {
            // A Word file: only a plain document goes on to the Xerox PC's Word. Its pages are counted there.
            DocxInspector.Verdict v = DocxInspector.inspect(bytes);
            return v.ok() ? new Result(type, 0, sha, null, null, null) : Result.problem(v.code(), v.message());
        }

        if (type == FileType.PDF) {
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                int pages = doc.getNumberOfPages();
                if (pages < 1) {
                    return Result.problem("EMPTY_FILE", "This PDF has no pages.");
                }
                return new Result(type, pages, sha, null, null, null);
            } catch (InvalidPasswordException e) {
                return Result.problem("PASSWORD_PROTECTED",
                        "This PDF is locked with a password. Save a copy without the password and try again.");
            } catch (Exception e) {
                return Result.problem("UNREADABLE", "This PDF is damaged and cannot be opened.");
            }
        }

        // PNG or JPG: one page. Make sure it really is a readable picture, and measure it.
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Result.problem("UNREADABLE", "This picture is damaged and cannot be opened.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 1 || h < 1) {
                    return Result.problem("UNREADABLE", "This picture is empty.");
                }
                double dpi = dpi(reader);
                int orientation = type == FileType.JPEG ? exifOrientation(bytes) : 1;
                return new Result(type, 1, sha, new ImageInfo(w, h, dpi, orientation), null, null);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return Result.problem("UNREADABLE", "This picture is damaged and cannot be opened.");
        }
    }

    /**
     * Dots per inch written in the picture (PNG pHYs, JPEG JFIF). Phones
     * often write 72; scanners 300. Anything missing or silly counts as 96,
     * the size a picture has on a normal screen.
     */
    static double dpi(ImageReader reader) {
        try {
            IIOMetadata meta = reader.getImageMetadata(0);
            if (meta == null || !meta.isStandardMetadataFormatSupported()) return ImageInfo.DEFAULT_DPI;
            Node root = meta.getAsTree("javax_imageio_1.0");
            Double mmPerPixel = find(root, "HorizontalPixelSize");
            if (mmPerPixel == null || mmPerPixel <= 0) return ImageInfo.DEFAULT_DPI;
            double dpi = 25.4 / mmPerPixel;
            if (dpi < 30 || dpi > 2400) return ImageInfo.DEFAULT_DPI;
            return Math.round(dpi * 100) / 100.0;
        } catch (Exception | LinkageError e) {
            return ImageInfo.DEFAULT_DPI;
        }
    }

    private static Double find(Node n, String name) {
        if (name.equals(n.getNodeName())) {
            NamedNodeMap a = n.getAttributes();
            Node v = a == null ? null : a.getNamedItem("value");
            if (v != null) {
                try {
                    return Double.parseDouble(v.getNodeValue());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            Double d = find(c, name);
            if (d != null) return d;
        }
        return null;
    }

    /**
     * Reads the EXIF orientation (1 = upright ... 8) from a JPEG. Returns 1 for
     * JPEGs without it, and for anything unexpected. Same reading as the
     * Xerox PC's (agent: ImageLayout), so both turn the picture the same way.
     */
    static int exifOrientation(byte[] b) {
        try {
            int end = Math.min(b.length, 256 * 1024);
            if (end < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return 1;
            int p = 2;
            while (p + 4 < end && (b[p] & 0xFF) == 0xFF) {
                int marker = b[p + 1] & 0xFF;
                int len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                if (marker == 0xDA || len < 2) break;                  // image data starts: no EXIF
                int seg = p + 4;
                if (marker == 0xE1 && seg + 14 < end
                        && b[seg] == 'E' && b[seg + 1] == 'x' && b[seg + 2] == 'i' && b[seg + 3] == 'f') {
                    int tiff = seg + 6;
                    boolean le = b[tiff] == 'I';
                    int ifd = tiff + (int) read(b, tiff + 4, 4, le);
                    int count = (int) read(b, ifd, 2, le);
                    for (int i = 0; i < count; i++) {
                        int entry = ifd + 2 + i * 12;
                        if (entry + 12 > end) break;
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
