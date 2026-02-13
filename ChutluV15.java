package gj.eh;

import robocode.*;
import java.util.HashMap;
import java.awt.Color;

public class ChutluV15 extends Robot {

    // Keep away from borders/sentries
    private final double MARGIN = 200;

    // Enemy tracking
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

            double absoluteBearing = Math.toRadians(bot.getHeading()) + e.getBearingRadians();
            this.x = bot.getX() + Math.sin(absoluteBearing) * e.getDistance();
            this.y = bot.getY() + Math.cos(absoluteBearing) * e.getDistance();
            this.lastX = this.x;
            this.lastY = this.y;
        }
    }

    /**
     * Main run loop - Robot-only, sentry-safe movement + constant scanning
     */
    public void run() {
        setBodyColor(new Color(50, 50, 50));
        setGunColor(new Color(255, 0, 0));
        setRadarColor(new Color(0, 255, 0));
        setBulletColor(new Color(255, 255, 0));

        // Start by moving toward center (sentry-safe)
        goTo(getBattleFieldWidth() / 2.0, getBattleFieldHeight() / 2.0);

        while (true) {
            // Full radar sweep so we keep finding enemies
            turnRadarRight(360);

            // Move in short jittery steps inside safe box (dodges sentries + CHAPPIE)
            centerJitterStep();
        }
    }

    /**
     * Keep movement inside a safe rectangle away from walls (sentry + border guard safety)
     */
    private void centerJitterStep() {
        if (isNearWall()) {
            goTo(getBattleFieldWidth() / 2.0, getBattleFieldHeight() / 2.0);
            return;
        }

        // small random body turns
        double turn = (Math.random() * 60 - 30); // -30..+30
        turnRight(turn);

        // short moves so radar keeps updating
        double dist = 70 + Math.random() * 110; // 70..180
        ahead(dist);
    }

    private boolean isNearWall() {
        double x = getX(), y = getY();
        double w = getBattleFieldWidth(), h = getBattleFieldHeight();
        return (x < MARGIN || x > w - MARGIN || y < MARGIN || y > h - MARGIN);
    }

    private double normalRelativeAngle(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    /**
     * Event handler - add dodge + keep your gun system
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        String enemyName = e.getName();

        if (lastTarget != null && !lastTarget.equals(enemyName)) {
            resetPatternHistory();
        }
        lastTarget = enemyName;

        EnemyData enemy = new EnemyData(e, getTime(), this);

        if (enemies.containsKey(enemyName)) {
            EnemyData oldData = enemies.get(enemyName);
            enemy.lastHeading = oldData.heading;
            enemy.lastX = oldData.x;
            enemy.lastY = oldData.y;
        }

        enemies.put(enemyName, enemy);
        currentTarget = enemyName;

        // --- Radar lock (Robot-style) ---
        double radarTurn = getHeading() + e.getBearing() - getRadarHeading();
        radarTurn += (radarTurn < 0 ? -10 : 10); // a little overshoot
        turnRadarRight(normalizeBearing(radarTurn));

        // --- Detect when CHAPPIE fires (energy drop) and dodge ---
        double energyDrop = lastEnemyEnergy - e.getEnergy();
        lastEnemyEnergy = e.getEnergy();
        boolean enemyJustFired = (energyDrop > 0.0 && energyDrop <= 3.0);

        if (enemyJustFired) {
            moveDirection *= -1; // reverse when they shoot
        }

        // Perpendicular dodge movement (simple, strong vs CHAPPIE linear)
        double dodgeTurn = e.getBearing() + 90 * moveDirection;
        dodgeTurn += (Math.random() * 20 - 10); // jitter
        turnRight(normalizeBearing(dodgeTurn));

        if (isNearWall()) {
            goTo(getBattleFieldWidth() / 2.0, getBattleFieldHeight() / 2.0);
        } else {
            double step = 80 + Math.random() * 100; // 80..180
            if (Math.random() < 0.5) ahead(step);
            else back(step);
        }

        // Store movement history for your predictor
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        enemyXHistory[historyIndex] = enemy.x;
        enemyYHistory[historyIndex] = enemy.y;
        historyIndex = (historyIndex + 1) % 10;

        // Fire (your system)
        fireControlSystem(enemy);
    }

    /**
     * FIRE CONTROL SYSTEM (kept) - Robot-friendly tweak:
     * - don’t fire if gun isn’t roughly aligned
     */
    private void fireControlSystem(EnemyData enemy) {
        double firePower = calculateBulletPower(enemy.distance, enemy.energy);

        double predictedAngle = predictEnemyPosition(enemy, firePower);
        double predictedDegrees = Math.toDegrees(predictedAngle);

        double gunTurn = normalizeBearing(predictedDegrees - getGunHeading());
        turnGunRight(gunTurn);

        double remaining = normalizeBearing(predictedDegrees - getGunHeading());

        // Fire when aligned and gun is cool
        if (getGunHeat() <= 0.0 && enemy.energy > 0 && getEnergy() >= firePower) {
            if (Math.abs(remaining) < 10) { // a bit tighter than 12 for Robot consistency
                fire(firePower);
            }
        }
    }

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
     * Prediction (kept)
     */
    private double predictEnemyPosition(EnemyData enemy, double bulletPower) {
        double bulletSpeed = 20 - 3 * bulletPower;
        long time = (long) (enemy.distance / bulletSpeed);

        boolean isSpinning = detectSpinbot();
        boolean isWallHugger = detectWallHugger();

        if (isWallHugger) {
            return predictLinearWithLead(enemy, bulletSpeed, time);
        } else if (isSpinning) {
            return predictCircular(enemy, bulletSpeed, time);
        } else {
            double avgVelocity = 0;
            for (double vel : enemyVelocityHistory) {
                avgVelocity += Math.abs(vel);
            }
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

        double wallPercentage = (double) nearWallCount / n;
        double avgMovement = totalMovement / n;

        return wallPercentage > 0.6 && avgMovement < 6.0;
    }

    private double predictLinear(EnemyData enemy, double bulletSpeed, long time) {
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    private double predictLinearWithLead(EnemyData enemy, double bulletSpeed, long time) {
        long adjustedTime = (long) (time * 1.4);
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
        // Get off wall fast (sentries punish walls)
        back(80);
        turnRight(90);
        ahead(140);
    }

    public void onHitByBullet(HitByBulletEvent e) {
        // Break enemy lock
        moveDirection *= -1;
        turnRight(30 * moveDirection);
        ahead(160);
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

    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    /**
     * goTo() rewritten to NOT go blind for ages:
     * moves in chunks and scans between chunks (important for Robot)
     */
    private void goTo(double x, double y) {
        double dx = x - getX();
        double dy = y - getY();

        double angleToTarget = Math.toDegrees(Math.atan2(dx, dy));
        double turnAngle = normalRelativeAngle(angleToTarget - getHeading());
        turnRight(turnAngle);

        double distance = Math.sqrt(dx * dx + dy * dy);

        while (distance > 0) {
            double step = Math.min(140, distance);
            ahead(step);
            distance -= step;

            // scan between steps so we keep shooting
            turnRadarRight(90);
        }
    }
}
