package edu.campus.agent.print;

import edu.campus.agent.net.Messages.JobSettings;

import javax.print.PrintService;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.*;

/**
 * The settings Java hands to the Windows driver for one document: copies,
 * collation, colour or black & white, the paper size, one- or two-sided and
 * quality. Anything the driver cannot take this way goes through the print
 * ticket (TicketPlan) or stops the document before printing (PrintEngine).
 */
public final class PrintAttributes {

    private PrintAttributes() {
    }

    public static PrintRequestAttributeSet of(PrintService service, PrintJob job) {
        JobSettings s = job.settings();
        PrintRequestAttributeSet a = new HashPrintRequestAttributeSet();
        a.add(new JobName(job.jobName(), null));
        a.add(new Copies(job.copies()));
        // 1-2-3, 1-2-3 (collated) is what people expect; uncollated only when asked for.
        a.add(s.collate() || job.copies() == 1 ? SheetCollate.COLLATED : SheetCollate.UNCOLLATED);
        a.add(s.color() ? Chromaticity.COLOR : Chromaticity.MONOCHROME);
        a.add(PaperSizes.mediaFor(service, job.paper().widthMm(), job.paper().heightMm())
                .orElseThrow(() -> new IllegalStateException("The printer has no " + job.paper().id() + " paper size")));
        Sides sides = sides(s);
        if (!s.twoSided() || service.isAttributeValueSupported(sides, null, null)) {
            a.add(sides);                         // otherwise it went into the print ticket
        }
        if ("HIGH".equals(s.quality()) && service.isAttributeValueSupported(PrintQuality.HIGH, null, null)) {
            a.add(PrintQuality.HIGH);
        }
        return a;
    }

    public static Sides sides(JobSettings s) {
        return switch (s.duplex() == null ? "ONE_SIDED" : s.duplex()) {
            case "LONG_EDGE" -> Sides.TWO_SIDED_LONG_EDGE;
            case "SHORT_EDGE" -> Sides.TWO_SIDED_SHORT_EDGE;
            default -> Sides.ONE_SIDED;
        };
    }
}
