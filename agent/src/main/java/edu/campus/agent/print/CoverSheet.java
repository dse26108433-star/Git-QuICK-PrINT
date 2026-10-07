package edu.campus.agent.print;

import javax.print.PrintService;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.standard.Chromaticity;
import javax.print.attribute.standard.Copies;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.MediaSizeName;
import java.awt.*;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One sheet printed on top of a file, with the order's number in huge
 * letters (only when asked for: agent.yml coverSheetMinSheets, or when the
 * small label could not be put on the page). It separates thick piles of
 * paper; the counter screen shows the same number next to the order.
 *
 * Drawn with plain Java graphics, so file names in any language print fine.
 */
public final class CoverSheet {

    private CoverSheet() {
    }

    public static void print(PrintService service, PrintJob s, int sheets) throws Exception {
        PrinterJob job = PrinterJob.getPrinterJob();
        job.setPrintService(service);
        job.setJobName(s.jobName() + "-cover");
        job.setPrintable(new Sheet(s, sheets));

        PrintRequestAttributeSet attrs = new HashPrintRequestAttributeSet();
        attrs.add(new JobName(s.jobName() + "-cover", null));
        attrs.add(new Copies(1));
        attrs.add(Chromaticity.MONOCHROME);
        attrs.add(MediaSizeName.ISO_A4);
        job.print(attrs);
    }

    private record Sheet(PrintJob s, int pages) implements Printable {

        @Override
        public int print(Graphics g0, PageFormat pf, int index) {
            if (index > 0) return NO_SUCH_PAGE;
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            int x = (int) pf.getImageableX() + 36;
            int y = (int) pf.getImageableY() + 60;
            int width = (int) pf.getImageableWidth() - 72;

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            g.drawString("CAMPUS PRINT  -  ORDER", x, y);

            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 110));
            y += 120;
            g.drawString(s.pickupCode(), x, y);

            g.setStroke(new BasicStroke(3f));
            y += 30;
            g.drawLine(x, y, x + width, y);

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            y += 40;
            g.drawString("File:    " + shorten(s.fileName(), 60)
                    + (s.documentCount() > 1 ? "   (file " + s.documentNumber() + " of " + s.documentCount() + ")" : ""),
                    x, y);
            y += 26;
            g.drawString("Print:   " + (s.color() ? "COLOUR" : "Black & white") + "   x " + s.copies()
                    + (s.copies() == 1 ? " copy" : " copies") + "   (" + pages + (pages == 1 ? " sheet" : " sheets")
                    + " each, " + s.paper().id() + (s.settings().twoSided() ? ", two-sided" : "") + ")", x, y);
            if (!s.allPages()) {
                y += 26;
                g.drawString("Pages:   " + shorten(s.settings().pages(), 60) + "  (of the file)", x, y);
            }
            y += 26;
            g.drawString("Time:    " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")),
                    x, y);
            y += 26;
            g.drawString("Printer: " + s.windowsPrinterName(), x, y);

            y += 60;
            g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 14));
            g.drawString("Staff: these pages are order " + s.pickupCode() + ". The student shows the same files on their phone.", x, y);
            return PAGE_EXISTS;
        }

        private static String shorten(String v, int n) {
            if (v == null) return "";
            return v.length() <= n ? v : v.substring(0, n - 3) + "...";
        }
    }
}
