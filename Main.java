import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;

public class Main {
    private static JFrame frame;
    private static boolean isFullscreen = false;
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("2dTank Battle Arena");
            GamePanel panel = new GamePanel();

            // Set window icon (the little image in the title bar / taskbar)
            frame.setIconImage(new ImageIcon("Logo.png").getImage());

            frame.add(panel);
            frame.pack();
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setLocationRelativeTo(null);
            frame.setResizable(false);
            
            // Add window listener to save score when player closes the game
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    // Save score if the game is in progress or game over state
                    panel.saveScoreOnExit();
                    System.exit(0);
                }
                
                @Override
                public void windowIconified(WindowEvent e) {
                    // Game is minimized - pause if not already paused
                    if (panel.isGameRunning()) {
                        panel.pauseGame();
                    }
                }
                
                @Override
                public void windowDeiconified(WindowEvent e) {
                    // Game is restored from minimized state
                    // No need to do anything, player can resume
                }
            });
            
            // Focus on panel for key events and fullscreen toggle
            panel.setFocusable(true);
            panel.requestFocus();
            
            frame.setVisible(true);
        });
    }
    
    public static void toggleFullscreen() {
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        
        if (isFullscreen) {
            // Exit fullscreen
            frame.dispose();
            frame.setUndecorated(false);
            frame.setResizable(false);
            gd.setFullScreenWindow(null);
            frame.setVisible(true);
            isFullscreen = false;
        } else {
            // Enter fullscreen
            frame.dispose();
            frame.setUndecorated(true);
            frame.setResizable(false);
            gd.setFullScreenWindow(frame);
            frame.setVisible(true);
            isFullscreen = true;
        }
    }
}