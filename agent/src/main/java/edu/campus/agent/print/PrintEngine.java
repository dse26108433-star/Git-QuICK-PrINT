package edu.campus.agent.print;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Prints one order on one printer: the student's file with the pickup code
 * small on its first page (no extra sheet), and a separate cover sheet only
 * when the settings ask for one.
 */
public class PrintEngine {

    private static final Logger log = LoggerFactory.getLogger(PrintEngine.class);

    private final PrintStrategy strategy;
    private final boolean codeOnPage;
    private final int coverSheetMinSheets;

    /**
     * @param codeOnPage          print the pickup code on the first page of the order
     * @param coverSheetMinSheets also print a cover sheet for orders of at least this many
     *                            sheets (pages x copies); 0 = never. Without the code on the
     *                            page, every order gets a cover sheet, so staff can always
     *                            match paper to students.
     */
    public PrintEngine(PrintStrategy strategy, boolean codeOnPage, int coverSheetMinSheets) {
        this.strategy = strategy;
        this.codeOnPage = codeOnPage;
        this.coverSheetMinSheets = Math.max(0, coverSheetMinSheets);
    }

    /** Throws if Windows refused the document. Returns the queue name to watch. */
    public String print(Path file, PrintStrategy.Settings s) throws Exception {
        PDDocument doc = open(file, s);
        try {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                throw new IllegalStateException("The document has no pages");
            }
            // The counter can switch the label off for a while: then only the student's file prints,
            // with nothing added (no label, no cover sheet).
            boolean label = codeOnPage && s.pickupLabel();
            boolean cover = s.pickupLabel() && (!codeOnPage
                    || (coverSheetMinSheets > 0 && (long) pages * Math.max(1, s.copies()) >= coverSheetMinSheets));
            if (!s.pickupLabel()) {
                log.info("Order {}: pickup code switched off on the counter; printing the file only", s.pickupCode());
            }
            if (label) {
                try {
                    PickupCodeStamp.Result r = PickupCodeStamp.apply(doc, s);
                    log.info("Order {}: pickup code printed on the first page ({})", s.pickupCode(),
                            r == PickupCodeStamp.Result.IN_MARGIN ? "in the blank margin"
                                    : "page shrunk slightly to make room");
                } catch (Exception e) {
                    // Never lose an order over the label: start again from the
                    // untouched file and give it a cover sheet instead.
                    log.warn("Order {}: could not put the pickup code on the page ({}); printing a cover sheet instead",
                            s.pickupCode(), e.toString());
                    doc.close();
                    doc = open(file, s);
                    cover = true;
                }
            }
            if (cover) {
                printCover(s, pages);
            }
            strategy.print(doc, file, s);
            return s.jobName();
        } finally {
            doc.close();
        }
    }

    /** The document to print: a picture as one page, or the PDF with only the chosen pages. */
    private static PDDocument open(Path file, PrintStrategy.Settings s) throws Exception {
        if (s.isImage()) {
            return ImageToPdf.convert(file);
        }
        PDDocument doc = Loader.loadPDF(file.toFile());
        if (!s.allPages()) {
            try {
                int total = doc.getNumberOfPages();
                int left = PageSelection.keepOnly(doc, s.pages());
                log.info("Order {}: printing {} of {} pages ({})", s.pickupCode(), left, total, s.pages());
            } catch (RuntimeException e) {
                doc.close();
                throw e;
            }
        }
        return doc;
    }

    private static void printCover(PrintStrategy.Settings s, int pages) {
        try {
            CoverSheet.print(PrinterDiscovery.require(s.windowsPrinterName()), s, pages);
        } catch (Exception e) {
            // Not worth losing the order over: the document still prints.
            log.warn("Order {}: cover sheet did not print ({})", s.pickupCode(), e.getMessage());
        }
    }
}
