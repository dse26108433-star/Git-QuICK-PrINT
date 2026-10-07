package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The small picture of the first sheet that the counter and the student's
 * phone show: it has to be a real JPEG of that sheet, small enough to send,
 * and grey for a black & white job.
 */
class SheetPictureTest {

    private static PDDocument sheet(PDRectangle size, boolean withPhoto) throws Exception {
        PDDocument doc = new PDDocument();
        PDPage page = new PDPage(size);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.setNonStrokingColor(Color.RED);
            cs.addRect(60, size.getHeight() - 160, 200, 100);
            cs.fill();
            cs.setNonStrokingColor(Color.BLACK);
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 28);
            cs.newLineAtOffset(60, size.getHeight() - 220);
            cs.showText("Thermodynamics notes");
            cs.endText();
            if (withPhoto) {
                // noise: the hardest thing to make small
                BufferedImage noise = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
                Random r = new Random(7);
                for (int y = 0; y < 1600; y++) for (int x = 0; x < 1600; x++) noise.setRGB(x, y, r.nextInt(0xFFFFFF));
                cs.drawImage(LosslessFactory.createFromImage(doc, noise), 0, 0, size.getWidth(), size.getHeight());
            }
        }
        doc.addPage(new PDPage(size));
        return doc;
    }

    @Test
    void theFirstSheetBecomesASmallJpeg() throws Exception {
        try (PDDocument doc = sheet(PDRectangle.A4, false)) {
            byte[] jpeg = SheetPicture.of(doc, true, 180 * 1024);
            assertThat(jpeg).isNotNull();
            assertThat(jpeg.length).isBetween(2_000, 180 * 1024);
            assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
            assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertThat(Math.max(img.getWidth(), img.getHeight())).isEqualTo(SheetPicture.LONG_SIDE_PX);
            assertThat(img.getHeight()).isGreaterThan(img.getWidth());                 // A4 upright stays upright
            // the red box is where it is on the sheet (top left), and red
            Color box = new Color(img.getRGB(img.getWidth() * 160 / 595, img.getHeight() * 110 / 842));
            assertThat(box.getRed()).isGreaterThan(180);
            assertThat(box.getGreen()).isLessThan(90);
            Color paper = new Color(img.getRGB(img.getWidth() - 20, img.getHeight() - 20));
            assertThat(paper.getRed()).isGreaterThan(235);
        }
    }

    @Test
    void aBlackAndWhiteJobGivesAGreyPicture() throws Exception {
        try (PDDocument doc = sheet(PDRectangle.A4, false)) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(SheetPicture.of(doc, false, 180 * 1024)));
            Color box = new Color(img.getRGB(img.getWidth() * 160 / 595, img.getHeight() * 110 / 842));
            assertThat(Math.abs(box.getRed() - box.getGreen())).isLessThan(12);
            assertThat(Math.abs(box.getRed() - box.getBlue())).isLessThan(12);
            assertThat(box.getRed()).isBetween(40, 200);                               // grey, not white: the box is there
        }
    }

    @Test
    void aSheetFullOfDetailIsMadeSmallerUntilItFits() throws Exception {
        try (PDDocument doc = sheet(PDRectangle.A3, true)) {
            byte[] jpeg = SheetPicture.of(doc, true, 60 * 1024);
            assertThat(jpeg).isNotNull();
            assertThat(jpeg.length).isLessThanOrEqualTo(60 * 1024);
            assertThat(ImageIO.read(new ByteArrayInputStream(jpeg))).isNotNull();
        }
    }

    @Test
    void withoutSheetsThereIsNoPictureAndNoError() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            assertThat(SheetPicture.of(doc, true, 180 * 1024)).isNull();
        }
    }
}
