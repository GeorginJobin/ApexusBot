package gj.eh;

import robocode.*;
import java.util.HashMap;
import java.awt.Color;

public class ChutluV17 extends Robot {

    // Distance to keep from walls
    private final double WALL_MARGIN_DISTANCE = 165;

    // Enemy tracking
    private HashMap<String, EnemyData> trackedEnemies = new HashMap<String, EnemyData>();
    private String currentTargetName = null;
    private String previousTargetName = null;

    // Targeting history (for prediction)
    private double[] enemyHeadingHistory = new double[10];
    private double[] enemyVelocityHistory = new double[10];
    private double[] enemyXHistory = new double[10];
    private double[] enemyYHistory = new double[10];
    private int historyIndex = 0;

    // Track last energy for fire detection
    private double lastEnemyEnergy = 100.0;

    /**
     * Stores scanned enemy information
     */
    class EnemyData {
        double bearing;
        double distance;
        double energy;
        double heading;
        double velocity;
        double xPosition;
        double yPosition;

        EnemyData(ScannedRobotEvent event) {
            bearing = event.getBearing();
            distance = event.getDistance();
            energy = event.getEnergy();
            heading = event.getHeading();
            velocity = event.getVelocity();

            double absoluteBearing =
                    Math.toRadians(getHeading()) + event.getBearingRadians();

            xPosition = getX() + Math.sin(absoluteBearing) * distance;
            yPosition = getY() + Math.cos(absoluteBearing) * distance;
        }
    }

    /**
     * Main loop
     */
    public void run() {

        setBodyColor(new Color(50, 50, 50));
        setGunColor(new Color(255, 0, 0));
        setRadarColor(new Color(0, 255, 0));
        setBulletColor(new Color(255, 255, 0));

        moveToNearestWall();

        while (true) {

            double battlefieldWidth = getBattleFieldWidth();
            double battlefieldHeight = getBattleFieldHeight();

            // Radar behavior
            if (currentTargetName == null) {
                turnRadarRight(45);
            } else {
                turnRadarRight(20);
            }

            // X-pattern corner movement
            moveToPosition(WALL_MARGIN_DISTANCE, WALL_MARGIN_DISTANCE);
            moveToPosition(battlefieldWidth - WALL_MARGIN_DISTANCE,
                           battlefieldHeight - WALL_MARGIN_DISTANCE);
            moveToPosition(WALL_MARGIN_DISTANCE,
                           battlefieldHeight - WALL_MARGIN_DISTANCE);
            moveToPosition(battlefieldWidth - WALL_MARGIN_DISTANCE,
                           WALL_MARGIN_DISTANCE);
        }
    }

    /**
     * Moves robot toward a coordinate
     */
    private void moveToPosition(double targetX, double targetY) {

        double deltaX = targetX - getX();
        double deltaY = targetY - getY();

        double angleToTarget =
                Math.toDegrees(Math.atan2(deltaX, deltaY));

        double turnAngle =
                normalizeAngle(angleToTarget - getHeading());

        turnRight(turnAngle);

        double distanceToTravel =
                Math.sqrt(deltaX * deltaX + deltaY * deltaY);

        ahead(distanceToTravel);
    }

    /**
     * Move to closest wall at start
     */
    private void moveToNearestWall() {

        double battlefieldWidth = getBattleFieldWidth();
        double battlefieldHeight = getBattleFieldHeight();

        double distanceLeft = getX();
        double distanceRight = battlefieldWidth - getX();
        double distanceBottom = getY();
        double distanceTop = battlefieldHeight - getY();

        double smallestDistance = distanceLeft;

        double targetX = WALL_MARGIN_DISTANCE;
        double targetY = getY();

        if (distanceRight < smallestDistance) {
            smallestDistance = distanceRight;
            targetX = battlefieldWidth - WALL_MARGIN_DISTANCE;
            targetY = getY();
        }

        if (distanceBottom < smallestDistance) {
            smallestDistance = distanceBottom;
            targetX = getX();
            targetY = WALL_MARGIN_DISTANCE;
        }

        if (distanceTop < smallestDistance) {
            targetX = getX();
            targetY = battlefieldHeight - WALL_MARGIN_DISTANCE;
        }

        moveToPosition(targetX, targetY);
    }

    /**
     * Normalize angle to -180 to +180
     */
    private double normalizeAngle(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    /**
     * Called when scanning another robot
     */
    public void onScannedRobot(ScannedRobotEvent event) {

        String enemyName = event.getName();

        if (previousTargetName != null &&
            !previousTargetName.equals(enemyName)) {
            resetHistory();
        }

        previousTargetName = enemyName;

        EnemyData enemy = new EnemyData(event);
        trackedEnemies.put(enemyName, enemy);
        currentTargetName = enemyName;

        // Radar lock
        double radarTurn =
                getHeading() + event.getBearing() - getRadarHeading();

        if (radarTurn < 0) radarTurn -= 5;
        else radarTurn += 5;

        turnRadarRight(normalizeAngle(radarTurn));

        // Store movement history
        enemyHeadingHistory[historyIndex] = event.getHeading();
        enemyVelocityHistory[historyIndex] = event.getVelocity();
        enemyXHistory[historyIndex] = enemy.xPosition;
        enemyYHistory[historyIndex] = enemy.yPosition;
        historyIndex = (historyIndex + 1) % 10;

        fireAtEnemy(enemy);
    }

    /**
     * Firing system
     */
    private void fireAtEnemy(EnemyData enemy) {

        double firePower =
                calculateBulletPower(enemy.distance);

        double predictedAngle =
                predictEnemyPosition(enemy, firePower);

        double predictedDegrees =
                Math.toDegrees(predictedAngle);

        double gunTurn =
                normalizeAngle(predictedDegrees - getGunHeading());

        turnGunRight(gunTurn);

        double remaining =
                normalizeAngle(predictedDegrees - getGunHeading());

        if (getGunHeat() == 0 &&
            enemy.energy > 0 &&
            getEnergy() > firePower) {

            if (Math.abs(remaining) < 12) {
                fire(firePower);
            }
        }
    }

    /**
     * Predict enemy future position
     */
    private double predictEnemyPosition(EnemyData enemy,
                                        double bulletPower) {

        double bulletSpeed = 20 - 3 * bulletPower;
        long time =
                (long) (enemy.distance / bulletSpeed);

        // Average velocity from history
        double averageVelocity = 0;
        for (double velocityValue : enemyVelocityHistory) {
            averageVelocity += Math.abs(velocityValue);
        }
        averageVelocity /= enemyVelocityHistory.length;

        if (averageVelocity > 7.0) {
            return predictCircular(enemy, time);
        } else {
            return predictLinear(enemy, time);
        }
    }

    /**
     * Linear prediction
     */
    private double predictLinear(EnemyData enemy, long time) {

        double futureX =
                enemy.xPosition +
                Math.sin(Math.toRadians(enemy.heading))
                * enemy.velocity * time;

        double futureY =
                enemy.yPosition +
                Math.cos(Math.toRadians(enemy.heading))
                * enemy.velocity * time;

        return Math.atan2(futureX - getX(),
                          futureY - getY());
    }

    /**
     * Circular prediction
     */
    private double predictCircular(EnemyData enemy, long time) {

        double averageTurnRate = 0;
        int samples = 0;

        for (int i = 1; i < historyIndex; i++) {
            double change =
                normalizeAngle(enemyHeadingHistory[i]
                              - enemyHeadingHistory[i - 1]);
            averageTurnRate += change;
            samples++;
        }

        if (samples > 0) {
            averageTurnRate /= samples;
        }

        double futureHeading =
                enemy.heading + averageTurnRate * time;

        double futureX =
                enemy.xPosition +
                Math.sin(Math.toRadians(futureHeading))
                * enemy.velocity * time;

        double futureY =
                enemy.yPosition +
                Math.cos(Math.toRadians(futureHeading))
                * enemy.velocity * time;

        return Math.atan2(futureX - getX(),
                          futureY - getY());
    }

    /**
     * Bullet power scaling
     */
    private double calculateBulletPower(double distance) {

        if (getEnergy() < 15) return 1.0;

        if (distance < 200) return 3.0;
        if (distance < 350) return 2.5;
        if (distance < 500) return 2.0;
        return 1.5;
    }

    /**
     * Reset history arrays
     */
    private void resetHistory() {
        for (int i = 0; i < enemyHeadingHistory.length; i++) {
            enemyHeadingHistory[i] = 0;
            enemyVelocityHistory[i] = 0;
            enemyXHistory[i] = 0;
            enemyYHistory[i] = 0;
        }
        historyIndex = 0;
    }

    public void onHitWall(HitWallEvent event) {
        back(50);
        turnRight(90);
    }

    public void onRobotDeath(RobotDeathEvent event) {
        trackedEnemies.remove(event.getName());
    }
}
