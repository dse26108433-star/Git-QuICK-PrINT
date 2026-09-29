package edu.campus.agent.print;

import edu.campus.agent.net.Messages.ImageInfo;
import edu.campus.agent.net.Messages.JobSettings;
import edu.campus.agent.net.Messages.Paper;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquare;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Lays real files out and looks at the result: where does the ink land?
 * Positions are checked against Layout, i.e. against what the website's
 * preview shows.
 */
class ImposerTest {

    private static final float A4W = PDRectangle.A4.getWidth(), A4H = PDRectangle.A4.getHeight();

    @TempDir
    Path dir;

    // ------------------------------------------------------------------ PDFs

    @Test
    void aPageLandsExactlyWhereTheLayoutSays() throws Exception {
        Path pdf = pdf(page(A4W, A4H, 0, 0f, A4H - 20, 20));      // black square in the top-left corner
        JobSettings s = settings("A3", "FIT", 100, 1, 10, "AUTO", null);
        try (Imposer.Imposed out = Imposer.impose(pdf, job("PDF", s, "A3", 297, 420, null))) {
            PDPage sheet = out.sheets().getPage(0);
            assertThat(sheet.getMediaBox().getWidth()).isCloseTo(297 * (float) Layout.MM, within(0.01f));
            Layout.Cell c = out.layout().get(0).cells().get(0);
            double sc = c.w() / A4W;
            BufferedImage img = render(out.sheets(), 0);
            // the square's centre, and a point just outside the page (in the margin)
            assertThat(ink(img, c.x() + 10 * sc, c.y() + (A4H - 10) * sc, sheet)).isTrue();
            assertThat(ink(img, c.x() + 40 * sc, c.y() + (A4H - 40) * sc, sheet)).isFalse();
            assertThat(ink(img, 5 * Layout.MM, sheet.getMediaBox().getHeight() - 5 * Layout.MM, sheet)).isFalse();
        }
    }

    @Test
    void rotatedPagesComeOutTheWayViewersShowThem() throws Exception {
        // A portrait page turned 90 degrees by /Rotate: viewers show it landscape, and the page's own
        // top-left corner appears top-right.
        Path pdf = pdf(page(A4W, A4H, 90, 0f, A4H - 20, 20));
        JobSettings s = settings("A4", "FIT", 100, 1, 5, "AUTO", null);
        try (Imposer.Imposed out = Imposer.impose(pdf, job("PDF", s, "A4", 210, 297, null))) {
            PDPage sheet = out.sheets().getPage(0);
            assertThat(sheet.getMediaBox().getWidth()).isGreaterThan(sheet.getMediaBox().getHeight());   // landscape
            Layout.Cell c = out.layout().get(0).cells().get(0);
            BufferedImage img = render(out.sheets(), 0);
            double sc = c.w() / A4H;                                   // seen width = the page's height
            assertThat(ink(img, c.x() + c.w() - 10 * sc, c.y() + c.h() - 10 * sc, sheet)).as("top-right").isTrue();
            assertThat(ink(img, c.x() + 10 * sc, c.y() + c.h() - 10 * sc, sheet)).as("top-left").isFalse();
        }
    }

    @Test
    void aCropBoxIsWhatPrints() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(new PDRectangle(A4W, A4H));
            p.setCropBox(new PDRectangle(100, 100, 300, 400));
            d.addPage(p);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.setNonStrokingColor(Color.BLACK);
                cs.addRect(0, 0, 90, 90);            // outside the crop box: must not print
                cs.addRect(100, 480, 20, 20);        // the crop box's top-left corner
                cs.fill();
            }
            Path pdf = dir.resolve("crop.pdf");
            d.save(pdf.toFile());
            JobSettings s = settings("A4", "ACTUAL", 100, 1, 5, "PORTRAIT", null);
            try (Imposer.Imposed out = Imposer.impose(pdf, job("PDF", s, "A4", 210, 297, null))) {
                Layout.Cell c = out.layout().get(0).cells().get(0);
                assertThat(c.w()).isCloseTo(300, within(0.01));
                PDPage sheet = out.sheets().getPage(0);
                BufferedImage img = render(out.sheets(), 0);
                assertThat(ink(img, c.x() + 10, c.y() + c.h() - 10, sheet)).isTrue();
                assertThat(ink(img, c.x() - 40, c.y() - 40, sheet)).isFalse();
            }
        }
    }

    @Test
    void severalPagesPerSheetGoInReadingOrder() throws Exception {
        Path pdf = pdf(gray(0.1f), gray(0.35f), gray(0.6f), gray(0.85f));
        JobSettings s = settings("A4", "FIT", 100, 4, 5, "AUTO", null);
        try (Imposer.Imposed out = Imposer.impose(pdf, job("PDF", s, "A4", 210, 297, null))) {
            assertThat(out.sheets().getNumberOfPages()).isEqualTo(1);
            PDPage sheet = out.sheets().getPage(0);
            BufferedImage img = render(out.sheets(), 0);
            double w = sheet.getMediaBox().getWidth(), h = sheet.getMediaBox().getHeight();
            int tl = level(img, w * 0.25, h * 0.75, sheet), tr = level(img, w * 0.75, h * 0.75, sheet);
            int bl = level(img, w * 0.25, h * 0.25, sheet), br = level(img, w * 0.75, h * 0.25, sheet);
            assertThat(tl).isLessThan(tr);
            assertThat(tr).isLessThan(bl);
            assertThat(bl).isLessThan(br);
        }
    }

    @Test
    void onlyTheChosenPagesPrintInOrder() throws Exception {
        Path pdf = pdf(gray(0.1f), gray(0.3f), gray(0.5f), gray(0.7f), gray(0.9f));
        JobSettings s = settings("A4", "FIT", 100, 1, 5, "AUTO", "4,2");
        try (Imposer.Imposed out = Imposer.impose(pdf, job("PDF", s, "A4", 210, 297, null))) {
            assertThat(out.sheets().getNumberOfPages()).isEqualTo(2);
            assertThat(out.pagesUsed()).isEqualTo(2);
            int first = level(render(out.sheets(), 0), 300, 420, out.sheets().getPage(0));
            int second = level(render(out.sheets(), 1), 300, 420, out.sheets().getPage(1));
            assertThat(first).isCloseTo((int) (0.3 * 255), within(4));        // page 2
            assertThat(second).isCloseTo((int) (0.7 * 255), within(4));       // page 4
        }
    }

    @Test
    void printableAnnotationsPrintAndOthersDoNot() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(PDRectangle.A4);
            d.addPage(p);
            p.getAnnotations().add(square(d, 100, 600, true));
            p.getAnnotations().add(square(d, 300, 600, false));
            Path pdf = dir.resolve("annot.pdf");
            d.save(pdf.toFile());
            JobSettings s = settings("A4", "ACTUAL", 100, 1, 5, "AUTO", null);
            try (Imposer.Imposed out = Imposer.impose(pdf, job("PDF", s, "A4", 210, 297, null))) {
                PDPage sheet = out.sheets().getPage(0);
                BufferedImage img = render(out.sheets(), 0);
                assertThat(ink(img, 115, 615, sheet)).as("printable").isTrue();
                assertThat(ink(img, 315, 615, sheet)).as("screen only").isFalse();
            }
        }
    }

    // ------------------------------------------------------------------ pictures

    @Test
    void phonePhotosComeOutUpright() throws Exception {
        // Stored landscape with the left half black; EXIF 6 = "turn 90 degrees clockwise":
        // upright it is portrait with the black half on top.
        Path jpg = picture(200, 100, 6);
        JobSettings s = settings("A4", "FIT", 100, 1, 5, "AUTO", null);
        try (Imposer.Imposed out = Imposer.impose(jpg, job("JPEG", s, "A4", 210, 297, new ImageInfo(200, 100, 96, 6)))) {
            PDPage sheet = out.sheets().getPage(0);
            assertThat(sheet.getMediaBox().getHeight()).isGreaterThan(sheet.getMediaBox().getWidth());   // portrait
            Layout.Cell c = out.layout().get(0).cells().get(0);
            assertThat(c.w() / c.h()).isCloseTo(0.5, within(1e-6));             // never stretched
            BufferedImage img = render(out.sheets(), 0);
            assertThat(ink(img, c.x() + c.w() / 2, c.y() + c.h() * 0.75, sheet)).as("top").isTrue();
            assertThat(ink(img, c.x() + c.w() / 2, c.y() + c.h() * 0.25, sheet)).as("bottom").isFalse();
        }
    }

    @Test
    void theStudentsTurnIsAddedToTheCameras() throws Exception {
        Path png = picture(200, 100, 1);                           // left half black, upright
        for (int[] t : new int[][]{{90, 1}, {270, 0}, {180, -1}}) {
            JobSettings s = settings("A4", "FIT", 100, 1, 5, "AUTO", null, t[0]);
            try (Imposer.Imposed out = Imposer.impose(png, job("PNG", s, "A4", 210, 297, new ImageInfo(200, 100, 96, 1)))) {
                PDPage sheet = out.sheets().getPage(0);
                Layout.Cell c = out.layout().get(0).cells().get(0);
                BufferedImage img = render(out.sheets(), 0);
                if (t[1] >= 0) {
                    // 90: left side goes to the top; 270: to the bottom
                    boolean top = ink(img, c.x() + c.w() / 2, c.y() + c.h() * 0.75, sheet);
                    assertThat(top).as("turn " + t[0]).isEqualTo(t[1] == 1);
                } else {
                    // 180: left side goes to the right
                    assertThat(ink(img, c.x() + c.w() * 0.75, c.y() + c.h() / 2, sheet)).as("turn 180").isTrue();
                }
            }
        }
    }

    @Test
    void fillCoversThePageAndTrimsOnlyTheEdges() throws Exception {
        Path png = picture(300, 100, 1);                           // very wide
        JobSettings s = settings("PHOTO_4X6", "FILL", 100, 1, 0, "PORTRAIT", null);
        try (Imposer.Imposed out = Imposer.impose(png, job("PNG", s, "PHOTO_4X6", 101.6, 152.4,
                new ImageInfo(300, 100, 96, 1)))) {
            Layout.Sheet sheet = out.layout().get(0);
            Layout.Cell c = sheet.cells().get(0);
            assertThat(c.h()).isCloseTo(sheet.h(), within(0.01));   // covers the height...
            assertThat(c.w()).isGreaterThan(sheet.w());             // ...and sticks out at the sides (cut)
            assertThat(c.w() / c.h()).isCloseTo(3.0, within(1e-6));
            assertThat(sheet.clip()).isNotNull();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JobSettings settings(String paper, String scaling, int percent, int perSheet, int margin,
                                        String orientation, String pages) {
        return settings(paper, scaling, percent, perSheet, margin, orientation, pages, 0);
    }

    private static JobSettings settings(String paper, String scaling, int percent, int perSheet, int margin,
                                        String orientation, String pages, int rotation) {
        return new JobSettings(1, false, pages, "ONE_SIDED", paper, orientation, scaling, percent, perSheet, margin,
                rotation, true, true, null, null, null, null, "STANDARD");
    }

    private static PrintJob job(String type, JobSettings s, String paper, double wmm, double hmm, ImageInfo image) {
        return new PrintJob("job-1", "Test printer", type, "K7M4X", "f", 1, 1, s, new Paper(paper, wmm, hmm), image, true);
    }

    /** A page description: size, /Rotate, and a black square (x, y, size) in the page's own coordinates. */
    private record PageSpec(float w, float h, int rotate, float sx, float sy, float size, float gray) {}

    private static PageSpec page(float w, float h, int rotate, float sx, float sy, float size) {
        return new PageSpec(w, h, rotate, sx, sy, size, -1);
    }

    private static PageSpec gray(float g) {
        return new PageSpec(A4W, A4H, 0, 0, 0, 0, g);
    }

    private Path pdf(PageSpec... pages) throws Exception {
        try (PDDocument d = new PDDocument()) {
            for (PageSpec ps : pages) {
                PDPage p = new PDPage(new PDRectangle(ps.w(), ps.h()));
                p.setRotation(ps.rotate());
                d.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                    if (ps.gray() >= 0) {
                        cs.setNonStrokingColor(new Color(ps.gray(), ps.gray(), ps.gray()));
                        cs.addRect(0, 0, ps.w(), ps.h());
                    } else {
                        cs.setNonStrokingColor(Color.BLACK);
                        cs.addRect(ps.sx(), ps.sy(), ps.size(), ps.size());
                    }
                    cs.fill();
                }
            }
            Path f = Files.createTempFile(dir, "doc", ".pdf");
            d.save(f.toFile());
            return f;
        }
    }

    private static PDAnnotationSquare square(PDDocument d, float x, float y, boolean printed) throws Exception {
        PDAnnotationSquare a = new PDAnnotationSquare();
        a.setRectangle(new PDRectangle(x, y, 30, 30));
        a.setPrinted(printed);
        PDAppearanceStream ap = new PDAppearanceStream(d);
        ap.setBBox(new PDRectangle(0, 0, 30, 30));
        try (OutputStream os = ap.getContentStream().createOutputStream()) {
            os.write("0 0 0 rg 0 0 30 30 re f".getBytes());
        }
        PDAppearanceDictionary dict = new PDAppearanceDictionary();
        dict.setNormalAppearance(ap);
        a.setAppearance(dict);
        COSArray color = new COSArray();
        color.add(new COSFloat(0f));
        a.getCOSObject().setItem("C", color);
        return a;
    }

    /** A picture whose left half is black, with an EXIF orientation note when it is a JPEG. */
    private Path picture(int w, int h, int exif) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w / 2, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (exif == 1) {
            ImageIO.write(img, "png", out);
            Path f = Files.createTempFile(dir, "pic", ".png");
            Files.write(f, out.toByteArray());
            return f;
        }
        ImageIO.write(img, "jpg", out);
        byte[] jpg = out.toByteArray();
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) exif, 0, 0, 0, 0, 0, 0};
        byte[] all = new byte[jpg.length + app1.length];
        System.arraycopy(jpg, 0, all, 0, 2);
        System.arraycopy(app1, 0, all, 2, app1.length);
        System.arraycopy(jpg, 2, all, 2 + app1.length, jpg.length - 2);
        Path f = Files.createTempFile(dir, "pic", ".jpg");
        Files.write(f, all);
        assertThat(ImageOrientation.exifOrientation(f)).isEqualTo(exif);
        return f;
    }

    private static BufferedImage render(PDDocument d, int page) throws Exception {
        return new PDFRenderer(d).renderImage(page, 1f, ImageType.GRAY);      // 1 pixel per point
    }

    /** Gray level (0 black .. 255 white) at a point given in PDF coordinates (y up from the bottom). */
    private static int level(BufferedImage img, double x, double y, PDPage sheet) {
        int px = (int) Math.round(x);
        int py = (int) Math.round(sheet.getMediaBox().getHeight() - y);
        px = Math.max(0, Math.min(img.getWidth() - 1, px));
        py = Math.max(0, Math.min(img.getHeight() - 1, py));
        return img.getRaster().getSample(px, py, 0);
    }

    private static boolean ink(BufferedImage img, double x, double y, PDPage sheet) {
        return level(img, x, y, sheet) < 128;
    }
}
