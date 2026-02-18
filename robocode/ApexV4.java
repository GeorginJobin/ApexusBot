package MyBots;

import robocode.*;
import java.awt.Color;
import java.util.HashMap;

/**
 * ApexV4 - Complete fire control overhaul
 * 
 * NEW FIRE CONTROL SYSTEM:
 * - Infinity lock radar (IRST for intermediate bots)
 * - Continuous gun tracking
 * - Instant fire when aligned
 * - Proper gun direction calculation
 * - High DPS optimization
 */
public class ApexV4 extends Robot {
    
    // Enemy tracking
    private HashMap<String, EnemyData> enemies = new HashMap<>();
    private String currentTarget = null;
    private String lastTarget = null;
    
    // Movement control
    private int moveDirection = 1;
    private double lastEnemyEnergy = 100.0;
    private long lastMoveTime = 0;
    private static final int MOVE_COOLDOWN = 5;
    
    // FIRE CONTROL - track last known enemy position
    private double enemyAbsoluteBearing = 0;
    private double lastEnemyDistance = 0;
    
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
     * Main run method
     */
    public void run() {
        // Set colors
        setBodyColor(new Color(50, 50, 50));
        setGunColor(new Color(255, 0, 0));
        setRadarColor(new Color(0, 255, 0));
        setBulletColor(new Color(255, 255, 0));
        
        // Independent movement - CRITICAL for fire control
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);
        
        // Main loop - INFINITY LOCK RADAR
        while (true) {
            if (currentTarget == null) {
                // Wide sweep to find target
                turnRadarRight(45);
            } else {
                // INFINITY LOCK: sweep back and forth over enemy
                // This is the intermediate-safe version of perfect tracking
                double radarTurn = enemyAbsoluteBearing - Math.toRadians(getRadarHeading());
                radarTurn = normalizeRadians(radarTurn);
                
                // Add small sweep to maintain lock
                if (radarTurn < 0) {
                    radarTurn -= 0.05; // Slight overshoot
                } else {
                    radarTurn += 0.05;
                }
                
                turnRadarRight(Math.toDegrees(radarTurn));
            }
        }
    }
    
    /**
     * Event handler - NEW FIRE CONTROL
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
        
        // === CRITICAL: Update absolute bearing for radar lock ===
        enemyAbsoluteBearing = Math.toRadians(getHeading()) + e.getBearingRadians();
        lastEnemyDistance = e.getDistance();
        
        // Track enemy energy
        double energyDrop = lastEnemyEnergy - e.getEnergy();
        lastEnemyEnergy = e.getEnergy();
        
        // Store movement history
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        enemyXHistory[historyIndex] = enemy.x;
        enemyYHistory[historyIndex] = enemy.y;
        historyIndex = (historyIndex + 1) % 10;
        
        // === NEW FIRE CONTROL SYSTEM ===
        fireControlSystem(enemy);
        
        // === BULLET DETECTION ===
        if (energyDrop >= 0.1 && energyDrop <= 3.0) {
            moveDirection *= -1;
            // Immediate dodge
            double dodgeAngle = e.getBearing() + 90 * moveDirection;
            turnRight(normalizeBearing(dodgeAngle));
            ahead(100);
            lastMoveTime = getTime();
        }
        
        // === MOVEMENT ===
        if (getTime() - lastMoveTime >= MOVE_COOLDOWN) {
            executeSmoothMovement(enemy);
            lastMoveTime = getTime();
        }
    }
    
    /**
     * NEW FIRE CONTROL SYSTEM - High DPS, instant response
     */
    private void fireControlSystem(EnemyData enemy) {
        // Calculate optimal bullet power
        double firePower = calculateBulletPower(enemy.distance, enemy.energy);
        
        // Predict enemy position
        double predictedAngle = predictEnemyPosition(enemy, firePower);
        
        // === CRITICAL: Proper gun aiming ===
        // Convert predicted angle (radians) to degrees
        double predictedDegrees = Math.toDegrees(predictedAngle);
        
        // Calculate gun turn needed (shortest path)
        double gunTurn = normalizeBearing(predictedDegrees - getGunHeading());
        
        // Turn gun
        turnGunRight(gunTurn);
        
        // === AGGRESSIVE FIRING ===
        // Fire if reasonably aligned (not perfect, but good enough for DPS)
        if (getGunHeat() == 0 && enemy.energy > 0 && getEnergy() > firePower) {
            // RELAXED alignment check for higher fire rate
            if (Math.abs(gunTurn) < 20) {
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
     * SMOOTH movement
     */
    private void executeSmoothMovement(EnemyData enemy) {
        double enemyAngle = Math.atan2(enemy.x - getX(), enemy.y - getY());
        double enemyDistance = enemy.distance;
        
        boolean nearWall = getX() < WALL_MARGIN || 
                          getX() > getBattleFieldWidth() - WALL_MARGIN ||
                          getY() < WALL_MARGIN || 
                          getY() > getBattleFieldHeight() - WALL_MARGIN;
        
        boolean enemyIsWallHugger = detectWallHugger();
        
        double moveAngle;
        double moveDistance;
        
        if (nearWall) {
            double centerX = getBattleFieldWidth() / 2;
            double centerY = getBattleFieldHeight() / 2;
            moveAngle = Math.toDegrees(Math.atan2(centerX - getX(), centerY - getY()));
            moveDistance = 100;
        }
        else if (enemyIsWallHugger) {
            // Stay centered against wall huggers
            double centerX = getBattleFieldWidth() / 2;
            double centerY = getBattleFieldHeight() / 2;
            double distToCenter = Math.hypot(getX() - centerX, getY() - centerY);
            
            if (distToCenter > 150) {
                moveAngle = Math.toDegrees(Math.atan2(centerX - getX(), centerY - getY()));
                moveDistance = 80;
            } else {
                moveAngle = Math.toDegrees(enemyAngle) + (45 * moveDirection);
                moveDistance = 60;
            }
        }
        else if (enemyDistance < 150) {
            moveAngle = Math.toDegrees(enemyAngle) + 180 + (45 * moveDirection);
            moveDistance = 120;
        } else if (enemyDistance > 400) {
            moveAngle = Math.toDegrees(enemyAngle) + (30 * moveDirection);
            moveDistance = 100;
        }
        else {
            moveAngle = Math.toDegrees(enemyAngle) + (90 * moveDirection);
            moveDistance = 80;
        }
        
        if (!enemyIsWallHugger) {
            moveAngle += (Math.random() - 0.5) * 40;
        } else {
            moveAngle += (Math.random() - 0.5) * 20;
        }
        
        double turn = normalizeBearing(moveAngle - getHeading());
        
        if (Math.abs(turn) > 90) {
            turnRight(normalizeBearing(turn + 180));
            back(moveDistance);
        } else {
            turnRight(turn);
            ahead(moveDistance);
        }
        
        if (Math.random() < 0.15) {
            moveDirection *= -1;
        }
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
            for (double vel : enemyVelocityHistory) {
                avgVelocity += Math.abs(vel);
            }
            avgVelocity /= enemyVelocityHistory.length;
            
            if (avgVelocity > 7.0) {
                return predictCircular(enemy, bulletSpeed, time) * 0.7 + 
                       predictLinear(enemy, bulletSpeed, time) * 0.3;
            } else if (avgVelocity < 1.0) {
                return predictLinear(enemy, bulletSpeed, time);
            } else {
                return predictLinear(enemy, bulletSpeed, time) * 0.5 + 
                       predictCircular(enemy, bulletSpeed, time) * 0.5;
            }
        }
    }
    
    /**
     * Detect spinbot
     */
    private boolean detectSpinbot() {
        if (historyIndex < 5) return false;
        
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
        
        return avgVelocity > 7.0 && avgTurnRate > 5.0;
    }
    
    /**
     * Detect wall hugger
     */
    private boolean detectWallHugger() {
        if (historyIndex < 5) return false;
        
        int nearWallCount = 0;
        double totalMovement = 0;
        
        for (int i = 0; i < Math.min(historyIndex, 8); i++) {
            double x = enemyXHistory[i];
            double y = enemyYHistory[i];
            
            if (x < 100 || x > getBattleFieldWidth() - 100 ||
                y < 100 || y > getBattleFieldHeight() - 100) {
                nearWallCount++;
            }
            
            totalMovement += Math.abs(enemyVelocityHistory[i]);
        }
        
        double wallPercentage = (double)nearWallCount / Math.min(historyIndex, 8);
        double avgMovement = totalMovement / Math.min(historyIndex, 8);
        
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
        // Extra lead for wall huggers - they move predictably
        long adjustedTime = (long)(time * 1.3); // Increased from 1.2 to 1.3
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
            double change = normalizeBearing(enemyHeadingHistory[i] - enemyHeadingHistory[i-1]);
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
     * Calculate bullet power - AGGRESSIVE for high DPS
     */
    private double calculateBulletPower(double distance, double enemyEnergy) {
        // Low energy conservation
        if (getEnergy() < 15) {
            return 1.0;
        }
        
        // AGGRESSIVE power levels for maximum damage
        if (distance < 200) {
            return 3.0; // Max damage at close range
        } else if (distance < 350) {
            return 2.5; // High damage at medium
        } else if (distance < 500) {
            return 2.0; // Medium damage
        } else {
            return 1.5; // Still decent at long range
        }
    }
    
    /**
     * Handle being hit
     */
    public void onHitByBullet(HitByBulletEvent e) {
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
     * Normalize bearing (degrees)
     */
    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
    
    /**
     * Normalize radians
     */
    private double normalizeRadians(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }
}
