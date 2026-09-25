package edu.campus.print.storage;

import edu.campus.print.common.Secrets;
import edu.campus.print.domain.FileType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

/**
 * Looks inside an uploaded file BEFORE the student pays:
 * what it really is (not what it is called), whether it opens, and how many
 * pages it has, because the price depends on the page count.
 */
@Component
public class FileInspector {

    public record Result(FileType type, int pages, String sha256, String problemCode, String problem) {
        public boolean ok() {
            return problemCode == null;
        }

        static Result problem(String code, String message) {
            return new Result(null, 0, null, code, message);
        }
    }

    public Result inspect(byte[] bytes) {
        if (bytes.length == 0) {
            return Result.problem("EMPTY_FILE", "The file is empty.");
        }
        FileType type = FileType.detect(bytes);
        if (type == null) {
            return Result.problem("NOT_SUPPORTED", "Only PDF, PNG and JPG files can be printed.");
        }
        String sha = Secrets.sha256Hex(bytes);

        if (type == FileType.PDF) {
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                int pages = doc.getNumberOfPages();
                if (pages < 1) {
                    return Result.problem("EMPTY_FILE", "This PDF has no pages.");
                }
                return new Result(type, pages, sha, null, null);
            } catch (InvalidPasswordException e) {
                return Result.problem("PASSWORD_PROTECTED",
                        "This PDF is locked with a password. Save a copy without the password and try again.");
            } catch (Exception e) {
                return Result.problem("UNREADABLE", "This PDF is damaged and cannot be opened.");
            }
        }

        // PNG or JPG: one page. Just make sure it really is a readable picture.
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Result.problem("UNREADABLE", "This picture is damaged and cannot be opened.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                if (reader.getWidth(0) < 1 || reader.getHeight(0) < 1) {
                    return Result.problem("UNREADABLE", "This picture is empty.");
                }
            } finally {
                reader.dispose();
            }
            return new Result(type, 1, sha, null, null);
        } catch (Exception e) {
            return Result.problem("UNREADABLE", "This picture is damaged and cannot be opened.");
        }
    }
}
