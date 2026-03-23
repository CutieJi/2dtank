import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

public class GamePanel extends JPanel implements ActionListener, KeyListener {

    public static final int WIDTH = 1000;
    public static final int HEIGHT = 700;
    public static final int MAX_LEVEL = 10;

    private Timer timer;

    private Tank player;
    private ArrayList<Tank> enemies;
    private ArrayList<Bullet> bullets;
    private ArrayList<Wall> walls;
    private ArrayList<Explosion> explosions;

    private boolean up, down, left, right, shoot;

    private int score;
    private int level;
    private int shootCooldown;

    private GameState gameState = GameState.MENU;
    private final Random random = new Random();

    private Image playerImage;
    private Image enemyImage;
    private Image bossImage;
    private Image backgroundImage;

    public GamePanel() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setFocusable(true);
        addKeyListener(this);

        enemies = new ArrayList<>();
        bullets = new ArrayList<>();
        walls = new ArrayList<>();
        explosions = new ArrayList<>();

        loadAssets();

        timer = new Timer(16, this);
        timer.start();
    }

    private void loadAssets() {
        playerImage = ImageLoader.loadImage("images/player.png");
        enemyImage = ImageLoader.loadImage("images/enemy.png");
        bossImage = ImageLoader.loadImage("images/boss.png");
        backgroundImage = ImageLoader.loadImage("images/background.png");
    }

    private void startNewGame() {
        score = 0;
        level = 1;
        startLevel(level);
    }

    private void startLevel(int levelNumber) {
        bullets.clear();
        enemies.clear();
        walls.clear();
        explosions.clear();

        player = new Tank(80, HEIGHT / 2, 48, 48, new Color(40, 120, 255), 4, 100, true);
        player.direction = Direction.RIGHT;
        player.sprite = playerImage;

        shootCooldown = 0;

        createWallsForLevel(levelNumber);

        if (levelNumber == 10) {
            spawnBossLevel();
        } else {
            spawnEnemies(Math.min(3 + levelNumber, 12));
        }

        gameState = GameState.PLAYING;
    }

    private void spawnBossLevel() {
        Tank boss = new Tank(780, HEIGHT / 2 - 40, 80, 80, new Color(180, 30, 30), 3, 250, false, true);
        boss.direction = Direction.LEFT;
        boss.sprite = bossImage;
        enemies.add(boss);

        for (int i = 0; i < 4; i++) {
            Tank minion = new Tank(
                    650 + random.nextInt(180),
                    100 + random.nextInt(450),
                    42, 42,
                    new Color(220, 60, 60),
                    3, 60, false
            );
            minion.direction = Direction.values()[random.nextInt(4)];
            minion.sprite = enemyImage;

            if (!hitsWall(minion) && !minion.getBounds().intersects(player.getBounds())) {
                enemies.add(minion);
            }
        }
    }

    private void createWallsForLevel(int levelNumber) {
        Color wallColor = new Color(130, 90, 45);

        if (levelNumber == 1) {
            walls.add(new Wall(300, 150, 150, 30, wallColor));
            walls.add(new Wall(550, 350, 30, 180, wallColor));
            walls.add(new Wall(730, 150, 150, 30, wallColor));
        } else if (levelNumber == 2) {
            walls.add(new Wall(220, 120, 30, 200, wallColor));
            walls.add(new Wall(450, 260, 180, 30, wallColor));
            walls.add(new Wall(760, 420, 30, 180, wallColor));
        } else if (levelNumber == 3) {
            walls.add(new Wall(150, 250, 180, 30, wallColor));
            walls.add(new Wall(400, 100, 30, 200, wallColor));
            walls.add(new Wall(650, 300, 200, 30, wallColor));
            walls.add(new Wall(820, 120, 30, 150, wallColor));
        } else if (levelNumber == 4) {
            walls.add(new Wall(220, 100, 30, 180, wallColor));
            walls.add(new Wall(220, 400, 30, 180, wallColor));
            walls.add(new Wall(450, 240, 200, 30, wallColor));
            walls.add(new Wall(760, 180, 30, 240, wallColor));
        } else if (levelNumber == 5) {
            walls.add(new Wall(160, 140, 180, 30, wallColor));
            walls.add(new Wall(160, 500, 180, 30, wallColor));
            walls.add(new Wall(450, 150, 30, 350, wallColor));
            walls.add(new Wall(700, 240, 180, 30, wallColor));
        } else if (levelNumber == 6) {
            walls.add(new Wall(260, 90, 30, 180, wallColor));
            walls.add(new Wall(260, 420, 30, 180, wallColor));
            walls.add(new Wall(480, 260, 180, 30, wallColor));
            walls.add(new Wall(720, 90, 30, 180, wallColor));
            walls.add(new Wall(720, 420, 30, 180, wallColor));
        } else if (levelNumber == 7) {
            walls.add(new Wall(150, 200, 220, 30, wallColor));
            walls.add(new Wall(150, 470, 220, 30, wallColor));
            walls.add(new Wall(450, 120, 30, 180, wallColor));
            walls.add(new Wall(450, 400, 30, 180, wallColor));
            walls.add(new Wall(700, 260, 200, 30, wallColor));
        } else if (levelNumber == 8) {
            walls.add(new Wall(210, 100, 30, 180, wallColor));
            walls.add(new Wall(210, 420, 30, 180, wallColor));
            walls.add(new Wall(420, 250, 180, 30, wallColor));
            walls.add(new Wall(680, 100, 30, 180, wallColor));
            walls.add(new Wall(680, 420, 30, 180, wallColor));
            walls.add(new Wall(820, 250, 120, 30, wallColor));
        } else if (levelNumber == 9) {
            walls.add(new Wall(120, 150, 180, 30, wallColor));
            walls.add(new Wall(120, 500, 180, 30, wallColor));
            walls.add(new Wall(380, 120, 30, 180, wallColor));
            walls.add(new Wall(380, 400, 30, 180, wallColor));
            walls.add(new Wall(600, 260, 220, 30, wallColor));
            walls.add(new Wall(860, 120, 30, 180, wallColor));
        } else if (levelNumber == 10) {
            walls.add(new Wall(150, 120, 200, 30, wallColor));
            walls.add(new Wall(150, 550, 200, 30, wallColor));
            walls.add(new Wall(400, 200, 30, 300, wallColor));
            walls.add(new Wall(580, 120, 200, 30, wallColor));
            walls.add(new Wall(580, 550, 200, 30, wallColor));
            walls.add(new Wall(780, 200, 30, 300, wallColor));
            walls.add(new Wall(500, 330, 120, 30, wallColor));
        }
    }

    private void spawnEnemies(int count) {
        int spawned = 0;

        while (spawned < count) {
            int x = random.nextInt(WIDTH - 120) + 60;
            int y = random.nextInt(HEIGHT - 120) + 60;

            Tank enemy = new Tank(
                    x, y,
                    42, 42,
                    new Color(220, 60, 60),
                    Math.min(2 + level / 3, 5),
                    20 + (level * 10),
                    false
            );

            enemy.direction = Direction.values()[random.nextInt(4)];
            enemy.sprite = enemyImage;

            if (!enemy.getBounds().intersects(player.getBounds()) && !hitsWall(enemy) && !hitsEnemy(enemy, null)) {
                enemies.add(enemy);
                spawned++;
            }
        }
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (gameState == GameState.PLAYING) {
            updateGame();
        } else {
            updateExplosions();
        }
        repaint();
    }

    private void updateGame() {
        handlePlayerMovement();
        handlePlayerShooting();
        updateBullets();
        updateEnemies();
        updateExplosions();
        checkLevelProgress();

        if (shootCooldown > 0) shootCooldown--;
    }

    private void handlePlayerMovement() {
        int oldX = player.x;
        int oldY = player.y;

        if (up) {
            player.y -= player.speed;
            player.direction = Direction.UP;
        }
        if (down) {
            player.y += player.speed;
            player.direction = Direction.DOWN;
        }
        if (left) {
            player.x -= player.speed;
            player.direction = Direction.LEFT;
        }
        if (right) {
            player.x += player.speed;
            player.direction = Direction.RIGHT;
        }

        keepInside(player);

        if (hitsWall(player)) {
            player.x = oldX;
            player.y = oldY;
        }
    }

    private void handlePlayerShooting() {
        if (shoot && shootCooldown == 0) {
            bullets.add(createBullet(player));
            shootCooldown = 12;
            // SoundManager.playSound("sounds/shoot.wav");
        }
    }

    private Bullet createBullet(Tank tank) {
        int bulletSize = tank.isBoss ? 12 : 8;
        int bx = tank.x + tank.width / 2 - bulletSize / 2;
        int by = tank.y + tank.height / 2 - bulletSize / 2;
        int speed = tank.isBoss ? 10 : 8;

        int dx = 0;
        int dy = 0;

        switch (tank.direction) {
            case UP: dy = -speed; break;
            case DOWN: dy = speed; break;
            case LEFT: dx = -speed; break;
            case RIGHT: dx = speed; break;
        }

        return new Bullet(bx, by, bulletSize, bulletSize, dx, dy, tank.isPlayer);
    }

    private void updateBullets() {
        Iterator<Bullet> bulletIterator = bullets.iterator();

        while (bulletIterator.hasNext()) {
            Bullet bullet = bulletIterator.next();
            bullet.x += bullet.dx;
            bullet.y += bullet.dy;

            if (bullet.x < 0 || bullet.x > WIDTH || bullet.y < 0 || bullet.y > HEIGHT) {
                bulletIterator.remove();
                continue;
            }

            boolean removed = false;

            for (Wall wall : walls) {
                if (bullet.getBounds().intersects(wall.getBounds())) {
                    explosions.add(new Explosion(bullet.x, bullet.y));
                    bulletIterator.remove();
                    removed = true;
                    break;
                }
            }

            if (removed) continue;

            if (bullet.fromPlayer) {
                Iterator<Tank> enemyIterator = enemies.iterator();
                while (enemyIterator.hasNext()) {
                    Tank enemy = enemyIterator.next();
                    if (bullet.getBounds().intersects(enemy.getBounds())) {
                        enemy.health -= 20;
                        explosions.add(new Explosion(enemy.x + enemy.width / 2, enemy.y + enemy.height / 2));
                        bulletIterator.remove();

                        if (enemy.health <= 0) {
                            explosions.add(new Explosion(enemy.x + enemy.width / 2, enemy.y + enemy.height / 2));
                            score += enemy.isBoss ? 500 : 10 * level;
                            enemyIterator.remove();
                            // SoundManager.playSound("sounds/explosion.wav");
                        }

                        break;
                    }
                }
            } else {
                if (bullet.getBounds().intersects(player.getBounds())) {
                    player.health -= 10;
                    explosions.add(new Explosion(player.x + player.width / 2, player.y + player.height / 2));
                    bulletIterator.remove();

                    if (player.health <= 0) {
                        gameState = GameState.GAME_OVER;
                    }
                }
            }
        }
    }

    private void updateEnemies() {
        for (Tank enemy : enemies) {
            enemy.aiMoveCounter++;

            int oldX = enemy.x;
            int oldY = enemy.y;

            if (enemy.aiMoveCounter > Math.max(18, 50 - level * 2)) {
                enemy.direction = Direction.values()[random.nextInt(4)];
                enemy.aiMoveCounter = 0;
            }

            int moveChance = enemy.isBoss ? 2 : 1;

            if (random.nextInt(10) < 8) {
                switch (enemy.direction) {
                    case UP: enemy.y -= enemy.speed * moveChance; break;
                    case DOWN: enemy.y += enemy.speed * moveChance; break;
                    case LEFT: enemy.x -= enemy.speed * moveChance; break;
                    case RIGHT: enemy.x += enemy.speed * moveChance; break;
                }
            }

            keepInside(enemy);

            boolean collided = false;

            if (hitsWall(enemy)) collided = true;
            if (enemy.getBounds().intersects(player.getBounds())) collided = true;
            if (hitsEnemy(enemy, enemy)) collided = true;

            if (collided) {
                enemy.x = oldX;
                enemy.y = oldY;
                enemy.direction = Direction.values()[random.nextInt(4)];
            }

            enemy.shootCounter++;

            int shootRate = enemy.isBoss ? 20 : Math.max(25, 90 - level * 5);
            if (enemy.shootCounter > shootRate + random.nextInt(20)) {
                bullets.add(createBullet(enemy));
                enemy.shootCounter = 0;
            }
        }
    }

    private void updateExplosions() {
        Iterator<Explosion> iterator = explosions.iterator();
        while (iterator.hasNext()) {
            Explosion ex = iterator.next();
            ex.update();
            if (!ex.isAlive()) {
                iterator.remove();
            }
        }
    }

    private void checkLevelProgress() {
        if (enemies.isEmpty()) {
            if (level >= MAX_LEVEL) {
                gameState = GameState.WIN;
            } else {
                gameState = GameState.LEVEL_CLEARED;
            }
        }
    }

    private boolean hitsWall(Tank tank) {
        for (Wall wall : walls) {
            if (tank.getBounds().intersects(wall.getBounds())) return true;
        }
        return false;
    }

    private boolean hitsEnemy(Tank tank, Tank self) {
        for (Tank other : enemies) {
            if (other != self && tank.getBounds().intersects(other.getBounds())) {
                return true;
            }
        }
        return false;
    }

    private void keepInside(Tank tank) {
        if (tank.x < 0) tank.x = 0;
        if (tank.y < 0) tank.y = 0;
        if (tank.x + tank.width > WIDTH) tank.x = WIDTH - tank.width;
        if (tank.y + tank.height > HEIGHT) tank.y = HEIGHT - tank.height;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        drawBackground(g);

        if (gameState == GameState.MENU) {
            drawMenu(g);
            return;
        }

        drawWalls(g);

        if (player != null) drawTank(g, player);
        for (Tank enemy : enemies) drawTank(g, enemy);

        for (Bullet bullet : bullets) {
            g.setColor(bullet.fromPlayer ? Color.YELLOW : Color.ORANGE);
            g.fillOval(bullet.x, bullet.y, bullet.width, bullet.height);
        }

        drawExplosions(g);
        drawHUD(g);

        if (gameState == GameState.PAUSED) {
            drawPaused(g);
        } else if (gameState == GameState.LEVEL_CLEARED) {
            drawLevelCleared(g);
        } else if (gameState == GameState.GAME_OVER) {
            drawGameOver(g);
        } else if (gameState == GameState.WIN) {
            drawWin(g);
        }
    }

    private void drawBackground(Graphics g) {
        if (backgroundImage != null) {
            g.drawImage(backgroundImage, 0, 0, WIDTH, HEIGHT, this);
        } else {
            Graphics2D g2 = (Graphics2D) g;
            GradientPaint gp = new GradientPaint(
                    0, 0, new Color(40, 110, 50),
                    0, HEIGHT, new Color(20, 70, 30)
            );
            g2.setPaint(gp);
            g2.fillRect(0, 0, WIDTH, HEIGHT);

            g.setColor(new Color(60, 140, 70));
            for (int x = 0; x < WIDTH; x += 40) g.drawLine(x, 0, x, HEIGHT);
            for (int y = 0; y < HEIGHT; y += 40) g.drawLine(0, y, WIDTH, y);
        }
    }

    private void drawWalls(Graphics g) {
        for (Wall wall : walls) {
            g.setColor(wall.color);
            g.fillRect(wall.x, wall.y, wall.width, wall.height);
            g.setColor(new Color(85, 55, 20));
            g.drawRect(wall.x, wall.y, wall.width, wall.height);
        }
    }

    private void drawTank(Graphics g, Tank tank) {
        Graphics2D g2 = (Graphics2D) g;

        if (tank.sprite != null) {
            g2.drawImage(tank.sprite, tank.x, tank.y, tank.width, tank.height, this);
        } else {
            g.setColor(tank.color);
            g.fillRect(tank.x, tank.y, tank.width, tank.height);

            g.setColor(Color.DARK_GRAY);
            g.fillRect(tank.x - 4, tank.y, 4, tank.height);
            g.fillRect(tank.x + tank.width, tank.y, 4, tank.height);

            g.setColor(Color.GRAY);
            g.fillOval(tank.x + tank.width / 4, tank.y + tank.height / 4, tank.width / 2, tank.height / 2);
        }

        int cx = tank.x + tank.width / 2;
        int cy = tank.y + tank.height / 2;

        g.setColor(Color.BLACK);
        int cannonLength = tank.isBoss ? 26 : 20;

        switch (tank.direction) {
            case UP: g.fillRect(cx - 4, tank.y - (cannonLength - 5), 8, cannonLength); break;
            case DOWN: g.fillRect(cx - 4, tank.y + tank.height - 5, 8, cannonLength); break;
            case LEFT: g.fillRect(tank.x - (cannonLength - 5), cy - 4, cannonLength, 8); break;
            case RIGHT: g.fillRect(tank.x + tank.width - 5, cy - 4, cannonLength, 8); break;
        }

        g.setColor(Color.RED);
        g.fillRect(tank.x, tank.y - 10, tank.width, 6);

        g.setColor(Color.GREEN);
        int hpWidth = (int) ((tank.health / (double) tank.maxHealth) * tank.width);
        g.fillRect(tank.x, tank.y - 10, hpWidth, 6);

        if (tank.isBoss) {
            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, 12));
            g.drawString("BOSS", tank.x + 18, tank.y - 14);
        }
    }

    private void drawExplosions(Graphics g) {
        for (Explosion ex : explosions) {
            g.setColor(new Color(255, 140, 0, 180));
            g.fillOval(ex.x - ex.radius / 2, ex.y - ex.radius / 2, ex.radius, ex.radius);
            g.setColor(new Color(255, 220, 0, 170));
            g.fillOval(ex.x - ex.radius / 4, ex.y - ex.radius / 4, ex.radius / 2, ex.radius / 2);
        }
    }

    private void drawHUD(Graphics g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 20));
        g.drawString("Level: " + level + " / " + MAX_LEVEL, 20, 30);
        g.drawString("Score: " + score, 20, 60);
        g.drawString("Health: " + player.health, 20, 90);
        g.drawString("Enemies Left: " + enemies.size(), 20, 120);

        g.setFont(new Font("Arial", Font.PLAIN, 16));
        g.drawString("Move: W A S D / Arrow Keys | Shoot: SPACE | Pause: P", 20, HEIGHT - 20);
    }

    private void drawMenu(Graphics g) {
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 56));
        g.drawString("TANK BATTLE ARENA", 250, 180);

        g.setFont(new Font("Arial", Font.PLAIN, 26));
        g.drawString("A 2D Java Tank Game", 385, 240);

        g.setFont(new Font("Arial", Font.BOLD, 28));
        g.drawString("Press ENTER to Start", 355, 340);

        g.setFont(new Font("Arial", Font.PLAIN, 20));
        g.drawString("W A S D / Arrow Keys = Move", 360, 410);
        g.drawString("SPACE = Shoot", 430, 450);
        g.drawString("P = Pause", 455, 485);
        g.drawString("Defeat the Level 10 Boss to win", 340, 525);
    }

    private void drawPaused(Graphics g) {
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 52));
        g.drawString("PAUSED", 395, 290);

        g.setFont(new Font("Arial", Font.PLAIN, 26));
        g.drawString("Press P to Resume", 390, 350);
    }

    private void drawLevelCleared(Graphics g) {
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 48));
        g.drawString("LEVEL " + level + " CLEARED!", 320, 280);

        g.setFont(new Font("Arial", Font.PLAIN, 28));
        g.drawString("Press ENTER for next level", 345, 350);
    }

    private void drawGameOver(Graphics g) {
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 56));
        g.drawString("GAME OVER", 350, 260);

        g.setFont(new Font("Arial", Font.BOLD, 28));
        g.drawString("Final Score: " + score, 405, 330);

        g.setFont(new Font("Arial", Font.PLAIN, 24));
        g.drawString("Press R to return to menu", 375, 400);
    }

    private void drawWin(Graphics g) {
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.YELLOW);
        g.setFont(new Font("Arial", Font.BOLD, 56));
        g.drawString("YOU WON!", 380, 230);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 32));
        g.drawString("Boss defeated. All 10 levels cleared!", 255, 300);
        g.drawString("Final Score: " + score, 390, 350);

        g.setFont(new Font("Arial", Font.PLAIN, 24));
        g.drawString("Press R to return to menu", 375, 420);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int key = e.getKeyCode();

        if (gameState == GameState.MENU) {
            if (key == KeyEvent.VK_ENTER) startNewGame();
            return;
        }

        if (gameState == GameState.LEVEL_CLEARED) {
            if (key == KeyEvent.VK_ENTER) {
                level++;
                startLevel(level);
            }
            return;
        }

        if (gameState == GameState.GAME_OVER || gameState == GameState.WIN) {
            if (key == KeyEvent.VK_R) gameState = GameState.MENU;
            return;
        }

        if (key == KeyEvent.VK_P) {
            if (gameState == GameState.PLAYING) {
                gameState = GameState.PAUSED;
            } else if (gameState == GameState.PAUSED) {
                gameState = GameState.PLAYING;
            }
            return;
        }

        if (gameState == GameState.PAUSED) return;

        if (key == KeyEvent.VK_W || key == KeyEvent.VK_UP) up = true;
        if (key == KeyEvent.VK_S || key == KeyEvent.VK_DOWN) down = true;
        if (key == KeyEvent.VK_A || key == KeyEvent.VK_LEFT) left = true;
        if (key == KeyEvent.VK_D || key == KeyEvent.VK_RIGHT) right = true;
        if (key == KeyEvent.VK_SPACE) shoot = true;
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int key = e.getKeyCode();

        if (key == KeyEvent.VK_W || key == KeyEvent.VK_UP) up = false;
        if (key == KeyEvent.VK_S || key == KeyEvent.VK_DOWN) down = false;
        if (key == KeyEvent.VK_A || key == KeyEvent.VK_LEFT) left = false;
        if (key == KeyEvent.VK_D || key == KeyEvent.VK_RIGHT) right = false;
        if (key == KeyEvent.VK_SPACE) shoot = false;
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }
}