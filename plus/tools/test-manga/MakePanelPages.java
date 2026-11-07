import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Makes a fake manga for checking panel-by-panel reading on the emulator. Every panel shows a big
 * number: its place in the reading order, so a screenshot tells at once whether the order is
 * right. Synthetic, so no copyright questions.
 *
 *   java plus/tools/test-manga/MakePanelPages.java <output folder>
 *
 * Output: <output folder>/ReLad Panels/
 *   Chapter 1 Manga/   right to left: a classic page, a balloon across a gutter, slanted gutters,
 *                      black gutters, a two-page spread, a splash page without gutters
 *   Chapter 2 Comic/   the same layouts numbered left to right
 *   Chapter 3 Webtoon/ three 800 px wide strips: gaps of different sizes, a panel taller than
 *                      a screen, a balloon floating between panels, a panel cut by a page break
 */
public class MakePanelPages {

    static final int W = 1000;
    static final int H = 1500;
    static Random random = new Random(11);

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".", "ReLad Panels");
        for (boolean rtl : new boolean[] {true, false}) {
            File dir = new File(root, rtl ? "Chapter 1 Manga" : "Chapter 2 Comic");
            dir.mkdirs();
            random = new Random(11);
            write(classic(rtl), dir, "001.png");
            write(slanted(rtl), dir, "002.png");
            write(blackGutters(rtl), dir, "003.png");
            write(spread(rtl), dir, "004.png");
            write(splash(), dir, "005.png");
        }
        File webtoon = new File(root, "Chapter 3 Webtoon");
        webtoon.mkdirs();
        random = new Random(5);
        List<BufferedImage> strips = webtoon();
        for (int i = 0; i < strips.size(); i++) write(strips.get(i), webtoon, String.format("%03d.png", i + 1));
        System.out.println("Written to " + root.getAbsolutePath());
    }

    static void write(BufferedImage image, File dir, String name) throws Exception {
        ImageIO.write(image, "png", new File(dir, name));
    }

    /** A page in the usual tiers: wide panel, two side by side (a balloon across their gutter), tall + two. */
    static BufferedImage classic(boolean rtl) {
        BufferedImage image = blank(W, H, Color.WHITE);
        Graphics2D g = graphics(image);
        Rectangle2D top = rect(40, 40, 920, 430);
        Rectangle2D midRight = rect(510, 500, 450, 420);
        Rectangle2D midLeft = rect(40, 500, 440, 420);
        Rectangle2D lowRight = rect(510, 950, 450, 510);
        Rectangle2D lowLeftTop = rect(40, 950, 440, 240);
        Rectangle2D lowLeftBottom = rect(40, 1220, 440, 240);
        // Reading order: manga takes the right column first
        List<Shape> order = rtl
            ? List.of(top, midRight, midLeft, lowRight, lowLeftTop, lowLeftBottom)
            : List.of(top, midLeft, midRight, lowLeftTop, lowLeftBottom, lowRight);
        drawPanels(g, order);
        balloon(g, 495, 640, 110, 70, rtl ? "Two→Three" : "Two→Three");
        pageNumber(g, 1);
        g.dispose();
        return image;
    }

    /** Diagonal gutters, the kind that bounding boxes overlap on. */
    static BufferedImage slanted(boolean rtl) {
        BufferedImage image = blank(W, H, Color.WHITE);
        Graphics2D g = graphics(image);
        Polygon first = poly(40, 40, 960, 40, 960, 520, 40, 640);
        Polygon secondRight = poly(520, 590, 960, 545, 960, 1030, 470, 1080);
        Polygon secondLeft = poly(40, 665, 495, 618, 445, 1083, 40, 1120);
        Polygon third = poly(40, 1145, 960, 1060, 960, 1460, 40, 1460);
        drawPanels(g, rtl ? List.of(first, secondRight, secondLeft, third) : List.of(first, secondLeft, secondRight, third));
        pageNumber(g, 2);
        g.dispose();
        return image;
    }

    /** A flashback page: black gutters, white borders. */
    static BufferedImage blackGutters(boolean rtl) {
        BufferedImage image = blank(W, H, new Color(12, 12, 12));
        Graphics2D g = graphics(image);
        Rectangle2D right = rect(510, 40, 450, 700);
        Rectangle2D left = rect(40, 40, 440, 700);
        Rectangle2D bottom = rect(40, 770, 920, 690);
        drawPanels(g, rtl ? List.of(right, left, bottom) : List.of(left, right, bottom));
        g.dispose();
        return image;
    }

    /** Two pages in one image: each page is read on its own, for manga the right one first. */
    static BufferedImage spread(boolean rtl) {
        BufferedImage image = blank(2 * W, H, Color.WHITE);
        Graphics2D g = graphics(image);
        List<Shape> rightPage = List.of(rect(1040, 40, 920, 640), rect(1520, 710, 440, 750), rect(1040, 710, 450, 750));
        List<Shape> leftPage = List.of(rect(40, 40, 920, 640), rect(520, 710, 440, 750), rect(40, 710, 450, 750));
        List<Shape> order = new ArrayList<>();
        if (rtl) {
            order.addAll(rightPage);
            order.addAll(leftPage);
        } else {
            // Comics: left page first, and its lower row left to right
            order.add(leftPage.get(0));
            order.add(leftPage.get(2));
            order.add(leftPage.get(1));
            order.add(rightPage.get(0));
            order.add(rightPage.get(2));
            order.add(rightPage.get(1));
        }
        drawPanels(g, order);
        g.dispose();
        return image;
    }

    /** Art to the edges, no gutters: shown whole. */
    static BufferedImage splash() {
        BufferedImage image = blank(W, H, new Color(70, 70, 70));
        Graphics2D g = graphics(image);
        art(g, new Rectangle2D.Double(0, 0, W, H));
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 120));
        g.drawString("SPLASH", 260, 760);
        g.dispose();
        return image;
    }

    static List<BufferedImage> webtoon() {
        int w = 800;
        // One long strip, then cut into pages the way sites do: at fixed heights, not at gaps
        int total = 15000;
        BufferedImage strip = blank(w, total, Color.WHITE);
        Graphics2D g = graphics(strip);
        int[][] panels = {
            {0, 200, 800, 900}, // 1: full width
            {60, 1300, 680, 700}, // 2: after a normal gap
            {0, 2350, 800, 2600}, // 3: taller than a phone screen
            {0, 5900, 800, 900}, // 4: after a long empty stretch (with a floating balloon)
            {100, 6950, 600, 600}, // 5: narrow
            {0, 7650, 800, 1100}, // 6: cut by the first page break at 8000
            {0, 9000, 800, 800}, // 7
            {0, 10000, 800, 1800}, // 8
            {0, 12000, 800, 900}, // 9
            {0, 13100, 800, 1500}, // 10
        };
        List<Shape> shapes = new ArrayList<>();
        for (int[] p : panels) shapes.add(rect(p[0], p[1], p[2], p[3]));
        drawPanels(g, shapes);
        balloon(g, 400, 5300, 220, 110, "...");
        g.dispose();
        List<BufferedImage> pages = new ArrayList<>();
        for (int y = 0; y < total; y += 8000) {
            int h = Math.min(8000, total - y);
            pages.add(strip.getSubimage(0, y, w, h));
        }
        return pages;
    }

    static void drawPanels(Graphics2D g, List<Shape> order) {
        for (int i = 0; i < order.size(); i++) {
            Shape s = order.get(i);
            g.setColor(Color.WHITE);
            g.fill(s);
            art(g, s);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(6));
            g.draw(s);
            number(g, s, i + 1);
        }
    }

    /** Random strokes and a patch of screentone, clipped to the panel. */
    static void art(Graphics2D g, Shape panel) {
        Shape clip = g.getClip();
        g.clip(panel);
        Rectangle2D b = panel.getBounds2D();
        g.setColor(new Color(150, 150, 150));
        for (double y = b.getY(); y < b.getMaxY(); y += 7) {
            for (double x = b.getX() + (y % 14 == 0 ? 0 : 3.5); x < b.getX() + b.getWidth() * 0.4; x += 7) {
                g.fill(new Ellipse2D.Double(x, y, 3, 3));
            }
        }
        g.setColor(Color.DARK_GRAY);
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            int x1 = (int) (b.getX() + random.nextDouble() * b.getWidth());
            int y1 = (int) (b.getY() + random.nextDouble() * b.getHeight());
            int x2 = (int) (b.getX() + random.nextDouble() * b.getWidth());
            int y2 = (int) (b.getY() + random.nextDouble() * b.getHeight());
            g.drawLine(x1, y1, x2, y2);
        }
        g.setClip(clip);
    }

    /** The panel's place in the reading order, big, in a circle. */
    static void number(Graphics2D g, Shape panel, int n) {
        Rectangle2D b = panel.getBounds2D();
        int size = (int) Math.min(220, Math.min(b.getWidth(), b.getHeight()) * 0.5);
        int cx = (int) b.getCenterX();
        int cy = (int) b.getCenterY();
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(cx - size * 0.6, cy - size * 0.6, size * 1.2, size * 1.2));
        g.setColor(new Color(200, 30, 30));
        g.setStroke(new BasicStroke(5));
        g.draw(new Ellipse2D.Double(cx - size * 0.6, cy - size * 0.6, size * 1.2, size * 1.2));
        g.setFont(new Font("SansSerif", Font.BOLD, size));
        String text = String.valueOf(n);
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, cx - width / 2, cy + size * 0.36f);
    }

    static void balloon(Graphics2D g, int cx, int cy, int rx, int ry, String text) {
        Ellipse2D e = new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
        g.setColor(Color.WHITE);
        g.fill(e);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(4));
        g.draw(e);
        g.setFont(new Font("SansSerif", Font.BOLD, 30));
        int width = g.getFontMetrics().stringWidth(text);
        g.drawString(text, cx - width / 2, cy + 10);
    }

    static void pageNumber(Graphics2D g, int n) {
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.drawString(String.valueOf(n), W / 2 - 6, H - 10);
    }

    static Rectangle2D rect(int x, int y, int w, int h) {
        return new Rectangle2D.Double(x, y, w, h);
    }

    static Polygon poly(int... xy) {
        Polygon p = new Polygon();
        for (int i = 0; i < xy.length; i += 2) p.addPoint(xy[i], xy[i + 1]);
        return p;
    }

    static BufferedImage blank(int w, int h, Color color) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }
}
