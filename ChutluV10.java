package gj.eh;

import robocode.*;
import java.util.HashMap;
import java.awt.Color;

public class ChutluV10 extends Robot {

    // Edge detection/before hits the edge and activates on wall hit
    private final double MARGIN = 165;

    // Enemy tracking changed hash to make sure its java 6 just in case
    private HashMap<String, EnemyData> enemies = new HashMap<String, EnemyData>();
    private String currentTarget = null;
    private String lastTarget = null;

    // Movement control
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100.0;
    private long lastMoveTime = 0;
    private static final int MOVE_COOLDOWN = 5;

    // Targeting history
    private double[] enemyHeadingHistory = new double[10];
    private double[] enemyVelocityHistory = new double[10];
    private double[] enemyXHistory = new double[10];
    private double[] enemyYHistory = new double[10];
    private int historyIndex = 0;

    // Wall avoidance
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

        //Absolute position on the battlefield
        double x;
        double y;

        //Previous scan's values
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
     * Main run method - V3's PROVEN radar system & main movement
     */
    // The brain/loop of the bot
    public void run() {
        // Set colors
        setBodyColor(new Color(50, 50, 50));
        setGunColor(new Color(255, 0, 0));
        setRadarColor(new Color(0, 255, 0));
        setBulletColor(new Color(255, 255, 0));

        //Starts trying to go to the closest wall it can
        goToWall();

        //Main game loop
        while (true) {

            // Battlefield height and width in a var
            double width = getBattleFieldWidth();
            double height = getBattleFieldHeight();

            //Radar scan amount depends on whether we have a target locked
            if (currentTarget == null) {
                turnRadarRight(45);
            } else {
                turnRadarRight(20);
            }

            //The basic X/Corner movement
            // Bottom Left
            goTo(MARGIN, MARGIN);

            // Top Right diagonal across map
            goTo(width - MARGIN, height - MARGIN);

            // Top Left
            goTo(MARGIN, height - MARGIN);

            // Bottom Right diagonal across map
            goTo(width - MARGIN, MARGIN);
        }
    }

    /**
     * Moves robot toward a target position.
    */
    private void goTo(double x, double y) {

        // DX and DY are the legs of a right triangle getX/Y gets current position of
        // bot
        double dx = x - getX();
        double dy = y - getY();

        // Uses trignometry to find the angle from the current position of the bot to
        // the target returns in radians then gets converted into degrees
        double angleToTarget = Math.toDegrees(Math.atan2(dx, dy));
        // Calculates the difference where the bot is facing and where it wants to go
        double turnAngle = normalRelativeAngle(angleToTarget - getHeading());

        // Turns the amount that turnAngle calculated)
        turnRight(turnAngle);

        double distance = Math.sqrt(dx * dx + dy * dy);
        // The bot stops driving the distance when it reaches
        ahead(distance);

    }

    /**
     * Moves robot to the closest wall at start.
    */
    private void goToWall() {
        double width = getBattleFieldWidth();
        double height = getBattleFieldHeight();

        double left = getX();
        double right = width - getX();
        double bottom = getY();
        double top = height - getY();

        //Just a kickstart, assumes the left wall is the closest
        double min = left;

        //Move towards the left wall but keep the margin from above
        double targetX = MARGIN;
        double targetY = getY();

        //A if that checks if any other wall is closer use it
        if (right < min) {
            min = right;
            targetX = width - MARGIN;
            targetY = getY();
        }
        if (bottom < min) {
            min = bottom;
            targetX = getX();
            targetY = MARGIN;
        }
        if (top < min) {
            min = top;
            targetX = getX();
            targetY = height - MARGIN;
        }

        goTo(targetX, targetY);
    }
    
    /**
     * Normalizes an angle to the range -180, +1802.
     * Used so we always turn the shortest way.
    */
    private double normalRelativeAngle(double angle) {

        while (angle > 180)
            angle = angle - 360;
        while (angle < -180)
            angle = angle + 360;
        return angle;

    }

    /**
     * Called whenever we scan another robot.
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        String enemyName = e.getName();

        // Check if we switched targets, resets history so targets dont mix
        if (lastTarget != null && !lastTarget.equals(enemyName)) {
            resetPatternHistory();
        }
        lastTarget = enemyName;

        // Update/build enemy data
        EnemyData enemy = new EnemyData(e, getTime(), this);

        // Store previous data, and if target already tracked pull data
        if (enemies.containsKey(enemyName)) {
            EnemyData oldData = enemies.get(enemyName);
            enemy.lastHeading = oldData.heading;
            enemy.lastX = oldData.x;
            enemy.lastY = oldData.y;
        }

        enemies.put(enemyName, enemy);
        currentTarget = enemyName;

        // === V3's PROVEN RADAR LOCK (but tighter) ===
        double radarTurn = getHeading() + e.getBearing() - getRadarHeading();
        // Tighter lock - 5° instead of 10°
        if (radarTurn < 0) {
            radarTurn -= 5;
        } else {
            radarTurn += 5;
        }
        turnRadarRight(normalizeBearing(radarTurn));

        // Track energy (kept for future dodging if you want)
        double energyDrop = lastEnemyEnergy - e.getEnergy();
        lastEnemyEnergy = e.getEnergy();

        // Store movement history for prediction
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        enemyXHistory[historyIndex] = enemy.x;
        enemyYHistory[historyIndex] = enemy.y;
        historyIndex = (historyIndex + 1) % 10;

        // === V4's IMPROVED FIRE CONTROL ===
        fireControlSystem(enemy);

    }

    /**
     * V4's FIRE CONTROL SYSTEM - High DPS, instant response
     */
    private void fireControlSystem(EnemyData enemy) {
        // Calculate optimal bullet power
        double firePower = calculateBulletPower(enemy.distance, enemy.energy);

        // Predict enemy position
        double predictedAngle = predictEnemyPosition(enemy, firePower);

        // Convert predicted angle to degrees
        double predictedDegrees = Math.toDegrees(predictedAngle);

        // Calculate gun turn needed (shortest path)
        double gunTurn = normalizeBearing(predictedDegrees - getGunHeading());

        // Turn gun
        turnGunRight(gunTurn);

        double remaining = normalizeBearing(predictedDegrees - getGunHeading());

        // === AGGRESSIVE FIRING ===
        // Fire if reasonably aligned
        if (getGunHeat() <= 0.0 && enemy.energy > 0 && getEnergy() >= firePower) {
            // Relaxed alignment for higher fire rate
            if (Math.abs(remaining) < 12) { // Even more aggressive than V4's 20°
                fire(firePower);
            }
        }
    }

    /**
     * Reset movement pattern history
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
     * Enhanced enemy location prediction
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

    /**
     * Detect if the enemy is likely spinbot-type
     */
    private boolean detectSpinbot() {
        if (historyIndex < 5)
            return false;

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

    /**
     * Detect wall hugger
     */
    private boolean detectWallHugger() {
        if (historyIndex < 5)
            return false;

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

    /**
     * Linear prediction
     */
    private double predictLinear(EnemyData enemy, double bulletSpeed, long time) {
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    /**
     * Linear prediction with lead for wall huggers
     */
    private double predictLinearWithLead(EnemyData enemy, double bulletSpeed, long time) {
        // Extra lead for wall huggers
        long adjustedTime = (long) (time * 1.4); // Increased from 1.3
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * adjustedTime;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * adjustedTime;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    /**
     * Circular prediction
     */
    private double predictCircular(EnemyData enemy, double bulletSpeed, long time) {
        double turnRate = 0;
        int samples = 0;

        for (int i = 1; i < Math.min(historyIndex, 5); i++) {
            double change = normalizeBearing(enemyHeadingHistory[i] - enemyHeadingHistory[i - 1]);
            turnRate += change;
            samples++;
        }

        if (samples > 0) {
            turnRate /= samples;
        }

        double predictedHeading = enemy.heading + turnRate * time;
        double predictedX = enemy.x + Math.sin(Math.toRadians(predictedHeading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(predictedHeading)) * enemy.velocity * time;

        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    /**
     * V4's AGGRESSIVE bullet power for high DPS
     */
    private double calculateBulletPower(double distance, double enemyEnergy) {
        // Low energy conservation
        if (getEnergy() < 15) {
            return 1.0;
        }

        // AGGRESSIVE power levels for maximum damage
        if (distance < 200) {
            return 3.0;
        } else if (distance < 350) {
            return 2.5;
        } else if (distance < 500) {
            return 2.0;
        } else {
            return 1.5;
        }
    }

    public void onHitWall(HitWallEvent e) {
        back(50);
        turnRight(90);
    }

    /**
     * Handle enemy death
     */
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
        while (angle > 180)
            angle -= 360;
        while (angle < -180)
            angle += 360;
        return angle;
    }

}
