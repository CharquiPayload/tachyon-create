package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import tachyon.api.Bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Getting to where its hands can do the job: a spot to stand on near the machine, from which what the
 * job needs is in sight and in reach (a depot's top, a throw that lands in a basin). The spots are
 * the blocks around the machine a player could stand on, the nearest first; it walks to one, and to
 * the next when there is no way there.
 *
 * <p>Never on a machine (a belt would carry it off, a saw or crushing wheels would hurt it), nor in
 * a basin.
 */
final class Stand {

    /**
     * How well the job can be done from a spot, its eyes there, its feet's block: 0 not at all, 1
     * surely (a click in reach), between for a throw (how often it lands in).
     */
    interface Good {
        double from(Vec3 eye, BlockPos feet);
    }

    /** How well a spot must do: from where it stands without walking, and from a spot it walked to. */
    static final double HERE = 0.8, OK = 0.5;

    /** How it is getting there. */
    enum State { WALKING, THERE, NO_WAY }

    /** How far around the machine it looks for a spot, across and up or down. */
    private static final int ACROSS = 3, UP_DOWN = 2;
    /** How many spots it walks for before it gives up. */
    private static final int TRIES = 4;
    /** How many spots that do well enough it compares, the nearest first. */
    private static final int ASKED = 12;
    /** How many ticks it steps toward a spot by its keys, after the walk. */
    private static final int STEPS = 30;
    /** A standing player's eyes over its feet. */
    static final double EYES = 1.62;

    private final BlockPos target;
    private final Good good;
    private List<BlockPos> spots;
    private int tried;
    private BlockPos going;
    private int steps;
    private boolean stepping;

    Stand(BlockPos target, Good good) {
        this.target = target;
        this.good = good;
    }

    /** A tick of getting there, before the walk. */
    State tick(Bot bot) {
        ServerPlayer body = bot.body();
        // The last tick's step is let go of: if it goes on stepping, the hands' turn presses it again.
        if (stepping) body.zza = 0;
        stepping = false;
        if (going != null) {
            if (bot.walking()) return State.WALKING;
            // The walk ends near the spot, not on it: from where it stopped, the job may do already.
            if (good.from(body.getEyePosition(), body.blockPosition()) >= OK) return State.THERE;
            // Else the last step onto it, by its own keys ({@link #step}), as a player walks up to something.
            if (!bot.lost() && steps < STEPS && flatDistance(body, going) < 2.0 && Math.abs(body.getY() - going.getY()) < 0.6) {
                steps++;
                stepping = true;
                return State.WALKING;
            }
            going = null;
            steps = 0;
            tried++;
        } else if (tried == 0 && good.from(body.getEyePosition(), body.blockPosition()) >= HERE) {
            return State.THERE;
        }
        if (spots == null) spots = spots(body);
        if (tried >= Math.min(TRIES, spots.size())) return State.NO_WAY;
        going = spots.get(tried);
        bot.walkTo(going, 0.4);
        return State.WALKING;
    }

    /**
     * The last step onto the spot, after the walk pressed its keys: facing the spot's middle, forward,
     * slower as it gets close. Nothing when it is not stepping.
     */
    void step(Bot bot) {
        ServerPlayer body = bot.body();
        if (!stepping || going == null) return;
        double dx = going.getX() + 0.5 - body.getX(), dz = going.getZ() + 0.5 - body.getZ();
        double far = Math.sqrt(dx * dx + dz * dz);
        if (far < 0.15) {
            body.zza = 0;
            return;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        body.zza = (float) Math.min(1.0, far * 1.5);
    }

    /** Lets go of the keys a step pressed. */
    void stop(Bot bot) {
        if (stepping && bot.inGame()) bot.body().zza = 0;
        stepping = false;
    }

    private static double flatDistance(ServerPlayer body, BlockPos spot) {
        double dx = spot.getX() + 0.5 - body.getX(), dz = spot.getZ() + 0.5 - body.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The spots it could do the job from, the best first: the blocks around a player could stand on,
     * the nearest to it first, each asked how well the job does from it ({@link Good}) until
     * {@link #ASKED} did well enough; of those, the ones that do best, the nearer first among equals. A
     * throw's aim is worked out for each spot asked, so not for all of them.
     */
    private List<BlockPos> spots(ServerPlayer body) {
        Level level = body.level();
        List<BlockPos> standable = new ArrayList<>();
        for (int dx = -ACROSS; dx <= ACROSS; dx++) {
            for (int dz = -ACROSS; dz <= ACROSS; dz++) {
                for (int dy = -UP_DOWN; dy <= UP_DOWN; dy++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (!feet.equals(target) && standable(level, feet)) standable.add(feet);
                }
            }
        }
        Vec3 at = body.position();
        standable.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(at)));
        List<BlockPos> out = new ArrayList<>();
        java.util.Map<BlockPos, Double> scores = new java.util.HashMap<>();
        for (BlockPos feet : standable) {
            Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYES, feet.getZ() + 0.5);
            double score = good.from(eye, feet);
            if (score < OK) continue;
            out.add(feet);
            scores.put(feet, score);
            if (out.size() >= ASKED) break;
        }
        // Better first; a stable sort keeps the nearer first among those that do as well.
        out.sort(Comparator.comparingDouble(p -> -Math.round(scores.get(p) * 10) / 10.0));
        return out.subList(0, Math.min(TRIES, out.size()));
    }

    /** Whether a player can stand there: something to stand on, room for feet and head, no machine underfoot. */
    static boolean standable(Level level, BlockPos feet) {
        BlockPos under = feet.below();
        BlockState ground = level.getBlockState(under);
        if (ground.getCollisionShape(level, under).isEmpty() || Machine.underfoot(ground)) return false;
        return clear(level, feet) && clear(level, feet.above());
    }

    private static boolean clear(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty() && Machine.kind(s) == null;
    }
}
