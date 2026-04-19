import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

public class ImageAnalyzer {
    public static void main(String[] args) throws Exception {
        String[] files = {"1Mosaic_demo.png", "11Frozen_caves__IntGrid.png"};
        for (String f : files) {
            BufferedImage img = ImageIO.read(new File(f));
            System.out.println("-- " + f + " : " + img.getWidth() + "x" + img.getHeight() + " --");
            Map<Integer, Integer> map = new HashMap<>();
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int rgb = img.getRGB(x, y);
                    map.put(rgb, map.getOrDefault(rgb, 0) + 1);
                }
            }
            List<Map.Entry<Integer, Integer>> list = new ArrayList<>(map.entrySet());
            list.sort((a,b) -> b.getValue().compareTo(a.getValue()));
            System.out.println("Top colors:");
            for(int i=0; i<Math.min(10, list.size()); i++) {
                int c = list.get(i).getKey();
                int a = (c >> 24) & 0xFF;
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                System.out.printf("  %d pixels: (%02x, %d, %d, %d)\n", list.get(i).getValue(), a, r, g, b);
            }
        }
    }
}