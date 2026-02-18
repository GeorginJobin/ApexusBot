package MyBots;

import robocode.*;
import java.awt.Color;
import java.util.HashMap;

/**
 * ApexV3 - Fixed micro-movement and improved spinbot counter
 * 
 * Key fixes:
 * - Movement only when actually needed (not every scan)
 * - Larger, smoother movements
 * - Better spinbot prediction
 * - Proper dodging with direction changes
 */
public class ApexV3 extends Robot {
    
    // Enemy tracking
    private HashMap<String, EnemyData> enemies = new HashMap<>();
    private String currentTarget = null;
    private String lastTarget = null;
    
    // Movement control - PREVENT MICRO-MOVEMENTS
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100.0;
    private long lastMoveTime = 0;
    private static final int MOVE_COOLDOWN = 5; // Only move every 5 ticks
    
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
        
        // Main loop - wider sweeps for initial acquisition
        while (true) {
            if (currentTarget == null) {
                turnRadarRight(360);
            } else {
                // When we have a target, just keep the radar moving
                // The lock happens in onScannedRobot
                turnRadarRight(45);
            }
        }
    }
    
    /**
     * Event handler - targeting and controlled movement
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        String enemyName = e.getName();
        
        // Check if we switched targets
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
        // Narrow lock with overshoot
        if (radarTurn < 0) {
            radarTurn -= 10;
        } else {
            radarTurn += 10;
        }
        turnRadarRight(normalizeBearing(radarTurn));
        
        // Store movement history
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        enemyXHistory[historyIndex] = enemy.x;
        enemyYHistory[historyIndex] = enemy.y;
        historyIndex = (historyIndex + 1) % 10;
        
        // === TARGETING ===
        double firePower = calculateBulletPower(e.getDistance(), e.getEnergy());
        double predictedAngle = predictEnemyPosition(enemy, firePower);
        
        // Aim gun
        double gunTurn = normalizeBearing(Math.toDegrees(predictedAngle) - getGunHeading());
        turnGunRight(gunTurn);
        
        // Fire when aligned
        if (Math.abs(gunTurn) < 10 && getGunHeat() == 0 && e.getEnergy() > 0 && getEnergy() > firePower) {
            fire(firePower);
        }
        
        // === BULLET DETECTION AND DODGING ===
        if (energyDrop >= 0.1 && energyDrop <= 3.0) {
            // Enemy fired! IMMEDIATE evasive action
            moveDirection *= -1;
            // Execute dodge NOW
            double dodgeAngle = e.getBearing() + 90 * moveDirection;
            turnRight(normalizeBearing(dodgeAngle));
            ahead(100);
            lastMoveTime = getTime(); // Reset cooldown after dodge
        }
        
        // === CONTROLLED MOVEMENT (not every tick!) ===
        // Only move every MOVE_COOLDOWN ticks to avoid micro-movements
        if (getTime() - lastMoveTime >= MOVE_COOLDOWN) {
            executeSmoothMovement(enemy);
            lastMoveTime = getTime();
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
     * SMOOTH movement - larger movements, less frequent
     */
    private void executeSmoothMovement(EnemyData enemy) {
        // Calculate angle to enemy
        double enemyAngle = Math.atan2(enemy.x - getX(), enemy.y - getY());
        double enemyDistance = enemy.distance;
        
        // Check wall proximity
        boolean nearWall = getX() < WALL_MARGIN || 
                          getX() > getBattleFieldWidth() - WALL_MARGIN ||
                          getY() < WALL_MARGIN || 
                          getY() > getBattleFieldHeight() - WALL_MARGIN;
        
        double moveAngle;
        double moveDistance;
        
        // PRIORITY 1: Wall avoidance
        if (nearWall) {
            double centerX = getBattleFieldWidth() / 2;
            double centerY = getBattleFieldHeight() / 2;
            moveAngle = Math.toDegrees(Math.atan2(centerX - getX(), centerY - getY()));
            moveDistance = 100; // Strong wall escape
        }
        // PRIORITY 2: Distance management
        else if (enemyDistance < 150) {
            // Too close - move away
            moveAngle = Math.toDegrees(enemyAngle) + 180 + (45 * moveDirection);
            moveDistance = 120;
        } else if (enemyDistance > 400) {
            // Too far - move closer at angle
            moveAngle = Math.toDegrees(enemyAngle) + (30 * moveDirection);
            moveDistance = 100;
        }
        // PRIORITY 3: Perpendicular strafing (optimal distance)
        else {
            moveAngle = Math.toDegrees(enemyAngle) + (90 * moveDirection);
            moveDistance = 80;
        }
        
        // Add randomness to avoid patterns
        moveAngle += (Math.random() - 0.5) * 40; // More randomness
        
        // Execute turn and movement
        double turn = normalizeBearing(moveAngle - getHeading());
        
        if (Math.abs(turn) > 90) {
            // Reverse if turn is too sharp
            turnRight(normalizeBearing(turn + 180));
            back(moveDistance);
        } else {
            turnRight(turn);
            ahead(moveDistance);
        }
        
        // Random direction changes (less frequent)
        if (Math.random() < 0.15) {
            moveDirection *= -1;
        }
    }
    
    /**
     * Enhanced prediction for spinbots
     */
    private double predictEnemyPosition(EnemyData enemy, double bulletPower) {
        double bulletSpeed = 20 - 3 * bulletPower;
        long time = (long)(enemy.distance / bulletSpeed);
        
        // Detect if enemy is spinning (high turn rate, consistent velocity)
        boolean isSpinning = detectSpinbot();
        
        if (isSpinning) {
            // Use circular prediction for spinbots
            return predictCircular(enemy, bulletSpeed, time);
        } else {
            // Use weighted prediction for other bots
            double avgVelocity = 0;
            for (double vel : enemyVelocityHistory) {
                avgVelocity += Math.abs(vel);
            }
            avgVelocity /= enemyVelocityHistory.length;
            
            if (avgVelocity > 7.0) {
                // Fast mover
                return predictCircular(enemy, bulletSpeed, time) * 0.7 + 
                       predictLinear(enemy, bulletSpeed, time) * 0.3;
            } else if (avgVelocity < 1.0) {
                // Stationary or very slow
                return predictLinear(enemy, bulletSpeed, time);
            } else {
                // Normal movement
                return predictLinear(enemy, bulletSpeed, time) * 0.5 + 
                       predictCircular(enemy, bulletSpeed, time) * 0.5;
            }
        }
    }
    
    /**
     * Detect if enemy is a spinbot
     */
    private boolean detectSpinbot() {
        if (historyIndex < 5) return false; // Not enough data
        
        // Check for consistent high velocity and turn rate
        double avgVelocity = 0;
        double avgTurnRate = 0;
        int count = 0;
        
        for (int i = 1; i < Math.min(historyIndex, 8); i++) {
            avgVelocity += Math.abs(enemyVelocityHistory[i]);
            double turnChange = Math.abs(normalizeBearing(
                enemyHeadingHistory[i] - enemyHeadingHistory[i-1]));
            avgTurnRate += turnChange;
            count++;
        }
        
        if (count > 0) {
            avgVelocity /= count;
            avgTurnRate /= count;
        }
        
        // Spinbot characteristics: high speed (>7) and consistent turning (>5 degrees per tick)
        return avgVelocity > 7.0 && avgTurnRate > 5.0;
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
     * Circular prediction with better turn rate calculation
     */
    private double predictCircular(EnemyData enemy, double bulletSpeed, long time) {
        // Calculate turn rate from recent history
        double turnRate = 0;
        int samples = 0;
        
        for (int i = 1; i < Math.min(historyIndex, 5); i++) {
            double change = normalizeBearing(enemyHeadingHistory[i] - enemyHeadingHistory[i-1]);
            turnRate += change;
            samples++;
        }
        
        if (samples > 0) {
            turnRate /= samples;
        }
        
        // Project future heading with turn rate
        double predictedHeading = enemy.heading + turnRate * time;
        double predictedX = enemy.x + Math.sin(Math.toRadians(predictedHeading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(predictedHeading)) * enemy.velocity * time;
        
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }
    
    /**
     * Calculate bullet power with energy management
     */
    private double calculateBulletPower(double distance, double enemyEnergy) {
        // Low energy conservation
        if (getEnergy() < 20) {
            return 1.0;
        }
        
        // Distance-based
        if (distance < 150) {
            return Math.min(3.0, getEnergy() / 10);
        } else if (distance < 300) {
            return 2.0;
        } else if (distance < 500) {
            return 1.5;
        } else {
            return 1.0;
        }
    }
    
    /**
     * Handle being hit
     */
    public void onHitByBullet(HitByBulletEvent e) {
        // Immediate dodge
        moveDirection *= -1;
        double dodgeAngle = e.getBearing() + 90 * moveDirection;
        turnRight(normalizeBearing(dodgeAngle));
        ahead(100);
    }
    
    /**
     * Handle wall collision
     */
    public void onHitWall(HitWallEvent e) {
        moveDirection *= -1;
        back(100);
        turnRight(90 * moveDirection);
    }
    
    /**
     * Handle robot collision
     */
    public void onHitRobot(HitRobotEvent e) {
        if (e.getEnergy() < 20 && getEnergy() > 30) {
            fire(3.0);
            ahead(50);
        } else {
            back(100);
            moveDirection *= -1;
        }
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
     * Normalize bearing
     */
    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
