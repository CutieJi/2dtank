import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowStateListener;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class Main {
    private static JFrame    frame;
    private static GamePanel panel;
    private static boolean   isFullscreen = false;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("2dTank Battle Arena");
            panel = new GamePanel();

            frame.setIconImage(new ImageIcon("Logo.png").getImage());
            frame.add(panel);
            frame.pack();
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setLocationRelativeTo(null);
            frame.setResizable(true);
            frame.setMinimumSize(new Dimension(1200, 720));

            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e)      { panel.saveScoreOnExit(); System.exit(0); }
                @Override public void windowIconified(WindowEvent e)    { panel.pauseGame(); }
                @Override public void windowDeactivated(WindowEvent e)  { panel.pauseGame(); }
                @Override public void windowDeiconified(WindowEvent e)  { panel.requestFocusInWindow(); }
            });
            
            frame.addWindowStateListener(new WindowStateListener() {
                @Override
                public void windowStateChanged(WindowEvent e) {
                    if ((e.getNewState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH) {
                        toggleFullscreen();
                    }
                }
            });

            panel.setFocusable(true);
            panel.requestFocus();
            frame.setVisible(true);
        });
    }

    public static void toggleFullscreen() {
        GraphicsDevice gd = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice();

        if (isFullscreen) {
            // ── Exit fullscreen ───────────────────────────────────────
            GamePanel.fullscreen = false;
            panel.applyFullscreenScale(GamePanel.WIDTH, GamePanel.HEIGHT);

            gd.setFullScreenWindow(null);
            frame.dispose();
            frame.setUndecorated(false);

            // Restore panel to its logical (fixed) size
            panel.setPreferredSize(new Dimension(GamePanel.WIDTH, GamePanel.HEIGHT));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setResizable(true);
            frame.setMinimumSize(new Dimension(1200, 720));
            frame.setVisible(true);
            isFullscreen = false;

        } else {
            // ── Enter fullscreen ──────────────────────────────────────
            GamePanel.fullscreen = true;

            frame.dispose();
            frame.setUndecorated(true);

            // Make the panel fill the physical screen so our g2.scale() has room
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            panel.setPreferredSize(screen);
            frame.pack();
            gd.setFullScreenWindow(frame);
            frame.setVisible(true);
            isFullscreen = true;

            // Tell the panel the actual screen size so it can compute scale/offset
            panel.applyFullscreenScale(screen.width, screen.height);
        }

        SwingUtilities.invokeLater(() -> panel.requestFocusInWindow());
    }
}