package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.print.PrintService;
import javax.print.attribute.standard.Chromaticity;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Prints one document on one printer, in two steps:
 *
 *   prepare()  everything that can go wrong before paper: read the file, lay the
 *              chosen pages out on the chosen paper (Imposer), put the pickup code
 *              on the first sheet, and check the printer can do every setting.
 *              A problem here means nothing printed: the document goes back to
 *              the queue (another printer may take it).
 *   print()    hand the sheets to Windows with the document's settings.
 *
 * The student's settings are followed exactly or not at all: if the printer
 * cannot do one of them (no A3 in the driver, the driver drops stapling...),
 * the document is not printed differently, it is given back.
 */
public class PrintEngine {

    private static final Logger log = LoggerFactory.getLogger(PrintEngine.class);

    private final PrintStrategy strategy;
    private final boolean codeOnPage;
    private final int coverSheetMinSheets;
    private final PrintTicket tickets;
    private final CapabilityCache capabilities;

    /** The printer cannot do what this document needs. Nothing was printed. */
    public static class CannotPrint extends Exception {
        public CannotPrint(String message) {
            super(message);
        }
    }

    /** A document ready to print. Close it after printing (or when giving up). */
    public record Prepared(PrintJob job, Imposer.Imposed imposed, List<PrintTicket.Choice> ticket, boolean cover,
                           int sheetsPerCopy) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            imposed.close();
        }
    }

    /**
     * @param codeOnPage          print the pickup code on the first page of each document
     * @param coverSheetMinSheets also print a cover sheet for documents of at least this many
     *                            sheets (sheets x copies); 0 = never. Without the code on the
     *                            page, every document gets a cover sheet.
     * @param tickets             for settings Java cannot ask for (stapling...); null = not available
     */
    public PrintEngine(PrintStrategy strategy, boolean codeOnPage, int coverSheetMinSheets, PrintTicket tickets,
                       CapabilityCache capabilities) {
        this.strategy = strategy;
        this.codeOnPage = codeOnPage;
        this.coverSheetMinSheets = Math.max(0, coverSheetMinSheets);
        this.tickets = tickets;
        this.capabilities = capabilities == null ? new CapabilityCache() : capabilities;
    }

    public Prepared prepare(Path file, PrintJob job) throws Exception {
        PrintService service = PrinterDiscovery.find(job.windowsPrinterName())
                .orElseThrow(() -> new CannotPrint("Windows printer \"" + job.windowsPrinterName() + "\" not found"));
        List<PrintTicket.Choice> ticket = checkPrinter(service, job);

        Imposer.Imposed imposed = layOut(file, job);
        try {
            PDDocument sheets = imposed.sheets();
            int sheetsPerCopy = job.settings().twoSided()
                    ? (sheets.getNumberOfPages() + 1) / 2 : sheets.getNumberOfPages();

            boolean borderless = job.settings().marginMm() == 0;
            boolean label = codeOnPage && job.pickupLabel() && !borderless;
            boolean cover = job.pickupLabel() && !borderless && "A4".equals(job.paper().id()) && (!codeOnPage
                    || (coverSheetMinSheets > 0 && (long) sheetsPerCopy * job.copies() >= coverSheetMinSheets));
            if (!job.pickupLabel()) {
                log.info("Order {}: pickup code switched off on the counter; printing the file only", job.pickupCode());
            } else if (borderless) {
                log.info("Order {}: borderless, so no pickup code on the paper", job.pickupCode());
            }
            if (label) {
                try {
                    PickupCodeStamp.Result r = PickupCodeStamp.apply(sheets, job, sheetsPerCopy);
                    log.info("Order {}: pickup code printed on the first page ({})", job.pickupCode(),
                            r == PickupCodeStamp.Result.IN_MARGIN ? "in the blank margin"
                                    : "page shrunk slightly to make room");
                } catch (Exception e) {
                    // Never lose a document over the label: lay it out again and give it a cover sheet instead.
                    log.warn("Order {}: could not put the pickup code on the page ({}); cover sheet instead",
                            job.pickupCode(), e.toString());
                    imposed.close();
                    imposed = layOut(file, job);
                    cover = "A4".equals(job.paper().id());
                }
            }
            return new Prepared(job, imposed, ticket, cover, sheetsPerCopy);
        } catch (Exception e) {
            imposed.close();
            throw e;
        }
    }

    /** The sheets, laid out; two-sided with several copies: each copy starts on a new sheet of paper. */
    private static Imposer.Imposed layOut(Path file, PrintJob job) throws Exception {
        Imposer.Imposed imposed = Imposer.impose(file, job);
        PDDocument sheets = imposed.sheets();
        if (sheets.getNumberOfPages() == 0) {
            imposed.close();
            throw new IllegalStateException("The document has no pages");
        }
        if (job.settings().twoSided() && job.copies() > 1 && sheets.getNumberOfPages() % 2 == 1) {
            PDRectangle last = sheets.getPage(sheets.getNumberOfPages() - 1).getMediaBox();
            sheets.addPage(new PDPage(new PDRectangle(last.getWidth(), last.getHeight())));
        }
        return imposed;
    }

    /** Throws if Windows refused the document. Returns the queue name to watch. */
    public String print(Prepared p, Path original) throws Exception {
        PrintJob job = p.job();
        boolean ticketApplied = false;
        try {
            if (!p.ticket().isEmpty()) {
                tickets.apply(job.windowsPrinterName(), p.ticket());
                ticketApplied = true;
                log.info("Order {}: printer set for {}", job.pickupCode(), p.ticket());
            }
            if (p.cover()) printCover(job, p.sheetsPerCopy());
            strategy.print(p.imposed().sheets(), original, job);
            return job.jobName();
        } finally {
            if (ticketApplied) tickets.restore(job.windowsPrinterName());
        }
    }

    /** Prepare and print in one go (test prints). */
    public String print(Path file, PrintJob job) throws Exception {
        try (Prepared p = prepare(file, job)) {
            return print(p, file);
        }
    }

    /**
     * Can this printer do everything the document needs? Returns the settings
     * that go through the print ticket (checked with the driver already).
     */
    private List<PrintTicket.Choice> checkPrinter(PrintService service, PrintJob job) throws Exception {
        if (PaperSizes.mediaFor(service, job.paper().widthMm(), job.paper().heightMm()).isEmpty()) {
            throw new CannotPrint("The printer's driver has no " + job.paper().id() + " paper size");
        }
        if (job.color() && !service.isAttributeValueSupported(Chromaticity.COLOR, null, null)
                && !PrinterDiscovery.reportsColor(service)) {
            throw new CannotPrint("The printer cannot print in colour");
        }
        Optional<CapabilityDiscovery.Discovered> known = capabilities.get(job.windowsPrinterName());
        if (known.isEmpty() && needsTicket(job)) {
            capabilities.ensure(List.of(job.windowsPrinterName()));
            known = capabilities.get(job.windowsPrinterName());
        }
        if (job.settings().twoSided()
                && !service.isAttributeValueSupported(PrintAttributes.sides(job.settings()), null, null)
                && !known.map(d -> d.capabilities().path("duplexByTicket").asBoolean(false)).orElse(false)) {
            throw new CannotPrint("The printer cannot print two-sided");
        }
        List<PrintTicket.Choice> ticket = TicketPlan.choices(job.settings(), known);
        if (ticket.isEmpty()) return ticket;
        if (tickets == null) throw new CannotPrint("Printer settings like stapling are not available on this PC");
        try {
            List<String> missing = tickets.check(job.windowsPrinterName(), ticket);
            if (!missing.isEmpty()) {
                throw new CannotPrint("The printer's driver would not do: " + String.join(", ", missing));
            }
        } catch (PrintTicket.Refused e) {
            throw new CannotPrint(e.getMessage());
        }
        return ticket;
    }

    private static boolean needsTicket(PrintJob job) {
        var s = job.settings();
        return s.staple() != null || s.punch() != null || s.bind() != null || s.marginMm() == 0
                || s.mediaType() != null || "HIGH".equals(s.quality()) || s.twoSided();
    }

    private static void printCover(PrintJob job, int sheets) {
        try {
            CoverSheet.print(PrinterDiscovery.require(job.windowsPrinterName()), job, sheets);
        } catch (Exception e) {
            // Not worth losing the document over: it still prints.
            log.warn("Order {}: cover sheet did not print ({})", job.pickupCode(), e.getMessage());
        }
    }
}
