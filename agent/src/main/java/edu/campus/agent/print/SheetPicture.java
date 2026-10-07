package edu.campus.agent.print;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * A small picture of the first sheet, exactly as it goes to the printer (the
 * student's pages laid out on the paper, with the order-number label).
 *
 * The counter shows it next to the order, and the student's phone shows the
 * same file: staff hand the pages to the student whose phone matches the
 * paper. That is how prints are collected now; nobody shows or types a
 * pickup code.
 *
 * Only made from the sheets already prepared for printing. A problem here
 * never stops a print: there is just no picture then.
 */
public final class SheetPicture {

    /** Longest side of the picture: easy to recognise, and small to send. */
    static final int LONG_SIDE_PX = 1000;

    private SheetPicture() {
    }

    /**
     * @param color    false = a black & white job: the picture is grey, like the paper
     * @param maxBytes the picture is made smaller until it fits
     * @return a JPEG, or null if it could not be made
     */
    public static byte[] of(PDDocument sheets, boolean color, int maxBytes) {
        try {
            if (sheets.getNumberOfPages() == 0) return null;
            PDRectangle box = sheets.getPage(0).getCropBox();
            float longest = Math.max(box.getWidth(), box.getHeight());
            if (longest <= 0) return null;
            PDFRenderer renderer = new PDFRenderer(sheets);
            renderer.setSubsamplingAllowed(true);                 // big photos are read small: little memory
            BufferedImage img = renderer.renderImage(0, LONG_SIDE_PX / longest, color ? ImageType.RGB : ImageType.GRAY);
            float quality = 0.72f;
            for (int attempt = 0; attempt < 6; attempt++) {
                byte[] jpeg = jpeg(img, quality);
                if (jpeg.length <= maxBytes) return jpeg;
                if (quality > 0.45f) {
                    quality -= 0.14f;
                } else {
                    img = smaller(img, 0.75);
                }
            }
            return null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            ByteArrayOutputStream out = new ByteArrayOutputStream(96 * 1024);
            try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(stream);
                writer.write(null, new IIOImage(img, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage smaller(BufferedImage img, double factor) {
        int w = Math.max(1, (int) Math.round(img.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(img.getHeight() * factor));
        BufferedImage out = new BufferedImage(w, h, img.getType());
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
