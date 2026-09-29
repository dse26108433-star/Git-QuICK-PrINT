package edu.campus.agent.print;

import edu.campus.agent.net.Messages.ImageInfo;
import edu.campus.agent.net.Messages.JobSettings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds exactly the sheets to print: every sheet is a page of the chosen
 * paper size with the student's pages (or picture) placed on it by Layout.
 * The printer then prints each sheet at 100 %: the result is what the
 * website's preview showed, whatever the file's own page sizes were.
 *
 * Only the document in memory is built; the student's file is never changed.
 * Filled-in form fields and other printable annotations (stamps, comments
 * marked for printing) are drawn with their page, as printing a PDF does.
 */
public final class Imposer {

    private static final Logger log = LoggerFactory.getLogger(Imposer.class);

    private Imposer() {
    }

    /**
     * The sheets to print. Keep source open until the sheets are printed or
     * saved (the sheets use its fonts and pictures). Close both afterwards.
     */
    public record Imposed(PDDocument sheets, PDDocument source, List<Layout.Sheet> layout, int pagesUsed)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                sheets.close();
            } finally {
                if (source != null) source.close();
            }
        }
    }

    public static Imposed impose(Path file, PrintJob job) throws IOException {
        return job.isImage() ? picture(file, job) : pdf(file, job);
    }

    static Layout.Options options(PrintJob job) {
        JobSettings s = job.settings();
        return new Layout.Options(job.paper().widthMm() * Layout.MM, job.paper().heightMm() * Layout.MM,
                s.orientation() == null ? "AUTO" : s.orientation(), s.marginMm() * Layout.MM,
                s.scaling() == null ? "FIT" : s.scaling(), s.scalePercent() <= 0 ? 100 : s.scalePercent(),
                job.isImage() ? 1 : Math.max(1, s.pagesPerSheet()), !job.isImage() || s.center());
    }

    // ------------------------------------------------------------------ PDFs

    private static Imposed pdf(Path file, PrintJob job) throws IOException {
        PDDocument src = Loader.loadPDF(file.toFile());
        try {
            List<Integer> chosen = chosenPages(src, job.settings().pages());
            refreshFormAppearances(src);

            List<PageView> views = new ArrayList<>();
            List<Layout.Size> sizes = new ArrayList<>();
            for (int index : chosen) {
                PageView v = PageView.of(src.getPage(index));
                views.add(v);
                sizes.add(new Layout.Size(v.width(), v.height()));
            }
            List<Layout.Sheet> layout = Layout.sheets(sizes, options(job));

            PDDocument out = new PDDocument();
            Map<Integer, PDFormXObject> forms = new HashMap<>();
            boolean cellClip = job.settings().pagesPerSheet() > 1;
            for (Layout.Sheet sheet : layout) {
                PDPage page = new PDPage(new PDRectangle((float) sheet.w(), (float) sheet.h()));
                out.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                    cs.saveGraphicsState();
                    clip(cs, sheet.clip());
                    for (Layout.Cell cell : sheet.cells()) {
                        int index = chosen.get(cell.page());
                        PDPage srcPage = src.getPage(index);
                        PageView v = views.get(cell.page());
                        PDFormXObject form = forms.computeIfAbsent(index, i -> asForm(out, srcPage));

                        AffineTransform place = AffineTransform.getTranslateInstance(cell.x(), cell.y());
                        place.scale(cell.w() / v.width(), cell.h() / v.height());
                        place.concatenate(v.fromPage());

                        cs.saveGraphicsState();
                        if (cellClip) clip(cs, new Layout.Rect(cell.x(), cell.y(), cell.w(), cell.h()));
                        cs.transform(new Matrix(place));
                        cs.drawForm(form);
                        drawAnnotations(cs, srcPage);
                        cs.restoreGraphicsState();
                    }
                    cs.restoreGraphicsState();
                }
            }
            return new Imposed(out, src, layout, chosen.size());
        } catch (IOException | RuntimeException e) {
            src.close();
            throw e;
        }
    }

    /** 0-based indexes of the pages to print, in order, each once. Refuses pages that do not exist. */
    static List<Integer> chosenPages(PDDocument src, String spec) {
        int total = src.getNumberOfPages();
        if (total == 0) throw new IllegalStateException("The document has no pages");
        List<Integer> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            for (int i = 0; i < total; i++) out.add(i);
            return out;
        }
        boolean[] keep = PageSelection.parse(spec, total);
        for (int i = 0; i < total; i++) if (keep[i]) out.add(i);
        return out;
    }

    /** The page's content as a form the sheets can draw, clipped to what viewers show (the crop box). */
    private static PDFormXObject asForm(PDDocument out, PDPage page) {
        try (InputStream content = page.getContents()) {
            PDFormXObject form = new PDFormXObject(new PDStream(out, content, COSName.FLATE_DECODE));
            form.setResources(page.getResources());
            form.setBBox(page.getCropBox());
            COSBase group = page.getCOSObject().getDictionaryObject(COSName.GROUP);
            if (group != null) form.getCOSObject().setItem(COSName.GROUP, group);   // transparency, as on the page
            return form;
        } catch (IOException e) {
            throw new IllegalStateException("A page could not be read: " + e.getMessage(), e);
        }
    }

    /** Filled-in form fields: make sure they have an appearance to draw, as a PDF viewer would. */
    private static void refreshFormAppearances(PDDocument src) {
        try {
            PDAcroForm form = src.getDocumentCatalog().getAcroForm(null);
            if (form != null && form.getNeedAppearances()) {
                form.refreshAppearances();
            }
        } catch (Exception e) {
            log.debug("Form appearances not refreshed: {}", e.toString());
        }
    }

    /**
     * Printable annotations, drawn the way PDF viewers print them: the normal
     * appearance fitted into the annotation's rectangle (PDF 32000, 12.5.5).
     */
    private static void drawAnnotations(PDPageContentStream cs, PDPage page) throws IOException {
        for (PDAnnotation a : page.getAnnotations()) {
            if (a instanceof PDAnnotationPopup || a.isHidden() || !a.isPrinted()) continue;
            PDAppearanceStream ap = a.getNormalAppearanceStream();
            PDRectangle rect = a.getRectangle();
            if (ap == null || rect == null || ap.getBBox() == null) continue;
            Rectangle2D box = ap.getBBox().transform(ap.getMatrix()).getBounds2D();
            if (box.getWidth() <= 0 || box.getHeight() <= 0) continue;
            AffineTransform t = AffineTransform.getTranslateInstance(rect.getLowerLeftX(), rect.getLowerLeftY());
            t.scale(rect.getWidth() / box.getWidth(), rect.getHeight() / box.getHeight());
            t.translate(-box.getX(), -box.getY());
            cs.saveGraphicsState();
            cs.transform(new Matrix(t));
            cs.drawForm(ap);
            cs.restoreGraphicsState();
        }
    }

    // ------------------------------------------------------------------ pictures

    private static Imposed picture(Path file, PrintJob job) throws IOException {
        PDDocument out = new PDDocument();
        try {
            PDImageXObject img = PDImageXObject.createFromFileByContent(file.toFile(), out);
            ImageInfo info = job.image();
            int exif = info != null ? info.exifOrientation() : ImageOrientation.exifOrientation(file);
            if (info != null && (info.widthPx() != img.getWidth() || info.heightPx() != img.getHeight())) {
                throw new IllegalStateException("The picture is " + img.getWidth() + " x " + img.getHeight()
                        + " pixels, the server measured " + info.widthPx() + " x " + info.heightPx());
            }
            double dpi = info != null && info.dpi() > 0 ? info.dpi() : 96;
            int rotation = job.settings().rotation();
            boolean swap = ImageOrientation.swapsSides(exif, rotation);
            double w = (swap ? img.getHeight() : img.getWidth()) / dpi * 72;
            double h = (swap ? img.getWidth() : img.getHeight()) / dpi * 72;

            List<Layout.Sheet> layout = Layout.sheets(List.of(new Layout.Size(w, h)), options(job));
            Layout.Sheet sheet = layout.get(0);
            Layout.Cell cell = sheet.cells().get(0);
            PDPage page = new PDPage(new PDRectangle((float) sheet.w(), (float) sheet.h()));
            out.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                cs.saveGraphicsState();
                clip(cs, sheet.clip());
                AffineTransform t = AffineTransform.getTranslateInstance(cell.x(), cell.y());
                t.scale(cell.w(), cell.h());
                t.concatenate(ImageOrientation.unitTransform(exif, rotation));
                cs.transform(new Matrix(t));
                cs.drawImage(img, 0, 0, 1, 1);
                cs.restoreGraphicsState();
            }
            return new Imposed(out, null, layout, 1);
        } catch (IOException e) {
            out.close();
            throw e;
        } catch (RuntimeException e) {
            out.close();
            throw new IllegalStateException("The picture could not be read: " + e.getMessage(), e);
        }
    }

    private static void clip(PDPageContentStream cs, Layout.Rect r) throws IOException {
        if (r == null) return;
        cs.addRect((float) r.x(), (float) r.y(), (float) r.w(), (float) r.h());
        cs.clip();
    }
}
