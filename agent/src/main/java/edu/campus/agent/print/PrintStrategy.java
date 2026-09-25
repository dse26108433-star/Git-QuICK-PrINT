package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Path;

/**
 * How a document is handed to Windows. Both implementations go through the
 * Canon driver already installed on the PC; neither is a printer driver.
 *
 *   PdfBoxPrintStrategy       default, pure Java
 *   ExternalToolPrintStrategy SumatraPDF, for the rare file PDFBox draws badly
 */
public interface PrintStrategy {

    /**
     * Sends the document to the Windows print queue. Returns once Windows accepted it.
     *
     * @param doc      what to print: pictures already turned into a page, pickup code
     *                 already on the first page (see PrintEngine)
     * @param original the downloaded file; the folder it is in may hold a temporary copy
     */
    void print(PDDocument doc, Path original, Settings settings) throws Exception;

    /** The only choices a student makes: colour or B/W, how many copies, and which pages. */
    record Settings(
            String orderId,
            String windowsPrinterName,
            String fileType,      // PDF, PNG, JPEG
            boolean color,
            int copies,
            String pickupCode,
            String fileName,
            String pages,         // pages the student chose, e.g. "333-390"; null or empty = all
            boolean pickupLabel   // false = the counter switched the label off: print only the student's file
    ) {
        /** Name shown in the Windows print queue; the spooler monitor looks for it. */
        public String jobName() {
            String shortId = orderId.length() > 8 ? orderId.substring(0, 8) : orderId;
            return "CampusPrint-" + pickupCode + "-" + shortId;
        }

        public boolean isImage() {
            return "PNG".equals(fileType) || "JPEG".equals(fileType);
        }

        public boolean allPages() {
            return pages == null || pages.isBlank();
        }
    }
}
