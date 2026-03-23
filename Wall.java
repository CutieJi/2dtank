import java.awt.Color;
import java.awt.Rectangle;

public class Wall {
    int x, y, width, height;
    Color color;

    public Wall(int x, int y, int width, int height) {
        this(x, y, width, height, new Color(110, 75, 35));
    }

    public Wall(int x, int y, int width, int height, Color color) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.color = color;
    }

    public Rectangle getBounds() {
        return new Rectangle(x, y, width, height);
    }
}