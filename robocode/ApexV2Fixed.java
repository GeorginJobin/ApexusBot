package MyBots;

import robocode.*;
import java.awt.Color;
import java.util.HashMap;

/**
 * ApexV2Fixed - 100% Intermediate Robot class bot
 * 
 * Strictly uses only Robot class methods (NO AdvancedRobot features)
 * - No execute()
 * - No setXXX() non-blocking commands
 * - Only blocking commands: turnRight(), ahead(), fire(), etc.
 */
public class ApexV2Fixed extends Robot {
    
    // Enemy tracking
    private HashMap<String, EnemyData> enemies = new HashMap<>();
    private String currentTarget = null;
    private String lastTarget = null;
    
    // Movement variables
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100.0;
    
    // Targeting history for pattern matching
    private double[] enemyHeadingHistory = new double[10];
    private double[] enemyVelocityHistory = new double[10];
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
        double x;
        double y;
        double lastHeading;
        
        EnemyData(ScannedRobotEvent e, long currentTime) {
            this.bearing = e.getBearing();
            this.distance = e.getDistance();
            this.energy = e.getEnergy();
            this.heading = e.getHeading();
            this.velocity = e.getVelocity();
            this.time = currentTime;
            this.lastHeading = e.getHeading();
            
            // Calculate absolute position
            double absoluteBearing = getHeadingRadians() + e.getBearingRadians();
            this.x = getX() + Math.sin(absoluteBearing) * e.getDistance();
            this.y = getY() + Math.cos(absoluteBearing) * e.getDistance();
        }
    }
    
    /**
     * Main run method - uses only blocking Robot class commands
     */
    public void run() {
        // Set colors
        setBodyColor(new Color(50, 50, 50));
        setGunColor(new Color(255, 0, 0));
        setRadarColor(new Color(0, 255, 0));
        setBulletColor(new Color(255, 255, 0));
        
        // Independent movement
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        
        // Main loop - simple radar sweep
        while (true) {
            // Small radar sweeps to maintain awareness
            turnRadarRight(45);
        }
    }
    
    /**
     * ALL logic happens in onScannedRobot for Robot class
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        String enemyName = e.getName();
        
        // Check if we switched targets - reset pattern history
        if (lastTarget != null && !lastTarget.equals(enemyName)) {
            resetPatternHistory();
        }
        lastTarget = enemyName;
        
        // Update enemy data
        EnemyData enemy = new EnemyData(e, getTime());
        
        // Store previous heading for turn rate calculation
        if (enemies.containsKey(enemyName)) {
            enemy.lastHeading = enemies.get(enemyName).heading;
        }
        
        enemies.put(enemyName, enemy);
        currentTarget = enemyName;
        
        // Track enemy energy for bullet detection
        double energyDrop = lastEnemyEnergy - e.getEnergy();
        lastEnemyEnergy = e.getEnergy();
        
        // === RADAR LOCK ===
        double radarTurn = getHeading() + e.getBearing() - getRadarHeading();
        // Add extra turn to maintain lock
        if (radarTurn < 0) {
            radarTurn -= 5;
        } else {
            radarTurn += 5;
        }
        turnRadarRight(normalizeBearing(radarTurn));
        
        // Store movement history for pattern matching
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        historyIndex = (historyIndex + 1) % 10;
        
        // === TARGETING ===
        double firePower = calculateBulletPower(e.getDistance(), e.getEnergy());
        double predictedPosition = predictEnemyPosition(enemy, firePower);
        
        // Aim gun
        double gunTurn = normalizeBearing(Math.toDegrees(predictedPosition) - getGunHeading());
        turnGunRight(gunTurn);
        
        // Fire when aligned
        if (Math.abs(gunTurn) < 10 && getGunHeat() == 0 && e.getEnergy() > 0 && getEnergy() > firePower) {
            fire(firePower);
        }
        
        // Detect enemy fire
        if (energyDrop >= 0.1 && energyDrop <= 3.0) {
            moveDirection *= -1;
        }
        
        // === MOVEMENT ===
        executeSmartMovement(enemy);
    }
    
    /**
     * Reset pattern history when switching targets
     */
    private void resetPatternHistory() {
        for (int i = 0; i < enemyHeadingHistory.length; i++) {
            enemyHeadingHistory[i] = 0;
            enemyVelocityHistory[i] = 0;
        }
        historyIndex = 0;
    }
    
    /**
     * Smart movement using only blocking Robot commands
     */
    private void executeSmartMovement(EnemyData enemy) {
        // Calculate angle to enemy
        double enemyAngle = Math.atan2(enemy.x - getX(), enemy.y - getY());
        double enemyDistance = enemy.distance;
        
        // Check wall proximity
        boolean nearLeftWall = getX() < WALL_MARGIN;
        boolean nearRightWall = getX() > getBattleFieldWidth() - WALL_MARGIN;
        boolean nearBottomWall = getY() < WALL_MARGIN;
        boolean nearTopWall = getY() > getBattleFieldHeight() - WALL_MARGIN;
        boolean nearWall = nearLeftWall || nearRightWall || nearBottomWall || nearTopWall;
        
        // Base movement angle - perpendicular to enemy
        double moveAngle = Math.toDegrees(enemyAngle) + (90 * moveDirection);
        
        // Wall avoidance override
        if (nearWall) {
            // Turn toward center
            double centerX = getBattleFieldWidth() / 2;
            double centerY = getBattleFieldHeight() / 2;
            moveAngle = Math.toDegrees(Math.atan2(centerX - getX(), centerY - getY()));
        }
        // Distance management
        else if (enemyDistance < 150) {
            // Too close - move away
            moveAngle = Math.toDegrees(enemyAngle) + 180;
        } else if (enemyDistance > 350) {
            // Too far - move closer
            moveAngle = Math.toDegrees(enemyAngle);
        }
        
        // Calculate turn needed
        double turn = normalizeBearing(moveAngle - getHeading());
        
        // Execute movement with blocking commands
        if (Math.abs(turn) > 90) {
            // Reverse if turn is too sharp
            turnRight(normalizeBearing(turn + 180));
            back(30);
        } else {
            turnRight(turn);
            ahead(30);
        }
        
        // Random direction changes for unpredictability
        if (Math.random() < 0.08) {
            moveDirection *= -1;
        }
    }
    
    /**
     * Multi-method prediction
     */
    private double predictEnemyPosition(EnemyData enemy, double bulletPower) {
        double bulletSpeed = 20 - 3 * bulletPower;
        
        // Calculate all prediction methods
        double linearPrediction = predictLinear(enemy, bulletSpeed);
        double circularPrediction = predictCircular(enemy, bulletSpeed);
        double patternPrediction = predictPattern(enemy, bulletSpeed);
        
        // Analyze enemy movement pattern
        double avgVelocity = 0;
        for (double vel : enemyVelocityHistory) {
            avgVelocity += Math.abs(vel);
        }
        avgVelocity /= enemyVelocityHistory.length;
        
        // Weight predictions based on enemy behavior
        if (avgVelocity > 7.5) {
            // Fast moving - likely spinbot or circular
            return circularPrediction * 0.7 + patternPrediction * 0.3;
        } else if (avgVelocity < 2.0) {
            // Slow/stopped - use linear
            return linearPrediction;
        } else {
            // Mixed behavior
            return linearPrediction * 0.3 + circularPrediction * 0.4 + patternPrediction * 0.3;
        }
    }
    
    /**
     * Linear prediction
     */
    private double predictLinear(EnemyData enemy, double bulletSpeed) {
        long time = (long)(enemy.distance / bulletSpeed);
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * time;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }
    
    /**
     * Circular prediction with proper turn rate
     */
    private double predictCircular(EnemyData enemy, double bulletSpeed) {
        long time = (long)(enemy.distance / bulletSpeed);
        
        // Calculate normalized heading change
        double headingChange = normalizeBearing(enemy.heading - enemy.lastHeading);
        double turnRate = headingChange;
        
        // Project future heading
        double predictedHeading = enemy.heading + turnRate * time;
        double predictedX = enemy.x + Math.sin(Math.toRadians(predictedHeading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(predictedHeading)) * enemy.velocity * time;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }
    
    /**
     * Pattern-based prediction
     */
    private double predictPattern(EnemyData enemy, double bulletSpeed) {
        double avgHeading = 0;
        int validEntries = 0;
        
        for (double heading : enemyHeadingHistory) {
            if (heading != 0) {
                avgHeading += heading;
                validEntries++;
            }
        }
        
        if (validEntries > 0) {
            avgHeading /= validEntries;
        } else {
            avgHeading = enemy.heading;
        }
        
        long time = (long)(enemy.distance / bulletSpeed);
        double predictedX = enemy.x + Math.sin(Math.toRadians(avgHeading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(avgHeading)) * enemy.velocity * time;
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }
    
    /**
     * Calculate optimal bullet power with energy management
     */
    private double calculateBulletPower(double distance, double enemyEnergy) {
        // Low energy - conserve
        if (getEnergy() < 15) {
            return 1.0;
        }
        
        // Distance-based power
        if (distance < 100) {
            return Math.min(3.0, getEnergy() / 10);
        } else if (distance < 300) {
            double power = Math.min(2.5, enemyEnergy / 4);
            return Math.min(power, getEnergy() / 8);
        } else if (distance < 500) {
            return 1.5;
        } else {
            return 1.0;
        }
    }
    
    /**
     * Handle being hit by bullet
     */
    public void onHitByBullet(HitByBulletEvent e) {
        // Change direction
        moveDirection *= -1;
    }
    
    /**
     * Handle hitting wall
     */
    public void onHitWall(HitWallEvent e) {
        // Reverse and turn away
        moveDirection *= -1;
        back(50);
        turnRight(90);
    }
    
    /**
     * Handle robot collision
     */
    public void onHitRobot(HitRobotEvent e) {
        // Ram weak enemies
        if (e.getEnergy() < 20 && getEnergy() > 30) {
            fire(3.0);
            ahead(50);
        } else {
            back(50);
            moveDirection *= -1;
        }
    }
    
    /**
     * Handle enemy death - switch to next target
     */
    public void onRobotDeath(RobotDeathEvent e) {
        enemies.remove(e.getName());
        
        if (e.getName().equals(currentTarget)) {
            currentTarget = null;
            
            // Find nearest remaining enemy
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
     * Normalize bearing to [-180, 180]
     */
    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
