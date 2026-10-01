package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import tachyon.api.Bot;
import tachyon.api.Job;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A standing order: keeping the blaze burners of a factory lit, with fuel it carries, until told to
 * stop. It looks after the burners it has seen within {@code range} of where it started (it looks
 * around every two seconds, and walks among them, so it comes to know more of them); one that runs
 * low (its flame fading) or out (smouldering) gets what fits of the best fuel it has
 * ({@link Fuel#refill}). Only a burner that heats something (a basin, a tank on it) is kept lit.
 *
 * <p>Out of fuel, it tells its owner once and waits where it is: given more, it goes on.
 */
final class Stoke extends Job {

    /** A burner it can't get to is left be this long, in ticks, before it tries again. */
    private static final int SKIP = 60 * 20;

    private final ResourceKey<Level> world;
    private final BlockPos home;
    private final double range;
    private final Set<BlockPos> known = new LinkedHashSet<>();
    private final Map<BlockPos, Long> skipped = new HashMap<>();
    private BlockPos burner;
    private Stand stand;
    private boolean there;
    private int left;
    private int fed;
    private boolean noFuel;
    private long nextLook;
    private long nextClick;

    /** From {@code home}, within {@code range}; {@code first}, a burner a player pointed at, known from the start (or null). */
    Stoke(ServerPlayer body, BlockPos home, double range, BlockPos first) {
        this.world = body.level().dimension();
        this.home = home.immutable();
        this.range = range;
        if (first != null) known.add(first.immutable());
    }

    @Override
    public String think(Bot bot, long now) {
        ServerPlayer body = bot.body();
        Level level = body.level();
        if (level.dimension() != world || body.blockPosition().distSqr(home) > (range + 32) * (range + 32)) {
            return "stopped keeping the burners lit: I'm too far from them";
        }
        if (now >= nextLook) {
            nextLook = now + 40;
            look(body);
        }
        if (burner == null) {
            burner = next(body, now);
            if (burner == null) return null;
            BlockPos at = burner;
            stand = new Stand(at, (eye, feet) -> Sight.hit(body, eye, at, null, body.blockInteractionRange()) != null ? 1 : 0);
            there = false;
            left = 0;
        }
        if (Machine.kind(level.getBlockState(burner)) != Machine.BLAZE_BURNER) {
            known.remove(burner);
            burner = null;
            return null;
        }
        if (left == 0 && !needs(level, burner)) {
            burner = null;
            return null;
        }
        ItemStack fuel = Fuel.pick(body);
        if (fuel.isEmpty()) {
            if (!noFuel) {
                noFuel = true;
                bot.tellOwner("stoke_fuel", "ran out of fuel for the blaze burners");
            }
            return null;
        }
        noFuel = false;
        if (!there) {
            switch (stand.tick(bot)) {
                case WALKING -> {
                    return null;
                }
                case NO_WAY -> {
                    skipped.put(burner, now + SKIP);
                    burner = null;
                    return null;
                }
                case THERE -> {
                    stand.stop(bot);
                    there = true;
                    left = Fuel.refill(fuel);
                }
            }
        }
        return null;
    }

    @Override
    public void act(Bot bot) {
        if (!there && stand != null) stand.step(bot);
        if (!there || burner == null || left <= 0) return;
        ServerPlayer body = bot.body();
        long now = body.level().getGameTime();
        if (now < nextClick) return;
        nextClick = now + 4;
        ItemStack fuel = Fuel.pick(body);
        if (fuel.isEmpty() || !Hands.hold(body, s -> ItemStack.isSameItem(s, fuel))) {
            left = 0;
            return;
        }
        BlockHitResult hit = Sight.hit(body, body.getEyePosition(), burner, null, body.blockInteractionRange());
        if (hit == null) {
            there = false;
            return;
        }
        int before = Hands.count(body, Fuel.ANY);
        InteractionResult r = Hands.click(body, hit);
        int took = before - Hands.count(body, Fuel.ANY);
        if (r == null || took <= 0) {
            // Refused, or full: this one is done for now.
            if (r == null) skipped.put(burner, now + SKIP);
            left = 0;
        } else {
            fed += took;
            left -= took;
        }
        if (left <= 0) {
            burner = null;
            there = false;
        }
    }

    @Override
    public void end(Bot bot) {
        if (stand != null) stand.stop(bot);
        burner = null;
        there = false;
    }

    /** The burners it sees from where it stands, within its range of home, come to be known. */
    private void look(ServerPlayer body) {
        for (Machine.Found f : Sight.machines(body, EnumSet.of(Machine.BLAZE_BURNER), Sight.LOOK, false)) {
            if (f.pos().distSqr(home) <= range * range) known.add(f.pos());
        }
    }

    /** The nearest burner it knows that needs fuel, and isn't left be for now. */
    private BlockPos next(ServerPlayer body, long now) {
        Level level = body.level();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : known) {
            Long until = skipped.get(p);
            if (until != null && until > now) continue;
            if (!needs(level, p)) continue;
            double d = p.distToCenterSqr(body.position());
            if (d < bestDist) {
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

    /** Whether a burner needs fuel: a blaze in it running low, heating something, no stock keeper on it. */
    private static boolean needs(Level level, BlockPos p) {
        var state = level.getBlockState(p);
        return Fuel.hasBlaze(state) && Fuel.low(state) && Fuel.working(level, p) && !Fuel.keeper(level, p);
    }

    @Override
    public String status() {
        if (noFuel) return "out of fuel for the blaze burners";
        return "keeping " + known.size() + (known.size() == 1 ? " blaze burner" : " blaze burners") + " lit";
    }

    @Override
    public int progress() {
        return fed;
    }
}
