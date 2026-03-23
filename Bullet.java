import java.awt.Rectangle;

public class Bullet {
    int x, y, width, height;
    int dx, dy;
    boolean fromPlayer;

    public Bullet(int x, int y, int width, int height, int dx, int dy, boolean fromPlayer) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.dx = dx;
        this.dy = dy;
        this.fromPlayer = fromPlayer;
    }

    public Rectangle getBounds() {
        return new Rectangle(x, y, width, height);
    }
}