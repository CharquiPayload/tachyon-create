package tachyoncreate;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.function.LongConsumer;

/**
 * What a bot's ways keep off among Create's machines (M-KGL3: a bot jumped into a fan's current
 * over lava and was incinerated; the owner: "que los bots al navegar eviten pisar belts en
 * movimiento"): a moving belt, and a running fan's air current, the whole box it blows through
 * (it burns, smokes, washes or drowns what is in it). Only machines that turn: a still belt is a
 * floor like any other. Read from the loaded chunks' block entities, as each route is searched.
 */
final class Hazards {

    /** The tallest current marked, in blocks across any side: past it, something is off. */
    private static final int CURRENT_MOST = 64;

    private Hazards() {
    }

    static void avoid(ServerLevel level, BlockPos min, BlockPos max, LongConsumer out) {
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof BeltBlockEntity belt) {
                        if (belt.getSpeed() == 0) continue;
                        // On it, and in it: a belt is lower than a block, its riders' feet are in its own place.
                        out.accept(belt.getBlockPos().asLong());
                        out.accept(belt.getBlockPos().above().asLong());
                    } else if (be instanceof EncasedFanBlockEntity fan) {
                        AirCurrent current = fan.getAirCurrent();
                        if (fan.getSpeed() == 0 || current == null || current.bounds == null) continue;
                        box(current.bounds, out);
                    }
                }
            }
        }
    }

    /** Every place a current's box takes in. */
    private static void box(AABB b, LongConsumer out) {
        if (b.getXsize() > CURRENT_MOST || b.getYsize() > CURRENT_MOST || b.getZsize() > CURRENT_MOST) return;
        for (int x = Mth.floor(b.minX); x < Mth.ceil(b.maxX); x++) {
            for (int y = Mth.floor(b.minY); y < Mth.ceil(b.maxY); y++) {
                for (int z = Mth.floor(b.minZ); z < Mth.ceil(b.maxZ); z++) {
                    out.accept(BlockPos.asLong(x, y, z));
                }
            }
        }
    }
}
