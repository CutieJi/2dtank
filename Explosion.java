public class Explosion {
    int x, y;
    int radius;
    int maxRadius;
    int life;

    public Explosion(int x, int y) {
        this.x = x;
        this.y = y;
        this.radius = 10;
        this.maxRadius = 44;
        this.life = 20;
    }

    public void update() {
        if (radius < maxRadius) {
            radius += 2;
        }
        life--;
    }

    public boolean isAlive() {
        return life > 0;
    }
}