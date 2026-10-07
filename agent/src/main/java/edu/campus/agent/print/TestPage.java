package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.Color;
import java.nio.file.Path;
import java.time.LocalDateTime;

/** A one-page test PDF with a black box and a red box (to check colour vs B/W). */
public final class TestPage {

    private TestPage() {
    }

    public static void write(Path file) throws Exception {
        write(file, 1);
    }

    /** sides = 2: a second page saying "Side 2", to check two-sided printing. */
    public static void write(Path file, int sides) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            if (sides > 1) {
                PDPage back = new PDPage(PDRectangle.A4);
                doc.addPage(back);
                try (PDPageContentStream cs = new PDPageContentStream(doc, back)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 40);
                    cs.newLineAtOffset(60, 700);
                    cs.showText("Side 2 (back)");
                    cs.endText();
                }
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (sides > 1) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 40);
                    cs.newLineAtOffset(60, 450);
                    cs.showText("Side 1 (front)");
                    cs.endText();
                }
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 28);
                cs.newLineAtOffset(60, 760);
                cs.showText("XeoGo - test page");
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(0, -30);
                cs.showText("If you can read this, the Xerox PC can print. " + LocalDateTime.now().withNano(0));
                cs.endText();

                cs.setNonStrokingColor(Color.BLACK);
                cs.addRect(60, 560, 200, 120);
                cs.fill();
                cs.setNonStrokingColor(Color.RED);
                cs.addRect(300, 560, 200, 120);
                cs.fill();

                cs.beginText();
                cs.setNonStrokingColor(Color.BLACK);
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(300, 540);
                cs.showText("Red box: red in colour, grey in B/W");
                cs.endText();
            }
            doc.save(file.toFile());
        }
    }
}
