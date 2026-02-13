package gj.eh;

import robocode.*;
import java.util.HashMap;
import java.awt.Color;


public class ChutluV9 extends Robot {
	
	//Edge detection/before hits the edge and activates on wall hit
	private final double MARGIN = 18;
	private static final double STEP = 120;
	private static final double ARRIVE = 25;
   
	// Enemy tracking
    private HashMap<String, EnemyData> enemies = new HashMap<>();
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
     * Main run method - V3's PROVEN radar system & main movement
    */
	//The brain/loop of the bot 
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
		
		//game loop
		while (true) {
           
			//Battlefield height and width in a var
			double width = getBattleFieldWidth();	
			double height = getBattleFieldHeight();
			
			if (currentTarget == null) {
			// Bottom Left
            goTo(MARGIN, MARGIN);

            // Top Right (diagonal across map)
            goTo(width - MARGIN, height - MARGIN);

            // Top Left
            goTo(MARGIN, height - MARGIN);

            // Bottom Right (diagonal across map)
            goTo(width - MARGIN, MARGIN);
			} else {
				strafeCurrentTarget();
			}
        }
    }
	
	private void goTo(double x, double y) {
		
		while (distanceTo(x,y) > ARRIVE) {
			//DX and DY are the legs of a right triangle getX/Y gets current position of bot
			double dx = x - getX();	
			double dy = y - getY();
			
			//Uses trignometry to find the angle from the current position of the bot to the target returns in radians then gets converted into degrees
			double angleToTarget = Math.toDegrees(Math.atan2(dx, dy));
			//Calculates the difference where the bot is facing and where it wants to go
			double turnAngle = normalRelativeAngle(angleToTarget - getHeading());
			
			//Turns the amount that turnAngle calculated)
			turnRight(turnAngle);
			
			//keep radar scnaning while travelling
			if (currentTarget == null) {
				turnRadarRight(60);
			} else {
				turnRadarRight(20);
			}
		
			double distance = distanceTo(x, y);

			//The bot stops driving the distance when it reaches
			ahead(Math.min(STEP, distance));
		
		}

	}
	
	private void strafeCurrentTarget() {
	
		turnRight(35* moveDirection);
		ahead(120);
		
		if (Math.random() < 0.12) {
		
			moveDirection = moveDirection * -1;
		
		}
		
		if (currentTarget == null) {
		
			turnRadarRight(60);	

		} else {
		
			turnRadarRight(20);
			
		}
	
	}
	
	private double normalRelativeAngle(double angle) {

		while (angle > 180) angle = angle - 360;
		while (angle < -180) angle = angle + 360;
		return angle;

	}
	
	private double distanceTo(double x, double y) {
	
		double dx = x - getX();
		double dy = y - getY();
		return Math.sqrt(dx * dx + dy * dy);
		
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
		if (currentTarget == null) {
		
			currentTarget = enemyName;
			
		} else {
		
		    EnemyData cur = enemies.get(currentTarget);
			
		if (cur == null || enemy.distance < cur.distance) {
		
		        currentTarget = enemyName;
		}
		
	}

        
        // Track enemy energy
        double energyDrop = lastEnemyEnergy - e.getEnergy();
        lastEnemyEnergy = e.getEnergy();
        
        // === V3's PROVEN RADAR LOCK (but tighter) ===
        double radarTurn = getHeading() + e.getBearing() - getRadarHeading();
        // Tighter lock - 5° instead of 10°
        if (radarTurn < 0) {
            radarTurn -= 5;
        } else {
            radarTurn += 5;
        }
        turnRadarRight(normalizeBearing(radarTurn));
        
        // Store movement history
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        enemyXHistory[historyIndex] = enemy.x;
        enemyYHistory[historyIndex] = enemy.y;
        historyIndex = (historyIndex + 1) % 10;
        
        // === V4's IMPROVED FIRE CONTROL ===
        fireControlSystem(enemy);
        
        // === BULLET DETECTION ===
        if (energyDrop >= 0.1 && energyDrop <= 3.0) {
            moveDirection *= -1;
            // Immediate dodge
            double dodgeAngle = e.getBearing() + 90 * moveDirection;
            turnRight(normalizeBearing(dodgeAngle));
            ahead(60);
            lastMoveTime = getTime();
        }
        
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
        if (getGunHeat() == 0 && enemy.energy > 0 && getEnergy() > firePower) {
            // Relaxed alignment for higher fire rate
            if (Math.abs(remaining) < 18) { // Even more aggressive than V4's 20°
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
     * V3's smooth movement
     */
	/*
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
	*/
    
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
        // Extra lead for wall huggers
        long adjustedTime = (long)(time * 1.4); // Increased from 1.3
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


}
