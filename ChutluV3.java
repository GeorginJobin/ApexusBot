package gj.eh;

import robocode.Robot;

public class ChutluV3 extends Robot {

    private static final double MARGIN = 70;     // how close to wall to hug
    private static final double STEP = 20;       // smaller = smoother
    private static final double CORNER_OK = 35;  // how close counts as "at corner"

    public void run() {
        double w = getBattleFieldWidth();
        double h = getBattleFieldHeight();

        // Corner targets slightly inside the walls (so we can curve cleanly)
        double TLx = MARGIN,     TLy = h - MARGIN;
        double BLx = MARGIN,     BLy = MARGIN;
        double TRx = w - MARGIN, TRy = h - MARGIN;
        double BRx = w - MARGIN, BRy = MARGIN;

        // 1) Start by getting to Top-Left once (so the loop is consistent)
        goToPoint(TLx, TLy);

        while (true) {
            // 2) Hug left wall: TL -> BL
            hugVerticalWallToCorner(true, false, BLx, BLy);   // left wall, going down

            // 3) Curved cross: BL -> TR (curve + pull to center)
            crossCurvedToCorner(TRx, TRy, true);              // clockwise curve

            // 4) Hug right wall: TR -> BR
            hugVerticalWallToCorner(false, false, BRx, BRy);  // right wall, going down

            // 5) Curved cross back: BR -> TL
            crossCurvedToCorner(TLx, TLy, false);             // counter-clockwise curve
        }
    }

    // --- LEG TYPE A: GO TO A POINT (used once at start) ---
    private void goToPoint(double tx, double ty) {
        while (distanceTo(tx, ty) > CORNER_OK) {
            facePoint(tx, ty);
            ahead(STEP);
        }
    }

    // --- LEG TYPE B: HUG A VERTICAL WALL UNTIL NEXT CORNER ---
    // leftWall = true hugs left wall, false hugs right wall
    // goingUp = true means travel up, false means travel down
    private void hugVerticalWallToCorner(boolean leftWall, boolean goingUp, double tx, double ty) {

        // Ensure we are inside the "wall band" first (lock-in)
        while (!nearVerticalWall(leftWall)) {
            facePoint(tx, ty);
            ahead(STEP);
        }

        // Now hug that wall until we reach the corner
        double alongHeading = goingUp ? 0 : 180;

        while (distanceTo(tx, ty) > CORNER_OK) {
            turnTo(alongHeading);

            // Keep the bot at approximately x = MARGIN (left) or x = w-MARGIN (right)
            double error;
            if (leftWall) {
                error = getX() - MARGIN; // 0 = perfect
                // too far from wall => steer toward wall a bit
                if (error > 0) turnLeft(clamp(error * 0.25, 0, 10));
                else           turnRight(clamp(-error * 0.25, 0, 10));
            } else {
                double w = getBattleFieldWidth();
                error = (w - MARGIN) - getX(); // 0 = perfect
                if (error > 0) turnRight(clamp(error * 0.25, 0, 10));
                else           turnLeft(clamp(-error * 0.25, 0, 10));
            }

            ahead(STEP);
        }
    }

    // --- LEG TYPE C: CURVED CROSS THAT DOESN'T DRIFT TO WALLS ---
    private void crossCurvedToCorner(double tx, double ty, boolean clockwise) {
        double centerX = getBattleFieldWidth() / 2.0;
        double centerY = getBattleFieldHeight() / 2.0;

        while (distanceTo(tx, ty) > CORNER_OK) {
            facePoint(tx, ty);
            ahead(STEP);

            // Add smooth curve
            if (clockwise) turnRight(7);
            else           turnLeft(7);

            // IMPORTANT: If we drift too near a wall during the cross,
            // nudge back toward the center so it stays a true "∞" and not a diagonal bounce.
            if (nearAnyWallBand()) {
                facePoint(centerX, centerY);
                ahead(STEP);
            }
        }
    }

    // --- NAV HELPERS ---
    private void facePoint(double tx, double ty) {
        double angle = Math.toDegrees(Math.atan2(tx - getX(), ty - getY()));
        turnTo(angle);
    }

    private void turnTo(double absHeading) {
        double turn = normalize(absHeading - getHeading());
        turnRight(turn);
    }

    private double distanceTo(double x, double y) {
        return Math.hypot(x - getX(), y - getY());
    }

    private boolean nearVerticalWall(boolean leftWall) {
        double x = getX();
        double w = getBattleFieldWidth();
        return leftWall ? (x <= MARGIN + 12) : (x >= w - (MARGIN + 12));
    }

    private boolean nearAnyWallBand() {
        double x = getX(), y = getY();
        double w = getBattleFieldWidth(), h = getBattleFieldHeight();
        return (x <= MARGIN + 12) || (x >= w - (MARGIN + 12)) ||
               (y <= MARGIN + 12) || (y >= h - (MARGIN + 12));
    }

    private double normalize(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    private double clamp(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
