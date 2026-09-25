import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the Campus Print icon (a sheet of paper with a green "ready" lamp,
 * same as the logo in station-ui/index.html) at every size Windows uses.
 *
 *   java MakeIcons.java        (from the agent/packaging folder)
 *
 * Writes campus-print.ico (installer, program, shortcuts) and the PNGs the
 * app window and tray icon use.
 */
public class MakeIcons {

    public static void main(String[] args) throws Exception {
        int[] sizes = {16, 20, 24, 32, 40, 48, 64, 128, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int s : sizes) pngs.add(png(draw(s)));
        writeIco(new File("campus-print.ico"), sizes, pngs);
        ImageIO.write(draw(64), "png", new File("../src/main/resources/station-ui/icon-64.png"));
        ImageIO.write(draw(256), "png", new File("icon-256.png"));
        // Installer pictures (Inno Setup): the tall one on the first and last page, the small one top-right.
        ImageIO.write(banner(164, 314), "bmp", new File("wizard-100.bmp"));
        ImageIO.write(banner(328, 628), "bmp", new File("wizard-200.bmp"));
        ImageIO.write(small(55), "bmp", new File("wizard-small-100.bmp"));
        ImageIO.write(small(110), "bmp", new File("wizard-small-200.bmp"));
        // Student website: browser tab, home-screen app icons (Android "maskable" = full square), iPhone icon.
        String web = "../../web/";
        ImageIO.write(draw(192), "png", new File(web + "icon-192.png"));
        ImageIO.write(draw(512), "png", new File(web + "icon-512.png"));
        ImageIO.write(onNavy(512, 360), "png", new File(web + "icon-maskable-512.png"));
        ImageIO.write(onNavy(180, 150), "png", new File(web + "apple-touch-icon.png"));
        System.out.println("Wrote campus-print.ico, icon-256.png, station-ui/icon-64.png, the installer pictures and the web icons");
    }

    /** The logo on a full navy square: phones cut app icons into their own shapes. */
    static BufferedImage onNavy(int size, int logo) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x14263B));
        g.fillRect(0, 0, size, size);
        g.drawImage(draw(logo), (size - logo) / 2, (size - logo) / 2, null);
        g.dispose();
        return img;
    }

    /** Navy panel with the logo and the name, for the installer's welcome and finish pages. */
    static BufferedImage banner(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x1B3350), 0, h, new Color(0x0F1E30)));
        g.fillRect(0, 0, w, h);
        double k = w / 164.0;
        int logo = (int) (72 * k);
        g.drawImage(draw(logo), (w - logo) / 2, (int) (70 * k), null);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, (int) (19 * k)));
        centre(g, "Campus Print", w, (int) (180 * k));
        g.setColor(new Color(0xB9C6D3));
        g.setFont(new Font("Segoe UI", Font.PLAIN, (int) (14 * k)));
        centre(g, "Station", w, (int) (200 * k));
        g.setColor(new Color(0x2E7D4F));
        g.fillOval((int) (w / 2.0 - 4 * k), (int) (262 * k), (int) (8 * k), (int) (8 * k));
        g.setColor(new Color(0x8FA2B5));
        g.setFont(new Font("Segoe UI", Font.PLAIN, (int) (11 * k)));
        centre(g, "Xerox center app", w, (int) (290 * k));
        g.dispose();
        return img;
    }

    static BufferedImage small(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, s, s);
        g.drawImage(draw(s), 0, 0, null);
        g.dispose();
        return img;
    }

    static void centre(Graphics2D g, String text, int w, int y) {
        g.drawString(text, (w - g.getFontMetrics().stringWidth(text)) / 2, y);
    }

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 64.0, size / 64.0);
        g.setColor(new Color(0x14263B));
        g.fill(new RoundRectangle2D.Double(0, 0, 64, 64, 28, 28));
        Path2D sheet = new Path2D.Double();
        sheet.moveTo(20, 13); sheet.lineTo(37, 13); sheet.lineTo(46, 22); sheet.lineTo(46, 51);
        sheet.quadTo(46, 53, 44, 53); sheet.lineTo(20, 53); sheet.quadTo(18, 53, 18, 51);
        sheet.lineTo(18, 15); sheet.quadTo(18, 13, 20, 13); sheet.closePath();
        g.setColor(Color.WHITE);
        g.fill(sheet);
        Path2D fold = new Path2D.Double();
        fold.moveTo(37, 13); fold.lineTo(37, 22); fold.lineTo(46, 22); fold.closePath();
        g.setColor(new Color(0xD9DCD4));
        g.fill(fold);
        if (size >= 24) {                       // text lines only where they can be seen
            g.setColor(new Color(0x9AA3AC));
            g.fill(new RoundRectangle2D.Double(23, 29, 18, 2.6, 2.6, 2.6));
            g.fill(new RoundRectangle2D.Double(23, 35, 14, 2.6, 2.6, 2.6));
        }
        g.setColor(new Color(0x14263B));
        g.fill(new Ellipse2D.Double(36.5, 37.5, 19, 19));
        g.setColor(new Color(0x2E7D4F));
        g.fill(new Ellipse2D.Double(38, 39, 16, 16));
        g.dispose();
        return img;
    }

    static byte[] png(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** .ico with PNG images inside (Windows Vista and later). */
    static void writeIco(File file, int[] sizes, List<byte[]> pngs) throws Exception {
        int n = sizes.length;
        ByteBuffer head = ByteBuffer.allocate(6 + 16 * n).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) n);
        int offset = 6 + 16 * n;
        for (int i = 0; i < n; i++) {
            int s = sizes[i];
            head.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
            head.putShort((short) 1).putShort((short) 32).putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(head.array());
            for (byte[] p : pngs) out.write(p);
        }
    }
}
