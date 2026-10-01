package tachyoncreate;

import com.simibubi.create.api.data.datamaps.BlazeBurnerFuel;
import com.simibubi.create.api.registry.CreateDataMaps;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * A blaze burner's fuel, as a player who knows Create picks it: the burner's own rules say what burns
 * and for how long (Create's data maps, then a furnace's burn time). Plain fuel only: a blaze cake
 * superheats the burner, which changes what the machine above makes, so it goes in only when someone
 * names it. Coal and charcoal first, then what burns longest; never a tool, nor a bucket of lava.
 *
 * <p>A burner shows how it burns (its flame): a blaze with no fuel smoulders, one about to run out
 * fades. Fuel put in then counts whole, the first piece however much it is, and the burner keeps up
 * to {@link #FULL} ticks: more than that would be wasted, so a refill is what fits.
 */
final class Fuel {

    /**
     * Create's own tags of a burner's fuels, by name: the fields that name them are on their way out of
     * Create, and a field gone would break this as it loads.
     */
    private static final TagKey<Item> SPECIAL = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("create", "blaze_burner_fuel/special"));
    private static final TagKey<Item> REGULAR = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("create", "blaze_burner_fuel/regular"));

    /** The most a burner keeps, in ticks. */
    static final int FULL = BlazeBurnerBlockEntity.MAX_HEAT_CAPACITY;

    private Fuel() {
    }

    /** How long a piece burns in a blaze burner, in ticks: 0 for no plain fuel (a blaze cake is none). */
    static int burnTime(ItemStack stack) {
        if (stack.isEmpty() || stack.isDamageableItem() || stack.hasCraftingRemainingItem()) return 0;
        Holder<Item> holder = stack.getItem().builtInRegistryHolder();
        if (holder.getData(CreateDataMaps.SUPERHEATED_BLAZE_BURNER_FUELS) != null) return 0;
        if (stack.is(SPECIAL)) return 0;
        BlazeBurnerFuel regular = holder.getData(CreateDataMaps.REGULAR_BLAZE_BURNER_FUELS);
        if (regular != null) return regular.burnTime();
        int furnace = stack.getBurnTime(null);
        if (furnace > 0) return furnace;
        return stack.is(REGULAR) ? 1600 : 0;
    }

    /** What a bot carries that it would burn: plain fuel. */
    static final Predicate<ItemStack> ANY = s -> burnTime(s) > 0;

    /** The fuel it would put in now: coal or charcoal first, then what burns longest; empty for none. */
    static ItemStack pick(ServerPlayer p) {
        ItemStack best = ItemStack.EMPTY;
        int bestScore = 0;
        for (ItemStack s : p.getInventory().items) {
            int t = burnTime(s);
            if (t <= 0) continue;
            int score = (s.is(ItemTags.COALS) ? 1_000_000 : 0) + Math.min(t, FULL);
            if (score > bestScore) {
                best = s;
                bestScore = score;
            }
        }
        return best;
    }

    /** How many pieces of a fuel fill a burner that is running out: what fits in {@link #FULL}, one at least. */
    static int refill(ItemStack fuel) {
        int t = burnTime(fuel);
        return t <= 0 ? 0 : Math.max(1, FULL / t);
    }

    /** Whether a burner has a blaze in it and is running out (fading) or out (smouldering). */
    static boolean low(BlockState state) {
        if (!(state.getBlock() instanceof BlazeBurnerBlock)) return false;
        BlazeBurnerBlock.HeatLevel heat = state.getValue(BlazeBurnerBlock.HEAT_LEVEL);
        return heat == BlazeBurnerBlock.HeatLevel.SMOULDERING || heat == BlazeBurnerBlock.HeatLevel.FADING;
    }

    /** Whether a burner has a blaze in it at all: an empty one burns nothing. */
    static boolean hasBlaze(BlockState state) {
        return state.getBlock() instanceof BlazeBurnerBlock
                && state.getValue(BlazeBurnerBlock.HEAT_LEVEL) != BlazeBurnerBlock.HeatLevel.NONE;
    }

    /**
     * Whether a burner heats something: a basin or a fluid tank on it. One with nothing on it does no
     * work, and is left smouldering.
     */
    static boolean working(Level level, BlockPos pos) {
        BlockState above = level.getBlockState(pos.above());
        return Machine.kind(above) == Machine.BASIN || above.getBlock() instanceof com.simibubi.create.content.fluids.tank.FluidTankBlock;
    }

    /**
     * Whether a click on the burner would open something else (a stock keeper sits on it, and a click
     * there opens the shop's stock): such a burner is no one's to feed by hand.
     */
    static boolean keeper(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof BlazeBurnerBlockEntity be && be.stockKeeper;
    }
}
