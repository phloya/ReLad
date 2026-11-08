import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Side by side: a bad scan stretched x2 (left) and the same crop of its enhanced version (right).
 *
 *   java plus/tools/test-manga/CompareScans.java bad.jpg enhanced.jpg out.png x y w h
 *
 * x, y, w, h are in pixels of the enhanced (x2) page.
 */
public class CompareScans {
    public static void main(String[] a) throws Exception {
        BufferedImage bad = ImageIO.read(new File(a[0]));
        BufferedImage enh = ImageIO.read(new File(a[1]));
        int x = Integer.parseInt(a[3]), y = Integer.parseInt(a[4]), w = Integer.parseInt(a[5]), h = Integer.parseInt(a[6]);
        BufferedImage out = new BufferedImage(w * 2 + 20, h + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(bad, 0, 40, w, h + 40, x / 2, y / 2, (x + w) / 2, (y + h) / 2, null);
        g.drawImage(enh, w + 20, 40, w * 2 + 20, h + 40, x, y, x + w, y + h, null);
        g.setColor(Color.BLACK); g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.drawString("Bad scan, stretched x2", 10, 28); g.drawString("Re:Lad enhance (waifu2x, strong)", w + 30, 28);
        ImageIO.write(out, "png", new File(a[2]));
    }
}
