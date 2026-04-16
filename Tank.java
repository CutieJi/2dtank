import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;

public class Tank {
    int x, y, width, height;
    Color color;
    int speed;
    int health;
    int maxHealth;
    boolean isPlayer;
    boolean isBoss;
    boolean bossUnlocked = true;
    Direction direction;
    int aiMoveCounter = 0;
    int shootCounter = 0;
    Image sprite;

    public Tank(int x, int y, int width, int height, Color color, int speed, int health, boolean isPlayer) {
        this(x, y, width, height, color, speed, health, isPlayer, false);
    }

    public Tank(int x, int y, int width, int height, Color color, int speed, int health, boolean isPlayer, boolean isBoss) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.color = color;
        this.speed = speed;
        this.health = health;
        this.maxHealth = health;
        this.isPlayer = isPlayer;
        this.isBoss = isBoss;
        this.direction = Direction.RIGHT;
    }

    public Rectangle getBounds() {
        return new Rectangle(x, y, width, height);
    }
}