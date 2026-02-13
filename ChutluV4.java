package gj.eh;

import robocode.*;

public class ChutluV4 extends Robot {

    private final double MARGIN = 18;

    private int cornerIndex = 0;
    private long dodgeUntil = 0;

    public void run() {

        while (true) {
            double width = getBattleFieldWidth();
            double height = getBattleFieldHeight();

            double[][] corners = {
                {MARGIN, MARGIN},                 // Bottom Left
                {width - MARGIN, height - MARGIN},// Top Right
                {MARGIN, height - MARGIN},        // Top Left
                {width - MARGIN, MARGIN}          // Bottom Right
            };

            // If we're currently dodging, do the dodge motion instead of pathing
            if (getTime() < dodgeUntil) {
                // Keep moving unpredictably for a short moment
                turnRight(30);
                ahead(80);
                turnGunRight(60);
                continue;
            }

            // Go to current corner target
            goTo(corners[cornerIndex][0], corners[cornerIndex][1]);

            // Next corner in X pattern
            cornerIndex = (cornerIndex + 1) % 4;
        }
    }

    private void goTo(double x, double y) {
        double dx = x - getX();
        double dy = y - getY();

        double angleToTarget = Math.toDegrees(Math.atan2(dx, dy));
        double turnAngle = normalRelativeAngle(angleToTarget - getHeading());
        turnRight(turnAngle);

        double distance = Math.sqrt(dx * dx + dy * dy);

        // Move in chunks so scans can interrupt and trigger dodges
        while (distance > 60) {
            ahead(120);
            turnGunRight(45); // scan while moving

            dx = x - getX();
            dy = y - getY();
            distance = Math.sqrt(dx * dx + dy * dy);
        }

        ahead(distance);
    }

    private double normalRelativeAngle(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    public void onScannedRobot(ScannedRobotEvent e) {

        // Shoot a little
        fire(1);

        // If an enemy is in our way / close, dodge and then switch target corner
        // (Close threshold works for both 1v1 and melee)
        if (e.getDistance() < 220) {

            // Dodge: turn away from direct line and move
            // If enemy is on our right, dodge left; if on left, dodge right
            if (e.getBearing() >= 0) {
                turnLeft(60);
            } else {
                turnRight(60);
            }

            // Back up if very close; otherwise sidestep forward
            if (e.getDistance() < 120) {
                back(120);
            } else {
                ahead(140);
            }

            // After dodge, pick a different corner to "rejoin" path
            cornerIndex = (cornerIndex + 1) % 4;

            // Stay in dodge mode briefly so we don't instantly re-engage
            dodgeUntil = getTime() + 15;
        }
    }

    public void onHitWall(HitWallEvent e) {
        back(60);
        turnRight(90);
        // Also change corner so we don't keep pushing into the wall
        cornerIndex = (cornerIndex + 1) % 4;
    }

    public void onHitRobot(HitRobotEvent e) {
        // If we collide, break off immediately and change corner
        back(80);
        if (e.getBearing() >= 0) turnLeft(45); else turnRight(45);
        cornerIndex = (cornerIndex + 1) % 4;
        dodgeUntil = getTime() + 15;
        fire(2);
    }
}
