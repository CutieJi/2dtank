import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;
import javax.swing.*;

public class GamePanel extends JPanel implements ActionListener, KeyListener, MouseListener, MouseMotionListener {

    // ══════════════════════════════════════════════════════════════
    //  GAME STATE
    // ══════════════════════════════════════════════════════════════
    private enum GameState {
        MENU,
        NAME_INPUT,

        LB_NAME_WARN,      // warn: this name exists on leaderboard, fresh start resets run score
        TANK_SELECT,
        INSTRUCTIONS,
        LEADERBOARD,
        PLAYING,
        PAUSED,
        LEVEL_CLEARED,
        CARD_SELECT,       // card pick before a level starts (common or rare)
        GAME_OVER,
        WIN
    }

    // Whether the pending card select is a rare (post-boss) pick
    private boolean cardSelectIsRare = false;
    // The next level to start after a card is picked
    private int pendingNextLevel = 1;

    private enum CardPower {
        // ── Common cards (appear before every level) ──────────────────
        CONTINUOUS_BULLET("Continuous Bullet", "Hold SPACE to auto-fire", "No more tapping — hold to shoot nonstop"),
        SIX_BULLET("6-Way Shot", "Fire 6 bullets at once", "Covers a wide spread — great for crowds"),
        TRIPLE_BULLET("3-Way Shot", "Fire 3 bullets at once", "Cone spread — upgrades to 6-Way if you find it"),  // removed from pool once SIX_BULLET owned
        HEAVY_SHOT("Heavy Shot", "+15 dmg & bigger bullets", "Stacks! Best damage card in the pool"),
        QUICK_RELOAD("Quick Reload", "Cut reload time by 1s", "One-time pick — brings reload to its minimum speed"),
        MAG_PLUS("Magazine +2", "+2 to your current mag size", "Stacks! Each pick adds 2 more shots to your mag"),
        BULLET_SPEED("Bullet Speed", "Bullets move faster", "Stacks! Harder for enemies to dodge"),
        BIG_BULLETS("Big Bullets", "Larger bullet hitbox", "Stacks! Easier to hit — pairs well with spread shots"),
        DAMAGE_UP("Damage Up", "+6 bullet damage", "Stacks every pick — great to grab multiple times"),
        MAX_HP_UP("Armor Up", "+30 max HP (permanent)", "Stacks! Permanently increases your max HP by 30 each pick"),
        BATTLE_MEDIC("Battle Medic", "+50 max HP (permanent)", "Stacks! Permanently increases your max HP by 50 each pick"),
        ENGINE_BOOST("Speed Up", "+1 move speed", "Stacks! Makes dodging much easier"),
        SECOND_LIFE("Second Life", "Auto-revive once on death", "Triggers automatically — restores 50% HP & full ammo"),
        // ── Rare cards (reward for defeating a boss) ──────────────────
        RARE_DOUBLE_DAMAGE("RARE: Double Damage", "All bullet damage ×2!", "Applies to your current damage — massive upgrade"),
        RARE_DOUBLE_HP("RARE: Iron Fortress", "Max HP doubled!", "Your HP cap permanently doubles — massive survivability boost"),
        RARE_DOUBLE_SCORE("RARE: Score Surge", "All score gains ×2", "Every kill earns double points for the rest of the run"),
        RARE_GHOST_RELOAD("RARE: Infinite Mag", "Magazine size ×3!", "Triples your current mag cap — rarely need to reload"),
        RARE_SLOW_ENEMIES("RARE: Time Warp", "Enemies 50% slower", "Permanent for this run — makes everything way easier"),
        RARE_RAPID_FIRE("RARE: Gatling Mode", "Auto-fire at triple speed", "Hold SPACE to unload — best fire rate possible");

        final String title;
        final String desc;
        final String detail;

        CardPower(String title, String desc, String detail) {
            this.title  = title;
            this.desc   = desc;
            this.detail = detail;
        }
    }

    // Tracks whether RARE_DOUBLE_SCORE is active (score multiplier)
    private int scoreMultiplier = 1;
    // Tracks whether RARE_SLOW_ENEMIES is active
    private boolean enemySlowActive = false;

    // ══════════════════════════════════════════════════════════════
    //  LAYOUT
    // ══════════════════════════════════════════════════════════════
    public static final int GAME_W  = 1000;
    public static final int SIDEBAR = 200;
    public static final int WIDTH   = GAME_W + SIDEBAR;
    public static final int HEIGHT  = 700;
    public static final int MAX_LEVEL = 15;
    private static final int TOP_BAR = 34;

    // AI
    private static final int AI_CELL = 32;
    private static final int DODGE_RANGE = 190;
    private static final int FLANK_RADIUS = 120;

    // Reload
    private static final int PLAYER_MAG_SIZE_DEFAULT = 6;
    private static final int ENEMY_MAG_SIZE_DEFAULT = 3;
    private static final int BASE_RELOAD_FRAMES = 80; // ~3 sec // ~3 sec

    // Pause (menu handled in drawPauseMenu, button rects not used)

    private boolean pauseMenuOpen  = false;
    private boolean settingsOpen   = false;
    private int settingsCursor     = 0;
    private int borderStyle        = 0;
    private static final String[] BORDER_NAMES = {"Simple","Double","None"};
    private int pauseCursor        = 0;
    private static final String[] PAUSE_ITEMS = {
            "RESUME", "RETRY LEVEL", "SETTINGS", "MAIN MENU", "QUIT GAME"
    };

    private final Timer timer;

    private Tank player;
    private final ArrayList<Tank> enemies = new ArrayList<>();
    private final ArrayList<Bullet> bullets = new ArrayList<>();
    private final ArrayList<Wall> walls = new ArrayList<>();
    private final ArrayList<Explosion> explosions = new ArrayList<>();

    // AI state
    private final Map<Tank, Integer> flankRole = new HashMap<>();
    private final Map<Tank, Integer> bossSpecialCooldown = new HashMap<>();
    private final Map<Tank, Integer> bossBurstCooldown = new HashMap<>();
    private final Map<Tank, Boolean> bossSummonedMinions = new HashMap<>();

    // ammo / reload
    private final Map<Tank, Integer> enemyAmmo = new HashMap<>();
    private final Map<Tank, Integer> enemyReloadTimers = new HashMap<>();
    private final Map<Bullet, Integer> bulletDamageMap = new HashMap<>();

    // cards
    private final ArrayList<CardPower> offeredCards = new ArrayList<>();
    private final HashSet<CardPower> pickedCards = new HashSet<>();  // cards already chosen (won't reappear)
    private final Rectangle[] cardRects = {
            new Rectangle(), new Rectangle(), new Rectangle()
    };

    private boolean up, down, left, right, shoot;
    private int score, level, shootCooldown, levelTimeRemaining, quotaRequired, quotaKilled;
    private int retriesLeft = 3;       // retries remaining for current level
    private int scoreAtLevelStart = 0; // score snapshot when level began (for reset on retry)
    private boolean isRetrying = false; // true when retrying same level (don't reset retries)

    private String playerName = "";
    private String nameErrorMsg = "";
    private boolean scoreSaved = false;

    private int lbExistingScore = 0;                     // LB best score shown in LB_NAME_WARN screen

    private GameState gameState = GameState.MENU;
    private final Random random = new Random();
    private final Random bgRng  = new Random(42L); // fixed-seed for menu background decorations

    private Image playerImage, enemyImage, bossImage, backgroundImage;
    private BufferedImage levelMapImage;
    private BufferedImage levelMapMask;
    private boolean usingLevelMap = false;

    // ── Tank skin selection ──────────────────────────────────────────
    private int selectedTankDesign = 0;
    private int tankSelectHover    = -1;

    private static final String[] TANK_DESIGN_NAMES    = {
        "Steel Blue", "Forest Green", "Desert Sand",
        "Arctic Snow", "Shadow Black", "Flame Red"
    };
    private static final Color[] TANK_DESIGN_PRIMARY   = {
        new Color(40,120,255),  new Color(30,150,60),   new Color(200,160,80),
        new Color(220,235,255), new Color(40,40,55),    new Color(220,60,30)
    };
    private static final Color[] TANK_DESIGN_ACCENT    = {
        new Color(16,70,180),   new Color(16,96,38),    new Color(150,110,44),
        new Color(170,195,220), new Color(18,18,28),    new Color(150,22,8)
    };
    private static final Color[] TANK_DESIGN_HIGHLIGHT = {
        new Color(130,195,255), new Color(90,210,110),  new Color(255,220,140),
        new Color(255,255,255), new Color(90,90,120),   new Color(255,150,80)
    };

    // ── AI awareness / wander ────────────────────────────────────────
    private static final int AWARENESS_RANGE    = 290;
    private static final int ENEMY_TURN_COOLDOWN = 22; // Slower turning - more realistic tank feel

    private final Map<Tank, Direction> wanderDir    = new HashMap<>();
    private final Map<Tank, Integer>   wanderTimer  = new HashMap<>();
    private final Map<Tank, Integer>   turnCooldown = new HashMap<>();
    private final Map<Tank, Integer>   wallStuckTimer = new HashMap<>();

    private long lastSecondTick = System.currentTimeMillis();
    private float titlePulse = 0f;

    // menu buttons
    private final Rectangle startBtn  = new Rectangle(0, 0, 200, 46);
    private final Rectangle lboardBtn    = new Rectangle(0, 0, 200, 46);
    private final Rectangle lbWarnYesRect = new Rectangle();
    private final Rectangle lbWarnNoRect  = new Rectangle();
    private final Rectangle fsBtn     = new Rectangle(0, 0, 26, 22); // fullscreen toggle in top bar
    private final Rectangle instrBtn  = new Rectangle(0, 0, 200, 46);
    private final Rectangle exitBtn   = new Rectangle(0, 0, 200, 46);

    private Point mousePos = new Point(0, 0);

    // player stats / powers
    private int playerMagazineSize = PLAYER_MAG_SIZE_DEFAULT;
    private int playerAmmo = PLAYER_MAG_SIZE_DEFAULT;
    private int playerReloadTimer = 0;
    private int playerReloadFrames = BASE_RELOAD_FRAMES;

    private int playerMoveSpeedStat = 4;
    private int playerMaxHealthStat = 100;

    private int playerShotCooldownFrames = 12;
    private int playerBulletPattern = 1; // 1 / 3 / 5
    private int playerBulletSpeedBonus = 0;
    private int playerBulletSizeBonus = 0;
    private int playerBulletDamage = 20;
    private boolean playerContinuousFire = false;
    private boolean playerSecondLife = false;

    // Initialize level to 1 (not 0) - maps are LEVEL1 through LEVEL15
    static {
        // Ensure no level 0 exists in the system
    }

    // ══════════════════════════════════════════════════════════════
    public GamePanel() {
        // Initialize level safely (Java defaults to 0, but we ensure it's at least 1)
        // This will be set properly when startLevel(1) is called from the menu
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setFocusable(true);
        @SuppressWarnings("this-escape")
        GamePanel panel = this;
        panel.addKeyListener(panel);
        panel.addMouseListener(panel);
        panel.addMouseMotionListener(panel);
        loadAssets();

        // Pause menu rendered without stored button rects

        timer = new Timer(16, this);
        timer.start();
    }

    private void loadAssets() {
        playerImage     = ImageLoader.loadImage("images/player.png");
        enemyImage      = ImageLoader.loadImage("images/enemy.png");
        bossImage       = ImageLoader.loadImage("images/boss.png");
        backgroundImage = ImageLoader.loadImage("images/background.png");
    }

    // ══════════════════════════════════════════════════════════════
    //  DIFFICULTY
    // ══════════════════════════════════════════════════════════════
    private boolean isEasyLevel(int n) {
        return n >= 1 && n <= 5;
    }

    private boolean isMediumLevel(int n) {
        return n >= 6 && n <= 10;
    }



    private int getQuotaForLevel(int n) {
        // Custom enemy count for each level
        switch(n) {
            case 1:  return 3;
            case 2:  return 4;
            case 3:  return 5;
            case 4:  return 5;
            case 5:  return 4;  // Boss level
            case 6:  return 4;
            case 7:  return 5;
            case 8:  return 5;
            case 9:  return 6;
            case 10: return 5;  // Boss level
            case 11: return 5;
            case 12: return 5;
            case 13: return 6;
            case 14: return 7;
            case 15: return 6;  // Boss level
            default: return 3;
        }
    }

    private int getTimeForLevel(int n) {
        if (isEasyLevel(n))   return 95 - (n - 1) * 3;
        if (isMediumLevel(n)) return 78 - (n - 6) * 2;
        return 64 - (n - 11) * 2;
    }

    private int getEnemySpeedForLevel(int n) {
        int base;
        if (isEasyLevel(n))   base = 2;
        else if (isMediumLevel(n)) base = 3;
        else base = 4;
        // RARE Time Warp card: halve enemy speed (minimum 1)
        return enemySlowActive ? Math.max(1, base / 2) : base;
    }

    private int getEnemyHealthForLevel(int n) {
        if (isEasyLevel(n))   return 30 + ((n - 1) * 8);
        if (isMediumLevel(n)) return 80 + ((n - 6) * 14);
        return 150 + ((n - 11) * 18);
    }

    private int getEnemyFireRate(Tank en) {
        if (en.isBoss) {
            if (isEasyLevel(level))   return 34;
            if (isMediumLevel(level)) return 24;
            return 16;
        }

        if (isEasyLevel(level))   return 55;
        if (isMediumLevel(level)) return 38;
        return 24;
    }

    private int getBossBurstCooldown() {
        if (isEasyLevel(level))   return 190;
        if (isMediumLevel(level)) return 145;
        return 100;
    }

    private int getBossSpreadCooldown() {
        if (isEasyLevel(level))   return 105;
        if (isMediumLevel(level)) return 75;
        return 48;
    }

    private int getBossMinionHealth() {
        if (isEasyLevel(level))   return 55;
        if (isMediumLevel(level)) return 95;
        return 140;
    }

    private int getBossMinionSpeed() {
        if (isEasyLevel(level))   return 2;
        if (isMediumLevel(level)) return 3;
        return 4;
    }

    private String getDifficultyName(int n) {
        if (isEasyLevel(n)) return "EASY";
        if (isMediumLevel(n)) return "MEDIUM";
        return "HARD";
    }

    // ══════════════════════════════════════════════════════════════
    //  GAME INIT
    // ══════════════════════════════════════════════════════════════


    // ── Reset all per-run player upgrades (called on fresh game start) ──
    private void resetPlayerStats() {
        playerMagazineSize       = PLAYER_MAG_SIZE_DEFAULT;
        playerAmmo               = PLAYER_MAG_SIZE_DEFAULT;
        playerReloadTimer        = 0;
        playerReloadFrames       = BASE_RELOAD_FRAMES;
        playerMoveSpeedStat      = 6;   // 6px/frame compensates for cardinal-only movement
        playerMaxHealthStat      = 100;
        playerShotCooldownFrames = 12;
        playerBulletPattern      = 1;
        playerBulletSpeedBonus   = 0;
        playerBulletSizeBonus    = 0;
        playerBulletDamage       = 20;
        playerContinuousFire     = false;
        playerSecondLife         = false;
        score                    = 0;
        scoreSaved               = false;
        retriesLeft              = 3;
        isRetrying               = false;
        scoreMultiplier          = 1;
        enemySlowActive          = false;
        pickedCards.clear();  // reset non-stackable card memory on new game
    }

    private void startLevel(int n) {
        // Ensure level is always between 1 and MAX_LEVEL (15) - no level 0
        n = Math.max(1, Math.min(n, MAX_LEVEL));
        level = n;
        
        bullets.clear();
        enemies.clear();
        walls.clear();
        explosions.clear();

        flankRole.clear();
        bossSpecialCooldown.clear();
        bossBurstCooldown.clear();
        bossSummonedMinions.clear();
        wanderDir.clear();
        wanderTimer.clear();
        turnCooldown.clear();
        wallStuckTimer.clear();

        pauseMenuOpen = false;
        settingsOpen = false;

        loadLevelMap(n);
        createWalls(n);

        player = new Tank(80, HEIGHT / 2, 42, 42, TANK_DESIGN_PRIMARY[selectedTankDesign], playerMoveSpeedStat, playerMaxHealthStat, true);
        spawnPlayerSafe();
        player.direction = Direction.RIGHT;
        player.sprite = playerImage;

        playerAmmo = playerMagazineSize;
        playerReloadTimer = 0;
        enemyAmmo.clear();
        enemyReloadTimers.clear();
        bulletDamageMap.clear();

        shootCooldown = 0;
        quotaKilled = 0;
        quotaRequired = getQuotaForLevel(n);
        levelTimeRemaining = getTimeForLevel(n);
        lastSecondTick = System.currentTimeMillis();
        // retriesLeft is only reset via resetPlayerStats() on a brand-new game — never here
        isRetrying = false; // clear flag
        scoreAtLevelStart = score; // snapshot so retry can revert

        switch (n) {
            case 5 -> spawnMiniBossLevel();
            case 10 -> spawnEliteLevel();
            case 15 -> spawnFinalBossLevel();
            default -> spawnEnemies(quotaRequired);
        }

        gameState = GameState.PLAYING;
    }

    private void spawnMiniBossLevel() {
        int spawned = 0, tries = 0;
        while (spawned < 3 && tries < 2000) {
            tries++;
            Tank m = new Tank(
                    480 + random.nextInt(380),
                    TOP_BAR + 40 + random.nextInt(HEIGHT - TOP_BAR - 80),
                    42, 42, new Color(220, 120, 60), 2, 45, false
            );
            m.direction = Direction.values()[random.nextInt(4)];
            m.sprite = enemyImage;
            moveTankToNearestOpen(m, m.x, m.y);
            if (!hitsWallFull(m, 6) && !m.getBounds().intersects(player.getBounds()) && !hitsEnemy(m, null)) {
                enemies.add(m);
                spawned++;
            }
        }
        Tank mb = new Tank(GAME_W - 145, HEIGHT / 2 - 35, 70, 70, new Color(200, 80, 20), 2, 150, false, true);
        moveTankToNearestOpen(mb, GAME_W - 145, HEIGHT / 2 - 35);
        mb.direction = Direction.LEFT; mb.sprite = bossImage; mb.bossUnlocked = false;
        enemies.add(mb);
        quotaRequired = 3;
    }

    private void spawnEliteLevel() {
        int spawned = 0, tries = 0;
        while (spawned < 5 && tries < 2000) {
            tries++;
            Tank m = new Tank(
                    480 + random.nextInt(380),
                    TOP_BAR + 40 + random.nextInt(HEIGHT - TOP_BAR - 80),
                    42, 42, new Color(180, 60, 180), 3, 110, false
            );
            m.direction = Direction.values()[random.nextInt(4)];
            m.sprite = enemyImage;
            moveTankToNearestOpen(m, m.x, m.y);
            if (!hitsWallFull(m, 6) && !m.getBounds().intersects(player.getBounds()) && !hitsEnemy(m, null)) {
                enemies.add(m);
                spawned++;
            }
        }
        Tank elite = new Tank(GAME_W - 150, HEIGHT / 2 - 40, 80, 80, new Color(100, 20, 140), 3, 260, false, true);
        moveTankToNearestOpen(elite, GAME_W - 150, HEIGHT / 2 - 40);
        elite.direction = Direction.LEFT; elite.sprite = bossImage; elite.bossUnlocked = false;
        enemies.add(elite);
        quotaRequired = 5;
    }

    private void spawnFinalBossLevel() {
        int spawned = 0, tries = 0;
        while (spawned < 6 && tries < 2000) {
            tries++;
            Tank m = new Tank(
                    480 + random.nextInt(380),
                    TOP_BAR + 40 + random.nextInt(HEIGHT - TOP_BAR - 80),
                    42, 42, new Color(220, 60, 60), 4, 170, false
            );
            m.direction = Direction.values()[random.nextInt(4)];
            m.sprite = enemyImage;
            moveTankToNearestOpen(m, m.x, m.y);
            if (!hitsWallFull(m, 6) && !m.getBounds().intersects(player.getBounds()) && !hitsEnemy(m, null)) {
                enemies.add(m);
                spawned++;
            }
        }
        Tank boss = new Tank(GAME_W - 165, HEIGHT / 2 - 47, 95, 95, new Color(120, 20, 20), 4, 420, false, true);
        moveTankToNearestOpen(boss, GAME_W - 165, HEIGHT / 2 - 47);
        boss.direction = Direction.LEFT; boss.sprite = bossImage; boss.bossUnlocked = false;
        enemies.add(boss);
        quotaRequired = 6;
    }

    private void spawnEnemies(int count) {
        int spawned = 0, tries = 0;
        int maxTries = 8000;

        while (spawned < count && tries < maxTries) {
            tries++;

            int x = 120 + random.nextInt(GAME_W - 240);
            int y = TOP_BAR + 50 + random.nextInt(Math.max(1, HEIGHT - TOP_BAR - 100));

            Tank e = new Tank(
                    x, y,
                    42, 42,
                    new Color(220, 60, 60),
                    getEnemySpeedForLevel(level),
                    getEnemyHealthForLevel(level),
                    false
            );
            e.direction = Direction.values()[random.nextInt(4)];
            e.sprite = enemyImage;

            // Use stricter margins for new maps (6-15) to ensure enemies spawn only in safe areas
            int margin = (level >= 6) ? 12 : 6;
            
            // Use a LARGER check box (margin px all around) so enemies never
            // visually overlap a wall pixel even if the inset collision box misses it
            if (!e.getBounds().intersects(player.getBounds())
                    && !hitsWallFull(e, margin)
                    && !hitsEnemy(e, null)) {
                enemies.add(e);
                spawned++;
            }
        }
    }

    /** Checks wall collision using a custom inset — 0 or negative = use full/expanded bounds. */
    private boolean hitsWallFull(Tank t, int margin) {
        // Build a rectangle that is margin px LARGER on each side than the tank's actual position
        java.awt.Rectangle bounds = new java.awt.Rectangle(
            t.x - margin, t.y - margin,
            t.width + margin * 2, t.height + margin * 2
        );

        if (usingLevelMap && levelMapMask != null) {
            // Dense pixel scan on the expanded bounds
            int shrink = 1;
            int x0 = bounds.x + shrink, y0 = bounds.y + shrink;
            int x1 = bounds.x + bounds.width  - shrink - 1;
            int y1 = bounds.y + bounds.height - shrink - 1;
            for (int sy = y0; sy <= y1; sy += 3) {
                for (int sx = x0; sx <= x1; sx += 3) {
                    if (isSolidAtScreenPoint(sx, sy)) return true;
                }
                if (isSolidAtScreenPoint(x1, sy)) return true;
            }
            for (int sx = x0; sx <= x1; sx += 3) {
                if (isSolidAtScreenPoint(sx, y1)) return true;
            }
            return false;
        }

        for (Wall w : walls) {
            if (bounds.intersects(w.getBounds())) return true;
        }
        return false;
    }

    private void createWalls(int n) {
        if (usingLevelMap && levelMapMask != null) return;

        Color c = new Color(130,90,45);
        switch (n) {
            case 1  -> { walls.add(new Wall(110,80,80,28,c)); walls.add(new Wall(110,580,80,28,c)); walls.add(new Wall(270,140,160,28,c)); walls.add(new Wall(510,320,28,200,c)); walls.add(new Wall(690,140,160,28,c)); walls.add(new Wall(410,505,130,28,c)); walls.add(new Wall(680,430,120,28,c)); }
            case 2  -> { walls.add(new Wall(155,100,28,215,c)); walls.add(new Wall(370,260,195,28,c)); walls.add(new Wall(665,400,28,200,c)); walls.add(new Wall(240,490,145,28,c)); walls.add(new Wall(545,100,145,28,c)); walls.add(new Wall(795,250,145,28,c)); }
            case 3  -> { walls.add(new Wall(100,240,190,28,c)); walls.add(new Wall(315,90,28,215,c)); walls.add(new Wall(555,280,215,28,c)); walls.add(new Wall(755,100,28,160,c)); walls.add(new Wall(115,490,190,28,c)); walls.add(new Wall(615,500,130,28,c)); walls.add(new Wall(820,390,28,160,c)); }
            case 4  -> { walls.add(new Wall(155,100,28,200,c)); walls.add(new Wall(155,410,28,200,c)); walls.add(new Wall(375,250,215,28,c)); walls.add(new Wall(675,160,28,260,c)); walls.add(new Wall(425,510,160,28,c)); walls.add(new Wall(765,500,145,28,c)); }
            case 5  -> { walls.add(new Wall(95,130,190,28,c)); walls.add(new Wall(95,510,190,28,c)); walls.add(new Wall(345,130,28,380,c)); walls.add(new Wall(595,240,190,28,c)); walls.add(new Wall(615,490,160,28,c)); walls.add(new Wall(820,100,28,215,c)); }
            case 6  -> { walls.add(new Wall(115,80,28,190,c)); walls.add(new Wall(115,420,28,190,c)); walls.add(new Wall(335,260,215,28,c)); walls.add(new Wall(635,80,28,190,c)); walls.add(new Wall(635,420,28,190,c)); walls.add(new Wall(295,520,215,28,c)); walls.add(new Wall(800,315,130,28,c)); }
            case 7  -> { walls.add(new Wall(80,210,230,28,c)); walls.add(new Wall(80,470,230,28,c)); walls.add(new Wall(365,110,28,200,c)); walls.add(new Wall(365,400,28,200,c)); walls.add(new Wall(595,260,215,28,c)); walls.add(new Wall(695,510,130,28,c)); walls.add(new Wall(835,100,28,200,c)); }
            case 8  -> { walls.add(new Wall(95,100,28,200,c)); walls.add(new Wall(95,420,28,200,c)); walls.add(new Wall(305,260,195,28,c)); walls.add(new Wall(575,100,28,200,c)); walls.add(new Wall(575,420,28,200,c)); walls.add(new Wall(755,260,130,28,c)); walls.add(new Wall(355,510,130,28,c)); walls.add(new Wall(745,510,100,28,c)); }
            case 9  -> { walls.add(new Wall(80,150,190,28,c)); walls.add(new Wall(80,510,190,28,c)); walls.add(new Wall(275,100,28,200,c)); walls.add(new Wall(275,410,28,200,c)); walls.add(new Wall(505,270,235,28,c)); walls.add(new Wall(805,100,28,200,c)); walls.add(new Wall(675,510,130,28,c)); walls.add(new Wall(455,510,130,28,c)); }
            case 10 -> { walls.add(new Wall(75,110,215,28,c)); walls.add(new Wall(75,555,215,28,c)); walls.add(new Wall(275,200,28,315,c)); walls.add(new Wall(475,110,215,28,c)); walls.add(new Wall(475,555,215,28,c)); walls.add(new Wall(695,200,28,315,c)); walls.add(new Wall(375,330,135,28,c)); walls.add(new Wall(575,430,155,28,c)); walls.add(new Wall(105,360,115,28,c)); walls.add(new Wall(775,360,115,28,c)); }
            case 11 -> { walls.add(new Wall(90,120,200,28,c)); walls.add(new Wall(90,540,200,28,c)); walls.add(new Wall(300,220,28,260,c)); walls.add(new Wall(500,120,180,28,c)); walls.add(new Wall(700,220,28,260,c)); walls.add(new Wall(490,540,200,28,c)); walls.add(new Wall(390,340,130,28,c)); }
            case 12 -> { walls.add(new Wall(80,100,28,240,c)); walls.add(new Wall(80,390,28,240,c)); walls.add(new Wall(280,240,220,28,c)); walls.add(new Wall(560,100,28,240,c)); walls.add(new Wall(560,390,28,240,c)); walls.add(new Wall(740,240,200,28,c)); walls.add(new Wall(380,450,160,28,c)); walls.add(new Wall(820,440,28,180,c)); }
            case 13 -> { walls.add(new Wall(100,100,180,28,c)); walls.add(new Wall(100,560,180,28,c)); walls.add(new Wall(340,200,28,300,c)); walls.add(new Wall(540,280,180,28,c)); walls.add(new Wall(760,100,28,250,c)); walls.add(new Wall(760,400,28,230,c)); walls.add(new Wall(440,460,160,28,c)); walls.add(new Wall(200,350,100,28,c)); }
            case 14 -> { walls.add(new Wall(75,80,28,280,c)); walls.add(new Wall(75,360,28,280,c)); walls.add(new Wall(260,160,240,28,c)); walls.add(new Wall(260,520,240,28,c)); walls.add(new Wall(540,80,28,280,c)); walls.add(new Wall(540,360,28,280,c)); walls.add(new Wall(720,200,200,28,c)); walls.add(new Wall(720,480,200,28,c)); walls.add(new Wall(380,300,130,28,c)); }
            case 15 -> { walls.add(new Wall(60,80,240,28,c)); walls.add(new Wall(60,580,240,28,c)); walls.add(new Wall(260,160,28,360,c)); walls.add(new Wall(460,80,200,28,c)); walls.add(new Wall(460,580,200,28,c)); walls.add(new Wall(680,160,28,360,c)); walls.add(new Wall(360,300,100,28,c)); walls.add(new Wall(560,380,120,28,c)); walls.add(new Wall(110,320,100,28,c)); walls.add(new Wall(800,300,28,200,c)); }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GAME LOOP
    // ══════════════════════════════════════════════════════════════
    @Override
    public void actionPerformed(ActionEvent e) {
        titlePulse += 0.05f;
        if (titlePulse > 6.283f) titlePulse -= 6.283f; // wrap at 2π
        if (gameState == GameState.PLAYING) updateGame();
        else updateExplosions();
        repaint();
    }

    private void updateGame() {
        tick();
        updateReloads();
        move();
        shootPlayer();
        updateBullets();
        updateEnemies();
        updateExplosions();
        unlockBoss();
        checkProgress();
        if (shootCooldown > 0) shootCooldown--;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (now - lastSecondTick >= 1000) {
            lastSecondTick = now;
            if (--levelTimeRemaining <= 0) {
                // Route through handlePlayerDefeat so Second Life can trigger on timeout too
                if (player != null) player.health = 0;
                handlePlayerDefeat();
            }
        }
    }

    private void updateReloads() {
        if (playerReloadTimer > 0) {
            playerReloadTimer--;
            if (playerReloadTimer == 0) {
                playerAmmo = playerMagazineSize;
            }
        }

        for (Tank en : new ArrayList<>(enemyReloadTimers.keySet())) {
            int time = enemyReloadTimers.getOrDefault(en, 0);
            if (time > 0) {
                time--;
                enemyReloadTimers.put(en, time);
                if (time == 0) {
                    enemyAmmo.put(en, ENEMY_MAG_SIZE_DEFAULT);
                }
            }
        }
    }

    private Rectangle getWallCollisionBox(Tank t) {
        // 4px inset — tight enough to feel solid, small enough to avoid sprite-edge false positives
        int inset = 4;
        int w = Math.max(8, t.width  - inset * 2);
        int h = Math.max(8, t.height - inset * 2);
        return new Rectangle(t.x + inset, t.y + inset, w, h);
    }
    private void move() {
        // Determine intended direction - preventing diagonal movement
        Direction moveDir = null;
        if (up)         moveDir = Direction.UP;
        else if (down)  moveDir = Direction.DOWN;
        else if (left)  moveDir = Direction.LEFT;
        else if (right) moveDir = Direction.RIGHT;

        if (moveDir == null) return;

        // Apply movement only in the chosen cardinal direction
        player.direction = moveDir;
        int dx = 0, dy = 0;
        switch (moveDir) {
            case UP    -> dy = -1;
            case DOWN  -> dy = 1;
            case LEFT  -> dx = -1;
            case RIGHT -> dx = 1;
        }

        for (int s = 0; s < player.speed; s++) {
            player.x += dx;
            player.y += dy;
            clamp(player);
            if (hitsWall(player)) {
                player.x -= dx;
                player.y -= dy;
                break;
            }
        }
    }

    private void addBullet(Bullet b, int damage) {
        bullets.add(b);
        bulletDamageMap.put(b, damage);
    }

    private boolean firePattern(Tank shooter, int pattern, int damage, int speedBonus, int sizeBonus, boolean fromPlayer) {
        int cx = shooter.x + shooter.width / 2;
        int cy = shooter.y + shooter.height / 2;

        int size = (shooter.isBoss ? 12 : 8) + sizeBonus;
        int speed = (shooter.isBoss ? 10 : 8) + speedBonus;

        int[] spread;
        if (pattern >= 6) spread = new int[]{-5, -3, -1, 1, 3, 5};
        else if (pattern >= 5) spread = new int[]{-4, -2, 0, 2, 4};
        else if (pattern == 3) spread = new int[]{-2, 0, 2};
        else spread = new int[]{0};

        for (int offset : spread) {
            int dx = 0;
            int dy = 0;

            switch (shooter.direction) {
                case UP -> {
                    dx = offset;
                    dy = -speed;
                }
                case DOWN -> {
                    dx = offset;
                    dy = speed;
                }
                case LEFT -> {
                    dx = -speed;
                    dy = offset;
                }
                case RIGHT -> {
                    dx = speed;
                    dy = offset;
                }
            }

            Bullet b = new Bullet(cx - size / 2, cy - size / 2, size, size, dx, dy, fromPlayer);
            addBullet(b, damage);
        }
        return true;
    }

    private void startPlayerReload() {
        if (playerReloadTimer <= 0) {
            playerReloadTimer = playerReloadFrames;
        }
    }

    private void startEnemyReload(Tank en) {
        if (enemyReloadTimers.getOrDefault(en, 0) <= 0) {
            enemyReloadTimers.put(en, BASE_RELOAD_FRAMES);
        }
    }

    private void handlePlayerDefeat() {
        if (playerSecondLife) {
            playerSecondLife = false;
            // Revive: restore half HP (minimum 40), full ammo, reset reload, reset timer
            player.health = Math.max(40, player.maxHealth / 2);
            playerAmmo = playerMagazineSize;
            playerReloadTimer = 0;
            levelTimeRemaining = Math.max(levelTimeRemaining, 15); // give at least 15s after revive
            explosions.add(new Explosion(player.x + player.width / 2, player.y + player.height / 2));
            SoundManager.playSound("sounds/revive.wav"); // plays if file exists, silent otherwise
            return;
        }
        // No revive — game over
        score = scoreAtLevelStart;
        saveScore();
        gameState = GameState.GAME_OVER;
    }

    private void shootPlayer() {
        if (!shoot) return;
        if (playerReloadTimer > 0) return;
        if (shootCooldown > 0) return;

        if (playerAmmo <= 0) {
            startPlayerReload();
            return;
        }

        firePattern(
                player,
                playerBulletPattern,
                playerBulletDamage,
                playerBulletSpeedBonus,
                playerBulletSizeBonus,
                true
        );

        playerAmmo--;
        shootCooldown = playerContinuousFire ? 4 : playerShotCooldownFrames;

        if (playerAmmo <= 0) {
            startPlayerReload();
        }
    }

    private void updateBullets() {
        Iterator<Bullet> bi = bullets.iterator();
        while (bi.hasNext()) {
            Bullet b = bi.next();

            // Step the bullet 1px at a time so it can't tunnel through thin walls
            int absDx = Math.abs(b.dx);
            int absDy = Math.abs(b.dy);
            int steps = Math.max(1, Math.max(absDx, absDy));
            double sx = (double) b.dx / steps;
            double sy = (double) b.dy / steps;
            double bxD = b.x, byD = b.y;

            boolean removed = false;
            for (int s = 0; s < steps; s++) {
                bxD += sx;
                byD += sy;
                b.x = (int) Math.round(bxD);
                b.y = (int) Math.round(byD);

                if (b.x < 0 || b.x > GAME_W || b.y < 0 || b.y > HEIGHT) {
                    bulletDamageMap.remove(b);
                    bi.remove();
                    removed = true;
                    break;
                }

                boolean wallHit = false;
                if (usingLevelMap && levelMapMask != null && hitsLevelMap(b.getBounds())) {
                    explosions.add(new Explosion(b.x, b.y));
                    bulletDamageMap.remove(b);
                    bi.remove();
                    removed = true;
                    wallHit = true;
                }
                if (!wallHit) {
                    for (Wall w : walls) {
                        if (b.getBounds().intersects(w.getBounds())) {
                            explosions.add(new Explosion(b.x, b.y));
                            bulletDamageMap.remove(b);
                            bi.remove();
                            removed = true;
                            wallHit = true;
                            break;
                        }
                    }
                }
                if (wallHit) break;

                // Tank hit checks
                if (b.fromPlayer) {
                    boolean hit = false;
                    Iterator<Tank> ei = enemies.iterator();
                    while (ei.hasNext()) {
                        Tank en = ei.next();
                        if (en.isBoss && !en.bossUnlocked) continue;
                        if (b.getBounds().intersects(en.getBounds())) {
                            int damage = bulletDamageMap.getOrDefault(b, playerBulletDamage);
                            en.health -= damage;
                            explosions.add(new Explosion(en.x + en.width/2, en.y + en.height/2));
                            bulletDamageMap.remove(b);
                            bi.remove();
                            removed = true;
                            if (en.health <= 0) {
                                explosions.add(new Explosion(en.x + en.width/2, en.y + en.height/2));
                                if (!en.isBoss) { quotaKilled++; score += 10 * level * scoreMultiplier; }
                                else            { score += 500 * scoreMultiplier; }
                                ei.remove();
                            }
                            hit = true;
                            break;
                        }
                    }
                    if (hit) break;
                } else {
                    if (b.getBounds().intersects(player.getBounds())) {
                        int damage = bulletDamageMap.getOrDefault(b, 10);
                        player.health -= damage;
                        explosions.add(new Explosion(player.x + player.width/2, player.y + player.height/2));
                        bulletDamageMap.remove(b);
                        bi.remove();
                        removed = true;
                        if (player.health <= 0) handlePlayerDefeat();
                        break;
                    }
                }
            }
            if (removed) continue; // already removed — skip
        }
    }

    private void updateEnemies() {
        cleanupEnemyAiState();

        for (int index = 0; index < enemies.size(); index++) {
            Tank en = enemies.get(index);

            if (en.isBoss && !en.bossUnlocked) continue;

            flankRole.putIfAbsent(en, index % 4);
            bossSpecialCooldown.putIfAbsent(en, 0);
            bossBurstCooldown.putIfAbsent(en, 0);
            bossSummonedMinions.putIfAbsent(en, false);
            enemyAmmo.putIfAbsent(en, ENEMY_MAG_SIZE_DEFAULT);
            enemyReloadTimers.putIfAbsent(en, 0);

            if (bossSpecialCooldown.get(en) > 0) bossSpecialCooldown.put(en, bossSpecialCooldown.get(en) - 1);
            if (bossBurstCooldown.get(en) > 0)   bossBurstCooldown.put(en, bossBurstCooldown.get(en) - 1);

            // ── 1. Choose direction ───────────────────────────────────
            Direction nextDir = chooseCombatDirection(en, index);
            if (nextDir != null) en.direction = nextDir;

            // ── 2. Move 1px at a time so enemies can't tunnel thin walls ─
            int moveAmount = en.speed * (en.isBoss ? 2 : 1);
            int ox = en.x, oy = en.y;

            int ddx = 0, ddy = 0;
            switch (en.direction) {
                case UP    -> ddy = -1;
                case DOWN  -> ddy =  1;
                case LEFT  -> ddx = -1;
                case RIGHT -> ddx =  1;
            }
            for (int s = 0; s < moveAmount; s++) {
                en.x += ddx; en.y += ddy;
                clamp(en);
                if (isBlockedPosition(en)) {
                    en.x -= ddx; en.y -= ddy;
                    break;
                }
                ox = en.x; oy = en.y; // update "last good" position
            }
            // After stepping, ox/oy reflect last valid position
            // (they are already set correctly above)

            // ── 3. Separation push if still overlapping another enemy ──
            {
                Rectangle eb = getWallCollisionBox(en);
                for (Tank other : enemies) {
                    if (other == en) continue;
                    Rectangle ob = getWallCollisionBox(other);
                    if (!eb.intersects(ob)) continue;
                    int overlapX = (eb.x + eb.width / 2) - (ob.x + ob.width / 2);
                    int overlapY = (eb.y + eb.height / 2) - (ob.y + ob.height / 2);
                    int pushX = 0, pushY = 0;
                    if (Math.abs(overlapX) >= Math.abs(overlapY)) {
                        pushX = (overlapX >= 0) ? 2 : -2;
                    } else {
                        pushY = (overlapY >= 0) ? 2 : -2;
                    }
                    int sx = en.x, sy = en.y;
                    en.x += pushX; en.y += pushY; clamp(en);
                    if (hitsWall(en)) { en.x = sx; en.y = sy; }
                    break;
                }
            }

            // ── 4. If now blocked, pick an open direction ─────────────
            if (isBlockedPosition(en)) {
                en.x = ox; en.y = oy;
                turnCooldown.put(en, 0);
                wallStuckTimer.put(en, 0);

                Direction open = findAnyOpenDirection(en);
                if (open != null) {
                    en.direction = open;
                    int odx = 0, ody = 0;
                    switch (open) {
                        case UP    -> ody = -1;
                        case DOWN  -> ody =  1;
                        case LEFT  -> odx = -1;
                        case RIGHT -> odx =  1;
                    }
                    for (int s = 0; s < moveAmount; s++) {
                        en.x += odx; en.y += ody; clamp(en);
                        if (isBlockedPosition(en)) { en.x -= odx; en.y -= ody; break; }
                    }
                    if (isBlockedPosition(en)) { en.x = ox; en.y = oy; }
                }
            }

            // ── 4. Shooting ──────────────────────────────────────────
            en.shootCounter++;
            int enemyReload = enemyReloadTimers.getOrDefault(en, 0);
            int ammo = enemyAmmo.getOrDefault(en, ENEMY_MAG_SIZE_DEFAULT);

            Direction shootDir = getShootDirection(en);
            if (shootDir != null && enemyReload == 0 && en.shootCounter >= getEnemyFireRate(en) && ammo > 0) {
                Direction savedDir = en.direction;
                en.direction = shootDir;
                firePattern(en, 1, en.isBoss ? 12 : 10, 0, 0, false);
                en.direction = savedDir;
                en.shootCounter = 0;
                ammo--;
                enemyAmmo.put(en, ammo);
                if (ammo <= 0) startEnemyReload(en);
            }

            if (en.isBoss) performBossSpecials(en);
        }
    }

    /** Returns true if the tank is currently overlapping a wall, enemy, or the player. */
    private boolean isBlockedPosition(Tank en) {
        if (hitsWall(en)) return true;
        Rectangle eb = getWallCollisionBox(en);
        if (eb.intersects(getWallCollisionBox(player))) return true;
        for (Tank other : enemies) {
            if (other == en) continue;
            if (eb.intersects(getWallCollisionBox(other))) return true;
        }
        return false;
    }

    /** Finds the first open direction, trying perpendicular axes before reversing. */
    private Direction findAnyOpenDirection(Tank en) {
        // Try perpendicular to current direction first (turn, don't reverse)
        Direction[] perp = perpendicularDirs(en.direction);
        for (Direction d : perp) {
            if (canMoveInDirection(en, d)) return d;
        }
        // Try all four
        for (Direction d : Direction.values()) {
            if (canMoveInDirection(en, d)) return d;
        }
        return null;
    }

    private void cleanupEnemyAiState() {
        flankRole.keySet().retainAll(enemies);
        bossSpecialCooldown.keySet().retainAll(enemies);
        bossBurstCooldown.keySet().retainAll(enemies);
        bossSummonedMinions.keySet().retainAll(enemies);
        enemyAmmo.keySet().retainAll(enemies);
        enemyReloadTimers.keySet().retainAll(enemies);
        wanderDir.keySet().retainAll(enemies);
        wanderTimer.keySet().retainAll(enemies);
        turnCooldown.keySet().retainAll(enemies);
        wallStuckTimer.keySet().retainAll(enemies);
    }

    private Direction chooseCombatDirection(Tank en, int index) {
        int px = player.x + player.width / 2;
        int py = player.y + player.height / 2;
        int ex = en.x + en.width / 2;
        int ey = en.y + en.height / 2;
        double dist = Point.distance(ex, ey, px, py);

        // ── Turn-rate limiter — ONLY when current direction is actually open ──
        // If the current direction is blocked, skip the cooldown and pick a new one.
        if (!en.isBoss) {
            int tc = turnCooldown.getOrDefault(en, 0);
            if (tc > 0 && canMoveInDirection(en, en.direction)) {
                turnCooldown.put(en, tc - 1);
                return en.direction; // hold current direction — it's open
            }
        }

        // ── Outside awareness range → random wander, NO shooting ──────
        if (!en.isBoss && dist > AWARENESS_RANGE) {
            Direction wd = getWanderDirection(en);
            if (wd != en.direction) turnCooldown.put(en, ENEMY_TURN_COOLDOWN + random.nextInt(10));
            return wd;
        }

        // ── Inside range but no line of sight → use pathfinding, not raw chase ──
        // This prevents mobs from spinning/thrashing when the player is behind a wall.
        boolean hasHorizontalLOS = Math.abs((player.y + player.height / 2) - (en.y + en.height / 2)) <= 24
                && hasLineOfSight(en, player, true);
        boolean hasVerticalLOS   = Math.abs((player.x + player.width / 2) - (en.x + en.width / 2)) <= 24
                && hasLineOfSight(en, player, false);
        boolean hasAnyLOS = hasHorizontalLOS || hasVerticalLOS;

        // ── Inside range: chase/combat logic ──────────────────────────
        // If no LOS, only use pathfinding (BFS around walls) — never raw directCardinalTo.
        Direction chosen;
        if (!en.isBoss && !hasAnyLOS) {
            // Use BFS pathfinding so the mob navigates around walls instead of thrashing
            chosen = findPathDirection(en, px, py);
            if (chosen == null) chosen = getWanderDirection(en); // fully stuck — wander
        } else {
            chosen = chooseCombatDirectionInner(en, index, px, py, dist);
        }
        if (chosen != null && chosen != en.direction) {
            turnCooldown.put(en, en.isBoss ? 6 : ENEMY_TURN_COOLDOWN / 2);
        }
        return chosen;
    }

    /** Wander: pick a random cardinal direction, hold it 60-120 frames (slower, more natural). */
    private Direction getWanderDirection(Tank en) {
        wanderDir.putIfAbsent(en, Direction.values()[random.nextInt(4)]);
        wanderTimer.putIfAbsent(en, 0);

        int wanderTime = wanderTimer.get(en);
        Direction dir = wanderDir.get(en);

        if (wanderTime <= 0 || !canMoveInDirection(en, dir)) {
            // Build a list of open directions, shuffle for randomness
            List<Direction> shuffled = new ArrayList<>(Arrays.asList(Direction.values()));
            Collections.shuffle(shuffled, random);

            Direction newDir = null;
            // Prefer a direction different from current to avoid back-tracking
            for (Direction d : shuffled) {
                if (canMoveInDirection(en, d) && d != dir) { newDir = d; break; }
            }
            // If nothing different works, accept current or any open dir
            if (newDir == null) {
                for (Direction d : shuffled) {
                    if (canMoveInDirection(en, d)) { newDir = d; break; }
                }
            }
            if (newDir == null) newDir = dir; // completely stuck — keep dir, wall handler will deal

            dir = newDir;
            wanderDir.put(en, dir);
            wanderTimer.put(en, 60 + random.nextInt(60));
        } else {
            wanderTimer.put(en, wanderTime - 1);
        }
        return dir;
    }

    /** Inner combat logic (cardinal-only movement, no diagonal shortcuts). */
    private Direction chooseCombatDirectionInner(Tank en, int index, int px, int py, @SuppressWarnings("unused") double dist) {
        Direction chosen = null;

        if (isEasyLevel(level)) {
            chosen = directCardinalTo(en, px, py);

        } else if (isMediumLevel(level)) {
            Bullet danger = findDangerousBullet(en);
            if (danger != null && random.nextInt(100) < 35) {
                Direction dodge = chooseDodgeDirection(en, danger);
                if (dodge != null) chosen = dodge;
            }
            if (chosen == null) chosen = findPathDirection(en, px, py);
            if (chosen == null) chosen = directCardinalTo(en, px, py);

        } else {
            // Hard: dodge + flank + path
            Bullet danger = findDangerousBullet(en);
            if (danger != null) {
                Direction dodge = chooseDodgeDirection(en, danger);
                if (dodge != null) chosen = dodge;
            }

            if (chosen == null) {
                boolean alignedH = Math.abs((player.y + player.height / 2) - (en.y + en.height / 2)) <= 18
                        && hasLineOfSight(en, player, true);
                boolean alignedV = Math.abs((player.x + player.width / 2) - (en.x + en.width / 2)) <= 18
                        && hasLineOfSight(en, player, false);
                if (alignedH || alignedV) {
                    chosen = chooseStrafeDirection(en, index, alignedH);
                }
            }

            if (chosen == null) {
                Point target = getFlankTarget(en, index);
                chosen = findPathDirection(en, target.x, target.y);
                if (chosen == null) chosen = directCardinalTo(en, target.x, target.y);
            }
        }

        // ── Safety check: if the chosen direction is blocked, pick any open one ──
        // This prevents mobs from endlessly trying to move UP/DOWN in a narrow
        // horizontal corridor (or LEFT/RIGHT in a narrow vertical one).
        if (chosen != null && !canMoveInDirection(en, chosen)) {
            // Try the perpendicular axes first (not reversing — no retreating)
            Direction[] perpendicular = perpendicularDirs(chosen);
            for (Direction d : perpendicular) {
                if (canMoveInDirection(en, d)) { chosen = d; break; }
            }
            // If still blocked, try all four
            if (!canMoveInDirection(en, chosen)) {
                for (Direction d : Direction.values()) {
                    if (canMoveInDirection(en, d)) { chosen = d; break; }
                }
            }
        }
        return chosen;
    }

    /** Returns the two directions perpendicular to the given one. */
    private Direction[] perpendicularDirs(Direction d) {
        return switch (d) {
            case LEFT, RIGHT -> new Direction[]{ Direction.UP, Direction.DOWN };
            case UP, DOWN    -> new Direction[]{ Direction.LEFT, Direction.RIGHT };
        };
    }

    /**
     * Cardinal-only direction toward target.
     * Checks which axes are actually open before picking.
     * No diagonal movement — tanks must turn to change axis.
     */
    private Direction directCardinalTo(Tank en, int tx, int ty) {
        int ex = en.x + en.width / 2;
        int ey = en.y + en.height / 2;
        int dx = tx - ex;
        int dy = ty - ey;

        // Determine which axes are actually open
        boolean canH = canMoveInDirection(en, dx < 0 ? Direction.LEFT : Direction.RIGHT);
        boolean canV = canMoveInDirection(en, dy < 0 ? Direction.UP   : Direction.DOWN);

        // If only one axis is open, use it (narrow corridor handling)
        if (canH && !canV) return dx < 0 ? Direction.LEFT : Direction.RIGHT;
        if (canV && !canH) return dy < 0 ? Direction.UP   : Direction.DOWN;

        // Both open: prefer the axis the tank is already on (less spinning),
        // then fall back to dominant-distance axis
        boolean currentlyH = (en.direction == Direction.LEFT || en.direction == Direction.RIGHT);
        boolean currentlyV = (en.direction == Direction.UP   || en.direction == Direction.DOWN);

        if (currentlyH) {
            // Continue horizontal if still far enough horizontally
            if (Math.abs(dx) >= 16) return dx < 0 ? Direction.LEFT : Direction.RIGHT;
            // Close horizontally — switch to vertical
            return dy < 0 ? Direction.UP : Direction.DOWN;
        }
        if (currentlyV) {
            // Continue vertical if still far enough vertically
            if (Math.abs(dy) >= 16) return dy < 0 ? Direction.UP : Direction.DOWN;
            // Close vertically — switch to horizontal
            return dx < 0 ? Direction.LEFT : Direction.RIGHT;
        }

        // Default: dominant axis
        if (Math.abs(dx) > Math.abs(dy)) return dx < 0 ? Direction.LEFT : Direction.RIGHT;
        return dy < 0 ? Direction.UP : Direction.DOWN;
    }

    private Point getFlankTarget(Tank en, int index) {
        int px = player.x + player.width / 2;
        int py = player.y + player.height / 2;

        if (en.isBoss) {
            return new Point(px, py);
        }

        int role = flankRole.getOrDefault(en, index % 4);
        int radius = FLANK_RADIUS + (role % 2) * 20;

        Point p = switch (role) {
            case 0 -> new Point(px - radius, py);
            case 1 -> new Point(px + radius, py);
            case 2 -> new Point(px, py - radius);
            default -> new Point(px, py + radius);
        };

        p.x = Math.max(30, Math.min(GAME_W - 30, p.x));
        p.y = Math.max(TOP_BAR + 30, Math.min(HEIGHT - 30, p.y));
        return p;
    }

    private Direction chooseStrafeDirection(Tank en, int index, boolean horizontalShotLine) {
        int role = flankRole.getOrDefault(en, index % 4);

        Direction first;
        Direction second;

        if (horizontalShotLine) {
            first = (role % 2 == 0) ? Direction.UP : Direction.DOWN;
            second = (first == Direction.UP) ? Direction.DOWN : Direction.UP;
        } else {
            first = (role % 2 == 0) ? Direction.LEFT : Direction.RIGHT;
            second = (first == Direction.LEFT) ? Direction.RIGHT : Direction.LEFT;
        }

        if (canMoveInDirection(en, first)) return first;
        if (canMoveInDirection(en, second)) return second;
        return null;
    }

    private Direction chooseFallbackDirection(Tank en, int index) {
        Point target = getFlankTarget(en, index);

        Direction bestDir = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            if (!canMoveInDirection(en, dir)) continue;

            int tx = en.x + en.width / 2;
            int ty = en.y + en.height / 2;
            int step = en.speed * (en.isBoss ? 2 : 1);

            switch (dir) {
                case UP    -> ty -= step;
                case DOWN  -> ty += step;
                case LEFT  -> tx -= step;
                case RIGHT -> tx += step;
            }

            double dist = Point.distance(tx, ty, target.x, target.y);
            if (dist < bestDist) {
                bestDist = dist;
                bestDir = dir;
            }
        }

        return bestDir;
    }

    private Direction findPathDirection(Tank en, int targetX, int targetY) {
        int cols = (GAME_W + AI_CELL - 1) / AI_CELL;
        int rows = ((HEIGHT - TOP_BAR) + AI_CELL - 1) / AI_CELL;

        int startCellX = Math.max(0, Math.min(cols - 1, (en.x + en.width / 2) / AI_CELL));
        int startCellY = Math.max(0, Math.min(rows - 1, (en.y + en.height / 2 - TOP_BAR) / AI_CELL));

        int targetCellX = Math.max(0, Math.min(cols - 1, targetX / AI_CELL));
        int targetCellY = Math.max(0, Math.min(rows - 1, (targetY - TOP_BAR) / AI_CELL));

        boolean[][] visited = new boolean[rows][cols];
        int[][] prevX = new int[rows][cols];
        int[][] prevY = new int[rows][cols];

        for (int y = 0; y < rows; y++) {
            Arrays.fill(prevX[y], -1);
            Arrays.fill(prevY[y], -1);
        }

        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startCellX, startCellY});
        visited[startCellY][startCellX] = true;
        prevX[startCellY][startCellX] = -2;
        prevY[startCellY][startCellX] = -2;

        int bestX = startCellX;
        int bestY = startCellY;
        double bestScore = Point.distance(startCellX, startCellY, targetCellX, targetCellY);

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int cx = cur[0];
            int cy = cur[1];

            double distToTarget = Point.distance(cx, cy, targetCellX, targetCellY);
            if (distToTarget < bestScore) {
                bestScore = distToTarget;
                bestX = cx;
                bestY = cy;
            }

            if (cx == targetCellX && cy == targetCellY) {
                bestX = cx;
                bestY = cy;
                break;
            }

            for (int[] d : dirs) {
                int nx = cx + d[0];
                int ny = cy + d[1];

                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue;
                if (visited[ny][nx]) continue;
                if (!canOccupyCell(en, nx, ny)) continue;

                visited[ny][nx] = true;
                prevX[ny][nx] = cx;
                prevY[ny][nx] = cy;
                queue.add(new int[]{nx, ny});
            }
        }

        int stepX = bestX;
        int stepY = bestY;

        while (prevX[stepY][stepX] != -2 && prevX[stepY][stepX] != -1) {
            int px = prevX[stepY][stepX];
            int py = prevY[stepY][stepX];

            if (px == startCellX && py == startCellY) break;

            stepX = px;
            stepY = py;
        }

        if (stepX > startCellX) return Direction.RIGHT;
        if (stepX < startCellX) return Direction.LEFT;
        if (stepY > startCellY) return Direction.DOWN;
        if (stepY < startCellY) return Direction.UP;

        return null;
    }

    private boolean canOccupyCell(Tank self, int cellX, int cellY) {
        int centerX = cellX * AI_CELL + AI_CELL / 2;
        int centerY = TOP_BAR + cellY * AI_CELL + AI_CELL / 2;

        // Match getWallCollisionBox inset (4px)
        int inset = 4;
        int w = Math.max(8, self.width  - inset * 2);
        int h = Math.max(8, self.height - inset * 2);

        Rectangle r = new Rectangle(
                centerX - w / 2,
                centerY - h / 2,
                w, h
        );

        if (r.x < 0 || r.x + r.width  > GAME_W)  return false;
        if (r.y < TOP_BAR || r.y + r.height > HEIGHT) return false;

        if (usingLevelMap && levelMapMask != null && hitsLevelMap(r)) return false;

        for (Wall wall : walls) {
            if (r.intersects(wall.getBounds())) return false;
        }

        return true;
    }



    private boolean canMoveInDirection(Tank en, Direction dir) {
        int ox = en.x;
        int oy = en.y;

        int step = en.speed * (en.isBoss ? 2 : 1);
        switch (dir) {
            case UP    -> en.y -= step;
            case DOWN  -> en.y += step;
            case LEFT  -> en.x -= step;
            case RIGHT -> en.x += step;
        }

        clamp(en);

        boolean blocked = hitsWall(en) || hitsEnemy(en, en) || getWallCollisionBox(en).intersects(getWallCollisionBox(player));

        en.x = ox;
        en.y = oy;
        return !blocked;
    }

    private Bullet findDangerousBullet(Tank en) {
        int ex = en.x + en.width / 2;
        int ey = en.y + en.height / 2;

        for (Bullet b : bullets) {
            if (!b.fromPlayer) continue;

            int bx = b.x + b.width / 2;
            int by = b.y + b.height / 2;

            if (Math.abs(by - ey) <= en.height / 2 + 10) {
                if (b.dx > 0 && bx < ex && ex - bx <= DODGE_RANGE) return b;
                if (b.dx < 0 && bx > ex && bx - ex <= DODGE_RANGE) return b;
            }

            if (Math.abs(bx - ex) <= en.width / 2 + 10) {
                if (b.dy > 0 && by < ey && ey - by <= DODGE_RANGE) return b;
                if (b.dy < 0 && by > ey && by - ey <= DODGE_RANGE) return b;
            }
        }

        return null;
    }

    private Direction chooseDodgeDirection(Tank en, Bullet danger) {
        Direction first;
        Direction second;

        if (danger.dx != 0) {
            first = (player.y < en.y) ? Direction.DOWN : Direction.UP;
            second = (first == Direction.UP) ? Direction.DOWN : Direction.UP;
        } else {
            first = (player.x < en.x) ? Direction.RIGHT : Direction.LEFT;
            second = (first == Direction.LEFT) ? Direction.RIGHT : Direction.LEFT;
        }

        if (canMoveInDirection(en, first)) return first;
        if (canMoveInDirection(en, second)) return second;
        return null;
    }

    /** Returns the direction to shoot (toward player if aligned), or null if shouldn't shoot.
     *  Does NOT mutate en.direction — caller fires in the returned direction without changing movement. */
    private Direction getShootDirection(Tank en) {
        int ex = en.x + en.width / 2;
        int ey = en.y + en.height / 2;
        int px = player.x + player.width / 2;
        int py = player.y + player.height / 2;

        double dist = Point.distance(ex, ey, px, py);

        if (!en.isBoss && dist > AWARENESS_RANGE) return null;

        int dx = px - ex;
        int dy = py - ey;

        // Horizontal alignment: check both that tanks share roughly the same Y
        // AND that the horizontal path (left/right) is clear of walls.
        if (Math.abs(dy) <= 18 && hasLineOfSight(en, player, true)) {
            Direction shootDir = dx < 0 ? Direction.LEFT : Direction.RIGHT;
            // Extra check: make sure a bullet fired in this direction won't immediately
            // hit a wall before reaching the player (prevents shooting through walls).
            if (isBulletPathClear(ex, ey, shootDir, (int) dist)) {
                return shootDir;
            }
        }

        // Vertical alignment: check both that tanks share roughly the same X
        // AND that the vertical path (up/down) is clear of walls.
        if (Math.abs(dx) <= 18 && hasLineOfSight(en, player, false)) {
            Direction shootDir = dy < 0 ? Direction.UP : Direction.DOWN;
            if (isBulletPathClear(ex, ey, shootDir, (int) dist)) {
                return shootDir;
            }
        }

        // Boss fallback: shoot in current facing direction if close enough AND path is clear
        if (en.isBoss && dist < 240) {
            if (isBulletPathClear(ex, ey, en.direction, 240)) {
                return en.direction;
            }
        }

        return null;
    }

    /**
     * Returns true if a bullet fired from (originX, originY) in the given direction
     * will travel at least 'distance' pixels without hitting a wall.
     * Uses step-by-step ray casting matching the bullet's actual travel path.
     */
    private boolean isBulletPathClear(int originX, int originY, Direction dir, int distance) {
        int stepSize = 4; // smaller step = more accurate collision detection
        int steps = Math.max(1, distance / stepSize);
        int bx = originX, by = originY;
        int bSize = 8; // default bullet size

        for (int s = 0; s < steps; s++) {
            switch (dir) {
                case LEFT  -> bx -= stepSize;
                case RIGHT -> bx += stepSize;
                case UP    -> by -= stepSize;
                case DOWN  -> by += stepSize;
            }

            // Out of bounds = clear (bullet will be removed naturally)
            if (bx < 0 || bx > GAME_W || by < 0 || by > HEIGHT) return true;

            Rectangle bRect = new Rectangle(bx - bSize / 2, by - bSize / 2, bSize, bSize);

            // Check level map collision
            if (usingLevelMap && levelMapMask != null && hitsLevelMap(bRect)) return false;

            // Check wall collision - be aggressive
            for (Wall w : walls) {
                if (bRect.intersects(w.getBounds())) return false;
            }

            // Reached (or passed) the player — path is clear up to target
            if (bRect.intersects(player.getBounds())) return true;
        }
        return true;
    }

    private boolean shouldEnemyShoot(Tank en) {
        return getShootDirection(en) != null;
    }

    private boolean hasLineOfSight(Tank from, Tank to, boolean horizontal) {
        int fromCx = from.x + from.width / 2;
        int fromCy = from.y + from.height / 2;
        int toCx = to.x + to.width / 2;
        int toCy = to.y + to.height / 2;

        if (horizontal) {
            int y = fromCy;
            int start = Math.min(fromCx, toCx);
            int end = Math.max(fromCx, toCx);

            // Use a 10px-tall ray - thick enough to catch any blocking walls
            Rectangle ray = new Rectangle(start, y - 5, Math.max(1, end - start), 10);

            if (usingLevelMap && levelMapMask != null && hitsLevelMap(ray)) return false;
            for (Wall w : walls) {
                if (ray.intersects(w.getBounds())) return false;
            }
            return true;
        } else {
            int x = fromCx;
            int start = Math.min(fromCy, toCy);
            int end = Math.max(fromCy, toCy);

            // Use a 10px-wide ray - thick enough to catch any blocking walls
            Rectangle ray = new Rectangle(x - 5, start, 10, Math.max(1, end - start));

            if (usingLevelMap && levelMapMask != null && hitsLevelMap(ray)) return false;
            for (Wall w : walls) {
                if (ray.intersects(w.getBounds())) return false;
            }
            return true;
        }
    }

    private void performBossSpecials(Tank boss) {
        if (!bossSummonedMinions.getOrDefault(boss, false) && boss.health <= boss.maxHealth / 2) {
            spawnBossMinions(boss);
            bossSummonedMinions.put(boss, true);
        }

        if (bossBurstCooldown.getOrDefault(boss, 0) <= 0) {
            spawnBossRadialBurst(boss);
            bossBurstCooldown.put(boss, getBossBurstCooldown());
        }

        if (bossSpecialCooldown.getOrDefault(boss, 0) <= 0) {
            Direction sd = getShootDirection(boss);
            if (sd != null) {
                Direction savedDir = boss.direction;
                boss.direction = sd;
                spawnBossSpreadShot(boss);
                boss.direction = savedDir;
                bossSpecialCooldown.put(boss, getBossSpreadCooldown());
            }
        }
    }

    private void spawnBossSpreadShot(Tank boss) {
        int cx = boss.x + boss.width / 2;
        int cy = boss.y + boss.height / 2;

        switch (boss.direction) {
            case UP -> {
                if (isBulletPathClear(cx, cy, Direction.UP, 240)) {
                    fireCustomBossBullet(cx, cy, -3, -9);
                    fireCustomBossBullet(cx, cy, 0, -10);
                    fireCustomBossBullet(cx, cy, 3, -9);
                }
            }
            case DOWN -> {
                if (isBulletPathClear(cx, cy, Direction.DOWN, 240)) {
                    fireCustomBossBullet(cx, cy, -3, 9);
                    fireCustomBossBullet(cx, cy, 0, 10);
                    fireCustomBossBullet(cx, cy, 3, 9);
                }
            }
            case LEFT -> {
                if (isBulletPathClear(cx, cy, Direction.LEFT, 240)) {
                    fireCustomBossBullet(cx, cy, -9, -3);
                    fireCustomBossBullet(cx, cy, -10, 0);
                    fireCustomBossBullet(cx, cy, -9, 3);
                }
            }
            case RIGHT -> {
                if (isBulletPathClear(cx, cy, Direction.RIGHT, 240)) {
                    fireCustomBossBullet(cx, cy, 9, -3);
                    fireCustomBossBullet(cx, cy, 10, 0);
                    fireCustomBossBullet(cx, cy, 9, 3);
                }
            }
        }
    }

    private void fireCustomBossBullet(int cx, int cy, int dx, int dy) {
        Bullet b = new Bullet(cx - 5, cy - 5, 10, 10, dx, dy, false);
        addBullet(b, 12);
    }

    private void spawnBossRadialBurst(Tank boss) {
        int cx = boss.x + boss.width / 2;
        int cy = boss.y + boss.height / 2;

        Direction[] dirs = {Direction.RIGHT, Direction.LEFT, Direction.DOWN, Direction.UP};
        
        // Main cardinal directions - check walls
        for (Direction d : dirs) {
            if (isBulletPathClear(cx, cy, d, 240)) {
                switch(d) {
                    case RIGHT  -> { Bullet b = new Bullet(cx - 5, cy - 5, 10, 10, 10, 0, false); addBullet(b, 12); }
                    case LEFT   -> { Bullet b = new Bullet(cx - 5, cy - 5, 10, 10, -10, 0, false); addBullet(b, 12); }
                    case DOWN   -> { Bullet b = new Bullet(cx - 5, cy - 5, 10, 10, 0, 10, false); addBullet(b, 12); }
                    case UP     -> { Bullet b = new Bullet(cx - 5, cy - 5, 10, 10, 0, -10, false); addBullet(b, 12); }
                }
            }
        }
        
        // Diagonal shots - MUST check both axes since they move diagonally
        int[][] diagonals = {{7, 7}, {7, -7}, {-7, 7}, {-7, -7}};
        Direction[] xDirs = {Direction.RIGHT, Direction.RIGHT, Direction.LEFT, Direction.LEFT};
        Direction[] yDirs = {Direction.DOWN, Direction.UP, Direction.DOWN, Direction.UP};
        for (int i = 0; i < diagonals.length; i++) {
            // For diagonal shots, BOTH axes must be clear
            if (isBulletPathClear(cx, cy, xDirs[i], 180) && isBulletPathClear(cx, cy, yDirs[i], 180)) {
                int[] d = diagonals[i];
                Bullet b = new Bullet(cx - 5, cy - 5, 10, 10, d[0], d[1], false);
                addBullet(b, 12);
            }
        }
    }

    private void spawnBossMinions(Tank boss) {
        int[][] offsets = {
                {-90, -60},
                {-90,  60}
        };

        for (int[] off : offsets) {
            Tank m = new Tank(
                    boss.x + off[0],
                    boss.y + off[1],
                    42, 42,
                    new Color(220, 80, 80),
                    getBossMinionSpeed(),
                    getBossMinionHealth(),
                    false
            );
            m.direction = Direction.LEFT;
            m.sprite = enemyImage;
            moveTankToNearestOpen(m, m.x, m.y);

            if (!hitsWallFull(m, 6) && !hitsEnemy(m, null) && !m.getBounds().intersects(player.getBounds())) {
                enemies.add(m);
            }
        }
    }

    private void unlockBoss() {
        if (level != 5 && level != 10 && level != 15) return;

        boolean mobs = false;
        Tank boss = null;
        for (Tank en : enemies) {
            if (en.isBoss) boss = en;
            else mobs = true;
        }

        if (boss != null && !mobs) boss.bossUnlocked = true;
    }

    private void checkProgress() {
        boolean isBossLevel = (level == 5 || level == 10 || level == 15);

        if (isBossLevel) {
            if (enemies.stream().noneMatch(e -> e.isBoss) && quotaKilled >= quotaRequired) {
                if (level == MAX_LEVEL) {
                    saveScore();
                    gameState = GameState.WIN;
                } else {
                    // After a boss level, reward with RARE cards before moving on
                    pendingNextLevel = level + 1;
                    cardSelectIsRare = true;
                    rollRewardCards(true);
                    gameState = GameState.CARD_SELECT;
                }
            }
        } else {
            if (quotaKilled >= quotaRequired && enemies.isEmpty()) {
                gameState = GameState.LEVEL_CLEARED;
            }
        }
    }

    private void saveScore() {
        String name = playerName.isBlank() ? "Player" : playerName;
        if (score > 0 && LeaderboardManager.qualifiesForTop10(score)) {
            LeaderboardManager.saveScore(name, score);
        }
        scoreSaved = true;
    }

    /** Snapshot all current player upgrades into a SaveData object. */
    /**
     * Called by window listener when game window is closing.
     * Saves leaderboard score on exit.
     */
    public void saveScoreOnExit() {
        // Only save if a game was actually in progress
        if (gameState == GameState.MENU || gameState == GameState.NAME_INPUT
                || gameState == GameState.TANK_SELECT || gameState == GameState.INSTRUCTIONS
                || gameState == GameState.LEADERBOARD) return;
        if (playerName.isBlank()) return;

        // Save the score the player had at the START of the current level
        // (i.e. what they legitimately earned from cleared levels).
        // This avoids rewarding partial mid-level progress on accidental exit.
        int scoreToSave = (gameState == GameState.GAME_OVER || gameState == GameState.WIN)
                ? score              // game finished — save actual final score
                : scoreAtLevelStart; // mid-game exit — save last fully-cleared level score

        if (scoreToSave > 0 && LeaderboardManager.qualifiesForTop10(scoreToSave)) {
            String name = playerName.isBlank() ? "Player" : playerName;
            LeaderboardManager.saveScore(name, scoreToSave);
        }
    }

    /**
     * Returns true if the game is currently in PLAYING state
     */
    public boolean isGameRunning() {
        return gameState == GameState.PLAYING;
    }

    /**
     * Pauses the game (transitions to PAUSED state)
     */
    public void pauseGame() {
        if (gameState == GameState.PLAYING) {
            gameState = GameState.PAUSED;
        }
    }

    /**
     * Cheat function: Skip current level and complete the game
     * Triggered by Ctrl+P during gameplay
     * Clears each level instantly with the level cleared animation until reaching the end
     */
    private void activateLevelSkipCheat() {
        if (level < MAX_LEVEL) {
            // Set to LEVEL_CLEARED state - don't increment level here!
            // The keyPressed handler will increment level when player presses Enter
            gameState = GameState.LEVEL_CLEARED;
            System.out.println("CHEAT: Level " + level + " cleared! Press Enter to advance to level " + (level + 1));
        } else if (level == MAX_LEVEL) {
            // Reached final level, complete the game
            gameState = GameState.WIN;
            saveScore();
            System.out.println("CHEAT: Game completed! Final score: " + score);
        }
    }

    private void updateExplosions() {
        explosions.removeIf(ex -> {
            ex.update();
            return !ex.isAlive();
        });
    }

    private boolean hitsWall(Tank t) {
    Rectangle hitbox = getWallCollisionBox(t);

    if (usingLevelMap && levelMapMask != null && hitsLevelMap(hitbox)) return true;

    for (Wall w : walls) {
        if (hitbox.intersects(w.getBounds())) return true;
    }
    return false;
}

    private boolean hitsEnemy(Tank t, Tank self) {
        Rectangle tb = getWallCollisionBox(t);
        return enemies.stream().anyMatch(o -> o != self && tb.intersects(getWallCollisionBox(o)));
    }

    private void clamp(Tank t) {
        t.x = Math.max(0, Math.min(t.x, GAME_W - t.width));
        t.y = Math.max(TOP_BAR, Math.min(t.y, HEIGHT - t.height));
    }

    private void loadLevelMap(int n) {
        if (n < 1 || n > MAX_LEVEL) {
            levelMapImage = null;
            levelMapMask  = null;
            usingLevelMap = false;
            return;
        }

        String fileName = "LEVEL" + n + ".png";
        String[] pathsToTry = {
            fileName,                                                       // run from game dir
            System.getProperty("user.dir") + File.separator + fileName,    // explicit working dir
            "maps"  + File.separator + fileName,                           // maps/ subfolder
            "levels" + File.separator + fileName,                          // levels/ subfolder
            ".."    + File.separator + fileName                            // one dir up
        };

        levelMapImage = null;
        levelMapMask  = null;
        usingLevelMap = false;

        for (String path : pathsToTry) {
            try {
                File file = new File(path);
                if (!file.exists()) continue;

                BufferedImage loaded = ImageIO.read(file);
                if (loaded == null) continue;

                levelMapMask  = loaded;
                levelMapImage = loaded;   // same image used for both display and collision
                usingLevelMap = true;
                System.out.println("Loaded level map: " + file.getAbsolutePath());
                return;
            } catch (IOException ignored) { }
        }

        System.out.println("No map image found for LEVEL" + n + "- using hardcoded walls.");
    }

    private boolean hitsLevelMap(Rectangle bounds) {
        if (levelMapMask == null) return false;

        // Use a small inset (2px) just to avoid catching the very edge of the sprite
        int shrink = 2;
        int x0 = bounds.x + shrink;
        int y0 = bounds.y + shrink;
        int x1 = bounds.x + bounds.width  - shrink - 1;
        int y1 = bounds.y + bounds.height - shrink - 1;

        if (x0 > x1 || y0 > y1) return false;

        // Sample with very fine granularity for the new maps (step=2 for better accuracy)
        int step = 2;
        for (int sy = y0; sy <= y1; sy += step) {
            for (int sx = x0; sx <= x1; sx += step) {
                if (isSolidAtScreenPoint(sx, sy)) return true;
            }
            // Always check the right edge column too
            if (isSolidAtScreenPoint(x1, sy)) return true;
        }
        // Always check the bottom row
        for (int sx = x0; sx <= x1; sx += step) {
            if (isSolidAtScreenPoint(sx, y1)) return true;
        }
        // Check all four corners and midpoints for thorough detection
        if (isSolidAtScreenPoint(x0, y0)) return true;
        if (isSolidAtScreenPoint(x1, y0)) return true;
        if (isSolidAtScreenPoint(x0, y1)) return true;
        if (isSolidAtScreenPoint(x1, y1)) return true;
        // Check center point too
        if (isSolidAtScreenPoint((x0 + x1) / 2, (y0 + y1) / 2)) return true;
        return false;
    }

    private boolean isSolidAtScreenPoint(int screenX, int screenY) {
        if (levelMapMask == null) return false;
        // Out of play area = solid boundary
        if (screenX < 0 || screenX >= GAME_W) return true;
        if (screenY < TOP_BAR || screenY >= HEIGHT) return true;

        // Map screen coords → image pixel coords
        int gameH = HEIGHT - TOP_BAR;
        int imgX = (screenX) * levelMapMask.getWidth()  / GAME_W;
        int imgY = (screenY - TOP_BAR) * levelMapMask.getHeight() / gameH;

        imgX = Math.max(0, Math.min(levelMapMask.getWidth()  - 1, imgX));
        imgY = Math.max(0, Math.min(levelMapMask.getHeight() - 1, imgY));

        return isSolidLevelPixel(levelMapMask.getRGB(imgX, imgY));
    }

    private boolean isSolidLevelPixel(int rgb) {
        int a = (rgb >>> 24) & 0xFF;
        if (a < 16) return true;   // transparent = solid (out of bounds)

        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8)  & 0xFF;
        int b =  rgb         & 0xFF;

        if (level >= 1 && level <= 5) {
            // Levels 1-5: copper/dungeon brick maps
            // Wall pixels: red-dominant (R > G by 20+, R > 80)
            // Floor pixels: blue-dominant dark grey (R < G or very dark)
            return (r > g + 20 && r > 80);
        }

        if (level >= 6 && level <= 9) {
            // Levels 6-9: bright green cave maps
            // Wall pixels: bright lime green (high G value, G > 100)
            // Floor pixels: dark navy blue
            return (g > 100 && g > r + 20 && g > b + 20);
        }

        if (level == 10) {
            // Level 10: boss level - mixed bright walls
            // Wall pixels: bright green or bright colors
            // Floor pixels: dark navy blue
            int brightness = (r + g + b) / 3;
            boolean brightGreen = (g > 80 && g > r + 15);
            boolean brightColor = (brightness > 110);
            return brightGreen || brightColor;
        }

        if (level >= 11 && level <= 14) {
            // Levels 11-14: white/grey outline maps
            // Wall pixels: bright white outlines
            // Floor pixels: very dark navy blue
            int brightness = (r + g + b) / 3;
            return brightness > 115;
        }

        // Level 15: final boss level - adapted detection
        // Wall pixels: bright walls (high brightness)
        // Floor pixels: dark navy blue
        int brightness = (r + g + b) / 3;
        return brightness > 110;
    }

    private void moveTankToNearestOpen(Tank tank, int preferredX, int preferredY) {
        tank.x = preferredX;
        tank.y = preferredY;
        clamp(tank);

        if (!hitsWall(tank)) return;

        // Spiral outward search for an open position
        for (int radius = 8; radius <= 400; radius += 8) {
            for (int dy = -radius; dy <= radius; dy += 8) {
                for (int dx = -radius; dx <= radius; dx += 8) {
                    if (Math.abs(dx) != radius && Math.abs(dy) != radius) continue;
                    tank.x = preferredX + dx;
                    tank.y = preferredY + dy;
                    clamp(tank);
                    if (!hitsWall(tank)) return;
                }
            }
        }

        // Last resort: scan whole play area
        for (int attempt = 0; attempt < 300; attempt++) {
            tank.x = 60 + random.nextInt(GAME_W - 120);
            tank.y = TOP_BAR + 40 + random.nextInt(HEIGHT - TOP_BAR - 80);
            if (!hitsWall(tank)) return;
        }

        // Give up — reset to preferred (shouldn't happen)
        tank.x = preferredX;
        tank.y = preferredY;
        clamp(tank);
    }

    /**
     * Finds a safe spawn for the player on the LEFT side of the map (x < 300),
     * well away from the top/bottom borders. Scans candidate rows at the vertical
     * center first, then expands outward. Falls back to full-map scan if needed.
     * This prevents the player from appearing inside border-wall pixels of level maps.
     */
    private void spawnPlayerSafe() {
        int midY  = TOP_BAR + (HEIGHT - TOP_BAR) / 2;
        int minY  = TOP_BAR + 60;   // stay clear of top border
        int maxY  = HEIGHT  - 60;   // stay clear of bottom border
        int minX  = 60;
        int maxX  = 280;            // left third of the arena

        // Try a grid of positions on the left side, center rows first
        for (int radius = 0; radius <= (HEIGHT / 2); radius += 16) {
            for (int dy : new int[]{0, radius, -radius}) {
                int ty = midY + dy;
                if (ty < minY || ty > maxY) continue;
                for (int tx = minX; tx <= maxX; tx += 16) {
                    player.x = tx;
                    player.y = ty;
                    clamp(player);
                    if (!hitsWall(player)) return;
                }
            }
        }

        // Fallback: any open position on the left half
        for (int attempt = 0; attempt < 500; attempt++) {
            player.x = minX + random.nextInt(maxX - minX);
            player.y = minY + random.nextInt(maxY - minY);
            clamp(player);
            if (!hitsWall(player)) return;
        }

        // Last resort: anywhere open on the full map
        moveTankToNearestOpen(player, 80, midY);
    }

    // ══════════════════════════════════════════════════════════════
    //  CARDS
    // ══════════════════════════════════════════════════════════════
    // ── Common card pool (excludes rare cards) ─────────────────────
    private static final CardPower[] COMMON_CARDS = {
        CardPower.CONTINUOUS_BULLET, CardPower.SIX_BULLET, CardPower.TRIPLE_BULLET,
        CardPower.QUICK_RELOAD, CardPower.MAG_PLUS, CardPower.BULLET_SPEED,
        CardPower.BIG_BULLETS, CardPower.DAMAGE_UP, CardPower.MAX_HP_UP,
        CardPower.BATTLE_MEDIC, CardPower.ENGINE_BOOST, CardPower.SECOND_LIFE,
        CardPower.HEAVY_SHOT
    };
    // ── Rare card pool (boss-reward only) ──────────────────────────
    private static final CardPower[] RARE_CARDS = {
        CardPower.RARE_DOUBLE_DAMAGE, CardPower.RARE_DOUBLE_HP,
        CardPower.RARE_DOUBLE_SCORE,  CardPower.RARE_GHOST_RELOAD,
        CardPower.RARE_SLOW_ENEMIES,  CardPower.RARE_RAPID_FIRE
    };

    private void rollRewardCards(boolean isRare) {
        offeredCards.clear();
        CardPower[] pool = isRare ? RARE_CARDS : COMMON_CARDS;
        ArrayList<CardPower> shuffled = new ArrayList<>(Arrays.asList(pool));
        // Remove cards already picked (won't reappear if they can't stack)
        shuffled.removeIf(card -> pickedCards.contains(card));
        Collections.shuffle(shuffled, random);
        int count = Math.min(3, shuffled.size());
        for (int i = 0; i < count; i++) offeredCards.add(shuffled.get(i));
    }

    private void applyCard(CardPower power) {
        switch (power) {
            // ── Common cards ──────────────────────────────────────────
            case CONTINUOUS_BULLET -> {
                playerContinuousFire = true;
                playerShotCooldownFrames = 4;
            }
            case SIX_BULLET -> playerBulletPattern = 6;
            case TRIPLE_BULLET -> {
                if (playerBulletPattern < 6) playerBulletPattern = 3;
            }
            case QUICK_RELOAD -> playerReloadFrames = Math.max(20, playerReloadFrames - 60);
            case MAG_PLUS -> {
                // Always adds +2 to whatever the current mag is
                playerMagazineSize += 2;
                playerAmmo = playerMagazineSize;
            }
            case BULLET_SPEED -> playerBulletSpeedBonus += 2;
            case BIG_BULLETS  -> playerBulletSizeBonus  += 2;
            case DAMAGE_UP    -> playerBulletDamage      += 6;
            case MAX_HP_UP -> {
                playerMaxHealthStat += 30;
                if (player != null) player.maxHealth = playerMaxHealthStat;
            }
            case BATTLE_MEDIC -> {
                playerMaxHealthStat += 50;
                if (player != null) player.maxHealth = playerMaxHealthStat;
            }
            case HEAVY_SHOT -> {
                playerBulletDamage   += 15;
                playerBulletSizeBonus += 2;
            }
            case ENGINE_BOOST -> {
                playerMoveSpeedStat += 1;
                if (player != null) player.speed = playerMoveSpeedStat;
            }
            case SECOND_LIFE -> playerSecondLife = true;

            // ── Rare cards ────────────────────────────────────────────
            case RARE_DOUBLE_DAMAGE -> playerBulletDamage = playerBulletDamage * 2;
            case RARE_DOUBLE_HP -> {
                playerMaxHealthStat = playerMaxHealthStat * 2;
                if (player != null) player.maxHealth = playerMaxHealthStat;
            }
            case RARE_DOUBLE_SCORE  -> scoreMultiplier *= 2;
            case RARE_GHOST_RELOAD  -> {
                playerMagazineSize = playerMagazineSize * 3;
                playerAmmo         = playerMagazineSize;
            }
            case RARE_SLOW_ENEMIES  -> enemySlowActive = true;
            case RARE_RAPID_FIRE -> {
                playerContinuousFire     = true;
                playerShotCooldownFrames = 2; // triple speed
            }
        }

        // ── Mark card as picked (non-stackable cards won't reappear) ──
        // Stackable: DAMAGE_UP, MAG_PLUS, BULLET_SPEED, BIG_BULLETS, MAX_HP_UP,
        //            ENGINE_BOOST, BATTLE_MEDIC, HEAVY_SHOT (all stackable, can reappear)
        // Non-stackable (one-time only): CONTINUOUS_BULLET, SIX_BULLET, TRIPLE_BULLET,
        //            QUICK_RELOAD, SECOND_LIFE, all RARE cards
        boolean isNonStackable = switch (power) {
            case CONTINUOUS_BULLET, SIX_BULLET, TRIPLE_BULLET,
                 QUICK_RELOAD,
                 SECOND_LIFE,
                 RARE_DOUBLE_DAMAGE, RARE_DOUBLE_HP, RARE_DOUBLE_SCORE,
                 RARE_GHOST_RELOAD, RARE_SLOW_ENEMIES, RARE_RAPID_FIRE -> true;
            default -> false;  // MAG_PLUS, BATTLE_MEDIC, HEAVY_SHOT, DAMAGE_UP etc. can all reappear
        };
        // Special case: TRIPLE_BULLET becomes non-stackable once SIX_BULLET is picked
        if (power == CardPower.TRIPLE_BULLET && playerBulletPattern >= 6) isNonStackable = true;
        if (isNonStackable) pickedCards.add(power);
        // Also prevent TRIPLE_BULLET from showing once we have 6-bullet
        if (playerBulletPattern >= 6) pickedCards.add(CardPower.TRIPLE_BULLET);
        // Once CONTINUOUS_BULLET is picked, RARE_RAPID_FIRE does same thing - exclude both
        if (playerContinuousFire) {
            pickedCards.add(CardPower.CONTINUOUS_BULLET);
            pickedCards.add(CardPower.RARE_RAPID_FIRE);
        }

        offeredCards.clear();
        cardSelectIsRare = false;
        startLevel(pendingNextLevel);
    }

    // ══════════════════════════════════════════════════════════════
    //  PAINT
    // ══════════════════════════════════════════════════════════════
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        // ── Fullscreen: fill letterbox area and apply scale ───────────
        if (fullscreen && (fsOffX > 0 || fsOffY > 0)) {
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());
        }
        if (fullscreen) {
            g2.translate(fsOffX, fsOffY);
            g2.scale(fsScaleX, fsScaleY);
            // Use nearest-neighbour for crisp pixels (no blurry bilinear stretch)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_SPEED);
        }

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        switch (gameState) {
            case MENU            -> { drawMenuBg(g2); drawMenu(g2);            return; }
            case NAME_INPUT      -> { drawMenuBg(g2); drawNameInput(g2);       return; }

            case LB_NAME_WARN    -> { drawMenuBg(g2); drawLbNameWarn(g2);      return; }
            case TANK_SELECT     -> { drawMenuBg(g2); drawTankSelect(g2);      return; }
            case INSTRUCTIONS    -> { drawMenuBg(g2); drawInstructions(g2);    return; }
            case LEADERBOARD     -> { drawMenuBg(g2); drawLeaderboard(g2);     return; }
            default -> {}
        }

        drawGameBg(g2);
        drawTopBar(g2);

        g2.setClip(0, TOP_BAR, GAME_W, HEIGHT - TOP_BAR);
        drawWalls(g2);
        if (player != null) drawTank(g2, player);
        for (Tank en : new ArrayList<>(enemies)) drawTank(g2, en);
        for (Bullet b  : new ArrayList<>(bullets)) drawBullet(g2, b);
        drawExplosions(g2);
        g2.setClip(null);

        drawSidebar(g2);

        if (gameState == GameState.CARD_SELECT) {
            drawCardSelect(g2);
            return;
        }

        if      (pauseMenuOpen || gameState == GameState.PAUSED) drawPauseMenu(g2);
        else if (gameState == GameState.LEVEL_CLEARED)           drawLevelCleared(g2);
        else if (gameState == GameState.GAME_OVER)               drawGameOver(g2);
        else if (gameState == GameState.WIN)                     drawWin(g2);
    }

    private void drawMenuBg(Graphics2D g2) {
        // ── Sky gradient ─────────────────────────────────────────────
        g2.setPaint(new GradientPaint(0, 0, new Color(10, 18, 48), WIDTH, HEIGHT * 2 / 3, new Color(22, 45, 100)));
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        // ── Stars ────────────────────────────────────────────────────
        Random rng = bgRng;
        rng.setSeed(42L); // reset to same seed so stars/trees are stable each frame
        g2.setColor(new Color(255, 255, 255, 140));
        for (int i = 0; i < 80; i++) {
            int sx = rng.nextInt(WIDTH);
            int sy = rng.nextInt(HEIGHT * 2 / 5);
            int ss = rng.nextInt(2) + 1;
            g2.fillOval(sx, sy, ss, ss);
        }

        // ── Distant mountains (back layer) ───────────────────────────
        int horizonY = HEIGHT * 2 / 3;
        g2.setPaint(new GradientPaint(0, horizonY - 120, new Color(18, 30, 72, 220), 0, horizonY, new Color(12, 22, 55, 255)));
        int[] mxBack = {0, 80, 180, 280, 370, 460, 570, 660, 750, 840, 940, WIDTH};
        int[] myBack = {horizonY, horizonY - 80, horizonY - 130, horizonY - 60, horizonY - 150,
                        horizonY - 90, horizonY - 140, horizonY - 70, horizonY - 120, horizonY - 80, horizonY - 110, horizonY};
        g2.fillPolygon(mxBack, myBack, mxBack.length);

        // ── Mid mountains (front layer) ──────────────────────────────
        g2.setPaint(new GradientPaint(0, horizonY - 80, new Color(12, 24, 58, 240), 0, horizonY, new Color(8, 16, 38, 255)));
        int[] mxFront = {0, 60, 150, 240, 330, 420, 510, 600, 690, 780, 880, WIDTH};
        int[] myFront = {horizonY, horizonY - 40, horizonY - 90, horizonY - 50, horizonY - 100,
                         horizonY - 60, horizonY - 110, horizonY - 45, horizonY - 85, horizonY - 55, horizonY - 70, horizonY};
        g2.fillPolygon(mxFront, myFront, mxFront.length);

        // ── Ground / battlefield floor ───────────────────────────────
        g2.setPaint(new GradientPaint(0, horizonY, new Color(22, 32, 18), 0, HEIGHT, new Color(12, 18, 8)));
        g2.fillRect(0, horizonY, WIDTH, HEIGHT - horizonY);

        // Grass texture (subtle horizontal stripes)
        g2.setColor(new Color(30, 50, 22, 40));
        for (int y = horizonY + 10; y < HEIGHT; y += 18) {
            g2.drawLine(0, y, WIDTH, y);
        }

        // ── Distant ruined structures / battlefield silhouettes ──────
        g2.setColor(new Color(8, 12, 28, 200));
        // Left ruined building
        g2.fillRect(55, horizonY - 55, 28, 55);
        g2.fillRect(45, horizonY - 38, 48, 38);
        g2.fillRect(58, horizonY - 65, 10, 12);
        g2.fillRect(72, horizonY - 70, 8, 16);
        // Right ruined building
        g2.fillRect(WIDTH - 95, horizonY - 65, 35, 65);
        g2.fillRect(WIDTH - 105, horizonY - 44, 55, 44);
        g2.fillRect(WIDTH - 85, horizonY - 75, 12, 12);
        // Center-left structure
        g2.fillRect(280, horizonY - 42, 22, 42);
        g2.fillRect(274, horizonY - 28, 34, 28);
        // Center-right
        g2.fillRect(WIDTH - 320, horizonY - 36, 20, 36);

        // ── Burned trees silhouettes ─────────────────────────────────
        g2.setColor(new Color(6, 10, 22, 180));
        int[] treeXs = {130, 200, 420, 680, 820, WIDTH - 180};
        for (int tx : treeXs) {
            int th = 30 + rng.nextInt(25);
            g2.fillRect(tx, horizonY - th, 4, th);
            g2.fillOval(tx - 8, horizonY - th - 14, 20, 18);
        }

        // ── Foreground terrain bumps ─────────────────────────────────
        g2.setPaint(new GradientPaint(0, HEIGHT - 80, new Color(16, 26, 12), 0, HEIGHT, new Color(8, 14, 6)));
        int[] fgX = {0, 120, 260, 400, 540, 680, 820, WIDTH};
        int[] fgY = {HEIGHT, HEIGHT - 25, HEIGHT - 15, HEIGHT - 32, HEIGHT - 12, HEIGHT - 28, HEIGHT - 18, HEIGHT};
        g2.fillPolygon(fgX, fgY, fgX.length);

        // ── Subtle grid overlay (tactical map feel) ──────────────────
        g2.setColor(new Color(40, 80, 160, 12));
        for (int x = 0; x < WIDTH; x += 50) g2.drawLine(x, 0, x, HEIGHT);
        for (int y = 0; y < HEIGHT; y += 50) g2.drawLine(0, y, WIDTH, y);

        // ── Horizon glow (battle fires) ──────────────────────────────
        float pulse = (float)(Math.sin(titlePulse * 0.7f) * 0.5 + 0.5);
        int glowAlpha = (int)(18 + 14 * pulse);
        g2.setPaint(new GradientPaint(0, horizonY - 20, new Color(220, 100, 20, glowAlpha),
                                      0, horizonY + 60, new Color(220, 60, 0, 0)));
        g2.fillRect(0, horizonY - 20, WIDTH, 80);
    }

    private void drawGameBg(Graphics2D g2) {
        if (usingLevelMap && levelMapImage != null) {
            g2.setColor(new Color(8, 10, 22));
            g2.fillRect(0, TOP_BAR, GAME_W, HEIGHT - TOP_BAR);
            // Draw map stretched to fill the entire game play area below the top bar
            g2.drawImage(levelMapImage, 0, TOP_BAR, GAME_W, HEIGHT - TOP_BAR, this);
        } else if (backgroundImage != null) {
            g2.drawImage(backgroundImage, 0, 0, GAME_W, HEIGHT, this);
        } else {
            drawLevelLandscape(g2);
        }

        g2.setColor(new Color(10, 12, 28));
        g2.fillRect(GAME_W, 0, SIDEBAR, HEIGHT);
    }

    private void drawLevelLandscape(Graphics2D g2) {
        int gx = 0, gy = TOP_BAR, gw = GAME_W, gh = HEIGHT - TOP_BAR;

        if (isEasyLevel(level)) {
            // ── Meadow / grassland ────────────────────────────────────
            g2.setPaint(new GradientPaint(gx,gy,new Color(72,160,58),gx,gy+gh,new Color(38,100,28)));
            g2.fillRect(gx,gy,gw,gh);

            // sky band at top
            g2.setPaint(new GradientPaint(gx,gy,new Color(100,195,240,100),gx,gy+120,new Color(72,160,58,0)));
            g2.fillRect(gx,gy,gw,120);

            // grass texture dots
            g2.setColor(new Color(55,135,40,55));
            random.setSeed(level * 31L);
            for(int i=0;i<220;i++){
                int rx=gx+random.nextInt(gw), ry=gy+random.nextInt(gh);
                g2.fillOval(rx,ry,5+random.nextInt(8),3+random.nextInt(4));
            }
            // distant hills
            g2.setPaint(new GradientPaint(gx,gy+gh/3,new Color(45,130,40,80),gx,gy+gh/2,new Color(38,110,28,0)));
            int[] hx = {0,120,240,380,500,640,760,900,1000};
            int[] hy = {gy+gh/3+40,gy+gh/3-20,gy+gh/3+10,gy+gh/3-40,gy+gh/3+20,gy+gh/3-30,gy+gh/3+10,gy+gh/3-15,gy+gh/3+30};
            g2.fillPolygon(hx,hy,hx.length);
            // grid overlay
            g2.setColor(new Color(50,115,38,40));
            for(int x=gx;x<gx+gw;x+=48) g2.drawLine(x,gy,x,gy+gh);
            for(int y=gy;y<gy+gh;y+=48) g2.drawLine(gx,y,gx+gw,y);

        } else if (isMediumLevel(level)) {
            // ── Desert / ruins ────────────────────────────────────────
            g2.setPaint(new GradientPaint(gx,gy,new Color(200,160,80),gx,gy+gh,new Color(155,110,48)));
            g2.fillRect(gx,gy,gw,gh);

            // heat haze sky
            g2.setPaint(new GradientPaint(gx,gy,new Color(230,185,100,90),gx,gy+100,new Color(200,160,80,0)));
            g2.fillRect(gx,gy,gw,100);

            // sand dunes
            g2.setColor(new Color(185,140,65,80));
            random.setSeed(level * 17L);
            for(int i=0;i<5;i++){
                int dw=200+random.nextInt(300), dx=gx+random.nextInt(gw-200), dy=gy+random.nextInt(gh);
                g2.fillOval(dx,dy-30,dw,70);
            }
            // sand particles
            g2.setColor(new Color(225,185,110,50));
            for(int i=0;i<180;i++){
                int rx=gx+random.nextInt(gw),ry=gy+random.nextInt(gh);
                g2.fillOval(rx,ry,2+random.nextInt(5),2);
            }
            // cracked ground lines
            g2.setColor(new Color(140,95,35,60));
            g2.setStroke(new BasicStroke(1f));
            for(int i=0;i<12;i++){
                int lx=gx+random.nextInt(gw),ly=gy+random.nextInt(gh);
                g2.drawLine(lx,ly,lx+random.nextInt(80)-40,ly+random.nextInt(60)-30);
            }
            g2.setStroke(new BasicStroke(1f));

        } else {
            // ── Dark cave / lava ──────────────────────────────────────
            g2.setPaint(new GradientPaint(gx,gy,new Color(18,8,8),gx,gy+gh,new Color(38,14,4)));
            g2.fillRect(gx,gy,gw,gh);

            // lava glow pools
            random.setSeed(level * 7L);
            for(int i=0;i<6;i++){
                int lx=gx+random.nextInt(gw),ly=gy+random.nextInt(gh);
                int lr=40+random.nextInt(80);
                g2.setPaint(new RadialGradientPaint(lx,ly,lr,new float[]{0f,1f},
                    new Color[]{new Color(220,80,0,90),new Color(220,80,0,0)}));
                g2.fillOval(lx-lr,ly-lr,lr*2,lr*2);
            }
            // cave stalactites (top)
            g2.setColor(new Color(28,14,10));
            for(int i=0;i<18;i++){
                int sx=gx+random.nextInt(gw),sh=20+random.nextInt(60);
                int[] stX={sx-10,sx,sx+10}; int[] stY={gy,gy+sh,gy};
                g2.fillPolygon(stX,stY,3);
            }
            // crack lines glowing
            g2.setColor(new Color(200,60,0,45));
            g2.setStroke(new BasicStroke(2f));
            for(int i=0;i<10;i++){
                int lx=gx+random.nextInt(gw),ly=gy+random.nextInt(gh);
                g2.drawLine(lx,ly,lx+random.nextInt(120)-60,ly+random.nextInt(80)-40);
            }
            g2.setStroke(new BasicStroke(1f));

            // ember particles
            g2.setColor(new Color(255,120,0,30));
            for(int i=0;i<120;i++){
                int rx=gx+random.nextInt(gw),ry=gy+random.nextInt(gh);
                g2.fillOval(rx,ry,2,2);
            }
        }

        // restore random seed
        random.setSeed(System.nanoTime());
    }

    private void drawMenu(Graphics2D g2) {
        float pulse = (float)(Math.sin(titlePulse) * 0.5 + 0.5);
        int glowA   = (int)(55 + 80 * pulse);
        FontMetrics fm;

        // ── LOGO HEADER ──────────────────────────────────────────────
        int titleH = 130;
        g2.setPaint(new GradientPaint(0,0,new Color(4,8,22),WIDTH,titleH,new Color(10,20,50)));
        g2.fillRect(0, 0, WIDTH, titleH);

        // animated corner accents
        g2.setColor(new Color(55,110,255, 80 + glowA / 3));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawLine(0,0,60,0); g2.drawLine(0,0,0,40);
        g2.drawLine(WIDTH,0,WIDTH-60,0); g2.drawLine(WIDTH,0,WIDTH,40);
        g2.drawLine(0,titleH,60,titleH); g2.drawLine(WIDTH,titleH,WIDTH-60,titleH);
        g2.setStroke(new BasicStroke(1f));

        // tank icon left of title — shows selected player design
        int iconX = WIDTH/2 - 340, iconY = 24;
        drawMenuTankPreview(g2, iconX, iconY, 68, selectedTankDesign, Direction.RIGHT, false);

        // tank icon right (boss)
        int iconX2 = WIDTH/2 + 272, iconY2 = 24;
        drawMenuTankPreview(g2, iconX2, iconY2, 68, -1, Direction.LEFT, true);

        // glow ring behind title
        int tcx = WIDTH/2, tcy = 62;
        g2.setPaint(new RadialGradientPaint(tcx,tcy,220,new float[]{0f,1f},
            new Color[]{new Color(40,100,255,glowA/4),new Color(40,100,255,0)}));
        g2.fillOval(tcx-220,tcy-80,440,160);

        // drop shadow
        g2.setFont(new Font("Arial",Font.BOLD,58));
        fm = g2.getFontMetrics();
        String line1 = "2DTANK";
        String line2 = "BATTLE ARENA";
        int tx1 = (WIDTH - fm.stringWidth(line1)) / 2;

        for (int d = 6; d >= 1; d--) {
            g2.setColor(new Color(20,60,200, glowA / d));
            g2.drawString(line1, tx1 + d, 56 + d);
        }
        g2.setColor(Color.WHITE);
        g2.drawString(line1, tx1, 56);

        g2.setFont(new Font("Arial",Font.BOLD,30));
        fm = g2.getFontMetrics();
        int tx2 = (WIDTH - fm.stringWidth(line2)) / 2;
        for (int d = 4; d >= 1; d--) {
            g2.setColor(new Color(20,60,200, glowA / d / 2));
            g2.drawString(line2, tx2 + d, 92 + d);
        }
        g2.setPaint(new GradientPaint(tx2,92,new Color(130,175,255),tx2+fm.stringWidth(line2),92,new Color(255,210,80)));
        g2.drawString(line2, tx2, 92);

        // subtitle tag line
        g2.setFont(new Font("Arial",Font.PLAIN,13));
        g2.setColor(new Color(90,130,210));
        String tag = "15 Levels  ·  Easy / Medium / Hard  ·  Boss Battles  ·  Reward Cards";
        g2.drawString(tag, (WIDTH - g2.getFontMetrics().stringWidth(tag)) / 2, 118);

        // glow separator line
        g2.setPaint(new GradientPaint(0,titleH,new Color(40,100,255,180),WIDTH/2,titleH,new Color(255,210,60,180)));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawLine(0, titleH, WIDTH/2, titleH);
        g2.setPaint(new GradientPaint(WIDTH/2,titleH,new Color(255,210,60,180),WIDTH,titleH,new Color(40,100,255,0)));
        g2.drawLine(WIDTH/2, titleH, WIDTH, titleH);
        g2.setStroke(new BasicStroke(1f));

        // ── MENU BUTTONS ─────────────────────────────────────────────
        int btnY = titleH + 10;
        int btnW = 200, btnH = 46, gap = 16;
        int totalBtns = 4;
        int totalW = totalBtns * btnW + (totalBtns - 1) * gap;
        int btnStartX = (WIDTH - totalW) / 2;

        startBtn.setBounds(btnStartX, btnY, btnW, btnH);
        lboardBtn.setBounds(btnStartX + (btnW + gap), btnY, btnW, btnH);
        instrBtn.setBounds(btnStartX + 2 * (btnW + gap), btnY, btnW, btnH);
        exitBtn.setBounds(btnStartX + 3 * (btnW + gap), btnY, btnW, btnH);

        drawMenuBtn(g2, startBtn,  ">  Start Game");
        drawMenuBtn(g2, lboardBtn, "#  Leaderboard");
        drawMenuBtn(g2, instrBtn,  "?  Instructions");
        drawMenuBtn(g2, exitBtn,   "X  Exit");

        int sepY = btnY + btnH + 10;
        g2.setColor(new Color(50,80,150,120));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(60, sepY, WIDTH - 60, sepY);
        g2.setStroke(new BasicStroke(1f));

        // ── ARENA PREVIEW ─────────────────────────────────────────────
        int arenaY = sepY + 4;
        int arenaH = HEIGHT - arenaY - 30;

        g2.setColor(new Color(255,255,255,5));
        for (int x = 0; x < WIDTH; x += 60) g2.drawLine(x, arenaY, x, arenaY + arenaH);
        for (int y = arenaY; y < arenaY + arenaH; y += 60) g2.drawLine(0, y, WIDTH, y);

        int vsCx = WIDTH / 2, vsCy = arenaY + arenaH / 2;

        // ── VS circle ──────────────────────────────────────────────
        g2.setPaint(new GradientPaint(vsCx-36,vsCy-36,new Color(60,20,8,220),vsCx+36,vsCy+36,new Color(14,8,48,220)));
        g2.fillOval(vsCx - 52, vsCy - 52, 104, 104);
        g2.setColor(new Color(255,200,40,180));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawOval(vsCx - 52, vsCy - 52, 104, 104);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(255,220,60));
        g2.setFont(new Font("Arial",Font.BOLD,40));
        fm = g2.getFontMetrics();
        g2.drawString("VS", vsCx - fm.stringWidth("VS")/2, vsCy + fm.getAscent()/2 - 2);

        // ── Player tank (left side, facing RIGHT) ───────────────────
        int tankSz = 100;
        int tankOffset = 270; // distance from VS center to each tank center
        int pTankX = vsCx - tankOffset - tankSz / 2;
        int tankCY = vsCy - tankSz / 2;

        // glow aura behind player tank
        g2.setColor(new Color(40,100,255, (int)(20 + 15 * pulse)));
        g2.fillOval(pTankX - 18, tankCY - 18, tankSz + 36, tankSz + 36);

        // draw player tank using preview method (no HP bar, no cannon overlap)
        drawMenuTankPreview(g2, pTankX, tankCY, tankSz, selectedTankDesign, Direction.RIGHT, false);

        // player label below
        g2.setColor(new Color(100,170,255));
        g2.setFont(new Font("Arial",Font.BOLD,17));
        fm = g2.getFontMetrics();
        String pl = "YOU";
        g2.drawString(pl, pTankX + (tankSz - fm.stringWidth(pl))/2, tankCY + tankSz + 22);

        // ── Boss tank (right side, facing LEFT) ─────────────────────
        int bTankX = vsCx + tankOffset - tankSz / 2;

        // glow aura behind boss tank
        g2.setColor(new Color(200,30,30, (int)(18 + 14 * pulse)));
        g2.fillOval(bTankX - 18, tankCY - 18, tankSz + 36, tankSz + 36);

        // draw boss tank using preview method (no HP bar, no cannon overlap)
        drawMenuTankPreview(g2, bTankX, tankCY, tankSz, -1, Direction.LEFT, true);

        // boss label below
        g2.setColor(new Color(252,90,90));
        g2.setFont(new Font("Arial",Font.BOLD,17));
        fm = g2.getFontMetrics();
        String bl = "FINAL BOSS";
        g2.drawString(bl, bTankX + (tankSz - fm.stringWidth(bl))/2, tankCY + tankSz + 22);

        // ── Hint ────────────────────────────────────────────────────
        g2.setColor(new Color(55,70,115));
        g2.setFont(new Font("Arial",Font.PLAIN,11));
        String hint = "WASD / Arrows - Move    SPACE - Shoot    ESC - Pause";
        g2.drawString(hint, (WIDTH - g2.getFontMetrics().stringWidth(hint)) / 2, HEIGHT - 10);
    }

    private void drawMenuBtn(Graphics2D g2, Rectangle r, String text) {
        boolean hover = r.contains(mousePos);
        g2.setColor(new Color(0,0,0,85));
        g2.fillRoundRect(r.x + 3, r.y + 3, r.width, r.height, 16, 16);

        if (hover) g2.setPaint(new GradientPaint(r.x, r.y, new Color(48,108,228), r.x, r.y + r.height, new Color(18,58,158)));
        else g2.setColor(new Color(16,20,50,218));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16);

        g2.setColor(hover ? new Color(95,155,255) : new Color(52,76,158,165));
        g2.setStroke(new BasicStroke(hover ? 2f : 1.5f));
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 16, 16);
        g2.setStroke(new BasicStroke(1f));

        g2.setColor(hover ? Color.WHITE : new Color(165,190,255));
        g2.setFont(new Font("Arial",Font.BOLD,20));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2, r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent());
    }

    private void drawNameInput(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,155));
        g2.fillRect(0,0,WIDTH,HEIGHT);

        int cx = WIDTH / 2, cy = HEIGHT / 2;
        g2.setColor(new Color(10,16,46,248));
        g2.fillRoundRect(cx - 290,cy - 148,580,296,22,22);
        g2.setColor(new Color(55,115,255));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(cx - 290,cy - 148,580,296,22,22);
        g2.setStroke(new BasicStroke(1f));

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,36));
        FontMetrics fm = g2.getFontMetrics();
        String t = "Enter Your Name";
        g2.drawString(t, cx - fm.stringWidth(t) / 2, cy - 82);

        g2.setColor(new Color(26,36,76));
        g2.fillRoundRect(cx - 218,cy - 52,436,56,10,10);
        g2.setColor(new Color(95,150,255));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(cx - 218,cy - 52,436,56,10,10);
        g2.setStroke(new BasicStroke(1f));

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Monospaced",Font.BOLD,26));
        g2.drawString(playerName + "|", cx - 205, cy - 12);

            g2.setColor(new Color(125,150,205));
        g2.setFont(new Font("Arial",Font.PLAIN,16));
        String h = "ENTER to start   |   BACKSPACE to erase   |   Max 15 chars";
        g2.drawString(h, cx - g2.getFontMetrics().stringWidth(h) / 2, cy + 56);

        if (!nameErrorMsg.isEmpty()) {
            g2.setColor(new Color(255, 80, 80));
            g2.setFont(new Font("Arial", Font.BOLD, 14));
            g2.drawString(nameErrorMsg, cx - g2.getFontMetrics().stringWidth(nameErrorMsg) / 2, cy + 32);
        }

        g2.setColor(new Color(110,130,195));
        g2.setFont(new Font("Arial",Font.ITALIC,13));
        String hint2 = "Note: Names already on the leaderboard cannot be reused.";
        g2.drawString(hint2, cx - g2.getFontMetrics().stringWidth(hint2) / 2, cy + 96);
    }

    // ══════════════════════════════════════════════════════════════
    //  CONTINUE PROMPT SCREEN
    // ══════════════════════════════════════════════════════════════
    private void drawLbNameWarn(Graphics2D g2) {
        int cx = WIDTH / 2, cy = HEIGHT / 2;

        // Dim background
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        // ── Panel — truly centered ────────────────────────────────────
        int panW = 660, panH = 316;
        int px = cx - panW / 2, py = cy - panH / 2;

        g2.setColor(new Color(10, 14, 42, 252));
        g2.fillRoundRect(px, py, panW, panH, 24, 24);
        g2.setColor(new Color(220, 70, 70));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawRoundRect(px, py, panW, panH, 24, 24);
        g2.setStroke(new BasicStroke(1f));

        int pad = 36; // inner horizontal padding from panel edge

        // ── Title ─────────────────────────────────────────────────────
        g2.setColor(new Color(255, 110, 110));
        g2.setFont(new Font("Arial", Font.BOLD, 26));
        FontMetrics fm = g2.getFontMetrics();
        String title = "! Name Already Taken !";
        g2.drawString(title, cx - fm.stringWidth(title) / 2, py + 46);

        // Divider
        g2.setColor(new Color(180, 60, 60, 120));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(px + pad, py + 58, px + panW - pad, py + 58);
        g2.setStroke(new BasicStroke(1f));

        // ── Info box ──────────────────────────────────────────────────
        int infoX = px + pad, infoY = py + 70;
        int infoW = panW - pad * 2, infoH = 140;

        g2.setColor(new Color(22, 12, 12, 230));
        g2.fillRoundRect(infoX, infoY, infoW, infoH, 12, 12);
        g2.setColor(new Color(180, 60, 60, 130));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(infoX, infoY, infoW, infoH, 12, 12);
        g2.setStroke(new BasicStroke(1f));

        int textX = infoX + 20;
        g2.setColor(new Color(210, 170, 170));
        g2.setFont(new Font("Arial", Font.BOLD, 14));
        g2.drawString("Player:  " + playerName, textX, infoY + 26);

        g2.setColor(new Color(255, 220, 80));
        g2.setFont(new Font("Arial", Font.BOLD, 20));
        g2.drawString("Leaderboard Best Score:  " + lbExistingScore, textX, infoY + 56);

        g2.setColor(new Color(255, 150, 150));
        g2.setFont(new Font("Arial", Font.PLAIN, 13));
        g2.drawString("Starting a new game resets your run score to 0.", textX, infoY + 84);
        g2.drawString("You will begin from Level 1 with no upgrades.", textX, infoY + 103);

        g2.setColor(new Color(120, 200, 140));
        g2.setFont(new Font("Arial", Font.ITALIC, 12));
        g2.drawString("Your leaderboard record is safe — it only updates if you beat it.", textX, infoY + 126);

        // ── Two buttons — equal width, equal gap from center ─────────
        int btnW   = 260, btnH = 52;
        int btnGap = 20; // gap between the two buttons
        int totalBtnW = btnW * 2 + btnGap;
        int btnStartX = cx - totalBtnW / 2;
        int btnY = infoY + infoH + 28; // 28px gap below info box

        // YES button
        int yBtnX = btnStartX;
        boolean yHover = mousePos != null
                && mousePos.x >= yBtnX && mousePos.x <= yBtnX + btnW
                && mousePos.y >= btnY  && mousePos.y <= btnY + btnH;
        g2.setColor(yHover ? new Color(210, 55, 55) : new Color(140, 25, 25));
        g2.fillRoundRect(yBtnX, btnY, btnW, btnH, 12, 12);
        g2.setColor(yHover ? Color.WHITE : new Color(255, 120, 120));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(yBtnX, btnY, btnW, btnH, 12, 12);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 14));
        fm = g2.getFontMetrics();
        String yes = "YES, START FRESH  [Y / ENTER]";
        g2.drawString(yes, yBtnX + (btnW - fm.stringWidth(yes)) / 2, btnY + btnH / 2 + fm.getAscent() / 2 - 2);

        // NO button
        int nBtnX = btnStartX + btnW + btnGap;
        boolean nHover = mousePos != null
                && mousePos.x >= nBtnX && mousePos.x <= nBtnX + btnW
                && mousePos.y >= btnY  && mousePos.y <= btnY + btnH;
        g2.setColor(nHover ? new Color(35, 140, 75) : new Color(18, 80, 42));
        g2.fillRoundRect(nBtnX, btnY, btnW, btnH, 12, 12);
        g2.setColor(nHover ? Color.WHITE : new Color(90, 210, 130));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(nBtnX, btnY, btnW, btnH, 12, 12);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 14));
        fm = g2.getFontMetrics();
        String no = "PICK A NAME  [N / ESC]";
        g2.drawString(no, nBtnX + (btnW - fm.stringWidth(no)) / 2, btnY + btnH / 2 + fm.getAscent() / 2 - 2);

        // also update mouse click rects used in mouseClicked handler
        lbWarnYesRect.setBounds(yBtnX, btnY, btnW, btnH);
        lbWarnNoRect .setBounds(nBtnX, btnY, btnW, btnH);
    }

    // ══════════════════════════════════════════════════════════════
    //  TANK SELECTION SCREEN
    // ══════════════════════════════════════════════════════════════
    private void drawTankSelect(Graphics2D g2) {
        // dark overlay
        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        // panel
        int pw = 860, ph = 620;
        int px2 = (WIDTH - pw) / 2, py2 = (HEIGHT - ph) / 2;
        g2.setColor(new Color(8, 14, 42, 252));
        g2.fillRoundRect(px2, py2, pw, ph, 24, 24);
        g2.setColor(new Color(55, 115, 255));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawRoundRect(px2, py2, pw, ph, 24, 24);
        g2.setStroke(new BasicStroke(1f));

        // title
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 36));
        FontMetrics fm = g2.getFontMetrics();
        String title = "CHOOSE YOUR TANK";
        g2.drawString(title, (WIDTH - fm.stringWidth(title)) / 2, py2 + 50);

        g2.setColor(new Color(85, 120, 215, 140));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(px2 + 30, py2 + 62, px2 + pw - 30, py2 + 62);
        g2.setStroke(new BasicStroke(1f));

        // ── 3×2 grid of tank cards ───────────────────────────────────
        int cols = 3, rows = 2;
        int cardW = 220, cardH = 220, gapX = 24, gapY = 20;
        int totalGridW = cols * cardW + (cols - 1) * gapX;
        int gridStartX = (WIDTH - totalGridW) / 2;
        int gridStartY = py2 + 80;

        for (int i = 0; i < 6; i++) {
            int col = i % cols;
            int row = i / cols;
            int cx3 = gridStartX + col * (cardW + gapX);
            int cy3 = gridStartY + row * (cardH + gapY);

            boolean selected = (i == selectedTankDesign);
            boolean hovered  = (i == tankSelectHover);

            // card background
            if (selected) {
                g2.setPaint(new GradientPaint(cx3, cy3, new Color(30, 60, 160), cx3, cy3 + cardH, new Color(14, 28, 80)));
                g2.fillRoundRect(cx3, cy3, cardW, cardH, 14, 14);
                g2.setColor(TANK_DESIGN_PRIMARY[i]);
                g2.setStroke(new BasicStroke(3f));
                g2.drawRoundRect(cx3, cy3, cardW, cardH, 14, 14);
                g2.setStroke(new BasicStroke(1f));
            } else if (hovered) {
                g2.setColor(new Color(22, 38, 90, 220));
                g2.fillRoundRect(cx3, cy3, cardW, cardH, 14, 14);
                g2.setColor(new Color(80, 110, 200));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(cx3, cy3, cardW, cardH, 14, 14);
                g2.setStroke(new BasicStroke(1f));
            } else {
                g2.setColor(new Color(14, 20, 54, 200));
                g2.fillRoundRect(cx3, cy3, cardW, cardH, 14, 14);
                g2.setColor(new Color(40, 55, 120, 160));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(cx3, cy3, cardW, cardH, 14, 14);
                g2.setStroke(new BasicStroke(1f));
            }

            // ── Fixed layout zones (top→bottom) ──────────────────────
            // Name + swatch : cy3+12 to cy3+46
            // Tank preview  : cy3+50 to cy3+146  (96px tall)
            // Stat bars     : cy3+152 to cy3+178
            // Badge         : cy3+184 to cy3+208 (selected only)

            // ── name ─────────────────────────────────────────────────
            g2.setColor(selected ? TANK_DESIGN_HIGHLIGHT[i] : new Color(185, 200, 245));
            g2.setFont(new Font("Arial", Font.BOLD, 14));
            fm = g2.getFontMetrics();
            String nm = TANK_DESIGN_NAMES[i];
            g2.drawString(nm, cx3 + (cardW - fm.stringWidth(nm)) / 2, cy3 + 24);

            // ── color swatch ─────────────────────────────────────────
            int swW = 50, swH = 8;
            int swX = cx3 + (cardW - swW) / 2;
            g2.setColor(TANK_DESIGN_PRIMARY[i]);
            g2.fillRoundRect(swX, cy3 + 30, swW, swH, 4, 4);
            g2.setColor(TANK_DESIGN_ACCENT[i]);
            g2.fillRoundRect(swX, cy3 + 38, swW, 4, 4, 4);

            // ── tank preview ─────────────────────────────────────────
            int tankSz = 80;
            int tx = cx3 + (cardW - tankSz) / 2;
            int ty = cy3 + 50;
            drawTankPreview(g2, tx, ty, tankSz, i);

            // ── mini stat bars (Speed / Armor) ───────────────────────
            int[] speeds = {4, 4, 3, 5, 5, 4};
            int[] armors = {3, 3, 4, 2, 4, 3};
            String[] statLabels = {"SPD", "ARM"};
            int[] statVals = {speeds[i], armors[i]};
            Color[] statColors = {new Color(80, 180, 255), new Color(100, 220, 120)};
            int barW = 100, barH = 8;
            int barX = cx3 + (cardW - barW) / 2 + 14;
            int barStartY = cy3 + 148;
            g2.setFont(new Font("Arial", Font.BOLD, 9));
            fm = g2.getFontMetrics();
            for (int s = 0; s < 2; s++) {
                int sy2 = barStartY + s * (barH + 8);
                g2.setColor(new Color(20, 30, 70));
                g2.fillRoundRect(barX, sy2, barW, barH, 3, 3);
                g2.setColor(statColors[s]);
                g2.fillRoundRect(barX, sy2, barW * statVals[s] / 5, barH, 3, 3);
                g2.setColor(new Color(140, 160, 210));
                g2.drawString(statLabels[s], barX - fm.stringWidth(statLabels[s]) - 4, sy2 + barH - 1);
            }

            // ── SELECTED badge ───────────────────────────────────────
            if (selected) {
                int badgeW = 84, badgeH = 20;
                int badgeX = cx3 + (cardW - badgeW) / 2;
                int badgeY = cy3 + cardH - badgeH - 8;
                g2.setColor(TANK_DESIGN_PRIMARY[i]);
                g2.fillRoundRect(badgeX, badgeY, badgeW, badgeH, 8, 8);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.BOLD, 11));
                fm = g2.getFontMetrics();
                g2.drawString("SELECTED", badgeX + (badgeW - fm.stringWidth("SELECTED")) / 2, badgeY + 14);
            }
        }

        // ── START BATTLE button ──────────────────────────────────────
        int btnW2 = 240, btnH2 = 46;
        int btnX2 = (WIDTH - btnW2) / 2;
        int btnBY  = py2 + ph - 74;

        Rectangle startBattleBtn = new Rectangle(btnX2, btnBY, btnW2, btnH2);
        boolean btnHov = startBattleBtn.contains(mousePos);
        if (btnHov) g2.setPaint(new GradientPaint(btnX2, btnBY, new Color(48, 120, 240), btnX2, btnBY + btnH2, new Color(20, 60, 160)));
        else        g2.setColor(new Color(22, 40, 110, 220));
        g2.fillRoundRect(btnX2, btnBY, btnW2, btnH2, 14, 14);
        g2.setColor(btnHov ? new Color(120, 180, 255) : new Color(70, 110, 210));
        g2.setStroke(new BasicStroke(btnHov ? 2.5f : 1.5f));
        g2.drawRoundRect(btnX2, btnBY, btnW2, btnH2, 14, 14);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 20));
        fm = g2.getFontMetrics();
        String btnLabel = ">  START BATTLE";
        g2.drawString(btnLabel, btnX2 + (btnW2 - fm.stringWidth(btnLabel)) / 2, btnBY + 30);

        // ── hint ─────────────────────────────────────────────────────
        g2.setColor(new Color(90, 120, 185));
        g2.setFont(new Font("Arial", Font.PLAIN, 13));
        fm = g2.getFontMetrics();
        String hint1 = "Click to select  ·  ENTER / E to start  ·  ← → to browse  ·  ESC to go back";
        g2.drawString(hint1, (WIDTH - fm.stringWidth(hint1)) / 2, btnBY + btnH2 + 18);
    }

    /** Draws a coloured tank preview (no sprite) for the selection screen. */
    private void drawTankPreview(Graphics2D g2, int x, int y, int sz, int design) {
        Color primary   = TANK_DESIGN_PRIMARY[design];
        Color accent    = TANK_DESIGN_ACCENT[design];
        Color highlight = TANK_DESIGN_HIGHLIGHT[design];

        // tracks
        g2.setColor(new Color(20,20,20));
        g2.fillRoundRect(x - 5, y + 5, 6, sz - 10, 3, 3);
        g2.fillRoundRect(x + sz - 1, y + 5, 6, sz - 10, 3, 3);

        // hull
        g2.setPaint(new GradientPaint(x, y, primary, x, y + sz, accent));
        g2.fillRoundRect(x, y, sz, sz, 10, 10);
        g2.setColor(accent);
        g2.fillRoundRect(x + 7, y + 7, sz - 14, sz - 14, 8, 8);

        // cannon (pointing right)
        g2.setColor(new Color(20,20,20));
        g2.fillRoundRect(x + sz - 4, y + sz/2 - 4, 20, 8, 3, 3);

        // turret dome
        g2.setPaint(new GradientPaint(x+sz/2-9,y+sz/2-9,highlight,x+sz/2+9,y+sz/2+9,primary));
        g2.fillOval(x + sz/2 - 9, y + sz/2 - 9, 18, 18);
        g2.setColor(new Color(255,255,255,160));
        g2.fillOval(x + sz/2 - 4, y + sz/2 - 6, 5, 4);
    }

    /** Draws a tank preview for the MENU only — no HP bar, no overlapping labels.
     *  design = -1 → boss style, direction controls cannon orientation. */
    private void drawMenuTankPreview(Graphics2D g2, int x, int y, int sz, int design, Direction dir, boolean isBoss) {
        if (isBoss) {
            // ── cannon drawn first so body sits on top at overlap ──
            int cx2 = x + sz/2, cy2 = y + sz/2, cl = 32, cw2 = 11;
            g2.setColor(new Color(26,6,6));
            switch (dir) {
                case LEFT  -> g2.fillRoundRect(x - cl + 8,  cy2 - cw2/2, cl, cw2, 4, 4);
                case RIGHT -> g2.fillRoundRect(x + sz - 8,  cy2 - cw2/2, cl, cw2, 4, 4);
                case UP    -> g2.fillRoundRect(cx2 - cw2/2, y - cl + 8,  cw2, cl, 4, 4);
                case DOWN  -> g2.fillRoundRect(cx2 - cw2/2, y + sz - 8,  cw2, cl, 4, 4);
            }
            // ── tracks ──────────────────────────────────────────────
            g2.setColor(new Color(10,10,10));
            g2.fillRoundRect(x - 7, y + 5, 9, sz - 10, 5, 5);
            g2.fillRoundRect(x + sz - 2, y + 5, 9, sz - 10, 5, 5);
            g2.setColor(new Color(36,36,36));
            for (int i = y + 8; i < y + sz - 8; i += 9) {
                g2.drawLine(x - 7, i, x + 2, i);
                g2.drawLine(x + sz - 2, i, x + sz + 7, i);
            }
            // ── body ────────────────────────────────────────────────
            g2.setPaint(new GradientPaint(x, y, new Color(125,20,20), x, y + sz, new Color(52,2,2)));
            g2.fillRoundRect(x, y, sz, sz, 16, 16);
            g2.setColor(new Color(155,30,30)); g2.fillRoundRect(x + 8, y + 8, sz - 16, sz - 16, 12, 12);
            g2.setColor(new Color(92,10,10));  g2.fillRoundRect(x + 18, y + 18, sz - 36, sz - 36, 8, 8);
            // ── turret ──────────────────────────────────────────────
            g2.setPaint(new GradientPaint(x+sz/2-13, y+sz/2-13, new Color(252,215,42), x+sz/2+13, y+sz/2+13, new Color(172,92,0)));
            g2.fillOval(x+sz/2-13, y+sz/2-13, 26, 26);
            g2.setColor(new Color(52,2,2));
            g2.fillOval(x+sz/2-5, y+sz/2-3, 5, 4);
            g2.fillOval(x+sz/2+1, y+sz/2-3, 5, 4);
            g2.setColor(new Color(255,255,192,192));
            g2.fillOval(x+sz/2-6, y+sz/2-8, 6, 4);
        } else {
            // ── cannon drawn first ───────────────────────────────────
            int cx2 = x + sz/2, cy2 = y + sz/2, cl = 28, cw2 = 8;
            g2.setColor(new Color(12,12,12));
            switch (dir) {
                case LEFT  -> g2.fillRoundRect(x - cl + 8,  cy2 - cw2/2, cl, cw2, 4, 4);
                case RIGHT -> g2.fillRoundRect(x + sz - 8,  cy2 - cw2/2, cl, cw2, 4, 4);
                case UP    -> g2.fillRoundRect(cx2 - cw2/2, y - cl + 8,  cw2, cl, 4, 4);
                case DOWN  -> g2.fillRoundRect(cx2 - cw2/2, y + sz - 8,  cw2, cl, 4, 4);
            }
            // ── tracks ──────────────────────────────────────────────
            Color primary   = TANK_DESIGN_PRIMARY[design];
            Color accent    = TANK_DESIGN_ACCENT[design];
            Color highlight = TANK_DESIGN_HIGHLIGHT[design];
            g2.setColor(new Color(16,16,16));
            g2.fillRoundRect(x - 5, y + 3, 7, sz - 6, 4, 4);
            g2.fillRoundRect(x + sz - 2, y + 3, 7, sz - 6, 4, 4);
            g2.setColor(new Color(42,42,42));
            for (int i = y + 6; i < y + sz - 6; i += 8) {
                g2.drawLine(x - 5, i, x + 2, i);
                g2.drawLine(x + sz - 2, i, x + sz + 5, i);
            }
            // ── body ────────────────────────────────────────────────
            g2.setPaint(new GradientPaint(x, y, primary, x, y + sz, accent));
            g2.fillRoundRect(x, y, sz, sz, 10, 10);
            g2.setColor(accent);
            g2.fillRoundRect(x + 8, y + 8, sz - 16, sz - 16, 8, 8);
            // ── turret dome ─────────────────────────────────────────
            g2.setPaint(new GradientPaint(x+sz/2-10, y+sz/2-10, highlight, x+sz/2+10, y+sz/2+10, primary));
            g2.fillOval(x+sz/2-10, y+sz/2-10, 20, 20);
            g2.setColor(new Color(255,255,255,172));
            g2.fillOval(x+sz/2-5, y+sz/2-7, 6, 5);
        }
    }

    private void drawInstructions(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,175));
        g2.fillRect(0,0,WIDTH,HEIGHT);

        g2.setColor(new Color(8,14,42,248));
        g2.fillRoundRect(60, 24, WIDTH - 120, HEIGHT - 48, 22, 22);
        g2.setColor(new Color(55,115,255));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(60, 24, WIDTH - 120, HEIGHT - 48, 22, 22);
        g2.setStroke(new BasicStroke(1f));

        // Title
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,38));
        FontMetrics fm = g2.getFontMetrics();
        String title = "HOW TO PLAY";
        g2.drawString(title,(WIDTH - fm.stringWidth(title)) / 2, 80);
        g2.setColor(new Color(55,115,255,135));
        g2.drawLine(100,94, WIDTH - 100, 94);

        // ── LEFT COLUMN: Movement keys diagram ────────────────────────
        int kx = 110, ky = 120;
        int kSz = 50, kGap = 7;

        int wKx = kx + kSz + kGap;
        drawKeyBox(g2, wKx, ky,                   kSz, "W", "UP");
        drawKeyBox(g2, kx,  ky + kSz + kGap,      kSz, "A", "LEFT");
        drawKeyBox(g2, wKx, ky + kSz + kGap,      kSz, "S", "DOWN");
        drawKeyBox(g2, kx + (kSz+kGap)*2, ky + kSz + kGap, kSz, "D", "RIGHT");

        // Arrow keys
        int ax = kx + (kSz + kGap) * 3 + 22;
        drawKeyBox(g2, ax + kSz + kGap, ky,              kSz, "↑", "UP");
        drawKeyBox(g2, ax,              ky + kSz + kGap, kSz, "←", "LEFT");
        drawKeyBox(g2, ax + kSz + kGap, ky + kSz + kGap, kSz, "↓", "DOWN");
        drawKeyBox(g2, ax + (kSz+kGap)*2, ky + kSz + kGap, kSz, "→", "RIGHT");

        g2.setColor(new Color(130,160,255));
        g2.setFont(new Font("Arial",Font.BOLD,13));
        fm = g2.getFontMetrics();
        int wasdGroupW  = kSz * 3 + kGap * 2;
        int arrowGroupW = kSz * 3 + kGap * 2;
        g2.drawString("WASD",       kx + (wasdGroupW  - fm.stringWidth("WASD"))       / 2, ky + kSz*2 + kGap*2 + 20);
        g2.drawString("ARROW KEYS", ax + (arrowGroupW - fm.stringWidth("ARROW KEYS")) / 2, ky + kSz*2 + kGap*2 + 20);

        g2.setColor(new Color(200,215,255));
        g2.setFont(new Font("Arial",Font.PLAIN,15));
        g2.drawString("Move your tank", kx, ky + kSz*2 + kGap*2 + 65);

        // SPACE key
        int spY = ky + kSz*2 + kGap*2 + 89;
        g2.setColor(new Color(28,52,118));
        g2.fillRoundRect(kx, spY, (kSz*2 + kGap), 40, 8, 8);
        g2.setColor(new Color(100,160,255));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(kx, spY, (kSz*2 + kGap), 40, 8, 8);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,15));
        fm = g2.getFontMetrics();
        g2.drawString("SPACE", kx + ((kSz*2+kGap) - fm.stringWidth("SPACE"))/2, spY + 26);
        g2.setColor(new Color(200,215,255));
        g2.setFont(new Font("Arial",Font.PLAIN,15));
        g2.drawString("Shoot  (hold = auto)", kx + (kSz*2 + kGap) + 14, spY + 26);

        // ESC key
        int escY = spY + 58;
        drawKeyBox(g2, kx, escY, kSz, "ESC", null);
        g2.setColor(new Color(200,215,255));
        g2.setFont(new Font("Arial",Font.PLAIN,15));
        g2.drawString("Pause / Settings", kx + kSz + 14, escY + 32);

        // P key
        int pY = escY + 62;
        drawKeyBox(g2, kx, pY, kSz, "P", null);
        g2.setColor(new Color(200,215,255));
        g2.setFont(new Font("Arial",Font.PLAIN,15));
        g2.drawString("Pause (toggle)", kx + kSz + 14, pY + 32);

        // F11 key
        int f11Y = pY + 62;
        drawKeyBox(g2, kx, f11Y, kSz, "F11", null);
        g2.setColor(new Color(200,215,255));
        g2.setFont(new Font("Arial",Font.PLAIN,15));
        g2.drawString("Fullscreen toggle", kx + kSz + 14, f11Y + 32);

        // ── RIGHT COLUMN: Game rules ───────────────────────────────────
        // Dynamically space rows to fill available height
        int rx = WIDTH / 2 + 30;
        int rulesTop = 110;
        int rulesBottom = HEIGHT - 72; // leave room for back button
        Object[][] rules = {
            {"LEVELS",     "15 total  ·  Easy 1–5  ·  Med 6–10  ·  Hard 11–15"},
            {"RETRIES",    "3 retries per run  —  no more = game ends"},
            {"SCORE",      "Resets to level-start value on retry"},
            {"LEADERBOARD","Top 10 only  —  low scores won't be recorded"},
            {"CARDS",      "Pick 1 of 3 common upgrades before every level"},
            {"RARE CARDS", "Defeat a boss → pick 1 of 3 RARE upgrades!"},
            {"LVL 5",      "Mini-Boss  (kill minions first to unlock)"},
            {"LVL 10",     "Elite Boss"},
            {"LVL 15",     "Final Boss  —  defeat to win!"},
            {"SCORING",    "+10 × level per kill  ·  Boss kill = +500"},
        };

        int rowCount = rules.length;
        // Each row: pill (22px tall) + value text below it — compute lineH to spread evenly
        int totalRowH = rulesBottom - rulesTop;
        int lineH = totalRowH / rowCount;  // e.g. ~56px per row at 700px height

        int pillW = 124, pillH = 24;

        int ry = rulesTop + lineH / 2; // start centered in first slot
        for (Object[] row : rules) {
            String tag = (String)row[0];
            String val = (String)row[1];

            // pill background — golden tint for rare-related rows
            boolean isRareRow = tag.equals("RARE CARDS");
            g2.setColor(isRareRow ? new Color(60,36,4) : new Color(22,44,110));
            g2.fillRoundRect(rx, ry - pillH + 4, pillW, pillH, 8, 8);
            g2.setColor(isRareRow ? new Color(220,160,40) : new Color(100,148,255));
            g2.setStroke(new BasicStroke(isRareRow ? 1.5f : 1f));
            g2.drawRoundRect(rx, ry - pillH + 4, pillW, pillH, 8, 8);
            g2.setStroke(new BasicStroke(1f));

            // pill label
            g2.setColor(isRareRow ? new Color(255,210,80) : new Color(140,180,255));
            g2.setFont(new Font("Arial",Font.BOLD,12));
            fm = g2.getFontMetrics();
            g2.drawString(tag, rx + (pillW - fm.stringWidth(tag))/2, ry - 2);

            // value text — right of pill
            g2.setColor(isRareRow ? new Color(255,230,160) : new Color(210,222,255));
            g2.setFont(new Font("Arial",Font.PLAIN,15));
            g2.drawString(val, rx + pillW + 14, ry);

            ry += lineH;
        }

        // Back button
        int bx = WIDTH / 2 - 145;
        int backBtnY = HEIGHT - 58;
        g2.setColor(new Color(32,52,128,208));
        g2.fillRoundRect(bx, backBtnY, 290, 40, 14, 14);
        g2.setColor(new Color(85,135,255));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(bx, backBtnY, 290, 40, 14, 14);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,17));
        fm = g2.getFontMetrics();
        String backLbl = "ESC  —  Return to Menu";
        g2.drawString(backLbl, bx + (290 - fm.stringWidth(backLbl)) / 2, backBtnY + 26);
    }

    /** Draws a single keyboard key box with a main label and optional sub-label. */
    private void drawKeyBox(Graphics2D g2, int x, int y, int sz, String label, String sub) {
        g2.setColor(new Color(28,52,118));
        g2.fillRoundRect(x, y, sz, sz, 8, 8);
        g2.setColor(new Color(80,130,255));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(x, y, sz, sz, 8, 8);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, label.length() > 2 ? 12 : 17));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, x + (sz - fm.stringWidth(label)) / 2, y + sz/2 + fm.getAscent()/2 - (sub != null ? 5 : 0));
        if (sub != null) {
            g2.setColor(new Color(130,170,255));
            g2.setFont(new Font("Arial", Font.PLAIN, 11));
            fm = g2.getFontMetrics();
            g2.drawString(sub, x + (sz - fm.stringWidth(sub)) / 2, y + sz - 6);
        }
    }

    private void drawLeaderboard(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,180));
        g2.fillRect(0,0,WIDTH,HEIGHT);

        int panelX = 160, panelW = WIDTH - 320;
        g2.setColor(new Color(8,14,42,248));
        g2.fillRoundRect(panelX, 34, panelW, HEIGHT - 68, 22, 22);
        g2.setColor(new Color(215,165,25));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(panelX, 34, panelW, HEIGHT - 68, 22, 22);
        g2.setStroke(new BasicStroke(1f));

        g2.setColor(new Color(255,212,65));
        g2.setFont(new Font("Arial",Font.BOLD,42));
        FontMetrics fm = g2.getFontMetrics();
        String title = "LEADERBOARD";
        g2.drawString(title,(WIDTH - fm.stringWidth(title)) / 2, 98);
        g2.setColor(new Color(155,115,0,135));
        g2.drawLine(panelX + 30, 112, panelX + panelW - 30, 112);

        // Column positions — evenly spaced inside the panel
        int innerX  = panelX + 30;
        int innerW  = panelW - 60;
        int c1 = innerX;                          // RANK  (80px wide)
        int c2 = innerX + 90;                     // NAME  (up to 260px)
        int c3 = innerX + innerW - 100;           // SCORE (right-aligned area)
        int nameMaxW = c3 - c2 - 20;              // max pixels for name text

        g2.setColor(new Color(125,162,252));
        g2.setFont(new Font("Arial",Font.BOLD,15));
        g2.drawString("RANK", c1, 146);
        g2.drawString("NAME", c2, 146);
        g2.drawString("SCORE", c3, 146);
        g2.setColor(new Color(52,72,138));
        g2.drawLine(panelX + 20, 152, panelX + panelW - 20, 152);

        List<ScoreEntry> sc = LeaderboardManager.loadScores();
        if (sc.isEmpty()) {
            g2.setColor(new Color(125,135,178));
            g2.setFont(new Font("Arial",Font.ITALIC,22));
            String empty = "No scores yet - be the first!";
            g2.drawString(empty, (WIDTH - g2.getFontMetrics().stringWidth(empty)) / 2, 338);
        } else {
            Color[] medals = {new Color(255,212,0),new Color(198,198,198),new Color(175,98,48)};
            int y = 186;
            for (int i = 0; i < Math.min(sc.size(), 10); i++) {
                ScoreEntry se = sc.get(i);
                boolean top = i < 3;

                // Row background
                g2.setColor(i % 2 == 0 ? new Color(18,28,68,108) : new Color(8,14,48,68));
                g2.fillRoundRect(panelX + 16, y - 18, panelW - 32, 28, 6, 6);

                // RANK
                g2.setColor(top ? medals[i] : new Color(152,162,208));
                g2.setFont(new Font("Arial", top ? Font.BOLD : Font.PLAIN, top ? 18 : 16));
                String rankStr = (i + 1) + (top ? (i == 0 ? " \u25CF" : i == 1 ? " \u25CF" : " \u25CF") : "");
                g2.drawString(rankStr, c1, y);

                // NAME — clip if too long
                g2.setColor(top ? Color.WHITE : new Color(192,202,236));
                g2.setFont(new Font("Arial", top ? Font.BOLD : Font.PLAIN, 16));
                String nameStr = fitText(g2, se.name, nameMaxW);
                g2.drawString(nameStr, c2, y);

                // SCORE — right-align within score column
                g2.setFont(new Font("Arial", Font.BOLD, 16));
                g2.setColor(top ? new Color(255,212,65) : new Color(152,192,255));
                String scoreStr = String.valueOf(se.score);
                fm = g2.getFontMetrics();
                // Right-align: draw so right edge sits at innerX + innerW
                g2.drawString(scoreStr, innerX + innerW - fm.stringWidth(scoreStr), y);

                y += 40;
            }
        }

        int bx = WIDTH / 2 - 135;
        int lbBackY = 34 + (HEIGHT - 68) - 52;
        g2.setColor(new Color(42,62,128,208));
        g2.fillRoundRect(bx, lbBackY, 270, 40, 14, 14);
        g2.setColor(new Color(165,128,38));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(bx, lbBackY, 270, 40, 14, 14);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,17));
        FontMetrics fmLb = g2.getFontMetrics();
        String lbBack = "ESC  -  Return to Menu";
        g2.drawString(lbBack, bx + (270 - fmLb.stringWidth(lbBack)) / 2, lbBackY + 26);
    }

    private void drawSidebar(Graphics2D g2) {
        int sx = GAME_W, sw = SIDEBAR;
        int px = sx + 12;
        int maxW = sw - 24;

        // Save original clip
        Shape origClip = g2.getClip();
        // Clip all rendering to sidebar bounds
        g2.setClip(sx, 0, sw, HEIGHT);

        // Background
        g2.setPaint(new GradientPaint(sx,0,new Color(10,13,32),sx+sw,HEIGHT,new Color(14,18,44)));
        g2.fillRect(sx, 0, sw, HEIGHT);
        g2.setColor(new Color(50,105,252,195));
        g2.setStroke(new BasicStroke(2f));
        g2.drawLine(sx, 0, sx, HEIGHT);
        g2.setStroke(new BasicStroke(1f));

        // Controls strip at bottom
        int ctrlH = 68;
        int ctrlY = HEIGHT - ctrlH;
        g2.setColor(new Color(20,25,55,210));
        g2.fillRect(sx+1, ctrlY, sw-1, ctrlH);
        g2.setColor(new Color(46,56,108,160));
        g2.drawLine(sx+8, ctrlY, sx+sw-8, ctrlY);
        g2.setColor(new Color(65,80,122));
        g2.setFont(new Font("Arial", Font.PLAIN, 10));
        int cy = ctrlY + 11;
        g2.drawString("WASD/Arrows Move",    px, cy); cy += 12;
        g2.drawString("SPACE Shoot",         px, cy); cy += 12;
        g2.drawString("ESC Options",         px, cy); cy += 12;
        g2.drawString("P/F11 Pause/FS",      px, cy);

        // Content area runs from below the top-bar to above the controls strip
        int py = TOP_BAR + 8;
        int maxPy = ctrlY - 6;

        // helper: draw a section label (small caps style) and return new py
        // LEVEL
        sbLbl(g2, "LEVEL", px, py); py += 18;
        g2.setColor(new Color(255,212,65));
        g2.setFont(new Font("Arial", Font.BOLD, 18));
        String lvlTxt = fitText(g2, level + " / " + MAX_LEVEL, maxW);
        g2.drawString(lvlTxt, px, py); py += 12;
        g2.setColor(new Color(180,180,255));
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        String diffTxt = fitText(g2, getDifficultyName(level), maxW);
        g2.drawString(diffTxt, px, py + 8); py += 16;
        if (level == 5 || level == 10 || level == 15) {
            String bl = level == 5 ? "MINI-BOSS" : level == 10 ? "ELITE BOSS" : "FINAL BOSS";
            g2.setColor(new Color(252,160,40));
            g2.setFont(new Font("Arial", Font.BOLD, 10));
            String bossTxt = fitText(g2, bl, maxW);
            g2.drawString(bossTxt, px, py + 2);
            py += 16;
        }
        if (py >= maxPy) return; sbDiv(g2, sx, sw, py); py += 8;

        // SCORE
        sbLbl(g2, "SCORE", px, py); py += 18;
        g2.setColor(new Color(95,252,115));
        g2.setFont(new Font("Arial", Font.BOLD, 18));
        String scoreTxt = fitText(g2, String.valueOf(score), maxW);
        g2.drawString(scoreTxt, px, py); py += 12;
        if (py >= maxPy) return; sbDiv(g2, sx, sw, py); py += 8;

        // HEALTH
        sbLbl(g2, "HEALTH", px, py); py += 18;
        float hp = player != null ? (float)player.health / player.maxHealth : 0f;
        g2.setColor(new Color(65,12,12));
        g2.fillRoundRect(px, py, maxW, 14, 4, 4);
        Color hc = hp > 0.5f ? new Color(46,205,46) : hp > 0.25f ? new Color(245,165,0) : new Color(235,28,28);
        g2.setColor(hc);
        g2.fillRoundRect(px, py, (int)(maxW * Math.max(0,hp)), 14, 4, 4);
        g2.setColor(new Color(255,255,255,55));
        g2.drawRoundRect(px, py, maxW, 14, 4, 4);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        String hpTxt = (player!=null?Math.max(0,player.health):0)+" / "+(player!=null?player.maxHealth:100)+" HP";
        String hpFit = fitText(g2, hpTxt, maxW - 6);
        g2.drawString(hpFit, px+3, py+11);
        py += 28;
        if (py >= maxPy) return; sbDiv(g2, sx, sw, py); py += 8;

        // QUOTA
        sbLbl(g2, "QUOTA", px, py); py += 18;
        for (int i = 0; i < quotaRequired; i++) {
            int col = i % 10, row = i / 10;
            g2.setColor(i < quotaKilled ? new Color(46,188,46) : new Color(46,46,72));
            g2.fillOval(px + col*15, py + row*15, 10, 10);
            if (i < quotaKilled) {
                g2.setColor(new Color(175,252,175));
                g2.setFont(new Font("Arial", Font.BOLD, 8));
                g2.drawString("v", px + col*15+2, py + row*15+9);
            }
        }
        int quotaRows = Math.max(1,(quotaRequired-1)/10+1);
        py += quotaRows*15 + 6;
        g2.setColor(new Color(165,165,165));
        g2.setFont(new Font("Arial", Font.PLAIN, 10));
        String quotaTxt = fitText(g2, quotaKilled + " / " + quotaRequired + " kills", maxW);
        g2.drawString(quotaTxt, px, py); py += 14;
        if (py >= maxPy) return; sbDiv(g2, sx, sw, py); py += 8;

        // RETRIES
        sbLbl(g2, "RETRIES", px, py); py += 18;
        for (int i = 0; i < 3; i++) {
            boolean active = i < retriesLeft;
            g2.setColor(active ? new Color(255,90,90) : new Color(55,30,30));
            g2.fillRoundRect(px + i*19, py-12, 14, 14, 3, 3);
            if (!active) { g2.setColor(new Color(100,55,55)); g2.drawRoundRect(px+i*19, py-12, 14, 14, 3, 3); }
        }
        py += 12;
        if (py >= maxPy) return; sbDiv(g2, sx, sw, py); py += 8;

        // AMMO
        sbLbl(g2, "AMMO", px, py); py += 18;
        g2.setColor(new Color(220,235,255));
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        if (playerReloadTimer > 0) {
            g2.setColor(new Color(255,185,45));
            String reloadTxt = fitText(g2, "Reloading...", maxW);
            g2.drawString(reloadTxt, px, py);
            g2.setFont(new Font("Arial", Font.PLAIN, 10));
            String timeTxt = fitText(g2, String.format("%.1fs left", playerReloadTimer/60.0), maxW);
            g2.drawString(timeTxt, px, py+14);
            py += 16;
        } else {
            String ammoTxt = fitText(g2, playerAmmo + " / " + playerMagazineSize, maxW);
            g2.drawString(ammoTxt, px, py);
            py += 10;
        }
        py += 10;
        if (py >= maxPy) return; sbDiv(g2, sx, sw, py); py += 8;

        // TIME
        sbLbl(g2, "TIME", px, py); py += 18;
        Color tc = levelTimeRemaining > 20 ? new Color(85,190,255)
                 : levelTimeRemaining > 10 ? new Color(255,185,45)
                 : new Color(252,50,50);
        g2.setColor(tc);
        g2.setFont(new Font("Arial", Font.BOLD, 20));
        String timeTxt = fitText(g2, String.format("%d:%02d", levelTimeRemaining/60, levelTimeRemaining%60), maxW);
        g2.drawString(timeTxt, px, py+3);
        py += 28;

        // BOSS STATUS
        if ((level == 5 || level == 10 || level == 15) && py + 30 < maxPy) {
            sbDiv(g2, sx, sw, py); py += 7;
            boolean locked = enemies.stream().anyMatch(e -> e.isBoss && !e.bossUnlocked);
            String bossLabel = level == 5 ? "MINI-BOSS" : level == 10 ? "ELITE BOSS" : "FINAL BOSS";
            sbLbl(g2, bossLabel, px, py); py += 13;
            if (py < maxPy) {
                if (locked) {
                    g2.setColor(new Color(252,85,85));
                    g2.setFont(new Font("Arial", Font.BOLD, 11));
                    g2.drawString("LOCKED", px, py);
                    if (py+12 < maxPy) { g2.setColor(new Color(145,145,145)); g2.setFont(new Font("Arial",Font.PLAIN,10)); g2.drawString("Kill minions first", px, py+12); }
                } else {
                    g2.setColor(new Color(252,48,48));
                    g2.setFont(new Font("Arial", Font.BOLD, 11));
                    g2.drawString("UNLOCKED!", px, py);
                }
            }
        }

        // Restore original clip
        g2.setClip(origClip);
    }

    private String fitText(Graphics2D g2, String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) return text;

        String ellipsis = "...";
        int limit = Math.max(0, maxWidth - fm.stringWidth(ellipsis));
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            String next = out.toString() + text.charAt(i);
            if (fm.stringWidth(next) > limit) break;
            out.append(text.charAt(i));
        }
        return out + ellipsis;
    }

    private void drawTopBar(Graphics2D g2) {
        g2.setPaint(new GradientPaint(0,0,new Color(10,14,38),WIDTH,TOP_BAR,new Color(18,24,58)));
        g2.fillRect(0,0,WIDTH,TOP_BAR);
        g2.setColor(new Color(50,105,252,200));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(0,TOP_BAR,WIDTH,TOP_BAR);
        g2.setStroke(new BasicStroke(1f));

        int mapCenterX = GAME_W / 2;
        int sidebarX   = GAME_W;

        g2.setColor(new Color(100,170,255));
        g2.setFont(new Font("Arial",Font.BOLD,15));
        g2.drawString("TANK BATTLE ARENA", 14, 22);

        g2.setColor(new Color(50,80,150));
        g2.drawLine(200,6,200,28);

        g2.setColor(new Color(150,160,190));
        g2.setFont(new Font("Arial",Font.PLAIN,11));
        g2.drawString("PLAYER", 212, 14);
        g2.setColor(new Color(220,235,255));
        g2.setFont(new Font("Arial",Font.BOLD,15));
        String shownName = fitText(g2, playerName.isBlank() ? "Player" : playerName, 180);
        g2.drawString(shownName, 212, 28);

        String lvlStr = "LVL " + level + " / " + MAX_LEVEL + "  " + getDifficultyName(level);
        String bossTag = "";
        switch (level) {
            case 5  -> bossTag = "  !! MINI-BOSS";
            case 10 -> bossTag = "  !! ELITE BOSS";
            case 15 -> bossTag = "  !! FINAL BOSS";
        }
        g2.setColor(new Color(255,212,65));
        g2.setFont(new Font("Arial",Font.BOLD,16));
        FontMetrics fm = g2.getFontMetrics();
        String fullLvl = fitText(g2, lvlStr + bossTag, 420);
        g2.drawString(fullLvl, mapCenterX - fm.stringWidth(fullLvl) / 2, 23);

        g2.setColor(new Color(50,80,150));
        g2.drawLine(sidebarX, 4, sidebarX, TOP_BAR - 4);

        // ── FULLSCREEN button — top-right of game top-bar, like a title-bar button ──
        int fsBtnW = 34, fsBtnH = 22;
        int fsBtnX = WIDTH - fsBtnW - 6;
        int fsBtnY = (TOP_BAR - fsBtnH) / 2;
        fsBtn.setBounds(fsBtnX, fsBtnY, fsBtnW, fsBtnH);
        boolean fsHover = fsBtn.contains(mousePos);

        // Background: dark like a title-bar button, blue-ish on hover
        g2.setColor(fsHover ? new Color(50,100,220,230) : new Color(22,30,65,200));
        g2.fillRoundRect(fsBtnX, fsBtnY, fsBtnW, fsBtnH, 4, 4);
        g2.setColor(fsHover ? new Color(120,170,255,200) : new Color(55,80,160,180));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(fsBtnX, fsBtnY, fsBtnW, fsBtnH, 4, 4);

        // Corner-bracket icon (expand or compress)
        g2.setColor(fsHover ? Color.WHITE : new Color(140,175,255));
        g2.setStroke(new BasicStroke(2f));
        int ix = fsBtnX + 6, iy = fsBtnY + 5, iw = fsBtnW - 12, ih = fsBtnH - 10;
        int mm = 4;
        if (fullscreen) {
            // Compress arrows — pointing inward
            g2.drawLine(ix,      iy+mm,   ix+mm,   iy+mm);   g2.drawLine(ix+mm,   iy,      ix+mm,   iy+mm);
            g2.drawLine(ix+iw,   iy+mm,   ix+iw-mm,iy+mm);   g2.drawLine(ix+iw-mm,iy,      ix+iw-mm,iy+mm);
            g2.drawLine(ix,      iy+ih-mm,ix+mm,   iy+ih-mm);g2.drawLine(ix+mm,   iy+ih,   ix+mm,   iy+ih-mm);
            g2.drawLine(ix+iw,   iy+ih-mm,ix+iw-mm,iy+ih-mm);g2.drawLine(ix+iw-mm,iy+ih,   ix+iw-mm,iy+ih-mm);
        } else {
            // Expand arrows — pointing outward
            g2.drawLine(ix,      iy,      ix+mm,   iy);      g2.drawLine(ix,      iy,      ix,      iy+mm);
            g2.drawLine(ix+iw,   iy,      ix+iw-mm,iy);      g2.drawLine(ix+iw,   iy,      ix+iw,   iy+mm);
            g2.drawLine(ix,      iy+ih,   ix+mm,   iy+ih);   g2.drawLine(ix,      iy+ih,   ix,      iy+ih-mm);
            g2.drawLine(ix+iw,   iy+ih,   ix+iw-mm,iy+ih);   g2.drawLine(ix+iw,   iy+ih,   ix+iw,   iy+ih-mm);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void sbLbl(Graphics2D g2, String t, int x, int y) {
        g2.setColor(new Color(85,120,205));
        g2.setFont(new Font("Arial",Font.BOLD,12));
        g2.drawString(t,x,y);
    }

    private void sbDiv(Graphics2D g2, int sx, int sw, int y) {
        g2.setColor(new Color(46,56,108,128));
        g2.drawLine(sx + 8,y,sx + sw - 8,y);
    }

    private void drawPauseMenu(Graphics2D g2) {
        if (settingsOpen) { drawSettingsScreen(g2); return; }

        g2.setColor(new Color(0,0,0,200));
        g2.fillRect(0,0,WIDTH,HEIGHT);

        int cw = 480, ch = 420, cx = (WIDTH - cw) / 2, cy = (HEIGHT - ch) / 2;
        g2.setColor(new Color(10,16,46,252));
        g2.fillRoundRect(cx,cy,cw,ch,22,22);
        g2.setColor(new Color(55,115,255));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(cx,cy,cw,ch,22,22);
        g2.setStroke(new BasicStroke(1f));

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,40));
        FontMetrics fm = g2.getFontMetrics();
        String title = "PAUSED";
        g2.drawString(title, cx + (cw - fm.stringWidth(title)) / 2, cy + 58);
        g2.setColor(new Color(55,115,255,135));
        g2.drawLine(cx + 30,cy + 70,cx + cw - 30,cy + 70);

        String[] icons = {">","R","*","@","X"};
        Color[] accs = {
                new Color(55,180,55),
                new Color(200,160,40),
                new Color(80,130,255),
                new Color(100,100,200),
                new Color(200,60,60)
        };

        int iy = cy + 100;
        for (int i = 0; i < PAUSE_ITEMS.length; i++) {
            boolean isRetry = (i == 1);
            boolean disabled = isRetry && retriesLeft <= 0;
            boolean sel = (i == pauseCursor) && !disabled;

            g2.setColor(sel ? new Color(accs[i].getRed(), accs[i].getGreen(), accs[i].getBlue(), 40)
                    : (disabled ? new Color(30,30,40,100) : new Color(18,26,68,160)));
            g2.fillRoundRect(cx + 28, iy, cw - 56, 46, 10, 10);

            if (sel) {
                g2.setColor(accs[i]);
                g2.setStroke(new BasicStroke(1.8f));
                g2.drawRoundRect(cx + 28, iy, cw - 56, 46, 10, 10);
                g2.setStroke(new BasicStroke(1f));
            } else if (disabled) {
                g2.setColor(new Color(60,60,70));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(cx + 28, iy, cw - 56, 46, 10, 10);
            }

            g2.setColor(disabled ? new Color(60,60,70) : (sel ? accs[i] : new Color(80,100,160)));
            g2.setFont(new Font("Arial",Font.BOLD,18));
            g2.drawString(icons[i], cx + 46, iy + 30);

            String itemText = PAUSE_ITEMS[i];
            if (isRetry) {
                itemText += " (" + retriesLeft + " LEFT)";
            }

            g2.setColor(disabled ? new Color(80,80,90) : (sel ? Color.WHITE : new Color(160,180,230)));
            g2.setFont(new Font("Arial",Font.BOLD,20));
            g2.drawString(itemText, cx + 80, iy + 30);

            iy += 52;
        }

        g2.setColor(new Color(65,85,140));
        g2.setFont(new Font("Arial",Font.PLAIN,13));
        String hint = "W / S - navigate     ENTER - select     ESC - resume";
        fm = g2.getFontMetrics();
        g2.drawString(hint, cx + (cw - fm.stringWidth(hint)) / 2, cy + ch - 14);
    }

    private void drawSettingsScreen(Graphics2D g2) {
        g2.setPaint(new GradientPaint(0,0,new Color(8,12,30),WIDTH,HEIGHT,new Color(18,32,68)));
        g2.fillRect(0,0,WIDTH,HEIGHT);
        g2.setColor(new Color(255,255,255,9));
        for (int x = 0; x < WIDTH; x += 50) g2.drawLine(x,0,x,HEIGHT);
        for (int y = 0; y < HEIGHT; y += 50) g2.drawLine(0,y,WIDTH,y);

        // Panel — wide enough so no text clips
        int cw = 720, ch = 500, cx = (WIDTH - cw) / 2, cy = (HEIGHT - ch) / 2;
        g2.setColor(new Color(10,16,46,248));
        g2.fillRoundRect(cx,cy,cw,ch,22,22);
        g2.setColor(new Color(55,115,255));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(cx,cy,cw,ch,22,22);
        g2.setStroke(new BasicStroke(1f));

        // ── Title ─────────────────────────────────────────────────────
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,34));
        FontMetrics fm = g2.getFontMetrics();
        String title = "SETTINGS";
        g2.drawString(title, cx + (cw - fm.stringWidth(title)) / 2, cy + 50);
        g2.setColor(new Color(55,115,255,135));
        g2.drawLine(cx + 30, cy + 62, cx + cw - 30, cy + 62);

        // Row geometry
        int rowX  = cx + 36;
        int rowW  = cw - 72;
        int iy    = cy + 78;
        final int ROW_H = 36, ROW_GAP = 5;

        // ── CONTROLS label ────────────────────────────────────────────
        g2.setColor(new Color(85,120,205));
        g2.setFont(new Font("Arial",Font.BOLD,11));
        g2.drawString("CONTROLS", rowX, iy); iy += 12;

        // Control rows
        String[][] ctrlRows = {
            {"MOVE",       "W / A / S / D  or  Arrow Keys"},
            {"SHOOT",      "SPACE"},
            {"PAUSE",      "P"},
            {"OPTIONS",    "ESC"},
            {"FULLSCREEN", "F11"},
        };
        for (String[] cr : ctrlRows) {
            g2.setColor(new Color(28,42,100));
            g2.fillRoundRect(rowX, iy, rowW, ROW_H, 8, 8);
            g2.setColor(new Color(55,85,180,80));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(rowX, iy, rowW, ROW_H, 8, 8);

            g2.setColor(new Color(130,155,220));
            g2.setFont(new Font("Arial",Font.BOLD,13));
            g2.drawString(cr[0], rowX + 14, iy + 23);

            g2.setColor(new Color(210,225,255));
            g2.setFont(new Font("Arial",Font.PLAIN,13));
            fm = g2.getFontMetrics();
            g2.drawString(cr[1], rowX + rowW - 12 - fm.stringWidth(cr[1]), iy + 23);
            iy += ROW_H + ROW_GAP;
        }

        // Divider
        iy += 6;
        g2.setColor(new Color(55,115,255,60));
        g2.drawLine(cx + 30, iy, cx + cw - 30, iy);
        iy += 12;

        // ── SIDEBAR BORDER ────────────────────────────────────────────
        final int OPT_H = 48;
        boolean selBorder = (settingsCursor == 2);
        g2.setColor(selBorder ? new Color(28,52,118) : new Color(18,28,68));
        g2.fillRoundRect(rowX, iy, rowW, OPT_H, 10, 10);
        if (selBorder) {
            g2.setColor(new Color(55,115,255));
            g2.setStroke(new BasicStroke(1.8f));
            g2.drawRoundRect(rowX, iy, rowW, OPT_H, 10, 10);
            g2.setStroke(new BasicStroke(1f));
        }
        g2.setColor(selBorder ? Color.WHITE : new Color(165,185,240));
        g2.setFont(new Font("Arial",Font.BOLD,14));
        g2.drawString("SIDEBAR BORDER", rowX + 14, iy + 20);
        g2.setColor(new Color(100,170,255));
        g2.setFont(new Font("Arial",Font.PLAIN,10));
        g2.drawString("ENTER to cycle", rowX + 14, iy + 36);

        // Value pill — right-aligned
        String bval = BORDER_NAMES[borderStyle];
        g2.setFont(new Font("Arial",Font.BOLD,12));
        fm = g2.getFontMetrics();
        int pvw = fm.stringWidth(bval) + 22;
        int pvx = rowX + rowW - pvw - 8;
        g2.setColor(new Color(46,98,228));
        g2.fillRoundRect(pvx, iy + 12, pvw, 22, 8, 8);
        g2.setColor(Color.WHITE);
        g2.drawString(bval, pvx + (pvw - fm.stringWidth(bval)) / 2, iy + 27);
        iy += OPT_H + 8;

        // ── RESET TO DEFAULT ──────────────────────────────────────────
        boolean selReset = (settingsCursor == 1);
        g2.setColor(selReset ? new Color(36,18,18) : new Color(22,14,14));
        g2.fillRoundRect(rowX, iy, rowW, OPT_H, 10, 10);
        if (selReset) {
            g2.setColor(new Color(210,60,60));
            g2.setStroke(new BasicStroke(1.8f));
            g2.drawRoundRect(rowX, iy, rowW, OPT_H, 10, 10);
            g2.setStroke(new BasicStroke(1f));
        }
        g2.setColor(selReset ? new Color(255,120,120) : new Color(200,80,80));
        g2.setFont(new Font("Arial",Font.BOLD,14));
        g2.drawString("RESET TO DEFAULT", rowX + 14, iy + 28);
        iy += OPT_H + 8;

        // ── BACK TO PAUSE ─────────────────────────────────────────────
        boolean selExit = (settingsCursor == 0);
        g2.setColor(selExit ? new Color(26,36,76) : new Color(16,22,52));
        g2.fillRoundRect(rowX, iy, rowW, OPT_H, 10, 10);
        if (selExit) {
            g2.setColor(new Color(95,150,255));
            g2.setStroke(new BasicStroke(1.8f));
            g2.drawRoundRect(rowX, iy, rowW, OPT_H, 10, 10);
            g2.setStroke(new BasicStroke(1f));
        }
        g2.setColor(selExit ? Color.WHITE : new Color(125,155,230));
        g2.setFont(new Font("Arial",Font.BOLD,14));
        g2.drawString("<-- BACK TO PAUSE", rowX + 14, iy + 28);

        // ── Bottom hint (two short lines instead of one long one) ─────
        g2.setColor(new Color(70,90,150));
        g2.setFont(new Font("Arial",Font.PLAIN,12));
        fm = g2.getFontMetrics();
        String h1 = "W / S  navigate       ENTER  confirm";
        String h2 = "ESC  back to pause";
        g2.drawString(h1, cx + (cw - fm.stringWidth(h1)) / 2, cy + ch - 28);
        g2.drawString(h2, cx + (cw - fm.stringWidth(h2)) / 2, cy + ch - 12);
    }

    private void drawLevelCleared(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,230));
        g2.fillRect(0,0,WIDTH,HEIGHT);

        int cardW = 560, cardH = 290;
        int cardX = (GAME_W - cardW) / 2;
        int cardY = 205;
        int cx = cardX + cardW / 2;

        g2.setColor(new Color(10,36,10,252));
        g2.fillRoundRect(cardX, cardY, cardW, cardH, 22, 22);
        g2.setColor(new Color(42,210,72));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(cardX, cardY, cardW, cardH, 22, 22);
        g2.setStroke(new BasicStroke(1f));

        FontMetrics fm;
        g2.setColor(new Color(70,250,108));
        g2.setFont(new Font("Arial",Font.BOLD,42));
        fm = g2.getFontMetrics();
        String title = "LEVEL " + level + " CLEARED!";
        g2.drawString(title, cx - fm.stringWidth(title) / 2, cardY + 70);

        g2.setColor(new Color(42,210,72,120));
        g2.drawLine(cardX + 28, cardY + 88, cardX + cardW - 28, cardY + 88);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,24));
        fm = g2.getFontMetrics();
        String killsLine = "Kills: " + quotaKilled + " / " + quotaRequired;
        String scoreLine = "Score: " + score;
        g2.drawString(killsLine, cx - fm.stringWidth(killsLine) / 2, cardY + 145);
        g2.drawString(scoreLine, cx - fm.stringWidth(scoreLine) / 2, cardY + 185);

        g2.setColor(new Color(135,252,148));
        g2.setFont(new Font("Arial",Font.BOLD,18));
        fm = g2.getFontMetrics();
        String hint = level < MAX_LEVEL
                ? "Press E / ENTER to pick an upgrade card → Level " + (level + 1)
                : "Press E / ENTER to see final results!";
        g2.drawString(hint, cx - fm.stringWidth(hint) / 2, cardY + cardH - 34);
    }

    private void drawGameOver(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,202));
        g2.fillRect(0,0,WIDTH,HEIGHT);

        int cx = GAME_W / 2;
        int panH = retriesLeft > 0 ? 360 : 320;
        g2.setColor(new Color(36,6,6,252));
        g2.fillRoundRect(cx - 295, 155, 590, panH, 22, 22);
        g2.setColor(new Color(210,36,36));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(cx - 295, 155, 590, panH, 22, 22);
        g2.setStroke(new BasicStroke(1f));

        FontMetrics fm;
        g2.setColor(new Color(252,70,70));
        g2.setFont(new Font("Arial",Font.BOLD,54));
        fm = g2.getFontMetrics();
        String goTitle = "GAME OVER";
        g2.drawString(goTitle, cx - fm.stringWidth(goTitle)/2, 228);

        // Player / level / score info
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,20));
        fm = g2.getFontMetrics();
        String nameLine  = "Player: " + (playerName.isBlank() ? "Player" : playerName);
        String levelLine = "Level: " + level + " / " + MAX_LEVEL;
        String scoreLine = "Score: " + score;
        g2.drawString(nameLine,  cx - fm.stringWidth(nameLine)/2,  278);
        g2.drawString(levelLine, cx - fm.stringWidth(levelLine)/2, 306);
        g2.drawString(scoreLine, cx - fm.stringWidth(scoreLine)/2, 334);

        // Retry counter pills
        g2.setFont(new Font("Arial",Font.BOLD,14));
        fm = g2.getFontMetrics();
        String retryLabel = "RETRIES LEFT:";
        g2.setColor(new Color(180,180,200));
        g2.drawString(retryLabel, cx - fm.stringWidth(retryLabel)/2 - 36, 366);
        int pillX = cx - fm.stringWidth(retryLabel)/2 + fm.stringWidth(retryLabel) - 28;
        for (int i = 0; i < 3; i++) {
            boolean active = i < retriesLeft;
            g2.setColor(active ? new Color(255,100,100) : new Color(60,30,30));
            g2.fillRoundRect(pillX + i * 22, 352, 16, 16, 6, 6);
            if (!active) {
                g2.setColor(new Color(120,60,60));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(pillX + i * 22, 352, 16, 16, 6, 6);
                g2.setStroke(new BasicStroke(1f));
            }
        }

        if (retriesLeft > 0) {
            // Score revert notice
            g2.setColor(new Color(255,200,80));
            g2.setFont(new Font("Arial",Font.ITALIC,13));
            fm = g2.getFontMetrics();
            String revertNote = "Score will reset to " + scoreAtLevelStart + " (start of this level)";
            g2.drawString(revertNote, cx - fm.stringWidth(revertNote)/2, 392);

            // Retry button
            g2.setColor(new Color(140,30,30,220));
            g2.fillRoundRect(cx - 130, 408, 260, 42, 12, 12);
            g2.setColor(new Color(255,120,120));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(cx - 130, 408, 260, 42, 12, 12);
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial",Font.BOLD,18));
            fm = g2.getFontMetrics();
            String retryLine = "R  -  Retry This Level";
            g2.drawString(retryLine, cx - fm.stringWidth(retryLine)/2, 434);

            // Menu button
            g2.setColor(new Color(28,42,86, 220));
            g2.fillRoundRect(cx - 130, 458, 260, 42, 12, 12);
            g2.setColor(new Color(100,160,255));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(cx - 130, 458, 260, 42, 12, 12);
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial",Font.BOLD,18));
            fm = g2.getFontMetrics();
            String menuLine = "M  -  Main Menu";
            g2.drawString(menuLine, cx - fm.stringWidth(menuLine)/2, 484);
        } else {
            // No retries left
            g2.setColor(new Color(255,80,80));
            g2.setFont(new Font("Arial",Font.BOLD,16));
            fm = g2.getFontMetrics();
            String noRetry = "No retries left!";
            g2.drawString(noRetry, cx - fm.stringWidth(noRetry)/2, 393);

            // Menu button
            g2.setColor(new Color(28,42,86, 220));
            g2.fillRoundRect(cx - 130, 415, 260, 42, 12, 12);
            g2.setColor(new Color(100,160,255));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRoundRect(cx - 130, 415, 260, 42, 12, 12);
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial",Font.BOLD,18));
            fm = g2.getFontMetrics();
            String menuLine = "M - Return to Main Menu";
            g2.drawString(menuLine, cx - fm.stringWidth(menuLine)/2, 441);
        }
    }

    private void drawWin(Graphics2D g2) {
        g2.setColor(new Color(0,0,0,192));
        g2.fillRect(0,0,WIDTH,HEIGHT);

        int cx = GAME_W / 2;
        g2.setPaint(new GradientPaint(cx - 328,152,new Color(36,32,4,252),cx + 328,498,new Color(16,36,6,252)));
        g2.fillRoundRect(cx - 328,152,656,385,22,22);
        g2.setColor(new Color(252,202,38));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawRoundRect(cx - 328,152,656,385,22,22);
        g2.setStroke(new BasicStroke(1f));

        FontMetrics fm;
        g2.setColor(new Color(255,222,65));
        g2.setFont(new Font("Arial",Font.BOLD,60));
        fm = g2.getFontMetrics();
        String winTitle = "YOU WIN!";
        g2.drawString(winTitle, cx - fm.stringWidth(winTitle) / 2, 234);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial",Font.BOLD,22));
        fm = g2.getFontMetrics();
        String pLine = "Player: " + (playerName.isBlank() ? "Player" : playerName);
        String bLine = "Final Boss Defeated  -  All 15 Levels Cleared!";
        g2.drawString(pLine, cx - fm.stringWidth(pLine)/2, 292);
        g2.drawString(bLine, cx - fm.stringWidth(bLine)/2, 332);

        g2.setColor(new Color(255,212,65));
        g2.setFont(new Font("Arial",Font.BOLD,32));
        fm = g2.getFontMetrics();
        String sLine = "Final Score: " + score;
        g2.drawString(sLine, cx - fm.stringWidth(sLine)/2, 386);

        g2.setColor(new Color(192,212,172));
        g2.setFont(new Font("Arial",Font.PLAIN,18));
        fm = g2.getFontMetrics();
        String savedLine = "Score saved to Leaderboard!";
        String menuLine  = "Press ENTER / M / R to return to Main Menu";
        g2.drawString(savedLine, cx - fm.stringWidth(savedLine)/2, 428);
        g2.drawString(menuLine,  cx - fm.stringWidth(menuLine)/2,  460);
    }

    private void drawCardSelect(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 215));
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        boolean isRare = cardSelectIsRare;

        // ── Header ──────────────────────────────────────────────────
        if (isRare) {
            // Golden/rare header
            g2.setPaint(new GradientPaint(0, 0, new Color(80, 40, 0), WIDTH, 0, new Color(60, 30, 0)));
            g2.fillRect(0, 0, WIDTH, 140);
            g2.setColor(new Color(255, 200, 40));
            g2.setFont(new Font("Arial", Font.BOLD, 38));
            FontMetrics fm = g2.getFontMetrics();
            String title = "!  BOSS DEFEATED — RARE REWARD  !";
            g2.drawString(title, (WIDTH - fm.stringWidth(title)) / 2, 58);
            g2.setColor(new Color(255, 230, 120));
            g2.setFont(new Font("Arial", Font.BOLD, 18));
            fm = g2.getFontMetrics();
            String sub = "Choose a RARE Power Card  —  heading to Level " + pendingNextLevel;
            g2.drawString(sub, (WIDTH - fm.stringWidth(sub)) / 2, 90);
            g2.setColor(new Color(255, 180, 40, 160));
            g2.setStroke(new java.awt.BasicStroke(2f));
            g2.drawLine(60, 104, WIDTH - 60, 104);
            g2.setStroke(new java.awt.BasicStroke(1f));
            g2.setColor(new Color(255, 200, 80, 140));
            g2.setFont(new Font("Arial", Font.PLAIN, 14));
            fm = g2.getFontMetrics();
            String keys = "Press  1  2  3  or click a card";
            g2.drawString(keys, (WIDTH - fm.stringWidth(keys)) / 2, 124);
        } else {
            // Standard blue header
            g2.setColor(new Color(100, 180, 255));
            g2.setFont(new Font("Arial", Font.BOLD, 32));
            FontMetrics fm = g2.getFontMetrics();
            String title = "LEVEL " + level + " CLEARED — CHOOSE AN UPGRADE";
            g2.drawString(title, (WIDTH - fm.stringWidth(title)) / 2, 60);
            g2.setColor(new Color(135, 200, 255));
            g2.setFont(new Font("Arial", Font.BOLD, 18));
            fm = g2.getFontMetrics();
            String sub = "Heading to Level " + pendingNextLevel;
            g2.drawString(sub, (WIDTH - fm.stringWidth(sub)) / 2, 90);
            g2.setColor(new Color(80, 120, 200, 100));
            g2.setFont(new Font("Arial", Font.PLAIN, 14));
            fm = g2.getFontMetrics();
            String keys = "Press  1  2  3  or click a card";
            g2.drawString(keys, (WIDTH - fm.stringWidth(keys)) / 2, 114);
        }

        // ── Current stats bar ────────────────────────────────────────
        int statsY = 148;
        g2.setColor(new Color(14, 22, 58, 200));
        g2.fillRoundRect(WIDTH/2 - 380, statsY, 760, 36, 10, 10);
        g2.setColor(new Color(50, 80, 160, 160));
        g2.setStroke(new java.awt.BasicStroke(1f));
        g2.drawRoundRect(WIDTH/2 - 380, statsY, 760, 36, 10, 10);

        String[] statLabels = { "DMG", "HP", "SPD", "MAG", "RELOAD" };
        String[] statVals   = {
            String.valueOf(playerBulletDamage),
            String.valueOf(playerMaxHealthStat),
            String.valueOf(playerMoveSpeedStat),
            String.valueOf(playerMagazineSize),
            String.format("%.1fs", playerReloadFrames / 60.0)
        };
        int statX = WIDTH/2 - 360;
        for (int i = 0; i < statLabels.length; i++) {
            g2.setColor(new Color(100, 140, 220));
            g2.setFont(new Font("Arial", Font.BOLD, 10));
            g2.drawString(statLabels[i], statX, statsY + 14);
            g2.setColor(new Color(255, 230, 100));
            g2.setFont(new Font("Arial", Font.BOLD, 15));
            g2.drawString(statVals[i], statX, statsY + 30);
            statX += 148;
        }

        // ── Three cards ──────────────────────────────────────────────
        int cardW = 260, cardH = 290, gap = 28;
        int totalW = cardW * 3 + gap * 2;
        int startX = (WIDTH - totalW) / 2;
        int y = 200;

        for (int i = 0; i < 3 && i < offeredCards.size(); i++) {
            Rectangle r = new Rectangle(startX + i * (cardW + gap), y, cardW, cardH);
            cardRects[i].setBounds(r);
            boolean hover = r.contains(mousePos);

            // Card shadow
            g2.setColor(new Color(0, 0, 0, 80));
            g2.fillRoundRect(r.x + 4, r.y + 4, r.width, r.height, 20, 20);

            // Card background — golden tint for rare
            if (isRare) {
                if (hover) {
                    g2.setPaint(new GradientPaint(r.x, r.y, new Color(60,44,4), r.x, r.y+r.height, new Color(28,18,2)));
                } else {
                    g2.setPaint(new GradientPaint(r.x, r.y, new Color(42,30,2), r.x, r.y+r.height, new Color(18,12,2)));
                }
            } else {
                if (hover) {
                    g2.setPaint(new GradientPaint(r.x, r.y, new Color(22,40,100), r.x, r.y+r.height, new Color(10,18,58)));
                } else {
                    g2.setColor(new Color(10, 18, 48));
                }
            }
            g2.fillRoundRect(r.x, r.y, r.width, r.height, 20, 20);

            // Border
            if (isRare) {
                g2.setColor(hover ? new Color(255, 230, 80) : new Color(200, 160, 40));
            } else {
                g2.setColor(hover ? new Color(120, 180, 255) : new Color(55, 115, 255));
            }
            g2.setStroke(new java.awt.BasicStroke(hover ? 3f : 2f));
            g2.drawRoundRect(r.x, r.y, r.width, r.height, 20, 20);
            g2.setStroke(new java.awt.BasicStroke(1f));

            // Number badge
            g2.setColor(isRare ? new Color(255, 220, 60) : new Color(255, 212, 65));
            g2.setFont(new Font("Arial", Font.BOLD, 22));
            g2.drawString(String.valueOf(i + 1), r.x + 16, r.y + 30);

            CardPower p = offeredCards.get(i);

            // "RARE" badge on rare cards
            if (isRare) {
                g2.setColor(new Color(255, 180, 0, 200));
                g2.fillRoundRect(r.x + r.width - 68, r.y + 10, 54, 20, 8, 8);
                g2.setColor(new Color(20, 10, 0));
                g2.setFont(new Font("Arial", Font.BOLD, 11));
                g2.drawString("RARE", r.x + r.width - 58, r.y + 24);
            }

            // Icon circle
            Color iconBg  = isRare ? new Color(120, 80, 0, 130) : new Color(40, 80, 200, 120);
            Color iconRim = isRare ? new Color(255, 200, 60, 200) : new Color(100, 160, 255, 180);
            g2.setColor(iconBg);
            g2.fillOval(r.x + r.width/2 - 26, r.y + 32, 52, 52);
            g2.setColor(iconRim);
            g2.setStroke(new java.awt.BasicStroke(1.5f));
            g2.drawOval(r.x + r.width/2 - 26, r.y + 32, 52, 52);
            g2.setStroke(new java.awt.BasicStroke(1f));
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 24));
            FontMetrics fm = g2.getFontMetrics();
            String icon = getCardIcon(p);
            g2.drawString(icon, r.x + r.width/2 - fm.stringWidth(icon)/2, r.y + 66);

            // Title
            g2.setColor(isRare ? new Color(255, 220, 100) : Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, isRare ? 18 : 22));
            drawCentered(g2, p.title.replace("RARE: ", ""), r.x + r.width / 2, r.y + 108);

            // Separator
            g2.setColor(isRare ? new Color(200, 140, 40, 80) : new Color(55, 115, 255, 80));
            g2.drawLine(r.x + 20, r.y + 118, r.x + r.width - 20, r.y + 118);

            // Description (main)
            g2.setColor(isRare ? new Color(255, 220, 150) : new Color(180, 210, 255));
            g2.setFont(new Font("Arial", Font.BOLD, 15));
            drawCentered(g2, p.desc, r.x + r.width / 2, r.y + 150);

            // Detail line (extra explanation)
            g2.setColor(isRare ? new Color(210, 170, 90, 210) : new Color(120, 155, 220, 210));
            g2.setFont(new Font("Arial", Font.PLAIN, 11));
            drawWrappedCentered(g2, p.detail, r.x + r.width / 2, r.y + 175, r.width - 28);

            // Pick button
            Color btnColor = isRare
                ? (hover ? new Color(200, 140, 20) : new Color(140, 90, 10))
                : (hover ? new Color(55, 140, 255) : new Color(30, 70, 180));
            g2.setColor(btnColor);
            g2.fillRoundRect(r.x + 20, r.y + cardH - 44, r.width - 40, 30, 10, 10);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial", Font.BOLD, 14));
            fm = g2.getFontMetrics();
            String btnTxt = "CHOOSE  [" + (i+1) + "]";
            g2.drawString(btnTxt, r.x + r.width/2 - fm.stringWidth(btnTxt)/2, r.y + cardH - 22);
        }
    }

    private String getCardIcon(CardPower p) {
        return switch (p) {
            case CONTINUOUS_BULLET -> ">>>";
            case SIX_BULLET       -> "*6*";
            case TRIPLE_BULLET     -> "*3*";
            case QUICK_RELOAD      -> "<<";
            case MAG_PLUS          -> "+2";
            case BULLET_SPEED      -> "=>";
            case BIG_BULLETS       -> "( )";
            case DAMAGE_UP         -> "+!";
            case MAX_HP_UP         -> "+HP";
            case BATTLE_MEDIC      -> "+50";
            case HEAVY_SHOT        -> "HVY";
            case ENGINE_BOOST      -> ">>";
            case SECOND_LIFE       -> "1UP";
            // Rare
            case RARE_DOUBLE_DAMAGE -> "x2!";
            case RARE_DOUBLE_HP     -> "x2H";
            case RARE_DOUBLE_SCORE  -> "x2$";
            case RARE_GHOST_RELOAD  -> "x3M";
            case RARE_SLOW_ENEMIES  -> "~~";
            case RARE_RAPID_FIRE    -> "<<<";
        };
    }

    private void drawCentered(Graphics2D g2, String text, int cx, int y) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, cx - fm.stringWidth(text) / 2, y);
    }

    /** Draw text centred, wrapping to next line if wider than maxW. */
    private void drawWrappedCentered(Graphics2D g2, String text, int cx, int y, int maxW) {
        if (text == null || text.isEmpty()) return;
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= maxW) {
            g2.drawString(text, cx - fm.stringWidth(text) / 2, y);
            return;
        }
        // Split into words and wrap
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (String word : words) {
            String test = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(test) > maxW && !line.isEmpty()) {
                String l = line.toString();
                g2.drawString(l, cx - fm.stringWidth(l) / 2, lineY);
                lineY += fm.getHeight() + 1;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (!line.isEmpty()) {
            String l = line.toString();
            g2.drawString(l, cx - fm.stringWidth(l) / 2, lineY);
        }
    }

    private void drawWalls(Graphics2D g2) {
        if (usingLevelMap && levelMapMask != null) return;
        for (Wall w : walls) {
            g2.setColor(new Color(0,0,0,52)); g2.fillRect(w.x + 3,w.y + 3,w.width,w.height);
            g2.setColor(w.color);             g2.fillRect(w.x,w.y,w.width,w.height);
            g2.setColor(new Color(172,132,72,108));
            g2.drawLine(w.x,w.y,w.x + w.width - 1,w.y);
            g2.drawLine(w.x,w.y,w.x,w.y + w.height - 1);
            g2.setColor(new Color(78,48,16));  g2.drawRect(w.x,w.y,w.width,w.height);
        }
    }

    private void drawTank(Graphics2D g2, Tank t) {
        if (t.sprite != null) {
            g2.drawImage(t.sprite, t.x, t.y, t.width, t.height, this);
        } else {
            if (t.isPlayer) drawPlayerTank(g2, t);
            else if (t.isBoss) drawBossTank(g2, t);
            else drawEnemyTank(g2, t);
            drawCannon(g2, t);
        }

        drawHPBar(g2, t);

        if (t.isBoss) {
            int labelY = t.y - 22;
            if (labelY < TOP_BAR + 12) labelY = t.y + t.height + 22;
            g2.setColor(t.bossUnlocked ? new Color(252,65,65) : Color.LIGHT_GRAY);
            g2.setFont(new Font("Arial",Font.BOLD,11));
            g2.drawString(t.bossUnlocked ? "BOSS" : "LOCKED", t.x + t.width / 2 - 18, labelY);
        }
    }

    private void drawPlayerTank(Graphics2D g2, Tank t) {
        // pick design – for demo tanks on menu/select, use the tank's own color
        boolean isRealPlayer = t.isPlayer && player != null && t == player;
        int d = isRealPlayer ? selectedTankDesign : 0;
        Color primary   = TANK_DESIGN_PRIMARY[d];
        Color accent    = TANK_DESIGN_ACCENT[d];
        Color highlight = TANK_DESIGN_HIGHLIGHT[d];

        // tracks
        g2.setColor(new Color(16,16,16));
        g2.fillRoundRect(t.x - 5,t.y + 3,7,t.height - 6,4,4);
        g2.fillRoundRect(t.x + t.width - 2,t.y + 3,7,t.height - 6,4,4);
        g2.setColor(new Color(42,42,42));
        for(int i = t.y + 6; i < t.y + t.height - 6; i += 8){
            g2.drawLine(t.x - 5,i,t.x + 2,i);
            g2.drawLine(t.x + t.width - 2,i,t.x + t.width + 5,i);
        }

        // hull
        g2.setPaint(new GradientPaint(t.x,t.y,primary,t.x,t.y+t.height,accent));
        g2.fillRoundRect(t.x,t.y,t.width,t.height,10,10);
        g2.setColor(accent);
        g2.fillRoundRect(t.x + 8,t.y + 8,t.width - 16,t.height - 16,8,8);

        // turret dome
        g2.setPaint(new GradientPaint(t.x+t.width/2-10,t.y+t.height/2-10,highlight,
                                      t.x+t.width/2+10,t.y+t.height/2+10,primary));
        g2.fillOval(t.x+t.width/2-10,t.y+t.height/2-10,20,20);
        g2.setColor(new Color(255,255,255,172));
        g2.fillOval(t.x+t.width/2-5,t.y+t.height/2-7,6,5);
    }

    private void drawEnemyTank(Graphics2D g2, Tank t) {
        g2.setColor(new Color(36,6,6));
        g2.fillRect(t.x - 4,t.y,5,t.height);
        g2.fillRect(t.x + t.width - 1,t.y,5,t.height);

        g2.setColor(new Color(62,16,16));
        for(int i = t.y + 4;i < t.y + t.height - 4;i += 8){
            g2.drawLine(t.x - 4,i,t.x + 1,i);
            g2.drawLine(t.x + t.width - 1,i,t.x + t.width + 4,i);
        }

        g2.setPaint(new GradientPaint(t.x,t.y,new Color(232,60,60),t.x,t.y + t.height,new Color(142,16,16)));
        g2.fillRect(t.x,t.y,t.width,t.height);
        g2.setColor(new Color(185,36,36)); g2.fillRect(t.x + 5,t.y + 5,t.width - 10,t.height - 10);
        g2.setColor(new Color(112,6,6));   g2.fillRect(t.x + 10,t.y + 10,t.width - 20,t.height - 20);
        g2.setColor(new Color(252,96,96)); g2.fillOval(t.x + t.width / 2 - 8,t.y + t.height / 2 - 8,16,16);
        g2.setColor(new Color(252,195,195,142)); g2.fillOval(t.x + t.width / 2 - 4,t.y + t.height / 2 - 6,5,4);
    }

    private void drawBossTank(Graphics2D g2, Tank t) {
        g2.setColor(new Color(10,10,10));
        g2.fillRoundRect(t.x - 7,t.y + 5,9,t.height - 10,5,5);
        g2.fillRoundRect(t.x + t.width - 2,t.y + 5,9,t.height - 10,5,5);

        g2.setColor(new Color(36,36,36));
        for(int i = t.y + 8;i < t.y + t.height - 8;i += 9){
            g2.drawLine(t.x - 7,i,t.x + 2,i);
            g2.drawLine(t.x + t.width - 2,i,t.x + t.width + 7,i);
        }

        g2.setPaint(new GradientPaint(t.x,t.y,new Color(125,20,20),t.x,t.y + t.height,new Color(52,2,2)));
        g2.fillRoundRect(t.x,t.y,t.width,t.height,16,16);
        g2.setColor(new Color(155,30,30)); g2.fillRoundRect(t.x + 8,t.y + 8,t.width - 16,t.height - 16,12,12);
        g2.setColor(new Color(92,10,10));  g2.fillRoundRect(t.x + 18,t.y + 18,t.width - 36,t.height - 36,8,8);

        g2.setPaint(new GradientPaint(t.x + t.width / 2 - 13,t.y + t.height / 2 - 13,new Color(252,215,42),t.x + t.width / 2 + 13,t.y + t.height / 2 + 13,new Color(172,92,0)));
        g2.fillOval(t.x + t.width / 2 - 13,t.y + t.height / 2 - 13,26,26);
        g2.setColor(new Color(52,2,2));
        g2.fillOval(t.x + t.width / 2 - 5,t.y + t.height / 2 - 3,5,4);
        g2.fillOval(t.x + t.width / 2 + 1,t.y + t.height / 2 - 3,5,4);
        g2.setColor(new Color(255,255,192,192));
        g2.fillOval(t.x + t.width / 2 - 6,t.y + t.height / 2 - 8,6,4);
    }

    private void drawCannon(Graphics2D g2, Tank t) {
        int cx = t.x + t.width / 2, cy = t.y + t.height / 2, cl = t.isBoss ? 30 : (t.isPlayer ? 24 : 20), cw = t.isBoss ? 11 : 8;
        g2.setColor(t.isPlayer ? new Color(12,12,12) : t.isBoss ? new Color(26,6,6) : new Color(46,6,6));
        switch(t.direction){
            case UP    -> g2.fillRoundRect(cx - cw / 2,t.y - (cl - 5),cw,cl,4,4);
            case DOWN  -> g2.fillRoundRect(cx - cw / 2,t.y + t.height - 5,cw,cl,4,4);
            case LEFT  -> g2.fillRoundRect(t.x - (cl - 5),cy - cw / 2,cl,cw,4,4);
            case RIGHT -> g2.fillRoundRect(t.x + t.width - 5,cy - cw / 2,cl,cw,4,4);
        }
    }

    private void drawHPBar(Graphics2D g2, Tank t) {
        int by = t.y - 10;
        // Don't draw HP bar above the top bar
        if (by < TOP_BAR) by = t.y + t.height + 2;
        float r = (float)t.health / t.maxHealth;
        g2.setColor(new Color(52,0,0,192)); g2.fillRoundRect(t.x, by, t.width, 8, 4, 4);
        g2.setColor(r > 0.5f ? new Color(46,200,46) : r > 0.25f ? new Color(232,150,0) : new Color(212,26,26));
        g2.fillRoundRect(t.x, by, (int)(t.width * r), 8, 4, 4);
        g2.setColor(new Color(255,255,255,72)); g2.drawRoundRect(t.x, by, t.width, 8, 4, 4);
    }

    private void drawBullet(Graphics2D g2, Bullet b) {
        if (b.fromPlayer) {
            g2.setColor(new Color(0,192,252,72));
            g2.fillOval(b.x - 3,b.y - 3,b.width + 6,b.height + 6);
            g2.setColor(new Color(92,225,252));
            g2.fillOval(b.x,b.y,b.width,b.height);
        } else {
            g2.setColor(new Color(252,92,0,72));
            g2.fillOval(b.x - 3,b.y - 3,b.width + 6,b.height + 6);
            g2.setColor(new Color(252,162,0));
            g2.fillOval(b.x,b.y,b.width,b.height);
        }
    }

    private void drawExplosions(Graphics2D g2) {
        for (Explosion ex : explosions) {
            float a = Math.max(0f, ex.life / 20f);
            g2.setColor(new Color(252,135,0,(int)(175 * a))); g2.fillOval(ex.x - ex.radius / 2,ex.y - ex.radius / 2,ex.radius,ex.radius);
            g2.setColor(new Color(252,235,72,(int)(155 * a))); g2.fillOval(ex.x - ex.radius / 4,ex.y - ex.radius / 4,ex.radius / 2,ex.radius / 2);
            g2.setColor(new Color(255,255,255,(int)(115 * a))); g2.fillOval(ex.x - ex.radius / 8,ex.y - ex.radius / 8,ex.radius / 4,ex.radius / 4);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  INPUT
    // ══════════════════════════════════════════════════════════════
    @Override
    public void keyTyped(KeyEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();
        
        // Fullscreen toggle (available anytime)
        if (k == KeyEvent.VK_F11) {
            Main.toggleFullscreen();
            return;
        }
        
        // Cheat: Ctrl+P to skip all levels (only when playing)
        if (k == KeyEvent.VK_P && e.isControlDown() && gameState == GameState.PLAYING) {
            activateLevelSkipCheat();
            return;
        }

        if (gameState == GameState.NAME_INPUT) {
            nameErrorMsg = ""; // Reset error when typing
            if (k == KeyEvent.VK_BACK_SPACE && !playerName.isEmpty()) {
                playerName = playerName.substring(0, playerName.length() - 1);
            } else if (k == KeyEvent.VK_ENTER) {
                String candidate = playerName.trim();
                if (candidate.isEmpty()) {
                    nameErrorMsg = "Name cannot be empty!";
                } else {
                    // Check if name is already taken on the leaderboard
                    List<ScoreEntry> scores = LeaderboardManager.loadScores();
                    boolean nameTaken = scores.stream()
                        .anyMatch(s -> s.name.equalsIgnoreCase(candidate));
                    if (nameTaken) {
                        // Block — must pick a different name
                        nameErrorMsg = "Name already taken! Pick a different name.";
                    } else {
                        playerName = candidate;
                        resetPlayerStats();
                        gameState = GameState.TANK_SELECT;
                    }
                }
            } else if (k == KeyEvent.VK_ESCAPE) {
                gameState = GameState.MENU;
            } else {
                char c = e.getKeyChar();
                if ((Character.isLetterOrDigit(c) || c == ' ') && playerName.length() < 15) {
                    playerName += c;
                }
            }
            repaint(); return;
        }


        // LB_NAME_WARN state retired — name conflicts now blocked at name input


        if (gameState == GameState.TANK_SELECT) {
            switch (k) {
                case KeyEvent.VK_ESCAPE -> {
                    // Go back to name input with blank field so user can retype
                    playerName = "";
                    nameErrorMsg = "";
                    gameState = GameState.NAME_INPUT;
                }
                case KeyEvent.VK_LEFT,  KeyEvent.VK_A -> selectedTankDesign = (selectedTankDesign + 5) % 6;
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> selectedTankDesign = (selectedTankDesign + 1) % 6;
                case KeyEvent.VK_UP    -> selectedTankDesign = (selectedTankDesign + 3) % 6;
                case KeyEvent.VK_DOWN  -> selectedTankDesign = (selectedTankDesign + 3) % 6;
                case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_E -> {
                    pendingNextLevel = 1;
                    cardSelectIsRare = false;
                    rollRewardCards(false);
                    gameState = GameState.CARD_SELECT;
                }
                default -> {}
            }
            repaint(); return;
        }

        if (gameState == GameState.INSTRUCTIONS || gameState == GameState.LEADERBOARD) {
            if (k == KeyEvent.VK_ESCAPE) gameState = GameState.MENU;
            repaint(); return;
        }

        if (gameState == GameState.CARD_SELECT) {
            if (k == KeyEvent.VK_1 && offeredCards.size() >= 1) applyCard(offeredCards.get(0));
            if (k == KeyEvent.VK_2 && offeredCards.size() >= 2) applyCard(offeredCards.get(1));
            if (k == KeyEvent.VK_3 && offeredCards.size() >= 3) applyCard(offeredCards.get(2));
            repaint(); return;
        }

        if (gameState == GameState.LEVEL_CLEARED) {
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_E) {
                if (level >= MAX_LEVEL) {
                    saveScore();
                    gameState = GameState.WIN;
                } else {
                    // Show common card pick before next level
                    pendingNextLevel  = level + 1;
                    cardSelectIsRare  = false;
                    rollRewardCards(false);
                    gameState = GameState.CARD_SELECT;
                }
            }
            return;
        }

        if (gameState == GameState.GAME_OVER) {
            if (k == KeyEvent.VK_R) {
                if (retriesLeft > 0) {
                    retriesLeft--;
                    score = scoreAtLevelStart; // revert score to what it was at level start
                    scoreSaved = false;
                    isRetrying = true;
                    startLevel(level);
                }
            } else if (k == KeyEvent.VK_M) {
                // Player died and chose to go to main menu - save the death score
                saveScore();
                gameState = GameState.MENU;
            }
            return;
        }

        if (gameState == GameState.WIN) {
            if (k == KeyEvent.VK_M || k == KeyEvent.VK_R || k == KeyEvent.VK_ENTER || k == KeyEvent.VK_E) {
                saveScore();
                gameState = GameState.MENU;
            }
            return;
        }

        if (settingsOpen) {
            if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP)    settingsCursor = Math.max(0,settingsCursor - 1);
            if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN)  settingsCursor = Math.min(2,settingsCursor + 1);
            if (k == KeyEvent.VK_ESCAPE) settingsOpen = false;
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) activateSettingsItem(settingsCursor);
            repaint(); return;
        }

        if (pauseMenuOpen || gameState == GameState.PAUSED) {
            if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP)   pauseCursor = Math.max(0,pauseCursor - 1);
            if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN) pauseCursor = Math.min(PAUSE_ITEMS.length - 1,pauseCursor + 1);
            if (k == KeyEvent.VK_ESCAPE) { pauseMenuOpen = false; settingsOpen = false; gameState = GameState.PLAYING; }
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) activatePauseItem(pauseCursor);
            repaint(); return;
        }

        if (k == KeyEvent.VK_ESCAPE && gameState == GameState.PLAYING) { pauseMenuOpen = true; pauseCursor = 0; gameState = GameState.PAUSED; return; }
        if (k == KeyEvent.VK_P && gameState == GameState.PLAYING) { pauseMenuOpen = true; pauseCursor = 0; gameState = GameState.PAUSED; return; }
        if (k == KeyEvent.VK_P && gameState == GameState.PAUSED && !pauseMenuOpen) { gameState = GameState.PLAYING; return; }

        if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP)    up = true;
        if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN)  down = true;
        if (k == KeyEvent.VK_A || k == KeyEvent.VK_LEFT)  left = true;
        if (k == KeyEvent.VK_D || k == KeyEvent.VK_RIGHT) right = true;
        if (k == KeyEvent.VK_SPACE)                        shoot = true;
    }

    private void activatePauseItem(int idx) {
        switch(idx) {
            case 0 -> { pauseMenuOpen = false; settingsOpen = false; gameState = GameState.PLAYING; }
            case 1 -> { 
                if (retriesLeft > 0) {
                    // RETRY LEVEL - revert score and doesn't save it
                    retriesLeft--;
                    pauseMenuOpen = false; 
                    settingsOpen = false; 
                    score = scoreAtLevelStart; // revert score to level start
                    scoreSaved = false; // allow score to be saved later if they die again
                    isRetrying = true;
                    startLevel(level); 
                }
            }
            case 2 -> { settingsOpen = true; settingsCursor = 2; } // default to SIDEBAR BORDER row
            case 3 -> { 
                // MAIN MENU - save score before leaving
                saveScore();
                pauseMenuOpen = false; 
                settingsOpen = false; 
                gameState = GameState.MENU; 
            }
            case 4 -> { 
                // QUIT GAME - save score before exiting
                saveScore();
                System.exit(0);
            }
        }
    }

    private void activateSettingsItem(int idx) {
        switch(idx) {
            case 0 -> settingsOpen = false;
            case 1 -> borderStyle = 0;
            case 2 -> borderStyle = (borderStyle + 1) % BORDER_NAMES.length;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_W || k == KeyEvent.VK_UP)    up = false;
        if (k == KeyEvent.VK_S || k == KeyEvent.VK_DOWN)  down = false;
        if (k == KeyEvent.VK_A || k == KeyEvent.VK_LEFT)  left = false;
        if (k == KeyEvent.VK_D || k == KeyEvent.VK_RIGHT) right = false;
        if (k == KeyEvent.VK_SPACE)                       shoot = false;
    }

    // ── Fullscreen scale state (set by Main.toggleFullscreen) ────────
    public static boolean fullscreen = false;
    private double fsScaleX = 1.0, fsScaleY = 1.0;
    private int    fsOffX   = 0,   fsOffY   = 0;

    /** Called by Main after a fullscreen toggle so the panel knows the current scale. */
    public void applyFullscreenScale(int screenW, int screenH) {
        if (fullscreen) {
            fsScaleX = (double) screenW / WIDTH;
            fsScaleY = (double) screenH / HEIGHT;
            // Use uniform scale (keep aspect ratio) and centre
            double s = Math.min(fsScaleX, fsScaleY);
            fsScaleX = s; fsScaleY = s;
            fsOffX   = (int)((screenW - WIDTH  * s) / 2);
            fsOffY   = (int)((screenH - HEIGHT * s) / 2);
        } else {
            fsScaleX = 1.0; fsScaleY = 1.0;
            fsOffX = 0; fsOffY = 0;
        }
    }

    /** Converts a screen-space point back to game-logic coords (for mouse input). */
    public Point toGamePoint(Point screen) {
        if (!fullscreen) return screen;
        int gx = (int)((screen.x - fsOffX) / fsScaleX);
        int gy = (int)((screen.y - fsOffY) / fsScaleY);
        return new Point(gx, gy);
    }

    @Override public void mouseMoved(MouseEvent e)   {
        mousePos = toGamePoint(e.getPoint());
        if (gameState == GameState.TANK_SELECT) {
            int cols = 3, cardW = 220, cardH = 220, gapX = 24, gapY = 20;
            int totalGridW = cols * cardW + (cols - 1) * gapX;
            int gridStartX = (WIDTH - totalGridW) / 2;
            int ph = 620, gridStartY = (HEIGHT - ph) / 2 + 80;
            tankSelectHover = -1;
            for (int i = 0; i < 6; i++) {
                int col = i % cols, row = i / cols;
                int cx3 = gridStartX + col * (cardW + gapX);
                int cy3 = gridStartY + row * (cardH + gapY);
                if (new Rectangle(cx3, cy3, cardW, cardH).contains(mousePos)) { tankSelectHover = i; break; }
            }
        }
        repaint();
    }
    @Override public void mouseDragged(MouseEvent e) { mousePos = toGamePoint(e.getPoint()); }

    @Override
    public void mouseClicked(MouseEvent e) {
        Point p = toGamePoint(e.getPoint());

        // Fullscreen button — available from any game state
        if (fsBtn.contains(p) && (gameState == GameState.PLAYING || gameState == GameState.PAUSED ||
                gameState == GameState.LEVEL_CLEARED || gameState == GameState.GAME_OVER ||
                gameState == GameState.WIN || gameState == GameState.CARD_SELECT)) {
            Main.toggleFullscreen();
            return;
        }

        if (gameState == GameState.MENU) {
            if      (startBtn.contains(p))  { resetPlayerStats(); playerName = ""; gameState = GameState.NAME_INPUT; }
            else if (lboardBtn.contains(p)) gameState = GameState.LEADERBOARD;
            else if (instrBtn.contains(p))  gameState = GameState.INSTRUCTIONS;
            else if (exitBtn.contains(p))   System.exit(0);
            return;
        }

        if (gameState == GameState.LB_NAME_WARN) {
            if (lbWarnYesRect.contains(p)) {
                lbExistingScore = 0;
                resetPlayerStats();
                gameState = GameState.TANK_SELECT;
                repaint(); return;
            }
            if (lbWarnNoRect.contains(p)) {
                lbExistingScore = 0;
                nameErrorMsg = "";
                gameState = GameState.NAME_INPUT;
                repaint(); return;
            }
            return;
        }

        if (gameState == GameState.TANK_SELECT) {
            int cols = 3, cardW = 220, cardH = 220, gapX = 24, gapY = 20;
            int totalGridW = cols * cardW + (cols - 1) * gapX;
            int gridStartX = (WIDTH - totalGridW) / 2;
            int ph = 620, py2 = (HEIGHT - ph) / 2;
            int gridStartY = py2 + 80;

            // Check START BATTLE button
            int btnW = 240, btnH = 46;
            int btnX = (WIDTH - btnW) / 2, btnY2 = py2 + ph - 74;
            Rectangle startBattleBtn = new Rectangle(btnX, btnY2, btnW, btnH);
            if (startBattleBtn.contains(p)) {
                pendingNextLevel = 1;
                cardSelectIsRare = false;
                rollRewardCards(false);
                gameState = GameState.CARD_SELECT;
                repaint();
                return;
            }

            // Check tank cards
            for (int i = 0; i < 6; i++) {
                int col = i % cols, row = i / cols;
                int cx3 = gridStartX + col * (cardW + gapX);
                int cy3 = gridStartY + row * (cardH + gapY);
                if (new Rectangle(cx3, cy3, cardW, cardH).contains(p)) {
                    selectedTankDesign = i;
                    repaint(); return;
                }
            }
            return;
        }

        if (gameState == GameState.CARD_SELECT) {
            for (int i = 0; i < 3; i++) {
                if (cardRects[i].contains(p) && i < offeredCards.size()) {
                    applyCard(offeredCards.get(i));
                    repaint();
                    return;
                }
            }
        }

        if (settingsOpen) {
            // Panel: ch=520, so cy = (HEIGHT-520)/2 = 90
            int ch2 = 520, cy2 = (HEIGHT - ch2) / 2;
            int cw2 = 680, cx2 = (WIDTH - cw2) / 2;
            // Row positions (must match drawSettingsScreen):
            // Controls label: cy2+84 (14px)  → 4 rows × (36+6) = 168px → ends at cy2+84+14+168 = cy2+266
            // Divider: cy2+266+6 = cy2+272, then +14 → iy = cy2+286
            // Border row: cy2+286, h=52 → ends cy2+338, +8 gap → iy = cy2+346
            // Reset row:  cy2+346, h=44 → ends cy2+390, +8 gap → iy = cy2+398
            // Back row:   cy2+398, h=44
            int borderRowY = cy2 + 286;
            int resetRowY  = cy2 + 346;
            int backRowY   = cy2 + 398;
            int rowW = cw2 - 76;
            int rowX = cx2 + 38;

            if (p.x >= rowX && p.x <= rowX + rowW) {
                if (p.y >= backRowY   && p.y <= backRowY   + 44) { settingsCursor = 0; activateSettingsItem(0); repaint(); return; }
                if (p.y >= resetRowY  && p.y <= resetRowY  + 44) { settingsCursor = 1; activateSettingsItem(1); repaint(); return; }
                if (p.y >= borderRowY && p.y <= borderRowY + 52) { settingsCursor = 2; activateSettingsItem(2); repaint(); return; }
            }
            return;
        }

        if (pauseMenuOpen || gameState == GameState.PAUSED) {
            int cw = 480, ch = 420, cx = (WIDTH - cw) / 2, cy = (HEIGHT - ch) / 2;
            int iy = cy + 100;
            for (int i = 0; i < PAUSE_ITEMS.length; i++) {
                if (p.x >= cx + 28 && p.x <= cx + cw - 28 && p.y >= iy && p.y <= iy + 46) {
                    pauseCursor = i;
                    activatePauseItem(i);
                    repaint();
                    return;
                }
                iy += 52;
            }
        }

        if (gameState == GameState.GAME_OVER) {
            int cx = GAME_W / 2;
            if (retriesLeft > 0) {
                Rectangle retryBtn = new Rectangle(cx - 130, 408, 260, 42);
                Rectangle menuBtn = new Rectangle(cx - 130, 458, 260, 42);
                if (retryBtn.contains(p)) {
                    retriesLeft--;
                    score = scoreAtLevelStart;
                    scoreSaved = false;
                    isRetrying = true;
                    startLevel(level);
                } else if (menuBtn.contains(p)) {
                    saveScore();
                    gameState = GameState.MENU;
                }
            } else {
                Rectangle menuBtn = new Rectangle(cx - 130, 415, 260, 42);
                if (menuBtn.contains(p)) {
                    saveScore();
                    gameState = GameState.MENU;
                }
            }
            repaint();
        }
    }

    @Override public void mousePressed(MouseEvent e)  {}
    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e)  {}
    @Override public void mouseExited(MouseEvent e)   {}
}