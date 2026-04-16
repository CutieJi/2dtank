import java.awt.*;
import java.awt.geom.AffineTransform;

/**
 * Generates a 2D Tank Battle Arena logo/icon
 * Used for branding and menu displays
 */
public class LogoGenerator {

    /**
     * Create a game logo as a BufferedImage
     * @param width desired width
     * @param height desired height
     * @return BufferedImage containing the logo
     */
    public static java.awt.image.BufferedImage createLogo(int width, int height) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Background
        g2.setColor(new Color(20, 30, 60));
        g2.fillRect(0, 0, width, height);
        
        // Draw two tanks facing each other
        int tankSize = Math.min(width, height) / 3;
        int leftTankX = width / 4 - tankSize / 2;
        int rightTankX = (3 * width) / 4 - tankSize / 2;
        int tankY = height / 2 - tankSize / 2;
        
        // Left tank (red)
        drawTank(g2, leftTankX, tankY, tankSize, new Color(220, 60, 60), 0);
        
        // Right tank (blue)
        drawTank(g2, rightTankX, tankY, tankSize, new Color(60, 120, 220), 180);
        
        // Center "VS" text
        g2.setColor(new Color(255, 200, 80));
        g2.setFont(new Font("Arial", Font.BOLD, width / 8));
        FontMetrics fm = g2.getFontMetrics();
        String vs = "VS";
        int vsX = (width - fm.stringWidth(vs)) / 2;
        int vsY = height / 2 + fm.getAscent() / 2;
        g2.drawString(vs, vsX, vsY);
        
        // Game title
        g2.setColor(new Color(100, 180, 255));
        g2.setFont(new Font("Arial", Font.BOLD, width / 32));
        String title = "2D TANK BATTLE";
        fm = g2.getFontMetrics();
        int titleX = (width - fm.stringWidth(title)) / 2;
        g2.drawString(title, titleX, height - 20);
        
        g2.dispose();
        return img;
    }
    
    /**
     * Draw a simplified tank on the graphics context
     * @param g2 graphics context
     * @param x x position
     * @param y y position
     * @param size tank size
     * @param color tank color
     * @param rotation rotation angle (0 or 180)
     */
    private static void drawTank(Graphics2D g2, int x, int y, int size, Color color, int rotation) {
        AffineTransform oldTransform = g2.getTransform();
        
        // Center for rotation
        int centerX = x + size / 2;
        int centerY = y + size / 2;
        
        g2.translate(centerX, centerY);
        if (rotation != 0) {
            g2.rotate(Math.toRadians(rotation));
        }
        g2.translate(-centerX + x, -centerY + y);
        
        // Tank body
        g2.setColor(color);
        g2.fillRect(x + size / 8, y + size / 4, size * 3 / 4, size / 2);
        
        // Tank turret (circle)
        g2.fillOval(x + size / 4, y + size / 3, size / 2, size / 2);
        
        // Tank barrel
        g2.setColor(new Color(color.getRed() / 2, color.getGreen() / 2, color.getBlue() / 2));
        g2.setStroke(new BasicStroke(size / 12));
        g2.drawLine(x + size * 3 / 4, y + size / 2, x + size, y + size / 2);
        
        // Tank tracks (wheels)
        g2.setColor(new Color(80, 80, 100));
        int wheelSize = size / 10;
        for (int i = 0; i < 3; i++) {
            int wheelX = x + size / 8 + i * size / 4;
            g2.fillOval(wheelX, y + size / 6, wheelSize, wheelSize);
            g2.fillOval(wheelX, y + size * 2 / 3, wheelSize, wheelSize);
        }
        
        g2.setTransform(oldTransform);
    }
}
