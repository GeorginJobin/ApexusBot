package gj.eh;
import robocode.*;

public class ChutluV2 extends Robot
{
    private static final double WALL_MARGIN = 60;
    private static final double STEP = 40;
    private static final double CURVE = 8;

    public void run() {

        while(true) {

            // Bottom Right
            goTo(getBattleFieldWidth(), 0);

            // Top Left
            goTo(0, getBattleFieldHeight());

            // Bottom Left
            goTo(0, 0);

            // Top Right
            goTo(getBattleFieldWidth(), getBattleFieldHeight());
        }
    }

    private void goTo(double targetX, double targetY) {

        while (distanceTo(targetX, targetY) > 60) {

            double dx = targetX - getX();
            double dy = targetY - getY();

            double angleToTarget = Math.toDegrees(Math.atan2(dx, dy));
            double turn = normalize(angleToTarget - getHeading());

            turnRight(turn);

            smoothWalls();

            ahead(STEP);

            // Curve movement to avoid sharp X pattern
            turnRight(CURVE);

            turnGunRight(360); // keep scanning
        }
    }

    private void smoothWalls() {

        double x = getX();
        double y = getY();
        double width = getBattleFieldWidth();
        double height = getBattleFieldHeight();

        if (x < WALL_MARGIN) {
            turnRight(20);
        }
        else if (x > width - WALL_MARGIN) {
            turnLeft(20);
        }

        if (y < WALL_MARGIN) {
            turnRight(20);
        }
        else if (y > height - WALL_MARGIN) {
            turnLeft(20);
        }
    }

    private double distanceTo(double x, double y) {
        return Math.hypot(x - getX(), y - getY());
    }

    private double normalize(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    public void onScannedRobot(ScannedRobotEvent e) {
        fire(1);
    }

    public void onHitByBullet(HitByBulletEvent e) {
        back(10);
    }

    public void onHitWall(HitWallEvent e) {
        back(20);
    }
}
