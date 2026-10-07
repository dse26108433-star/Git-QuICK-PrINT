package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;

import java.nio.file.Path;

/**
 * How the prepared sheets are handed to Windows. Both implementations go
 * through the printer's own installed driver; neither is a printer driver.
 *
 *   PdfBoxPrintStrategy       default, pure Java
 *   ExternalToolPrintStrategy SumatraPDF, for the rare file PDFBox draws badly
 */
public interface PrintStrategy {

    /**
     * Sends the sheets to the Windows print queue. Returns once Windows accepted them.
     *
     * @param sheets   what to print: each page already the size of the paper, laid out as
     *                 the student saw it, order number already on the first page (PrintEngine)
     * @param original the downloaded file; the folder it is in may hold a temporary copy
     */
    void print(PDDocument sheets, Path original, PrintJob job) throws Exception;
}
