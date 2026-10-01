package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What a bot sees and reaches, as a player would: a block is seen when a line from its eyes gets to
 * it with nothing in the way, and reached when that line is within its arm's reach. Never through a
 * wall: a machine behind one is no machine to it until it walks round and sees it.
 *
 * <p>Machines are looked for among the block entities of the chunks around the bot (every machine
 * Create has keeps one), which is cheap: the blocks themselves are never searched.
 */
final class Sight {

    /** How far a bot looks for a machine by itself: a room of a factory, not the whole of it. */
    static final double LOOK = 12;
    /** How far a player's pointing reaches: "this crank", from across a room. */
    static final double POINT = 24;

    private Sight() {
    }

    /**
     * The spots it aims at on a block: the middle of its outline, then on each of the outline's faces
     * (all of them, or the one a click must land on) its middle and four spots around it. A crank is a
     * small thing in a corner of its block, so its outline's, not its block's.
     */
    static List<Vec3> aims(Level level, BlockPos pos, Direction face) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        AABB box = (shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds()).move(pos);
        Vec3 mid = box.getCenter();
        List<Vec3> out = new ArrayList<>();
        if (face == null) out.add(mid);
        for (Direction d : Direction.values()) {
            if (face != null && d != face) continue;
            double x = d.getStepX() == 0 ? mid.x : d.getStepX() > 0 ? box.maxX : box.minX;
            double y = d.getStepY() == 0 ? mid.y : d.getStepY() > 0 ? box.maxY : box.minY;
            double z = d.getStepZ() == 0 ? mid.z : d.getStepZ() > 0 ? box.maxZ : box.minZ;
            Vec3 c = new Vec3(x, y, z);
            out.add(c);
            // Around the middle, a quarter of the face each way along its two sides.
            double ax = d.getAxis() == Direction.Axis.X ? 0 : box.getXsize() / 4;
            double ay = d.getAxis() == Direction.Axis.Y ? 0 : box.getYsize() / 4;
            double az = d.getAxis() == Direction.Axis.Z ? 0 : box.getZsize() / 4;
            if (d.getAxis() == Direction.Axis.Y) {
                out.add(c.add(ax, 0, az));
                out.add(c.add(-ax, 0, -az));
                out.add(c.add(ax, 0, -az));
                out.add(c.add(-ax, 0, az));
            } else {
                double side = d.getAxis() == Direction.Axis.X ? az : ax;
                out.add(c.add(0, ay, 0));
                out.add(c.add(0, -ay, 0));
                out.add(d.getAxis() == Direction.Axis.X ? c.add(0, 0, side) : c.add(side, 0, 0));
                out.add(d.getAxis() == Direction.Axis.X ? c.add(0, 0, -side) : c.add(-side, 0, 0));
            }
        }
        return out;
    }

    /**
     * Where a click from {@code eye} would land on the block, seen and within {@code reach}: on the
     * face asked for (null: any). Null when there is no such spot: the block is out of sight, too
     * far, or shows another face.
     */
    static BlockHitResult hit(ServerPlayer body, Vec3 eye, BlockPos pos, Direction face, double reach) {
        Level level = body.level();
        for (Vec3 aim : aims(level, pos, face)) {
            if (aim.distanceTo(eye) > reach + 0.5) continue;
            Vec3 past = aim.add(aim.subtract(eye).normalize().scale(0.05));
            BlockHitResult h = level.clip(new ClipContext(eye, past, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, body));
            if (h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(pos) && (face == null || h.getDirection() == face)
                    && h.getLocation().distanceTo(eye) <= reach && !Boxes.on(level, pos, h, body)) {
                return new BlockHitResult(h.getLocation(), h.getDirection(), pos.immutable(), h.isInside());
            }
        }
        return null;
    }

    /** Whether it sees the block from where it stands, near enough to know it ({@link #LOOK}). */
    static boolean sees(ServerPlayer body, BlockPos pos) {
        return hit(body, body.getEyePosition(), pos, null, LOOK + 2) != null;
    }

    /** The block a player points at, up to {@link #POINT} blocks off; null for none (the sky). */
    static BlockPos pointedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 to = eye.add(player.getViewVector(1.0F).scale(POINT));
        BlockHitResult h = player.level().clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return h.getType() == HitResult.Type.BLOCK ? h.getBlockPos() : null;
    }

    /**
     * The machines of some kinds that the bot sees from where it stands, within {@code radius}
     * blocks, the nearest first. A press or a mixer seen counts for what it works on when
     * {@code overhead} is set ("the press": the depot under it).
     */
    static List<Machine.Found> machines(ServerPlayer body, Set<Machine> kinds, double radius, boolean overhead) {
        List<Machine.Found> out = new ArrayList<>();
        Vec3 eye = body.getEyePosition();
        for (BlockEntity be : blockEntities(body.serverLevel(), body.blockPosition(), radius,
                be -> be.getBlockPos().distToCenterSqr(eye) <= radius * radius)) {
            BlockPos pos = be.getBlockPos();
            BlockState state = be.getBlockState();
            Machine.Found found;
            if (overhead) {
                if (!Machine.overhead(state)) continue;
                found = Machine.at(body.level(), pos);
            } else {
                Machine kind = Machine.kind(state);
                found = kind == null ? null : new Machine.Found(kind, pos.immutable());
            }
            if (found == null || !kinds.contains(found.kind())) continue;
            // Crushing wheels' gap has no outline to see: the wheels beside it are what is seen.
            boolean seen = found.kind() == Machine.CRUSHING_WHEELS ? seesAround(body, found.pos()) : sees(body, pos);
            if (seen && !out.contains(found)) out.add(found);
        }
        out.sort(Comparator.comparingDouble(f -> f.pos().distToCenterSqr(eye)));
        return out;
    }

    /** Whether it sees a block next to this one (the wheels beside crushing wheels' gap). */
    static boolean seesAround(ServerPlayer body, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (sees(body, pos.relative(d))) return true;
        }
        return false;
    }

    /**
     * The block entities of the loaded chunks within {@code radius} of a spot that pass {@code keep}.
     * A chunk not loaded is skipped, never loaded for this.
     */
    static List<BlockEntity> blockEntities(ServerLevel level, BlockPos at, double radius, Predicate<BlockEntity> keep) {
        List<BlockEntity> out = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        int cx0 = SectionPos.blockToSectionCoord(at.getX() - r), cx1 = SectionPos.blockToSectionCoord(at.getX() + r);
        int cz0 = SectionPos.blockToSectionCoord(at.getZ() - r), cz1 = SectionPos.blockToSectionCoord(at.getZ() + r);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!be.isRemoved() && keep.test(be)) out.add(be);
                }
            }
        }
        return out;
    }
}
