package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.printing.Orientation;
import org.apache.pdfbox.printing.PDFPageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.PrintService;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.*;
import java.awt.print.PrinterJob;
import java.nio.file.Path;

/**
 * The default way of printing.
 *
 * PDFBox draws each page with Java graphics; PrinterJob passes that to Windows,
 * which uses the installed Canon driver. (Sending raw PDF bytes through
 * javax.print does not work on Windows: the JDK's Windows print service does
 * not accept PDF.)
 */
public class PdfBoxPrintStrategy implements PrintStrategy {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxPrintStrategy.class);

    @Override
    public void print(PDDocument doc, Path original, Settings s) throws Exception {
        PrintService service = PrinterDiscovery.require(s.windowsPrinterName());
        int pages = doc.getNumberOfPages();

        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(service);
        job.setJobName(s.jobName());
        // Fits each page to the paper and centres it (PDFBox shrinks pages
        // that are larger than the printable area).
        job.setPageable(new PDFPageable(doc, Orientation.AUTO, false, 0, true));

        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
        attrs.add(new JobName(s.jobName(), null));
        int copies = Math.max(1, s.copies());
        job.setCopies(copies);
        attrs.add(new Copies(copies));
        attrs.add(SheetCollate.COLLATED);          // 1,2,3, 1,2,3 - not 1,1, 2,2, 3,3
        attrs.add(s.color() ? Chromaticity.COLOR : Chromaticity.MONOCHROME);
        attrs.add(MediaSizeName.ISO_A4);
        attrs.add(Sides.ONE_SIDED);

        log.info("Order {}: sending {} page(s) x{} ({}) to \"{}\"", s.pickupCode(), pages, copies,
                s.color() ? "colour" : "B/W", service.getName());
        job.print(attrs);      // returns when Windows has accepted the document
    }
}
