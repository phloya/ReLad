import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextLayout;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Makes a small fake manga chapter for checking Re:Lad on the emulator: panels, speech bubbles with
 * Japanese, Korean, Chinese and English text, a sound effect and a caption. Synthetic, so no copyright
 * questions. Needs macOS fonts (Hiragino Sans, Apple SD Gothic Neo, PingFang SC).
 *
 *   java plus/tools/test-manga/MakeTestManga.java <output folder>
 *
 * Output (no colon in names: Android storage forbids it):
 *   <output folder>/ReLad Test/Chapter 1/001.png … 003.png   clean pages
 *   <output folder>/ReLad Test/Chapter 2/001.jpg … 003.jpg   the same pages as very bad scans:
 *                                                            low resolution, noise, blur, heavy JPEG
 */
public class MakeTestManga {

    static final int W = 1000;
    static final int H = 1500;
    static final Random random = new Random(42);

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".", "ReLad Test/Chapter 1");
        dir.mkdirs();
        ImageIO.write(page1(), "png", new File(dir, "001.png"));
        ImageIO.write(page2(), "png", new File(dir, "002.png"));
        ImageIO.write(page3(), "png", new File(dir, "003.png"));
        System.out.println("Written to " + dir.getAbsolutePath());

        File bad = new File(args.length > 0 ? args[0] : ".", "ReLad Test/Chapter 2");
        bad.mkdirs();
        jpeg(degrade(page1(), 0.5, 14, 0), 0.12f, new File(bad, "001.jpg"));
        jpeg(degrade(page2(), 0.6, 6, 2), 0.20f, new File(bad, "002.jpg"));
        jpeg(degrade(page3(), 0.45, 0, 0), 0.06f, new File(bad, "003.jpg"));
        System.out.println("Written to " + bad.getAbsolutePath());
    }

    /** Shrinks the page, blurs it [blur] times and adds gray noise of strength [noise], like a cheap scan. */
    static BufferedImage degrade(BufferedImage page, double scale, int noise, int blur) {
        int w = (int) (page.getWidth() * scale);
        int h = (int) (page.getHeight() * scale);
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(page, 0, 0, w, h, null);
        g.dispose();
        for (int i = 0; i < blur; i++) small = boxBlur(small);
        Random r = new Random(7);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = small.getRGB(x, y);
                int n = (int) (r.nextGaussian() * noise);
                int v = Math.max(0, Math.min(255, (rgb & 0xFF) + n));
                small.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return small;
    }

    static BufferedImage boxBlur(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0;
                int count = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = Math.max(0, Math.min(w - 1, x + dx));
                        int yy = Math.max(0, Math.min(h - 1, y + dy));
                        sum += src.getRGB(xx, yy) & 0xFF;
                        count++;
                    }
                }
                int v = sum / count;
                out.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return out;
    }

    static void jpeg(BufferedImage image, float quality, File file) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        }
        writer.dispose();
    }

    static BufferedImage page1() {
        BufferedImage image = blank();
        Graphics2D g = graphics(image);
        panel(g, 40, 40, 920, 680);
        panel(g, 40, 760, 920, 700);
        art(g, 40, 40, 920, 680);
        art(g, 40, 760, 920, 700);
        bubble(g, new Ellipse2D.Double(90, 90, 420, 230), "こんにちは！", "元気？", jp(44));
        bubble(g, new Ellipse2D.Double(560, 380, 360, 220), "今日は", "いい天気だね。", jp(40));
        caption(g, 70, 790, "翌朝", jp(38));
        sfx(g, 520, 1250, "ドン！", jp(150));
        bubble(g, new Ellipse2D.Double(560, 820, 360, 200), "早く起きて！", null, jp(40));
        g.dispose();
        return image;
    }

    static BufferedImage page2() {
        BufferedImage image = blank();
        Graphics2D g = graphics(image);
        panel(g, 40, 40, 440, 700);
        panel(g, 520, 40, 440, 700);
        panel(g, 40, 780, 920, 680);
        art(g, 40, 40, 440, 700);
        art(g, 520, 40, 440, 700);
        art(g, 40, 780, 920, 680);
        bubble(g, new Ellipse2D.Double(70, 80, 380, 220), "안녕하세요!", "반가워요.", new Font("Apple SD Gothic Neo", Font.BOLD, 40));
        bubble(g, new Ellipse2D.Double(550, 420, 380, 220), "你好，朋友！", "我们走吧。", new Font("PingFang SC", Font.PLAIN, 40));
        bubble(g, new Ellipse2D.Double(300, 840, 400, 220), "WHAT?!", "No way...", new Font("SansSerif", Font.BOLD, 46));
        g.dispose();
        return image;
    }

    static BufferedImage page3() {
        BufferedImage image = blank();
        Graphics2D g = graphics(image);
        panel(g, 40, 40, 920, 1420);
        art(g, 40, 40, 920, 1420);
        // Vertical Japanese text, the usual layout in manga
        Shape tall = new Ellipse2D.Double(640, 120, 260, 560);
        g.setColor(Color.WHITE);
        g.fill(tall);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(4));
        g.draw(tall);
        verticalText(g, "ちょっと", 800, 230, jp(46));
        verticalText(g, "待って！", 740, 230, jp(46));
        bubble(g, new Ellipse2D.Double(100, 900, 440, 240), "どこへ", "行くの？", jp(44));
        g.dispose();
        return image;
    }

    static Font jp(int size) {
        return new Font("Hiragino Sans", Font.BOLD, size);
    }

    static BufferedImage blank() {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return image;
    }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(6));
        g.drawRect(x, y, w, h);
    }

    /** Fake artwork: gray gradient, speed lines and a few shapes. */
    static void art(Graphics2D g, int x, int y, int w, int h) {
        g.setPaint(new GradientPaint(x, y, new Color(235, 235, 235), x + w, y + h, new Color(150, 150, 150)));
        g.fillRect(x + 3, y + 3, w - 6, h - 6);
        g.setColor(new Color(60, 60, 60));
        g.setStroke(new BasicStroke(2));
        for (int i = 0; i < 25; i++) {
            int x1 = x + random.nextInt(w);
            int y1 = y + h - random.nextInt(h / 3);
            g.drawLine(x1, y1, x1 + random.nextInt(80) - 40, y1 - 120 - random.nextInt(120));
        }
        g.setColor(new Color(40, 40, 40));
        g.setStroke(new BasicStroke(5));
        g.drawOval(x + w / 3, y + h / 2, w / 4, h / 3);
    }

    static void bubble(Graphics2D g, Shape shape, String line1, String line2, Font font) {
        g.setColor(Color.WHITE);
        g.fill(shape);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(4));
        g.draw(shape);
        g.setFont(font);
        var bounds = shape.getBounds();
        int lineHeight = (int) (font.getSize() * 1.3);
        int lines = line2 == null ? 1 : 2;
        int top = bounds.y + bounds.height / 2 - lines * lineHeight / 2 + font.getSize();
        centered(g, line1, bounds.x + bounds.width / 2, top);
        if (line2 != null) centered(g, line2, bounds.x + bounds.width / 2, top + lineHeight);
    }

    static void caption(Graphics2D g, int x, int y, String text, Font font) {
        Shape box = new RoundRectangle2D.Double(x, y, 160, 70, 8, 8);
        g.setColor(Color.WHITE);
        g.fill(box);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3));
        g.draw(box);
        g.setFont(font);
        centered(g, text, x + 80, y + 50);
    }

    /** Big sound effect drawn over the art: black letters with a white outline. */
    static void sfx(Graphics2D g, int cx, int baseline, String text, Font font) {
        TextLayout layout = new TextLayout(text, font, g.getFontRenderContext());
        var outline = layout.getOutline(java.awt.geom.AffineTransform.getTranslateInstance(
            cx - layout.getAdvance() / 2, baseline));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(outline);
        g.setColor(Color.BLACK);
        g.fill(outline);
    }

    static void verticalText(Graphics2D g, String text, int x, int top, Font font) {
        g.setFont(font);
        int y = top;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            centered(g, ch, x, y);
            y += (int) (font.getSize() * 1.1);
            i += Character.charCount(cp);
        }
    }

    static void centered(Graphics2D g, String text, int cx, int baseline) {
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, cx - width / 2, baseline);
    }
}
