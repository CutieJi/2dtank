import java.awt.Image;
import java.io.File;
import javax.swing.ImageIcon;

public class ImageLoader {

    public static Image loadImage(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return null;
            }
            return new ImageIcon(path).getImage();
        } catch (Exception e) {
            return null;
        }
    }
}