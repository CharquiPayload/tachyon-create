package tachyoncreate;

import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Aiming a throw, as a player learns to: where a tossed item lands for each way of looking, worked out
 * with the game's own physics, and the look that lands it in a machine most often.
 *
 * <p>A player's toss starts 0.3 below the eyes, at 0.3 a tick along the look, plus 0.1 up; the game
 * adds a spread of its own (up to 0.1 more or less up, 0.02 to a side). Then each tick the item
 * falls by 0.04, moves, and keeps 98% of its speed. The spread is why one look is never sure: a set
 * of spreads, the same each time, says how often a look lands where it should.
 */
final class Throw {

    /** A look to throw with: its pitch (down is positive, in degrees) and how often it lands in. */
    record Aim(float pitch, double chance) {
    }

    /** Whether a stretch of the flight is clear of blocks (the throw's own path, not the bot). */
    interface Clear {
        boolean between(Vec3 from, Vec3 to);
    }

    /** The spreads the game may add: up (a triangle from -0.1 to 0.1), sideways (up to 0.02). */
    private static final double[][] SPREADS = spreads(64);

    private Throw() {
    }

    private static double[][] spreads(int n) {
        Random r = new Random(7);
        double[][] out = new double[n][3];
        for (int i = 0; i < n; i++) {
            double angle = r.nextDouble() * Math.PI * 2, side = 0.02 * r.nextDouble();
            out[i][0] = (r.nextDouble() - r.nextDouble()) * 0.1;
            out[i][1] = Math.cos(angle) * side;
            out[i][2] = Math.sin(angle) * side;
        }
        return out;
    }

    /**
     * The best look to throw from {@code from} (the toss's start: the eyes, 0.3 lower) into an
     * opening whose middle is {@code into}, a flat top {@code half} blocks to each side of it: the
     * look landing in most often whose flight is clear. Null when no look lands at all.
     *
     * <p>Looked for coarsely first (every 3 degrees, half the spreads), then degree by degree near the
     * best of those with all the spreads: a few thousand flights, not a hundred thousand, since it is
     * asked for every spot a bot might stand on.
     */
    static Aim best(Vec3 from, Vec3 into, double half, Clear clear) {
        double dx = into.x - from.x, dz = into.z - from.z;
        double far = Math.sqrt(dx * dx + dz * dz);
        if (far < 1e-6) return null;
        double ux = dx / far, uz = dz / far;
        int coarse = Integer.MIN_VALUE;
        double coarseChance = 0;
        for (int pitch = -84; pitch <= 84; pitch += 3) {
            double chance = chance(from, into, ux, uz, pitch, half, SPREADS.length / 2);
            if (chance <= coarseChance || !clearFlight(from, into.y, ux, uz, pitch, clear)) continue;
            coarse = pitch;
            coarseChance = chance;
        }
        if (coarse == Integer.MIN_VALUE) return null;
        Aim best = null;
        for (int pitch = Math.max(-85, coarse - 2); pitch <= Math.min(85, coarse + 2); pitch++) {
            double chance = chance(from, into, ux, uz, pitch, half, SPREADS.length);
            if (chance <= 0 || best != null && chance <= best.chance()) continue;
            if (!clearFlight(from, into.y, ux, uz, pitch, clear)) continue;
            best = new Aim(pitch, chance);
        }
        return best;
    }

    /** How often a throw at this pitch lands within {@code half} of {@code into}: over the first {@code n} spreads. */
    static double chance(Vec3 from, Vec3 into, double ux, double uz, double pitch, double half, int n) {
        int in = 0;
        for (int i = 0; i < n; i++) {
            double[] s = SPREADS[i];
            Vec3 at = landing(from, into.y, ux, uz, pitch, s[0], s[1], s[2]);
            if (at != null && Math.abs(at.x - into.x) <= half && Math.abs(at.z - into.z) <= half) in++;
        }
        return in / (double) n;
    }

    /**
     * Where a throw lands on the height {@code y}, going down through it: null when it never gets
     * there (it was thrown from under it, or flies too long).
     */
    static Vec3 landing(Vec3 from, double y, double ux, double uz, double pitch, double up, double sx, double sz) {
        double rad = Math.toRadians(pitch);
        double flat = Math.cos(rad) * 0.3;
        double vx = ux * flat + sx, vz = uz * flat + sz, vy = -Math.sin(rad) * 0.3 + 0.1 + up;
        double x = from.x, h = from.y, z = from.z;
        for (int tick = 0; tick < 100; tick++) {
            vy -= 0.04;
            double nx = x + vx, nh = h + vy, nz = z + vz;
            if (vy < 0 && nh <= y && h >= y) {
                double part = (h - y) / (h - nh);
                return new Vec3(x + vx * part, y, z + vz * part);
            }
            x = nx;
            h = nh;
            z = nz;
            vx *= 0.98;
            vy *= 0.98;
            vz *= 0.98;
        }
        return null;
    }

    /** Whether a throw's flight with no spread is clear until it comes down to {@code y}: walls, a ceiling. */
    private static boolean clearFlight(Vec3 from, double y, double ux, double uz, double pitch, Clear clear) {
        double rad = Math.toRadians(pitch);
        double flat = Math.cos(rad) * 0.3;
        double vx = ux * flat, vz = uz * flat, vy = -Math.sin(rad) * 0.3 + 0.1;
        Vec3 at = from;
        for (int tick = 0; tick < 100; tick++) {
            vy -= 0.04;
            Vec3 next = at.add(vx, vy, vz);
            boolean down = vy < 0 && next.y <= y;
            // The last stretch ends just above the top, where the machine itself would be met.
            if (down) next = new Vec3(at.x + vx * (at.y - y) / (at.y - next.y), y + 0.05, at.z + vz * (at.y - y) / (at.y - next.y));
            if (!clear.between(at, next)) return false;
            if (down) return true;
            at = next;
            vx *= 0.98;
            vy *= 0.98;
            vz *= 0.98;
        }
        return false;
    }

    /** The yaw, in the game's degrees, that faces from one spot to another. */
    static float yaw(Vec3 from, Vec3 to) {
        return (float) (Math.toDegrees(Math.atan2(to.z - from.z, to.x - from.x)) - 90.0);
    }
}
