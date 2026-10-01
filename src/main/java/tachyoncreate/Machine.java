package tachyoncreate;

import com.simibubi.create.content.fluids.drain.ItemDrainBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crank.HandCrankBlock;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlock;
import com.simibubi.create.content.kinetics.deployer.DeployerBlock;
import com.simibubi.create.content.kinetics.drill.DrillBlock;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlock;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlock;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlock;
import com.simibubi.create.content.kinetics.saw.SawBlock;
import com.simibubi.create.content.logistics.chute.AbstractChuteBlock;
import com.simibubi.create.content.logistics.depot.DepotBlock;
import com.simibubi.create.content.logistics.depot.EjectorBlock;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Create's machines as a player's hands know them: what each one is, how things go into it, and how
 * what it made comes out. Things go in either with a click, holding them (a depot takes the stack in
 * hand, a blaze burner one piece of fuel a click), or thrown on top, as Create's own ponder scenes put
 * them into a basin or a millstone. What a machine made comes out with a click of an empty hand.
 *
 * <p>Known by the block's class, so an addon's machine that is one of these (a depot of another
 * colour, a chute of another metal) counts too.
 */
enum Machine {
    HAND_CRANK("hand crank", In.NONE, false, "hand_crank", "crank"),
    BLAZE_BURNER("blaze burner", In.CLICK_EACH, false, "blaze_burner", "burner"),
    DEPOT("depot", In.CLICK_STACK, true, "depot", "ejector"),
    BASIN("basin", In.THROW, true, "basin"),
    MILLSTONE("millstone", In.THROW, true, "millstone"),
    CRUSHING_WHEELS("crushing wheels", In.THROW, false, "crushing_wheels", "crushing_wheel", "crusher"),
    SAW("saw", In.THROW, true, "saw", "mechanical_saw"),
    CHUTE("chute", In.THROW, true, "chute", "smart_chute"),
    BELT("belt", In.THROW, false, "belt", "mechanical_belt"),
    FUNNEL("funnel", In.CLICK_STACK, false, "funnel"),
    CRAFTER("mechanical crafter", In.CLICK_STACK, true, "mechanical_crafter", "crafter"),
    DEPLOYER("deployer", In.CLICK_STACK, true, "deployer"),
    ITEM_DRAIN("item drain", In.THROW, true, "item_drain", "drain");

    /** How things go into a machine. */
    enum In {
        /** Nothing goes in by hand (a crank is turned instead). */
        NONE,
        /** A click with them in hand: the stack in hand goes in (a depot, a funnel, a crafter's slot). */
        CLICK_STACK,
        /** A click with them in hand, one piece a click (a blaze burner's fuel). */
        CLICK_EACH,
        /** Thrown on top, to fall in (a basin, a millstone, crushing wheels). */
        THROW
    }

    /** What players and minds call it, for messages: "the millstone". */
    final String name;
    final In in;
    /** Whether a click of an empty hand takes out what it holds or made. */
    final boolean out;
    /** The words that name it in a command or a tool's arguments, the first its own. */
    final List<String> words;

    Machine(String name, In in, boolean out, String... words) {
        this.name = name;
        this.in = in;
        this.out = out;
        this.words = List.of(words);
    }

    /** Every machine something can be put into. */
    static final Set<Machine> FED = EnumSet.of(BLAZE_BURNER, DEPOT, BASIN, MILLSTONE, CRUSHING_WHEELS, SAW, CHUTE, BELT, FUNNEL,
            DEPLOYER, ITEM_DRAIN);
    /**
     * Every machine something can be taken out of. A mechanical crafter is in neither: a click on one
     * that is crafting throws its whole grid out, and a bot can't tell from its look whether it is.
     */
    static final Set<Machine> EMPTIED = EnumSet.of(DEPOT, BASIN, MILLSTONE, SAW, CHUTE, DEPLOYER, ITEM_DRAIN);

    /** A machine where it is: what it is, and the block a hand goes to (a press's depot, a wheel's gap). */
    record Found(Machine kind, BlockPos pos) {
    }

    /**
     * What machine a block is, as it is: null for anything else. A valve handle is no crank (a click
     * turns it a step, it is not held), a saw takes things only facing up, crushing wheels are their
     * gap (the block between the wheels, where things fall in), and a funnel only while it takes
     * things in (not pulling them out, not powered).
     */
    static Machine kind(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof HandCrankBlock) return b instanceof ValveHandleBlock ? null : HAND_CRANK;
        if (b instanceof BlazeBurnerBlock) return BLAZE_BURNER;
        if (b instanceof DepotBlock || b instanceof EjectorBlock) return DEPOT;
        if (b instanceof BasinBlock) return BASIN;
        if (b instanceof MillstoneBlock) return MILLSTONE;
        if (b instanceof CrushingWheelControllerBlock) {
            return state.getValue(CrushingWheelControllerBlock.VALID) ? CRUSHING_WHEELS : null;
        }
        if (b instanceof SawBlock) return state.getValue(BlockStateProperties.FACING) == Direction.UP ? SAW : null;
        if (b instanceof AbstractChuteBlock) return CHUTE;
        if (b instanceof BeltBlock) return BELT;
        if (b instanceof FunnelBlock) {
            return !state.getValue(FunnelBlock.EXTRACTING) && !state.getValue(BlockStateProperties.POWERED) ? FUNNEL : null;
        }
        if (b instanceof MechanicalCrafterBlock) return CRAFTER;
        if (b instanceof DeployerBlock) return DEPLOYER;
        if (b instanceof ItemDrainBlock) return ITEM_DRAIN;
        return null;
    }

    /**
     * The machine a player means by the block they point at: the block itself, or, for one that works
     * on another, that one (a press or a mixer works on what is two blocks under it, a depot, a belt or
     * a basin; a crushing wheel on the gap beside it). Null when it means none.
     */
    static Found at(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Machine kind = kind(state);
        if (kind != null) return new Found(kind, pos.immutable());
        Block b = state.getBlock();
        if (b instanceof MechanicalPressBlock || b instanceof MechanicalMixerBlock) {
            BlockPos under = pos.below(2);
            Machine below = kind(level.getBlockState(under));
            return below == DEPOT || below == BELT || below == BASIN ? new Found(below, under) : null;
        }
        if (b instanceof CrushingWheelBlock) {
            for (Direction d : Direction.values()) {
                BlockPos next = pos.relative(d);
                if (kind(level.getBlockState(next)) == CRUSHING_WHEELS) return new Found(CRUSHING_WHEELS, next);
            }
        }
        return null;
    }

    /**
     * The face a click must land on, or null for any: a depot's top, a funnel's open side, a
     * crafter's and a deployer's front.
     */
    Direction face(BlockState state) {
        return switch (this) {
            case DEPOT -> Direction.UP;
            case FUNNEL -> AbstractFunnelBlock.getFunnelFacing(state);
            case CRAFTER -> state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            case DEPLOYER -> state.getValue(BlockStateProperties.FACING);
            default -> null;
        };
    }

    /**
     * How far from the middle of its top a thrown thing may land and still go in: a basin's
     * opening is narrower than its block (its rim is a wall), a chute is narrower than a block.
     */
    double opening() {
        return switch (this) {
            case BASIN -> 0.25;
            case CHUTE -> 0.3;
            default -> 0.4;
        };
    }

    /** The height of its top above its block's floor, where a thrown thing lands: its shape's, else a whole block. */
    static double top(Level level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
    }

    /** "is", or "are" for what is called by a plural ("the crushing wheels are full"). */
    String is() {
        return this == CRUSHING_WHEELS ? "are" : "is";
    }

    /** The machine a word names ("basin", "Blaze burner", "the millstone"), or null. */
    static Machine named(String word) {
        String w = plain(word);
        for (Machine m : values()) {
            if (m.words.contains(w)) return m;
        }
        return null;
    }

    /**
     * Whether a word names a machine that works on another under it ("press", "mixer"): it means
     * the depot, belt or basin under the nearest one ({@link #at}).
     */
    static boolean overhead(String word) {
        String w = plain(word);
        return w.equals("press") || w.equals("mechanical_press") || w.equals("mixer") || w.equals("mechanical_mixer");
    }

    /**
     * Whether a block is no floor for a bot to stand on while it works: a machine (a belt carries it
     * off, a basin is no floor), and what hurts whoever stands on it (crushing wheels, a saw, a drill).
     */
    static boolean underfoot(BlockState state) {
        Block b = state.getBlock();
        return kind(state) != null || b instanceof CrushingWheelBlock || b instanceof SawBlock || b instanceof DrillBlock
                || b instanceof BeltBlock || b instanceof BasinBlock;
    }

    /** Whether a block works on what is under it: a press or a mixer. */
    static boolean overhead(BlockState state) {
        return state.getBlock() instanceof MechanicalPressBlock || state.getBlock() instanceof MechanicalMixerBlock;
    }

    private static String plain(String word) {
        if (word == null) return "";
        String w = word.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (w.startsWith("the_")) w = w.substring(4);
        if (w.startsWith("create:")) w = w.substring(7);
        return w;
    }

    /** The words a tool's argument may take, for its schema: each machine's own word. */
    static List<String> words(Set<Machine> kinds) {
        List<String> out = new ArrayList<>();
        for (Machine m : kinds) out.add(m.words.get(0));
        return out;
    }
}
