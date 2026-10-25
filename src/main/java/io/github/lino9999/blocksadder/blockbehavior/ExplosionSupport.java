package io.github.lino9999.blocksadder.blockbehavior;

import java.util.Set;

public final class ExplosionSupport {
    private ExplosionSupport() {}

    public static boolean survives(double resistance, float radius, double sample) {
        return resistance > 0 && radius * (.7 + sample * .6) <= (resistance + .3) * .3;
    }

    public static boolean shielded(double originX, double originY, double originZ, Cell target, Set<Cell> shields) {
        int[] current = {(int) Math.floor(originX), (int) Math.floor(originY), (int) Math.floor(originZ)};
        int[] destination = {target.x, target.y, target.z};
        if (current[0] == target.x && current[1] == target.y && current[2] == target.z) return false;
        if (shields.contains(new Cell(current[0], current[1], current[2]))) return true;
        double[] origin = {originX, originY, originZ};
        double[] next = new double[3];
        double[] increment = new double[3];
        int[] step = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            double delta = destination[axis] + .5 - origin[axis];
            step[axis] = Double.compare(delta, 0);
            increment[axis] = delta == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(delta);
            next[axis] = delta > 0 ? (current[axis] + 1 - origin[axis]) / delta : delta < 0 ? (origin[axis] - current[axis]) / -delta : Double.POSITIVE_INFINITY;
        }
        while (true) {
            double crossing = Math.min(next[0], Math.min(next[1], next[2]));
            if (crossing > 1) return false;
            for (int axis = 0; axis < 3; axis++) if (next[axis] == crossing) {
                current[axis] += step[axis];
                next[axis] += increment[axis];
            }
            Cell cell = new Cell(current[0], current[1], current[2]);
            if (cell.equals(target)) return false;
            if (shields.contains(cell)) return true;
        }
    }

    public record Cell(int x, int y, int z) {}
}
