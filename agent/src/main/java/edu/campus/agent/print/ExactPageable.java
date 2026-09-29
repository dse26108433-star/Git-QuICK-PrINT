package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.print.PageFormat;
import java.awt.print.Pageable;
import java.awt.print.Paper;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterIOException;
import java.io.IOException;

/**
 * Prints each sheet (made by Imposer, already the size of the paper) at
 * exactly 100 %, with its corner on the paper's corner. No shrinking, no
 * shifting by the printer's margins: the student's layout, margins and
 * "Actual size" come out as the preview showed them. (PDFBox's own printing
 * shrinks every page to the printer's printable area, which would change
 * the size by a few percent and move it.)
 *
 * What falls into the few millimetres a printer cannot reach is simply not
 * printed, as with any printer; the website warns about margins that small.
 */
public final class ExactPageable implements Pageable {

    private final PDDocument doc;
    private final PDFRenderer renderer;

    public ExactPageable(PDDocument doc) {
        this.doc = doc;
        this.renderer = new PDFRenderer(doc);
        this.renderer.setSubsamplingAllowed(false);
        RenderingHints hints = new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        hints.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        hints.put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        this.renderer.setRenderingHints(hints);
    }

    @Override
    public int getNumberOfPages() {
        return doc.getNumberOfPages();
    }

    /**
     * Paper the size of the sheet, all of it "imageable". Java works with
     * portrait paper; a landscape sheet is portrait paper turned (PDFBOX-2922).
     */
    @Override
    public PageFormat getPageFormat(int pageIndex) {
        PDRectangle box = doc.getPage(pageIndex).getMediaBox();
        double w = box.getWidth(), h = box.getHeight();
        boolean landscape = w > h;
        Paper paper = new Paper();
        double pw = landscape ? h : w, ph = landscape ? w : h;
        paper.setSize(pw, ph);
        paper.setImageableArea(0, 0, pw, ph);
        PageFormat f = new PageFormat();
        f.setPaper(paper);
        f.setOrientation(landscape ? PageFormat.LANDSCAPE : PageFormat.PORTRAIT);
        return f;
    }

    @Override
    public Printable getPrintable(int pageIndex) {
        return this::print;
    }

    private int print(Graphics graphics, PageFormat format, int pageIndex) throws PrinterException {
        if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return Printable.NO_SUCH_PAGE;
        Graphics2D g = (Graphics2D) graphics;
        try {
            // Java's printing origin is the corner of the paper; the sheet has no rotation or offset.
            PDPage page = doc.getPage(pageIndex);
            if (page.getRotation() != 0) throw new IllegalStateException("A sheet must not be rotated");
            g.setBackground(Color.WHITE);
            renderer.renderPageToGraphics(pageIndex, g, 1f, 1f, RenderDestination.PRINT);
            return Printable.PAGE_EXISTS;
        } catch (IOException e) {
            throw new PrinterIOException(e);
        }
    }
}
