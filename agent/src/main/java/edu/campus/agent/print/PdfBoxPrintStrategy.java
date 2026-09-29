package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.PrintService;
import javax.print.attribute.PrintRequestAttributeSet;
import java.awt.print.PrinterJob;
import java.nio.file.Path;

/**
 * The default way of printing.
 *
 * PDFBox draws each sheet with Java graphics at exactly 100 % (ExactPageable);
 * PrinterJob passes that to Windows, which uses the printer's installed
 * driver with the document's settings (PrintAttributes). (Sending raw PDF
 * bytes through javax.print does not work on Windows: the JDK's Windows print
 * service does not accept PDF.)
 */
public class PdfBoxPrintStrategy implements PrintStrategy {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxPrintStrategy.class);

    @Override
    public void print(PDDocument sheets, Path original, PrintJob job) throws Exception {
        PrintService service = PrinterDiscovery.require(job.windowsPrinterName());
        PrinterJob pj = PrinterJob.getPrinterJob();
        pj.setPrintService(service);
        pj.setJobName(job.jobName());
        pj.setPageable(new ExactPageable(sheets));
        PrintRequestAttributeSet attrs = PrintAttributes.of(service, job);
        pj.setCopies(job.copies());

        log.info("Order {} file {}/{}: sending {} sheet side(s) x{} ({}, {}, {}) to \"{}\"", job.pickupCode(),
                job.documentNumber(), job.documentCount(), sheets.getNumberOfPages(), job.copies(),
                job.color() ? "colour" : "B/W", job.paper().id(),
                job.settings().twoSided() ? "two-sided" : "one-sided", service.getName());
        pj.print(attrs);      // returns when Windows has accepted the document
    }
}
