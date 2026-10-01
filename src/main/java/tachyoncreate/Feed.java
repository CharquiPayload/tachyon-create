package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import tachyon.api.Bot;

import java.util.function.Predicate;

/**
 * Putting things into a machine, the way that machine takes them ({@link Machine.In}): a click with
 * them in hand (a depot takes the stack, a funnel what it has room for, a blaze burner one piece of
 * fuel a click), or thrown on top to fall in (a basin, a millstone, crushing wheels).
 *
 * <p>A throw is aimed ({@link Throw}) from a spot where it lands in most often, and watched: what
 * falls in counts, what lands beside is picked back up (once the toss's pickup wait is over, a player
 * walks over it) and thrown again. Three misses in a row and it gives up. What lies on top and is not
 * taken in means the machine is full.
 */
final class Feed extends MachineJob {

    /** How long a throw is watched before what is left of it is a miss, in ticks: under the toss's pickup wait. */
    private static final int FLIGHT = 30;
    /** How long it tries to get a miss back, in ticks. */
    private static final int FETCH = 200;

    private final Predicate<ItemStack> what;
    /** What was asked for, for words: "coal", "fuel". */
    private final String asked;
    private int left;
    private int put;
    private Item item;
    private ItemEntity flying;
    private int flyingCount;
    private long thrownAt;
    private int misses;
    private ItemEntity fetching;
    private int fetchTicks;
    private long nextClick;

    /** {@code count} of what passes {@code what} (as many as it carries for {@link Integer#MAX_VALUE}). */
    Feed(java.util.List<Machine.Found> options, Predicate<ItemStack> what, String asked, int count) {
        super(options);
        this.what = what;
        this.asked = asked;
        this.left = count;
    }

    @Override
    double good(ServerPlayer body, Vec3 eye, BlockPos feet) {
        if (target.kind().in == Machine.In.THROW) {
            Throw.Aim aim = aim(body, eye);
            return aim == null ? 0 : aim.chance();
        }
        return reaches(Sight.hit(body, eye, target.pos(), face(body), reach(body)) != null);
    }

    @Override
    public String think(Bot bot, long now) {
        if (over == null && fetching != null) {
            // A miss lies on the ground: it walks over it (picked up as a player does), then throws it
            // again from its spot. One it can't get to in 10 seconds is left where it lies.
            if (fetching.isRemoved() || ++fetchTicks > FETCH) {
                fetching = null;
                fetchTicks = 0;
                again();
            } else {
                if (!bot.walking()) bot.walkTo(fetching.blockPosition(), 0.3);
                return null;
            }
        }
        return super.think(bot, now);
    }

    @Override
    String gone() {
        return put > 0 ? "put " + Text.some(put, item) + " in, then the " + target.kind().name + " was gone" : super.gone();
    }

    @Override
    void work(Bot bot) {
        ServerPlayer body = bot.body();
        long now = body.level().getGameTime();
        if (flying != null) {
            watch(body, now);
            return;
        }
        if (fetching != null) return;           // it walks to a miss first (think)
        if (left <= 0) {
            over = done();
            return;
        }
        switch (target.kind().in) {
            case CLICK_STACK -> clickStack(body);
            case CLICK_EACH -> {
                if (now >= nextClick) {
                    nextClick = now + 4;
                    clickOne(body);
                }
            }
            case THROW -> toss(body, now);
            default -> over = "nothing goes into the " + target.kind().name + " by hand";
        }
    }

    /** A click with the stack in hand: a depot, a deployer, a funnel. */
    private void clickStack(ServerPlayer body) {
        if (!Hands.holdSome(body, what, Math.min(left, 64))) {
            over = none();
            return;
        }
        Item holding = body.getMainHandItem().getItem();
        BlockHitResult hit = Sight.hit(body, body.getEyePosition(), target.pos(), face(body), reach(body));
        if (hit == null) {
            again();
            return;
        }
        int before = Hands.count(body, what);
        InteractionResult r = Hands.click(body, hit);
        int took = before - Hands.count(body, what);
        if (r == null) {
            over = refused();
            return;
        }
        if (took <= 0) {
            over = put > 0 ? done() : "the " + target.kind().name + " won't take " + Text.name(holding);
            return;
        }
        item = holding;
        put += took;
        left -= took;
        // A depot holds one stack, a deployer one in its hand: one click is all they take.
        if (target.kind() != Machine.FUNNEL) left = 0;
    }

    /** A click with one piece: a blaze burner's fuel. */
    private void clickOne(ServerPlayer body) {
        Level level = body.level();
        if (Fuel.keeper(level, target.pos())) {
            over = "a stock keeper sits on that blaze burner: it's not mine to feed";
            return;
        }
        if (!Fuel.hasBlaze(level.getBlockState(target.pos()))) {
            over = "there's no blaze in that burner";
            return;
        }
        if (!Hands.hold(body, what)) {
            over = none();
            return;
        }
        Item holding = body.getMainHandItem().getItem();
        BlockHitResult hit = Sight.hit(body, body.getEyePosition(), target.pos(), null, reach(body));
        if (hit == null) {
            again();
            return;
        }
        int before = Hands.count(body, what);
        InteractionResult r = Hands.click(body, hit);
        int took = before - Hands.count(body, what);
        if (r == null) {
            over = refused();
            return;
        }
        if (took <= 0) {
            over = put > 0 ? done() + "; it's full" : "the blaze burner won't take " + Text.name(holding);
            return;
        }
        item = holding;
        put += took;
        left -= took;
    }

    /** A throw of what it holds, aimed to fall in. */
    private void toss(ServerPlayer body, long now) {
        if (!Hands.hold(body, what)) {
            over = none();
            return;
        }
        Vec3 eye = body.getEyePosition();
        Throw.Aim aim = aim(body, eye);
        if (aim == null || aim.chance() < Stand.OK) {
            again();
            return;
        }
        Item holding = body.getMainHandItem().getItem();
        int n = Math.min(left, body.getMainHandItem().getCount());
        float yaw = Throw.yaw(eye, into(body.level()));
        ItemEntity thrown = Hands.toss(body, yaw, aim.pitch(), n);
        if (thrown == null) {
            over = refused();
            return;
        }
        item = holding;
        flying = thrown;
        flyingCount = n;
        thrownAt = now;
    }

    /** A throw in the air: what falls in counts; what is left on the ground is a miss, fetched. */
    private void watch(ServerPlayer body, long now) {
        int still = flying.isRemoved() ? 0 : flying.getItem().getCount();
        if (still < flyingCount) {
            put += flyingCount - still;
            left -= flyingCount - still;
            flyingCount = still;
            misses = 0;
        }
        if (still == 0) {
            flying = null;
            return;
        }
        // Crushing wheels hold what fell between them while they crush it, then let it out below.
        if (target.kind() == Machine.CRUSHING_WHEELS && flying.blockPosition().equals(target.pos())) {
            put += still;
            left -= still;
            flying = null;
            misses = 0;
            return;
        }
        if (now - thrownAt < FLIGHT) return;
        // What is still there, after the flight: on the machine untaken (full), or beside it.
        BlockPos at = flying.blockPosition();
        if (at.equals(target.pos()) || at.equals(target.pos().above())) {
            over = (put > 0 ? done() + "; " : "") + "the " + target.kind().name + " " + target.kind().is() + " full";
            flying = null;
            return;
        }
        fetching = flying;
        flying = null;
        again();
        if (++misses >= 3) {
            over = put > 0 ? done() + ", then kept missing" : "gave up: I kept missing the " + target.kind().name;
        }
    }

    /** The look that lands a throw from {@code eye} in the machine most often, its flight clear; null for none. */
    private Throw.Aim aim(ServerPlayer body, Vec3 eye) {
        Level level = body.level();
        Vec3 into = into(level);
        if (eye.y - 0.3 <= into.y) return null;      // a throw from under the top never falls in
        return Throw.best(eye.subtract(0, 0.3, 0), into, target.kind().opening(), (a, b) -> level.clip(
                new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() == HitResult.Type.MISS);
    }

    /** The middle of the machine's top, where a throw should come down. */
    private Vec3 into(Level level) {
        BlockPos p = target.pos();
        return new Vec3(p.getX() + 0.5, p.getY() + Machine.top(level, p), p.getZ() + 0.5);
    }

    private net.minecraft.core.Direction face(ServerPlayer body) {
        return target.kind().face(body.level().getBlockState(target.pos()));
    }

    private String done() {
        return put > 0 ? "put " + Text.some(put, item) + " in the " + target.kind().name : "put nothing in the " + target.kind().name;
    }

    private String none() {
        return put > 0 ? done() + "; that's all the " + asked + " I had" : "I've got no " + asked + " on me";
    }

    private String refused() {
        return put > 0 ? done() + ", then I wasn't allowed to go on" : "I'm not allowed to use the " + target.kind().name + " here";
    }

    @Override
    public String status() {
        return "feeding the " + target.kind().name + (put > 0 ? ", " + put + " in so far" : "");
    }

    @Override
    public int progress() {
        return put;
    }
}
