import javax.sound.sampled.*;
import java.io.File;

public class SoundManager {

    public static void playSound(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return;
            }

            AudioInputStream audioInput = AudioSystem.getAudioInputStream(file);
            Clip clip = AudioSystem.getClip();
            clip.open(audioInput);
            clip.start();
        } catch (Exception e) {
            System.out.println("Sound error: " + e.getMessage());
        }
    }
}