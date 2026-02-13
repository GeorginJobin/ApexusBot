package gj.eh;
import robocode.*;
import java.util.HashMap;
import java.awt.Color;

public class ChutluV10V3 extends Robot {

    // Edge detection/before hits the edge and activates on wall hit
    private final double MARGIN = 18;

    // Enemy tracking changed hash to make sure its java 6 just in case
    private HashMap<String, EnemyData> enemies = new HashMap<String, EnemyData>();
    private String currentTarget = null;
    private String lastTarget = null;
    private long lastRadarSweepTime = 0;

    // Movement control
    private int moveDirection = 1;
    private long lastMoveTime = 0;
    private static final int MOVE_COOLDOWN = 5;

    // ✅ Targeting history (YOU WERE MISSING THESE)
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
        double lastEnergy;
        double heading;
        double velocity;
        long time;
        long lastSeen;
        double x;
        double y;
        double lastHeading;
        double lastX;
        double lastY;

        EnemyData(ScannedRobotEvent e, long currentTime, Robot bot) {
            update(e, currentTime, bot, true);
        }

        void update(ScannedRobotEvent e, long currentTime, Robot bot, boolean isNew) {
            if (!isNew) {
                this.lastHeading = this.heading;
                this.lastX = this.x;
                this.lastY = this.y;
                this.lastEnergy = this.energy;
            }

            this.bearing = e.getBearing();
            this.distance = e.getDistance();
            this.energy = e.getEnergy();
            this.heading = e.getHeading();
            this.velocity = e.getVelocity();
            this.time = currentTime;
            this.lastSeen = currentTime;

            double absoluteBearing = Math.toRadians(bot.getHeading()) + e.getBearingRadians();
            this.x = bot.getX() + Math.sin(absoluteBearing) * e.getDistance();
            this.y = bot.getY() + Math.cos(absoluteBearing) * e.getDistance();

            if (isNew) {
                this.lastHeading = this.heading;
                this.lastX = this.x;
                this.lastY = this.y;
                this.lastEnergy = this.energy;
            }
        }
    }

    /**
     * Main run method - radar system & movement
     */
    public void run() {

        // Set colors
        setBodyColor(new Color(50, 50, 50));
        setGunColor(new Color(255, 0, 0));
        setRadarColor(new Color(0, 255, 0));
        setBulletColor(new Color(255, 255, 0));

        goToWall();

        // Independent movement (keeping because it was in your original)
        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        // game loop
        while (true) {

            if (currentTarget != null && enemies.containsKey(currentTarget)) {
                keepRadarOnTarget(enemies.get(currentTarget));
            }

            if (currentTarget != null && enemies.containsKey(currentTarget)) {
                executeSmoothMovement(enemies.get(currentTarget));
            } else {
                turnRight(20);
                ahead(80);
            }

            if (getOthers() > 1 && getTime() - lastRadarSweepTime > 40) {
                lastRadarSweepTime = getTime();
                turnRadarRight(360);
            } else if (currentTarget == null) {
                turnRadarRight(360);
            }
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
        while (angle > 180) angle = angle - 360;
        while (angle < -180) angle = angle + 360;
        return angle;
    }

    /**
     * Event handler
     */
    public void onScannedRobot(ScannedRobotEvent e) {
        String enemyName = e.getName();

        EnemyData enemy;
        if (enemies.containsKey(enemyName)) {
            enemy = enemies.get(enemyName);
            enemy.update(e, getTime(), this, false);
        } else {
            enemy = new EnemyData(e, getTime(), this);
            enemies.put(enemyName, enemy);
        }

        if (shouldSwitchTarget(enemyName, enemy)) {
            if (lastTarget != null && !lastTarget.equals(enemyName)) {
                resetPatternHistory();
            }
            currentTarget = enemyName;
        }

        lastTarget = enemyName;

        if (enemyName.equals(currentTarget)) {
            updateRadarLock(e);
        }

        // Energy drop detection (enemy fired)
        double energyDrop = enemy.lastEnergy - enemy.energy;
        if (enemyName.equals(currentTarget)) {
            if (energyDrop > 0.1 && energyDrop <= 3.0) {
                moveDirection *= -1;
                if (moveDirection > 0) ahead(30);
                else back(30);
            }
        }

        // ✅ Store movement history (YOU WERE MISSING THIS TOO)
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        enemyXHistory[historyIndex] = enemy.x;
        enemyYHistory[historyIndex] = enemy.y;
        historyIndex = (historyIndex + 1) % 10;

        // Fire only at target
        if (enemyName.equals(currentTarget)) {
            fireControlSystem(enemy);
        }
    }

    private void fireControlSystem(EnemyData enemy) {

        double firePower = calculateBulletPower(enemy.distance, enemy.energy);

        double predictedAngle = predictEnemyPosition(enemy, firePower);
        double predictedDegrees = Math.toDegrees(predictedAngle);

        double gunTurn = normalizeBearing(predictedDegrees - getGunHeading());
        turnGunRight(gunTurn);

        double remaining = normalizeBearing(predictedDegrees - getGunHeading());

        if (getGunHeat() == 0 && enemy.energy > 0 && getEnergy() > firePower) {
            double aimAllowance;
            if (enemy.distance > 500) aimAllowance = 4;
            else if (enemy.distance > 300) aimAllowance = 6;
            else aimAllowance = 8;

            if (Math.abs(remaining) < aimAllowance) {
                fire(firePower);
            }
        }
    }

    // ✅ YOU WERE MISSING THIS METHOD
    private void resetPatternHistory() {
        for (int i = 0; i < enemyHeadingHistory.length; i++) {
            enemyHeadingHistory[i] = 0;
            enemyVelocityHistory[i] = 0;
            enemyXHistory[i] = 0;
            enemyYHistory[i] = 0;
        }
        historyIndex = 0;
    }

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
        } else if (enemyIsWallHugger) {
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
        } else if (enemyDistance < 150) {
            moveAngle = Math.toDegrees(enemyAngle) + 180 + (45 * moveDirection);
            moveDistance = 120;
        } else if (enemyDistance > 400) {
            moveAngle = Math.toDegrees(enemyAngle) + (30 * moveDirection);
            moveDistance = 100;
        } else {
            moveAngle = Math.toDegrees(enemyAngle) + (90 * moveDirection);
            moveDistance = 80;
        }

        if (!enemyIsWallHugger) moveAngle += (Math.random() - 0.5) * 40;
        else moveAngle += (Math.random() - 0.5) * 20;

        moveDistance = Math.min(moveDistance, 60);

        double turn = normalizeBearing(moveAngle - getHeading());

        if (Math.abs(turn) > 90) {
            turnRight(normalizeBearing(turn + 180));
            back(moveDistance);
        } else {
            turnRight(turn);
            ahead(moveDistance);
        }

        if (Math.random() < 0.15) moveDirection *= -1;
    }

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
            double turnChange = Math.abs(normalizeBearing(enemyHeadingHistory[i] - enemyHeadingHistory[i-1]));
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
        predictedX = clamp(predictedX, WALL_MARGIN, getBattleFieldWidth() - WALL_MARGIN);
        predictedY = clamp(predictedY, WALL_MARGIN, getBattleFieldHeight() - WALL_MARGIN);
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    private double predictLinearWithLead(EnemyData enemy, double bulletSpeed, long time) {
        long adjustedTime = (long)(time * 1.4);
        double predictedX = enemy.x + Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * adjustedTime;
        double predictedY = enemy.y + Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * adjustedTime;
        predictedX = clamp(predictedX, WALL_MARGIN, getBattleFieldWidth() - WALL_MARGIN);
        predictedY = clamp(predictedY, WALL_MARGIN, getBattleFieldHeight() - WALL_MARGIN);
        return Math.atan2(predictedX - getX(), predictedY - getY());
    }

    private double predictCircular(EnemyData enemy, double bulletSpeed, long time) {
        double turnRate = 0;
        int samples = 0;

        for (int i = 1; i < Math.min(historyIndex, 5); i++) {
            double change = normalizeBearing(enemyHeadingHistory[i] - enemyHeadingHistory[i-1]);
            turnRate += change;
            samples++;
        }

        if (samples > 0) turnRate /= samples;

        double predictedHeading = enemy.heading + turnRate * time;
        double predictedX = enemy.x + Math.sin(Math.toRadians(predictedHeading)) * enemy.velocity * time;
        double predictedY = enemy.y + Math.cos(Math.toRadians(predictedHeading)) * enemy.velocity * time;
        predictedX = clamp(predictedX, WALL_MARGIN, getBattleFieldWidth() - WALL_MARGIN);
        predictedY = clamp(predictedY, WALL_MARGIN, getBattleFieldHeight() - WALL_MARGIN);

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

    private void updateRadarLock(ScannedRobotEvent e) {
        double absoluteBearing = getHeading() + e.getBearing();
        double radarTurn = normalizeBearing(absoluteBearing - getRadarHeading());
        if (getOthers() == 1) {
            turnRadarRight(radarTurn * 2);
        } else {
            double overshoot = (radarTurn < 0) ? -8 : 8;
            turnRadarRight(normalizeBearing(radarTurn + overshoot));
        }
    }

    private void keepRadarOnTarget(EnemyData enemy) {
        double absoluteBearing = Math.toDegrees(Math.atan2(enemy.x - getX(), enemy.y - getY()));
        double radarTurn = normalizeBearing(absoluteBearing - getRadarHeading());
        if (getOthers() == 1) {
            turnRadarRight(radarTurn * 2);
        } else {
            double overshoot = (radarTurn < 0) ? -6 : 6;
            turnRadarRight(normalizeBearing(radarTurn + overshoot));
        }
    }

    private boolean shouldSwitchTarget(String enemyName, EnemyData enemy) {
        if (currentTarget == null) return true;
        if (enemyName.equals(currentTarget)) return true;

        EnemyData current = enemies.get(currentTarget);
        if (current == null) return true;

        if (getTime() - current.time > 20) return true;
        if (enemy.distance + 80 < current.distance) return true;

        return false;
    }

    private double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
