package gj.eh;

import robocode.*;
import java.awt.Color;
import java.util.HashMap;

public class ChutluV5 extends Robot {

    private HashMap<String, EnemyData> enemies = new HashMap<>();
    private String currentTarget = null;

    // DVD movement
    private static final double DVD_MARGIN = 35;
    private static final double DVD_STEP = 200;
    private int dirX = 1;
    private int dirY = 1;

    // Target history
    private double[] enemyHeadingHistory = new double[10];
    private double[] enemyVelocityHistory = new double[10];
    private int historyIndex = 0;

    class EnemyData {
        double distance;
        double energy;
        double heading;
        double velocity;
        double x;
        double y;

        EnemyData(ScannedRobotEvent e) {
            distance = e.getDistance();
            energy = e.getEnergy();
            heading = e.getHeading();
            velocity = e.getVelocity();

            double absBearing = Math.toRadians(getHeading()) + e.getBearingRadians();
            x = getX() + Math.sin(absBearing) * distance;
            y = getY() + Math.cos(absBearing) * distance;
        }
    }

    public void run() {

        setBodyColor(new Color(50,50,50));
        setGunColor(Color.RED);
        setRadarColor(Color.GREEN);
        setBulletColor(Color.YELLOW);

        setDiagonalHeading();

        while (true) {

            // Radar sweep
            if (currentTarget == null)
                turnRadarRight(60);
            else
                turnRadarRight(25);

            // DVD bounce movement
            bounceIfNeeded();
            setDiagonalHeading();
            ahead(DVD_STEP);
        }
    }

    public void onScannedRobot(ScannedRobotEvent e) {

        EnemyData enemy = new EnemyData(e);
        enemies.put(e.getName(), enemy);
        currentTarget = e.getName();

        // Radar lock
        double radarTurn = getHeading() + e.getBearing() - getRadarHeading();
        if (radarTurn < 0) radarTurn -= 5;
        else radarTurn += 5;
        turnRadarRight(normalizeBearing(radarTurn));

        // Store movement history
        enemyHeadingHistory[historyIndex] = e.getHeading();
        enemyVelocityHistory[historyIndex] = e.getVelocity();
        historyIndex = (historyIndex + 1) % 10;

        fireControlSystem(enemy);
    }

    private void fireControlSystem(EnemyData enemy) {

        double firePower = calculateBulletPower(enemy.distance);

        double predictedAngle = predictEnemyPosition(enemy, firePower);
        double predictedDeg = Math.toDegrees(predictedAngle);

        // Turn gun
        double gunTurn = normalizeBearing(predictedDeg - getGunHeading());
        turnGunRight(gunTurn);

        // Check alignment AFTER turning
        double remaining = normalizeBearing(predictedDeg - getGunHeading());

        if (getGunHeat() == 0 && getEnergy() > firePower) {
            if (Math.abs(remaining) < 12) {
                fire(firePower);
            }
        }
    }

    private double predictEnemyPosition(EnemyData enemy, double bulletPower) {

        double bulletSpeed = 20 - 3 * bulletPower;
        long time = (long)(enemy.distance / bulletSpeed);

        double futureX = enemy.x + 
                Math.sin(Math.toRadians(enemy.heading)) * enemy.velocity * time;

        double futureY = enemy.y + 
                Math.cos(Math.toRadians(enemy.heading)) * enemy.velocity * time;

        return Math.atan2(futureX - getX(), futureY - getY());
    }

    private double calculateBulletPower(double distance) {
        if (distance < 200) return 3.0;
        if (distance < 400) return 2.0;
        return 1.5;
    }

    private void bounceIfNeeded() {

        double x = getX();
        double y = getY();
        double w = getBattleFieldWidth();
        double h = getBattleFieldHeight();

        if (x <= DVD_MARGIN) dirX = 1;
        else if (x >= w - DVD_MARGIN) dirX = -1;

        if (y <= DVD_MARGIN) dirY = 1;
        else if (y >= h - DVD_MARGIN) dirY = -1;
    }

    private void setDiagonalHeading() {

        double target;

        if (dirX > 0 && dirY > 0) target = 45;
        else if (dirX > 0 && dirY < 0) target = 135;
        else if (dirX < 0 && dirY < 0) target = 225;
        else target = 315;

        turnRight(normalizeBearing(target - getHeading()));
    }

    public void onHitWall(HitWallEvent e) {
        dirX *= -1;
        dirY *= -1;
        setDiagonalHeading();
        ahead(150);
    }

    private double normalizeBearing(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
