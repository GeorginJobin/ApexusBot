package gj.eh;

import robocode.*;
import java.util.HashMap;
import java.awt.Color;

public class ChutluV16 extends Robot {

    // Edge detection/before hits the edge and activates on wall hit
    private final double MARGIN = 200;

    // Enemy tracking changed hash to make sure its java 6 just in case
    private HashMap<String, EnemyData> enemies = new HashMap<String, EnemyData>();
    private String currentTarget = null;
    private String lastTarget = null;

    // Movement control
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100.0;

    // Targeting history
    private double[] enemyHeadingHistory = new double[10];
    private double[] enemyVelocityHistory = new double[10];
    private double[] enemyXHistory = new double[10];
    private double[] enemyYHistory = new double[10];
    private int historyIndex = 0;

    // Wall avoidance (kept)
    private static final double WALL_MARGIN = 50;

    /**
     * Inner class to store enemy data
     */
    class EnemyData {
        double bearing;
        double distance;
        double energy;
        double heading;
        double velocity;
        long time;
        double x;
        double y;
        double lastHeading;
        double lastX;
        double lastY;

        EnemyData(ScannedRobotEvent e, long currentTime, Robot bot) {
            this.bearing = e.getBearing();
            this.distance = e.getDistance();
            this.energy = e.getEnergy();
            this.heading = e.getHeading();
            this.velocity = e.getVelocity();
            this.time = currentTime;
            this.lastHeading = e.getHeading();

            // Calculate absolute position
            double absoluteBearing = Math.toRadians(bot.getHeading()) + e.getBearingRadians();
            this.x = bot.getX() + Math.sin(absoluteBearing) * e.getDistance();
            this.y = bot.getY() + Math.cos(absoluteBearing) * e.getDistance();
            this.lastX = this.x;
            this.lastY = this.y;
        }
    }

    /**
     * Main run method - radar + movement
     * NOTE: Robot does NOT support setAdjust* methods, so those were removed.
     */
    public void run() {
        // Set colors
        setBodyColor(new Color(50, 50, 50));
        setGunColor(new Color(255, 0, 0));
        setRadarColor(new Color(0, 255, 0));
        setBulletColor(new Color(255, 255, 0));

        // Start somewhere safe
        goToWall();

        while (true) {
            // Always keep scanning
            turnRadarRight(360);

            // ✅ 1v1: use harder-to-hit movement
            if (is1v1()) {
                movement1v1Step();
                continue;
            }

            // ✅ Melee: keep your original corner tour (works fine in multibot sets)
            double width = getBattleFieldWidth();
            double height = getBattleFieldHeight();

            // Bottom Left
            goTo(MARGIN, MARGIN);

            // Top Right (diagonal across map)
            goTo(width - MARGIN, height - MARGIN);

            // Top Left
            goTo(MARGIN, height - MARGIN);

            // Bottom Right (diagonal across map)
            goTo(width - MARGIN, MARGIN);
        }
    }

    // ---------- 1v1 movement (small + effective, not CHAPPIE code) ----------

    private boolean is1v1() {
        return getOthers() == 1;
    }

    private void movement1v1Step() {
        double w = getBattleFieldWidth();
        double h = getBattleFieldHeight();

        // If near wall/sentry zone, head toward center-ish
        if (isNearWall()) {
            goTo(w / 2.0, h / 2.0);
            return;
        }

        // Small, jittery movement so linear guns struggle
        double turn = (Math.random() * 60) - 30; // -30..+30
        turnRight(turn);

        double step = 90 + Math.random() * 120;  // 90..210
        if (Math.random() < 0.5) ahead(step);
        else back(step);

        // extra scan so we don't go blind during movement
        turnRadarRight(90);
    }

    private boolean isNearWall() {
        double x = getX(), y = getY();
        double w = getBattleFieldWidth(), h = getBattleFieldHeight();
        return (x < MARGIN || x > w - MARGIN || y < MARGIN || y > h - MARGIN);
    }

    // ---------- Navigation ----------

    private void goTo(double x, double y) {
        // DX and DY are the legs of a right triangle getX/Y gets current position of bot
        double dx = x - getX();
        double dy = y - getY();

        // Uses trig to find the angle to the target in degrees
        double angleToTarget = Math.toDegrees(Math.atan2(dx, dy));
        double turnAngle = normalRelativeAngle(angleToTarget - getHeading());
        turnRight(turnAngle);

        double distance = Math.sqrt(dx * dx + dy * dy);

        // ✅ chunked movement so radar + onScannedRobot keep happening
        while (distance > 0) {
            double step = Math.min(140, distance);
            ahead(step);
            distance -= step;

            // quick scan between chunks (helps 1v1 and melee)
            turnRadarRight(45);
        }
    }

    private void goToWall() {
        double width = getBattleFieldWidth();
        double height = getBattleFieldHeight();

        double left = getX();
        double right = width - getX();
        double bottom = getY();
        double top = height - getY();

        double min = left;
        double targetX = MARGIN;
        double targetY = getY();

        if (right < min) { min = right; targetX = width - MARGIN; targetY = getY(); }
        if (bottom < min) { min = bottom; targetX = getX(); targetY = MARGIN; }
        if (top < min) { min = top; targetX = getX(); targetY = height - MARGIN; }

        goTo(targetX, targetY);
    }

    private double normalRelativeAngle(double angle) {
        while (angle > 180) angle = angle - 360;
        while (angle < -180) angle = angle + 360;
        return angle;
    }

    /**
     * Event handler - MERGED BEST FEATURES
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        String enemyName = e.getName();

        // Check if we switched targets
        if (lastTarget != null && !lastTarget.equals(enemyName)) {
            resetPatternHistory();
        }
        lastTarget = enemyName;

        // Update enemy data
        EnemyData enemy = new EnemyData(e, getTime(), this);

        // Store previous data
        if (enemies.containsKey(enemyName)) {
            EnemyData oldData = enemies.get(enemyName);
            enemy.lastHeading = oldData.heading;
            enemy.lastX = oldData.x;
            enemy.lastY = oldData.y;
        }

        enemies.put(enemyName, enemy);
        currentTarget = enemyName;

        // === Radar lock ===
        double radarTurn = getHeading() + e.getBearing() - getRadarHeading();
        if (radarTurn < 0) radarTurn -= 5;
        else radarTurn += 5;
        turnRadarRight(normalizeBearing(radarTurn));

        // Track energy (NOW USED for 1v1 dodge)
        double energyDrop = lastEnemyEnergy - e.getEnergy();
        lastEnemyEnergy = e.getEnergy();

        // ✅ 1v1: reverse direction when enemy fires (simple anti-linear)
        if (is1v1() && energyDrop > 0.0 && energyDrop <= 3.0) {
            moveDirection *= -1;
        }

        // Store movement history
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        enemyXHistory[historyIndex] = enemy.x;
        enemyYHistory[historyIndex] = enemy.y;
        historyIndex = (historyIndex + 1) % 10;

        // Optional: small perpendicular nudge in 1v1 without “drastic” change
        // (keeps your main structure intact)
        if (is1v1()) {
            double dodgeTurn = e.getBearing() + 90 * moveDirection;
            dodgeTurn += (Math.random() * 16 - 8);
            turnRight(normalizeBearing(dodgeTurn));
            if (!isNearWall()) {
                if (Math.random() < 0.5) ahead(60);
                else back(60);
            }
        }

        // Fire
        fireControlSystem(enemy);
    }

    /**
     * FIRE CONTROL SYSTEM - unchanged (just uses your predictor)
     */
    private void fireControlSystem(EnemyData enemy) {
        double firePower = calculateBulletPower(enemy.distance, enemy.energy);

        double predictedAngle = predictEnemyPosition(enemy, firePower);
        double predictedDegrees = Math.toDegrees(predictedAngle);

        double gunTurn = normalizeBearing(predictedDegrees - getGunHeading());
        turnGunRight(gunTurn);

        double remaining = normalizeBearing(predictedDegrees - getGunHeading());

        if (getGunHeat() <= 0.0 && enemy.energy > 0 && getEnergy() >= firePower) {
            if (Math.abs(remaining) < 12) {
                fire(firePower);
            }
        }
    }

    /**
     * Reset pattern history
     */
    private void resetPatternHistory() {
        for (int i = 0; i < enemyHeadingHistory.length; i++) {
            enemyHeadingHistory[i] = 0;
            enemyVelocityHistory[i] = 0;
            enemyXHistory[i] = 0;
            enemyYHistory[i] = 0;
        }
        historyIndex = 0;
    }

    /**
     * Enhanced prediction
     */
    private double predictEnemyPosition(EnemyData enemy, double bulletPower) {
        double bulletSpeed = 20 - 3 * bulletPower;
        long time = (long)(enemy.distance / bulletSpeed);

        boolean isSpinning = detectSpinbot();
        boolean isWallHugger = detectWallHugger();

        if (isWallHugger) {
            return predictLinearWithLead(enemy, bulletSpeed, time);
        } else if (isSpinning) {
            return predictCircular(enemy, bulletSpeed, time);
        } else {
            double avgVelocity = 0;
            for (double vel : enemyVelocityHistory) avgVelocity += Math.abs(vel);
            avgVelocity /= enemyVelocityHistory.length;

            if (avgVelocity > 7.0) {
                return predictCircular(enemy, bulletSpeed, time) * 0.7 + predictLinear(enemy, bulletSpeed, time) * 0.3;
            } else if (avgVelocity < 1.0) {
                return predictLinear(enemy, bulletSpeed, time);
            } else {
                return predictLinear(enemy, bulletSpeed, time) * 0.5 + predictCircular(enemy, bulletSpeed, time) * 0.5;
            }
        }
    }

    private boolean detectSpinbot() {
        if (historyIndex < 5) return false;

        double avgVelocity = 0;
        double avgTurnRate = 0;
        int count = 0;

        for (int i = 1; i < Math.min(historyIndex, 8); i++) {
            avgVelocity += Math.abs(enemyVelocityHistory[i]);
            double turnChange = Math.abs(normalizeBearing(enemyHeadingHistory[i] - enemyHeadingHistory[i - 1]));
            avgTurnRate += turnChange;
            count++;
        }

        if (count > 0) {
            avgVelocity /= count;
            avgTurnRate /= count;
        }

        return avgVelocity > 7.0 && avgTurnRate > 5.0;
    }

    private boolean detectWallHugger() {
        if (historyIndex < 5) return false;

        int nearWallCount = 0;
        double totalMovement = 0;
        int n = Math.min(historyIndex, 8);

        for (int i = 0; i < Math.min(historyIndex, 8); i++) {
            double x = enemyXHistory[i];
            double y = enemyYHistory[i];

            if (x < 100 || x > getBattleFieldWidth() - 100 ||
                y < 100 || y > getBattleFieldHeight() - 100) {
                nearWallCount++;
            }
            totalMovement += Math.abs(enemyVelocityHistory[i]);
        }

        double wallPercentage = (double)nearWallCount / n;
        double avgMovement = totalMovement / n;

        return wallPercentage > 0.6 && avgMovement < 6.0;
    }

    private double predictLinear(EnemyData enemy, double bulletSpeed, long time) {
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    private double predictLinearWithLead(EnemyData enemy, double bulletSpeed, long time) {
        long adjustedTime = (long)(time * 1.4);
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * adjustedTime;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * adjustedTime;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    private double predictCircular(EnemyData enemy, double bulletSpeed, long time) {
        double turnRate = 0;
        int samples = 0;

        for (int i = 1; i < Math.min(historyIndex, 5); i++) {
            double change = normalizeBearing(enemyHeadingHistory[i] - enemyHeadingHistory[i - 1]);
            turnRate += change;
            samples++;
        }

        if (samples > 0) turnRate /= samples;

        double predictedHeading = enemy.heading + turnRate * time;
        double predictedX = enemy.x + Math.sin(Math.toRadians(predictedHeading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(predictedHeading)) * enemy.velocity * time;

        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    private double calculateBulletPower(double distance, double enemyEnergy) {
        if (getEnergy() < 15) return 1.0;

        if (distance < 200) return 3.0;
        else if (distance < 350) return 2.5;
        else if (distance < 500) return 2.0;
        else return 1.5;
    }

    public void onHitWall(HitWallEvent e) {
        back(80);
        turnRight(90);
        ahead(140);
    }

    public void onHitByBullet(HitByBulletEvent e) {
        // simple anti-lock: flip direction and move
        moveDirection *= -1;
        turnRight(30 * moveDirection);
        ahead(120);
    }

    public void onRobotDeath(RobotDeathEvent e) {
        enemies.remove(e.getName());

        if (e.getName().equals(currentTarget)) {
            currentTarget = null;
            if (!enemies.isEmpty()) {
                double minDistance = Double.MAX_VALUE;
                for (String name : enemies.keySet()) {
                    EnemyData enemy = enemies.get(name);
                    if (enemy.distance < minDistance) {
                        minDistance = enemy.distance;
                        currentTarget = name;
                    }
                }
            }
            resetPatternHistory();
        }
    }

    /**
     * Normalize bearing (degrees)
     */
    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
