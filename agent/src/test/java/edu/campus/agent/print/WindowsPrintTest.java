package edu.campus.agent.print;

import edu.campus.agent.net.Messages.JobSettings;
import edu.campus.agent.net.Messages.Paper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Prints through the real Windows print path (Java -> the Windows spooler ->
 * a driver) on the test printer "CampusPrint Test BW": Microsoft Print to PDF
 * with a file port, so the "paper" is C:\CampusPrintTest\bw-output.pdf and can
 * be measured. Skipped on PCs without that printer.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsPrintTest {

    private static final String PRINTER = "CampusPrint Test BW";
    private static final Path OUTPUT = Path.of("C:/CampusPrintTest/bw-output.pdf");

    @TempDir
    Path dir;

    @BeforeEach
    void needsTheTestPrinter() {
        Assumptions.assumeTrue(PrinterDiscovery.find(PRINTER).isPresent(), "test printer not installed");
    }

    @Test
    void actualSizeLandsOnTheSameSpotOfThePaper() throws Exception {
        // An A4 page with a 20 pt square 100 pt from the left and 200 pt from the bottom.
        Path pdf = dir.resolve("marker.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(PDRectangle.A4);
            d.addPage(p);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.setNonStrokingColor(Color.BLACK);
                cs.addRect(100, 200, 20, 20);
                cs.fill();
            }
            d.save(pdf.toFile());
        }
        // (Margins do not move an actual-size page that is as big as the paper; 0 would mean borderless,
        // which this printer cannot do: it would be refused, see refusesWhatThePrinterCannotDo.)
        JobSettings s = new JobSettings(1, false, null, "ONE_SIDED", "A4", "PORTRAIT", "ACTUAL", 100, 1, 5, 0, true,
                true, null, null, null, null, "STANDARD");
        try (PDDocument out = printAndRead(pdf, job(s, "A4", 210, 297, false))) {
            assertThat(out.getNumberOfPages()).isEqualTo(1);
            PDRectangle box = out.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isCloseTo(595.3f, within(1.5f));
            assertThat(box.getHeight()).isCloseTo(841.9f, within(1.5f));
            BufferedImage img = new PDFRenderer(out).renderImage(0, 1f, ImageType.GRAY);
            // Exactly where it was on the student's page: not shrunk, not moved by the printer's margins.
            assertThat(gray(img, 110, 210, box)).isLessThan(100);
            assertThat(gray(img, 96, 210, box)).isGreaterThan(200);
            assertThat(gray(img, 124, 210, box)).isGreaterThan(200);
            assertThat(gray(img, 110, 196, box)).isGreaterThan(200);
            assertThat(gray(img, 110, 224, box)).isGreaterThan(200);
        }
    }

    @Test
    void a3TwoPerSheetWithCopiesAndThePickupCode() throws Exception {
        Path pdf = dir.resolve("three.pdf");
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < 3; i++) d.addPage(new PDPage(PDRectangle.A4));
            d.save(pdf.toFile());
        }
        JobSettings s = new JobSettings(2, false, null, "ONE_SIDED", "A3", "AUTO", "FIT", 100, 2, 5, 0, true,
                true, null, null, null, null, "STANDARD");
        try (PDDocument out = printAndRead(pdf, job(s, "A3", 297, 420, true))) {
            // 3 pages, 2 per sheet = 2 sheets; this driver makes copies by repeating: 2 copies = 4 sides.
            assertThat(out.getNumberOfPages()).isEqualTo(4);
            PDRectangle box = out.getPage(0).getMediaBox();
            float w = Math.max(box.getWidth(), box.getHeight()), h = Math.min(box.getWidth(), box.getHeight());
            assertThat(w).isCloseTo(1190.6f, within(2f));              // A3
            assertThat(h).isCloseTo(841.9f, within(2f));
            // The label "Pickup TEST1 · 2 sheets × 2" (drawn as shapes by this driver, so looked at, not read):
            // bottom-right of the landscape sheet, which is the top-right of the portrait paper.
            BufferedImage img = new PDFRenderer(out).renderImage(0, 1f, ImageType.GRAY);
            boolean portraitPaper = box.getWidth() < box.getHeight();
            int dark = 0;
            for (int x = 0; x < img.getWidth(); x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    boolean inCorner = portraitPaper
                            ? x > img.getWidth() - 40 && x < img.getWidth() - 10 && y > 20 && y < 200
                            : x > img.getWidth() - 200 && x < img.getWidth() - 20 && y > img.getHeight() - 40
                              && y < img.getHeight() - 10;
                    if (inCorner && img.getRaster().getSample(x, y, 0) < 128) dark++;
                }
            }
            assertThat(dark).as("ink of the pickup label").isGreaterThan(50);
            // and nothing anywhere else on these blank pages
            assertThat(gray(img, box.getWidth() / 2, box.getHeight() / 2, box)).isGreaterThan(250);
        }
    }

    @Test
    void refusesWhatThePrinterCannotDo() throws Exception {
        Path pdf = dir.resolve("one.pdf");
        try (PDDocument d = new PDDocument()) {
            d.addPage(new PDPage(PDRectangle.A4));
            d.save(pdf.toFile());
        }
        PrintEngine engine = new PrintEngine(new PdfBoxPrintStrategy(), true, 0, new PrintTicket(dir),
                new CapabilityCache());
        JobSettings borderless = new JobSettings(1, false, null, "ONE_SIDED", "A4", "AUTO", "FIT", 100, 1, 0, 0, true,
                true, null, null, null, null, "STANDARD");
        JobSettings twoSided = new JobSettings(1, false, null, "LONG_EDGE", "A4", "AUTO", "FIT", 100, 1, 5, 0, true,
                true, null, null, null, null, "STANDARD");
        JobSettings photo = new JobSettings(1, false, null, "ONE_SIDED", "PHOTO_4X6", "AUTO", "FIT", 100, 1, 5, 0,
                true, true, null, null, null, null, "STANDARD");
        for (JobSettings s : List.of(borderless, twoSided, photo)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.prepare(pdf, job(s, s.paperSize(),
                    "PHOTO_4X6".equals(s.paperSize()) ? 101.6 : 210, "PHOTO_4X6".equals(s.paperSize()) ? 152.4 : 297,
                    true))).isInstanceOf(PrintEngine.CannotPrint.class);
        }
    }

    @Test
    void aPrintTicketIsSetAndPutBack() throws Exception {
        PrintTicket tickets = new PrintTicket(dir);
        String before = userOrientation();
        String other = "Landscape".equals(before) ? "psk:Portrait" : "psk:Landscape";
        List<PrintTicket.Choice> choice = List.of(new PrintTicket.Choice("psk:PageOrientation", other));
        assertThat(tickets.check(PRINTER, choice)).isEmpty();
        tickets.apply(PRINTER, choice);
        try {
            assertThat("psk:" + userOrientation()).isEqualTo(other);
        } finally {
            tickets.restore(PRINTER);
        }
        assertThat(userOrientation()).isEqualTo(before);
        // The driver refuses what the printer does not have: nothing would be printed.
        assertThat(tickets.check(PRINTER, List.of(new PrintTicket.Choice("psk:JobStapleAllDocuments",
                "psk:StapleTopLeft")))).containsExactly("psk:StapleTopLeft");
    }

    // ------------------------------------------------------------------ helpers

    private static PrintJob job(JobSettings s, String paper, double w, double h, boolean label) {
        return new PrintJob(UUID.randomUUID().toString(), PRINTER, "PDF", "TEST1", "test.pdf", 1, 1, s,
                new Paper(paper, w, h), null, label);
    }

    private PDDocument printAndRead(Path file, PrintJob job) throws Exception {
        long before = Files.exists(OUTPUT) ? Files.getLastModifiedTime(OUTPUT).toMillis() : 0;
        PrintEngine engine = new PrintEngine(new PdfBoxPrintStrategy(), true, 0, new PrintTicket(dir),
                new CapabilityCache());
        String queue = engine.print(file, job);
        SpoolerMonitor.Result r = new SpoolerMonitor(true, Duration.ofSeconds(1)).awaitCompletion(PRINTER, queue, a -> { });
        assertThat(r.outcome()).isNotEqualTo(SpoolerMonitor.Outcome.REMOVED);
        long until = System.currentTimeMillis() + 60_000;
        long size = -1;
        while (System.currentTimeMillis() < until) {
            if (Files.exists(OUTPUT) && Files.getLastModifiedTime(OUTPUT).toMillis() > before) {
                long now = Files.size(OUTPUT);
                if (now > 0 && now == size) break;
                size = now;
            }
            Thread.sleep(500);
        }
        byte[] bytes = Files.readAllBytes(OUTPUT);
        return Loader.loadPDF(bytes);
    }

    private static int gray(BufferedImage img, double x, double y, PDRectangle box) {
        int px = (int) Math.round(x), py = (int) Math.round(box.getHeight() - y);
        return img.getRaster().getSample(px, py, 0);
    }

    /** This Windows user's orientation setting for the test printer. */
    private static String userOrientation() throws Exception {
        String ps = "Add-Type -AssemblyName System.Printing; $q = (New-Object System.Printing.LocalPrintServer)"
                + ".GetPrintQueue('" + PRINTER + "'); $t = $q.UserPrintTicket; if ($t -eq $null) { $t = $q.DefaultPrintTicket }; "
                + "[string]$t.PageOrientation";
        Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Sta", "-Command", ps)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(60, TimeUnit.SECONDS);
        return out.lines().reduce((a, b) -> b).orElse("").trim();
    }
}
