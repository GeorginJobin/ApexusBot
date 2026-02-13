package gj.eh;

import robocode.*;
import java.awt.Color;
import java.util.HashMap;

public class ChutluV6 extends Robot {

    private final double MARGIN = 18;

    // ===== Apex-style tracking =====
    private HashMap<String, EnemyData> enemies = new HashMap<>();
    private String currentTarget = null;
    private String lastTarget = null;

    private double lastEnemyEnergy = 100.0;

    // Targeting history
    private double[] enemyHeadingHistory = new double[10];
    private double[] enemyVelocityHistory = new double[10];
    private double[] enemyXHistory = new double[10];
    private double[] enemyYHistory = new double[10];
    private int historyIndex = 0;

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

    public void run() {
        setBodyColor(new Color(50, 50, 50));
        setGunColor(new Color(255, 0, 0));
        setRadarColor(new Color(0, 255, 0));
        setBulletColor(new Color(255, 255, 0));

        goToWall();

        while (true) {
            double width = getBattleFieldWidth();
            double height = getBattleFieldHeight();

            // Keep radar moving so we find enemies during travel
            if (currentTarget == null) turnRadarRight(45);
            else turnRadarRight(20);

            // Bottom Left
            goTo(MARGIN, MARGIN);

            // Top Right
            goTo(width - MARGIN, height - MARGIN);

            // Top Left
            goTo(MARGIN, height - MARGIN);

            // Bottom Right
            goTo(width - MARGIN, MARGIN);
        }
    }

    private void goTo(double x, double y) {
        double dx = x - getX();
        double dy = y - getY();

        double angleToTarget = Math.toDegrees(Math.atan2(dx, dy));
        double turnAngle = normalRelativeAngle(angleToTarget - getHeading());
        turnRight(turnAngle);

        double distance = Math.sqrt(dx * dx + dy * dy);
        ahead(distance);
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
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    // ===== ApexV5 firing system integrated =====
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

        // Tight radar lock (5 deg)
        double radarTurn = getHeading() + e.getBearing() - getRadarHeading();
        if (radarTurn < 0) radarTurn -= 5;
        else radarTurn += 5;
        turnRadarRight(normalizeBearing(radarTurn));

        // Track enemy energy (kept for compatibility; you can use it for dodging later)
        double energyDrop = lastEnemyEnergy - e.getEnergy();
        lastEnemyEnergy = e.getEnergy();

        // Store history
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        enemyXHistory[historyIndex] = enemy.x;
        enemyYHistory[historyIndex] = enemy.y;
        historyIndex = (historyIndex + 1) % 10;

        // Fire
        fireControlSystem(enemy);
    }

    private void fireControlSystem(EnemyData enemy) {
        double firePower = calculateBulletPower(enemy.distance, enemy.energy);

        double predictedAngle = predictEnemyPosition(enemy, firePower);
        double predictedDegrees = Math.toDegrees(predictedAngle);

        double gunTurn = normalizeBearing(predictedDegrees - getGunHeading());
        turnGunRight(gunTurn);

        // IMPORTANT: check alignment AFTER turning (fixes "not shooting" problem)
        double remaining = normalizeBearing(predictedDegrees - getGunHeading());

        if (getGunHeat() == 0 && enemy.energy > 0 && getEnergy() > firePower) {
            if (Math.abs(remaining) < 12) {
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

    private double predictEnemyPosition(EnemyData enemy, double bulletPower) {
        double bulletSpeed = 20 - 3 * bulletPower;
        long time = (long) (enemy.distance / bulletSpeed);

        boolean isSpinning = detectSpinbot();
        boolean isWallHugger = detectWallHugger();

        if (isWallHugger) {
            return predictLinearWithLead(enemy, time);
        } else if (isSpinning) {
            return predictCircular(enemy, time);
        } else {
            double avgVelocity = 0;
            for (double vel : enemyVelocityHistory) avgVelocity += Math.abs(vel);
            avgVelocity /= enemyVelocityHistory.length;

            if (avgVelocity > 7.0) {
                return predictCircular(enemy, time) * 0.7 +
                       predictLinear(enemy, time) * 0.3;
            } else if (avgVelocity < 1.0) {
                return predictLinear(enemy, time);
            } else {
                return predictLinear(enemy, time) * 0.5 +
                       predictCircular(enemy, time) * 0.5;
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
            double turnChange = Math.abs(normalizeBearing(
                    enemyHeadingHistory[i] - enemyHeadingHistory[i - 1]));
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

        for (int i = 0; i < n; i++) {
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

    private double predictLinear(EnemyData enemy, long time) {
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    private double predictLinearWithLead(EnemyData enemy, long time) {
        long adjustedTime = (long) (time * 1.4);
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * adjustedTime;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * adjustedTime;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    private double predictCircular(EnemyData enemy, long time) {
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
        back(50);
        turnRight(90);
    }

    public void onRobotDeath(RobotDeathEvent e) {
        enemies.remove(e.getName());
        if (e.getName().equals(currentTarget)) {
            currentTarget = null;
            resetPatternHistory();
        }
    }

    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
