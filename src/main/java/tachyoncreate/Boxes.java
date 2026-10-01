package tachyoncreate;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.SidedFilteringBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Create's small settings boxes on a machine's sides: a basin's filter, a funnel's, a deployer's. A
 * click on one goes to the box (it sets the filter to what is in hand, or clears it) and never to the
 * machine, so a bot aims beside them, as a player does who wants the machine: Create's own test of
 * whether a spot is on a box, for every box that would take the click.
 */
final class Boxes {

    private Boxes() {
    }

    /** Whether a click at this spot would land on one of the block's settings boxes. */
    static boolean on(Level level, BlockPos pos, BlockHitResult ray, ServerPlayer player) {
        if (!(level.getBlockEntity(pos) instanceof SmartBlockEntity sbe)) return false;
        boolean wrench = AllItems.WRENCH.isIn(player.getMainHandItem());
        for (BlockEntityBehaviour b : sbe.getAllBehaviours()) {
            if (!(b instanceof ValueSettingsBehaviour)) continue;
            BlockEntityBehaviour one = b instanceof SidedFilteringBehaviour sided ? sided.get(ray.getDirection()) : b;
            if (!(one instanceof ValueSettingsBehaviour box) || !box.isActive()) continue;
            if (box.onlyVisibleWithWrench() && !wrench) continue;
            if (box.getSlotPositioning() instanceof ValueBoxTransform.Sided side) side.fromSide(ray.getDirection());
            if (box.testHit(ray.getLocation())) return true;
        }
        return false;
    }
}
