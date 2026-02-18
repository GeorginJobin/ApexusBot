package MyBots;

import robocode.*;
import java.awt.Color;
import java.util.HashMap;

/**
 * ApexV2 - Intermediate bot designed to handle 90% of opponents
 * 
 * Fixed Issues:
 * - Non-blocking movement
 * - Proper radar lock
 * - Pattern history management
 * - Wall avoidance
 * - Turn rate calculation
 * - Energy management
 * - Multi-bot tracking
 */
public class ApexV2 extends Robot {
    
    // Enemy tracking
    private HashMap<String, EnemyData> enemies = new HashMap<>();
    private String currentTarget = null;
    private String lastTarget = null;
    
    // Movement variables
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100.0;
    
    // Targeting history for pattern matching (per enemy)
    private double[] enemyHeadingHistory = new double[10];
    private double[] enemyVelocityHistory = new double[10];
    private int historyIndex = 0;
    
    // Movement target
    private double moveAngle = 0;
    private double moveDistance = 0;
    
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
     * Main run method
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
        
        // Main loop - NON-BLOCKING commands only
        while (true) {
            // Radar management
            if (currentTarget == null) {
                // Wide sweep to find enemies
                turnRadarRight(45);
            }
            // If we have a target, radar lock is handled in onScannedRobot
            
            // Execute movement
            if (currentTarget != null && enemies.containsKey(currentTarget)) {
                executeMovement();
            } else {
                // No target, stay in center area
                double centerX = getBattleFieldWidth() / 2;
                double centerY = getBattleFieldHeight() / 2;
                double angle = Math.toDegrees(Math.atan2(centerX - getX(), centerY - getY()));
                turnRight(normalizeBearing(angle - getHeading()));
                ahead(20);
            }
            
            execute(); // Complete all actions for this tick
        }
    }
    
    /**
     * Targeting and tracking when we scan an enemy
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        String enemyName = e.getName();
        
        // Check if we switched targets - reset pattern history if so
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
        
        // FIXED: Proper radar lock with narrow sweep
        double radarTurn = getHeading() + e.getBearing() - getRadarHeading();
        // Add extra turn to ensure we scan them next turn
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
        
        // FIXED: Energy management - check our energy before calculating bullet power
        double firePower = calculateBulletPower(e.getDistance(), e.getEnergy());
        
        // Advanced targeting
        double predictedPosition = predictEnemyPosition(enemy, firePower);
        
        // Aim gun
        double gunTurn = normalizeBearing(Math.toDegrees(predictedPosition) - getGunHeading());
        turnGunRight(gunTurn);
        
        // Fire when aligned and have enough energy
        if (Math.abs(gunTurn) < 10 && getGunHeat() == 0 && e.getEnergy() > 0 && getEnergy() > firePower) {
            fire(firePower);
        }
        
        // Detect enemy fire for evasive action
        if (energyDrop >= 0.1 && energyDrop <= 3.0) {
            // Enemy fired! Change direction
            moveDirection *= -1;
        }
        
        // Calculate movement for next tick
        calculateMovement(enemy);
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
     * Advanced prediction combining linear, circular, and pattern matching
     */
    private double predictEnemyPosition(EnemyData enemy, double bulletPower) {
        double bulletSpeed = 20 - 3 * bulletPower;
        
        // Try multiple prediction methods and weight them
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
            // Fast moving enemy - likely circular or oscillating
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
     * FIXED: Circular prediction with proper turn rate calculation
     */
    private double predictCircular(EnemyData enemy, double bulletSpeed) {
        long time = (long)(enemy.distance / bulletSpeed);
        
        // Calculate turn rate from heading change (normalized)
        double headingChange = normalizeBearing(enemy.heading - enemy.lastHeading);
        double turnRate = headingChange; // degrees per tick
        
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
        // Calculate average heading from recent history
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
     * FIXED: Calculate optimal bullet power with energy management
     */
    private double calculateBulletPower(double distance, double enemyEnergy) {
        // Don't fire if we're low on energy
        if (getEnergy() < 15) {
            return 1.0;
        }
        
        // Close range - high power (but check energy)
        if (distance < 100) {
            return Math.min(3.0, getEnergy() / 10);
        }
        // Medium range - adaptive
        else if (distance < 300) {
            double power = Math.min(2.5, enemyEnergy / 4);
            return Math.min(power, getEnergy() / 8);
        }
        // Long range - conserve energy
        else if (distance < 500) {
            return 1.5;
        }
        // Very long range - minimum power
        else {
            return 1.0;
        }
    }
    
    /**
     * Calculate movement for next tick (non-blocking)
     */
    private void calculateMovement(EnemyData enemy) {
        // Calculate angle to enemy
        double enemyAngle = Math.atan2(enemy.x - getX(), enemy.y - getY());
        double enemyDistance = enemy.distance;
        
        // FIXED: Wall avoidance - stay away from walls
        double wallDanger = 0;
        double margin = 50;
        
        if (getX() < margin) wallDanger -= (margin - getX()) / margin;
        if (getX() > getBattleFieldWidth() - margin) wallDanger += (getX() - (getBattleFieldWidth() - margin)) / margin;
        
        double yWallDanger = 0;
        if (getY() < margin) yWallDanger -= (margin - getY()) / margin;
        if (getY() > getBattleFieldHeight() - margin) yWallDanger += (getY() - (getBattleFieldHeight() - margin)) / margin;
        
        // Combine forces for movement angle
        double baseAngle = enemyAngle + Math.PI; // Move away from enemy
        baseAngle += (Math.PI / 2) * moveDirection; // Perpendicular movement
        
        // Add wall avoidance
        if (Math.abs(wallDanger) > 0.3 || Math.abs(yWallDanger) > 0.3) {
            // Strong wall repulsion
            baseAngle += Math.atan2(wallDanger, yWallDanger);
        }
        
        // Add small randomness
        baseAngle += (Math.random() - 0.5) * 0.3;
        
        // Maintain optimal distance (150-350 pixels)
        double targetDistance = 250;
        if (enemyDistance < 150) {
            // Too close, move away
            baseAngle = enemyAngle + Math.PI;
        } else if (enemyDistance > 350) {
            // Too far, move closer
            baseAngle = enemyAngle;
        }
        
        // Store for execution
        moveAngle = Math.toDegrees(baseAngle);
        moveDistance = 30; // Variable distance for less predictability
        
        // Random direction changes
        if (Math.random() < 0.08) {
            moveDirection *= -1;
        }
    }
    
    /**
     * FIXED: Execute movement (non-blocking)
     */
    private void executeMovement() {
        double turn = normalizeBearing(moveAngle - getHeading());
        
        // If we need to turn more than 90 degrees, reverse
        if (Math.abs(turn) > 90) {
            turn = normalizeBearing(turn + 180);
            turnRight(turn);
            back(moveDistance);
        } else {
            turnRight(turn);
            ahead(moveDistance);
        }
    }
    
    /**
     * Handle being hit by bullet
     */
    public void onHitByBullet(HitByBulletEvent e) {
        // Immediate direction change
        moveDirection *= -1;
    }
    
    /**
     * FIXED: Handle hitting wall - more aggressive avoidance
     */
    public void onHitWall(HitWallEvent e) {
        // Reverse and turn to escape
        moveDirection *= -1;
        back(50);
        
        // Turn away from wall
        double bearing = e.getBearing();
        turnRight(normalizeBearing(bearing + 90));
    }
    
    /**
     * Handle colliding with robot
     */
    public void onHitRobot(HitRobotEvent e) {
        // Ram weak enemies, flee from strong ones
        if (e.getEnergy() < 20 && getEnergy() > 30) {
            fire(3.0);
            ahead(50);
        } else {
            back(50);
            moveDirection *= -1;
        }
    }
    
    /**
     * FIXED: Handle enemy death - track multiple enemies
     */
    public void onRobotDeath(RobotDeathEvent e) {
        enemies.remove(e.getName());
        
        if (e.getName().equals(currentTarget)) {
            // Find next target
            currentTarget = null;
            if (!enemies.isEmpty()) {
                // Switch to nearest remaining enemy
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
     * Normalize bearing to [-180, 180] degrees
     */
    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
