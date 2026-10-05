package tachyoncreate;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.simibubi.create.content.trains.station.StationBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tachyon.api.Ability;
import tachyon.api.Kit;

import java.util.UUID;

/**
 * A railway to try the trains on, for a development server only ({@code -Dtachyon_create.dev=true},
 * as {@link DevBrain}): {@code /tcdev rail <pos>} lays a straight track 64 blocks east of a place,
 * with a station "West" at its west end and "East" near its east end, and a one-bogey train at East
 * with its controls and a seat. {@code /tcdev assemble <pos>} assembles it again, from East's place.
 */
final class DevTrain implements Ability {

    private static final int LENGTH = 64, WEST = 4, EAST = 50;

    @Override
    public String id() {
        return "dev_train";
    }

    @Override
    public void declare(Kit kit) {
        kit.on(RegisterCommandsEvent.class, e -> e.getDispatcher().register(Commands.literal("tcdev").requires(s -> s.hasPermission(2))
                .then(Commands.literal("rail").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(DevTrain::rail)))
                .then(Commands.literal("info").executes(DevTrain::info))
                .then(Commands.literal("name").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(DevTrain::name))))
                .then(Commands.literal("disassemble").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(DevTrain::disassemble)))
                .then(Commands.literal("assemble").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(DevTrain::assemble)))));
    }

    private static int rail(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos o = BlockPosArgument.getLoadedBlockPos(c, "pos");
        ServerLevel level = c.getSource().getLevel();
        for (int i = 0; i < LENGTH; i++) {
            level.setBlock(o.offset(i, -1, 0), state("minecraft:stone"), 3);
            level.setBlock(o.offset(i, -1, 1), state("minecraft:stone"), 3);
            level.setBlock(o.offset(i, 0, 0), with(state("create:track"), "shape", "xo"), 3);
        }
        station(level, o.offset(WEST, 0, 1), "West", false);
        station(level, o.offset(EAST, 0, 1), "East", true);
        // The train: a bogey on the track a block past East (where its assembly looks first), casing
        // on it, controls facing east (forward: its assembly's way) and a seat each side, glued.
        BlockPos b = o.offset(EAST + 1, 1, 0);
        level.setBlock(b, with(state("create:small_bogey"), "axis", "x"), 3);
        for (int dx = -1; dx <= 1; dx++) level.setBlock(b.offset(dx, 1, 0), state("create:andesite_casing"), 3);
        level.setBlock(b.offset(0, 2, 0), with(state("create:controls"), "facing", "east"), 3);
        level.setBlock(b.offset(-1, 2, 0), state("create:white_seat"), 3);
        level.setBlock(b.offset(1, 2, 0), state("create:white_seat"), 3);
        level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                com.simibubi.create.content.contraptions.glue.SuperGlueEntity.span(b.offset(-1, 0, 0), b.offset(1, 2, 0))));
        c.getSource().sendSuccess(() -> Component.literal("rail laid; now /tcdev assemble " + o.getX() + " " + o.getY() + " " + o.getZ()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int assemble(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos o = BlockPosArgument.getLoadedBlockPos(c, "pos");
        ServerLevel level = c.getSource().getLevel();
        if (!(level.getBlockEntity(o.offset(EAST, 0, 1)) instanceof StationBlockEntity s)) {
            c.getSource().sendFailure(Component.literal("no station at East"));
            return 0;
        }
        // Named now: a station's name sticks once its point on the tracks is made, a tick or two after it is placed.
        if (level.getBlockEntity(o.offset(WEST, 0, 1)) instanceof StationBlockEntity w) w.updateName("West");
        s.updateName("East");
        boolean mode = s.enterAssemblyMode(null);
        s.refreshAssemblyInfo();
        String dir = String.valueOf(s.getAssemblyDirection());
        String valid = s.edgePoint.hasValidTrack() + " at " + s.edgePoint.getGlobalPosition() + ", bogeys " + bogeys(s);
        c.getSource().sendSuccess(() -> Component.literal("assembly direction " + dir + ", track " + valid), false);
        s.assemble(UUID.randomUUID());
        // Out of assembly mode, as a player's station is once its train is made: a station left in it is
        // no stop, and stands in the way of every train that would pass it.
        s.exitAssemblyMode();
        c.getSource().sendSuccess(() -> Component.literal("assembly mode " + mode + "; station " + (s.getStation() == null ? "none"
                : s.getStation().name + ", train there: " + (s.getStation().getPresentTrain() != null))), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int name(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos at = BlockPosArgument.getLoadedBlockPos(c, "pos");
        String name = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name");
        if (!(c.getSource().getLevel().getBlockEntity(at) instanceof StationBlockEntity s)) {
            c.getSource().sendFailure(Component.literal("no station there"));
            return 0;
        }
        boolean done = s.updateName(name);
        c.getSource().sendSuccess(() -> Component.literal("named " + name + ": " + done), false);
        return 1;
    }

    private static int disassemble(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos o = BlockPosArgument.getLoadedBlockPos(c, "pos");
        if (!(c.getSource().getLevel().getBlockEntity(o.offset(EAST, 0, 1)) instanceof StationBlockEntity s)) return 0;
        boolean done = s.tryDisassembleTrain(null);
        c.getSource().sendSuccess(() -> Component.literal("taken apart: " + done), false);
        return 1;
    }

    /** The nearest carriage's controls and seats, as the bot's drive sees them. */
    private static int info(CommandContext<CommandSourceStack> c) {
        var list = c.getSource().getLevel().getEntitiesOfClass(com.simibubi.create.content.trains.entity.CarriageContraptionEntity.class,
                new net.minecraft.world.phys.AABB(BlockPos.containing(c.getSource().getPosition())).inflate(200));
        for (var e : list) {
            BlockPos ctl = Rails.controls(e);
            var t = e.getCarriage() == null ? null : e.getCarriage().train;
            StringBuilder st = new StringBuilder();
            if (t != null) {
                st.append(" | train doubleEnded ").append(t.doubleEnded).append(", at ").append(t.getCurrentStation() == null ? null : t.getCurrentStation().name)
                        .append(", ahead ").append(name(t.navigation.findNearestApproachable(true))).append(", behind ")
                        .append(name(t.navigation.findNearestApproachable(false)));
                for (var g : Rails.stations(t)) {
                    st.append(" | ").append(g.name).append(": path ").append(t.navigation.findPathTo(g, Double.MAX_VALUE) != null)
                            .append("/").append(t.navigation.findPathTo(g, -1) != null);
                }
            }
            String line = "carriage at " + e.blockPosition() + ": controls " + ctl + ", seats " + e.getContraption().getSeats()
                    + ", free seat " + (ctl == null ? null : Rails.seat(e, ctl)) + st;
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return list.size();
    }

    private static String name(com.simibubi.create.content.trains.station.GlobalStation g) {
        return g == null ? "none" : g.name;
    }

    /** Where its assembly found bogeys, for this dev command's report (a field of Create's own). */
    private static String bogeys(StationBlockEntity s) {
        try {
            java.lang.reflect.Field f = StationBlockEntity.class.getDeclaredField("bogeyLocations");
            f.setAccessible(true);
            return java.util.Arrays.toString((int[]) f.get(s));
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /** A station beside the track, aimed at the track block next to it, named. */
    private static void station(ServerLevel level, BlockPos at, String name, boolean positive) {
        level.setBlock(at, with(state("create:track_station"), "facing", "north"), 3);
        BlockEntity be = level.getBlockEntity(at);
        if (be == null) return;
        CompoundTag tag = be.saveWithoutMetadata(level.registryAccess());
        tag.put("TargetTrack", NbtUtils.writeBlockPos(new BlockPos(0, 0, -1)));
        tag.putBoolean("TargetDirection", positive);
        be.loadWithComponents(tag, level.registryAccess());
        be.setChanged();
        if (be instanceof StationBlockEntity s) s.updateName(name);
    }

    private static BlockState state(String id) {
        Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return b.defaultBlockState();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState s, String prop, String value) {
        for (Property p : s.getProperties()) {
            if (p.getName().equals(prop)) {
                var v = p.getValue(value);
                if (v.isPresent()) return s.setValue(p, (Comparable) v.get());
            }
        }
        return s;
    }
}
