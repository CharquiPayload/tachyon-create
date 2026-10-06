package tachyoncreate;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import tachyon.api.Ability;
import tachyon.api.Bot;
import tachyon.api.Job;
import tachyon.api.Kit;
import tachyon.api.TachyonCommands;
import tachyon.api.ToolCall;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A factory's hands: bots that turn hand cranks, put things into Create's machines and take out what
 * they made, and keep blaze burners lit. Each is a tool for a bot's mind, a command
 * ({@code /bot <who> crank|feed|collect|stoke}) and a row on its page in the menu, in a group of its
 * own called Create.
 *
 * <p>A command's machine is the one at the coordinates given ({@code at <pos>}), else the one the
 * player giving it points at, else the nearest the bot sees ({@link Targets}).
 */
final class Factory implements Ability {

    /** Its settings' keys, as players type them: {@code /bot Ada set crank_time 120}. */
    static final String CRANK_TIME = "crank_time", STOKE_RANGE = "stoke_range";
    /** The group of the menu its rows and settings are in. */
    static final String GROUP = "Create";

    /** How long a crank may be turned by one order, in seconds: an hour. */
    private static final int MOST_SECONDS = 3600;
    /** How near a Create machine a bot must be for its mind to be offered these tools, and how often that is looked at. */
    private static final double NEAR = 32;
    private static final int LOOK_EVERY = 30 * 20;

    @Override
    public String id() {
        return "factory";
    }

    /**
     * What Create adds to the world that its model can not know: where its ore lies. Asked, a bot said
     * zinc was "most common near Y 40" (M-QCF8); Create 6 spreads it evenly from Y -63 to 70.
     */
    @Override
    public void rules(Bot bot, java.util.List<String> lines) {
        lines.add("Create zinc ore lies evenly anywhere from Y -63 to 70 in the overworld (no level is richer);"
                + " strip mine for it above Y 0 (say Y 16), where stone breaks quicker than deepslate.");
    }

    @Override
    public void declare(Kit kit) {
        kit.number(CRANK_TIME, 60, 5, MOST_SECONDS, "How long it turns a hand crank when told to, in seconds.")
                .label("Crank time").group(GROUP).basic();
        kit.number(STOKE_RANGE, 16, 4, 48, "How far from where it starts it looks after blaze burners, in blocks.")
                .label("Burner range").group(GROUP).advanced();
        tools(kit);
        words(kit);
        rows(kit);
    }

    // --- the mind's tools ------------------------------------------------------------------------------

    private void tools(Kit kit) {
        kit.tool("crank", "Turn a Create hand crank for a while, holding it as a player does: it powers what it's hooked up to."
                        + " It makes you hungry.")
                .optional("seconds", "integer", "How long, 5 to 3600; leave it out for your own crank time")
                .optional("backwards", "boolean", "Turn it the other way")
                .optional("x", "integer", "The crank's x, if you know it")
                .optional("y", "integer", "Its y")
                .optional("z", "integer", "Its z")
                .rule("Create's machines: crank turns a hand crank; feed_machine puts what you carry into a machine (fuel into a"
                        + " blaze burner); collect_from_machine takes out what it made; stoke_burners keeps blaze burners lit.")
                .offeredWhen(Factory::nearMachines)
                .run(call -> {
                    Targets.Pick pick = target(call, EnumSet.of(Machine.HAND_CRANK), "hand crank", null);
                    if (pick.found() == null) return pick.why();
                    Machine.Found f = pick.found();
                    int seconds = clampSeconds(call.number("seconds", (int) call.bot().number(CRANK_TIME)));
                    boolean back = call.flag("backwards", false);
                    Crank job = new Crank(pick.options(), seconds * 20, back);
                    call.start(job, "cranking");
                    return "started cranking " + job.where() + " for " + Text.time(seconds * 20) + (back ? ", backwards" : "")
                            + "; not done yet";
                });

        kit.tool("feed_machine", "Put things you carry into a Create machine: a depot, basin, millstone, crushing wheels, saw,"
                        + " chute, belt, funnel, deployer or item drain; or fuel into a blaze burner. A press or a mixer means the"
                        + " depot or basin under it.")
                .optional("item", "string", "What to put in, as an id or a name (iron_ore, coal); leave it out for fuel in a blaze burner")
                .optional("count", "integer", "How many; leave it out for all you have of it")
                .optional("machine", "string", "Which kind, if you don't give its place: the nearest one you see")
                .optional("x", "integer", "The machine's x, if you know it")
                .optional("y", "integer", "Its y")
                .optional("z", "integer", "Its z")
                .offeredWhen(Factory::nearMachines)
                .run(call -> {
                    String item = call.text("item");
                    Targets.Pick pick = target(call, Machine.FED, "machine to put things in", call.text("machine"));
                    if (pick.found() == null) return pick.why();
                    Machine.Found f = pick.found();
                    Predicate<ItemStack> what;
                    String asked;
                    if (item == null || item.isBlank() || item.equalsIgnoreCase("fuel")) {
                        if (f.kind() != Machine.BLAZE_BURNER) return "say what to put in the " + f.kind().name;
                        what = Fuel.ANY;
                        asked = "fuel";
                    } else {
                        what = Carried.match(call.bot().body(), item);
                        asked = item.trim();
                        if (what == null) return "I've got no " + asked + " on me";
                    }
                    int count = call.number("count", 0);
                    Feed job = new Feed(pick.options(), what, asked, count > 0 ? count : defaultCount(call.bot(), f, what));
                    call.start(job, "feeding the " + f.kind().name);
                    return "started putting " + asked + " in " + job.where() + "; not done yet";
                });

        kit.tool("collect_from_machine", "Take out what a Create machine holds or made: a depot, basin, millstone, saw, chute,"
                        + " deployer or item drain. A press or a mixer means the depot or basin under it.")
                .optional("machine", "string", "Which kind, if you don't give its place: the nearest one you see")
                .optional("x", "integer", "The machine's x, if you know it")
                .optional("y", "integer", "Its y")
                .optional("z", "integer", "Its z")
                .offeredWhen(Factory::nearMachines)
                .run(call -> {
                    Targets.Pick pick = target(call, Machine.EMPTIED, "machine to take things out of", call.text("machine"));
                    if (pick.found() == null) return pick.why();
                    Machine.Found f = pick.found();
                    Collect job = new Collect(pick.options());
                    call.start(job, "taking things out of the " + f.kind().name);
                    return "started taking things out of " + job.where() + "; not done yet";
                });

        kit.tool("stoke_burners", "Keep the Create blaze burners around you lit with fuel you carry, until told to stop: a"
                        + " standing order. It only tends burners that heat something.")
                .optional("range", "integer", "How far from where you are, 4 to 48 blocks; leave it out for your own range")
                .offeredWhen(Factory::nearMachines)
                .run(call -> {
                    ServerPlayer body = call.bot().body();
                    double range = Math.max(4, Math.min(48, call.number("range", (int) call.bot().number(STOKE_RANGE))));
                    if (Fuel.pick(body).isEmpty()) return "I've got no fuel for the blaze burners";
                    call.start(new Stoke(body, body.blockPosition(), range, pointedBurner(call.speaker(), body)),
                            "keeping the blaze burners lit");
                    return "started keeping the blaze burners lit within " + (int) range + " blocks, until told to stop";
                });
    }

    /** The machine a tool's call means ({@link Targets}), or why none. */
    private static Targets.Pick target(ToolCall call, java.util.Set<Machine> kinds, String what, String word) {
        BlockPos at = null;
        if (call.args().has("x") && call.args().has("y") && call.args().has("z")) {
            at = new BlockPos(call.number("x", 0), call.number("y", 0), call.number("z", 0));
        }
        return Targets.find(call.bot().body(), kinds, what, at, word, call.speaker());
    }

    // --- commands ----------------------------------------------------------------------------------------

    private void words(Kit kit) {
        // /bot <who> crank [<seconds>] [back], and the same after "at <pos>".
        kit.botWord("crank", (word, context) -> {
            word.executes(c -> crank(c, false, false, false));
            crankArgs(word, false);
            word.then(Commands.literal("at").then(crankArgs(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(c -> crank(c, true, false, false)), true)));
        });

        // /bot <who> feed [<item> [<count>]] [at <pos>]: no item is fuel, for a blaze burner.
        kit.botWord("feed", (word, context) -> word
                .executes(c -> feed(c, false, false, false))
                .then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> feed(c, false, false, true))))
                .then(Commands.argument("item", ItemArgument.item(context))
                        .executes(c -> feed(c, true, false, false))
                        .then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(c -> feed(c, true, false, true))))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64 * 36))
                                .executes(c -> feed(c, true, true, false))
                                .then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(c -> feed(c, true, true, true)))))));

        // /bot <who> collect [at <pos>]
        kit.botWord("collect", (word, context) -> word
                .executes(c -> collect(c, false))
                .then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> collect(c, true)))));

        // /bot <who> stoke [<range>]
        kit.botWord("stoke", (word, context) -> word
                .executes(c -> stoke(c, 0))
                .then(Commands.argument("range", IntegerArgumentType.integer(4, 48))
                        .executes(c -> stoke(c, IntegerArgumentType.getInteger(c, "range")))));
    }

    /** {@code [<seconds>] [back]} under a crank's word or its place. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T crankArgs(T node, boolean at) {
        node.then(Commands.literal("back").executes(c -> crank(c, at, false, true)));
        node.then(Commands.argument("seconds", IntegerArgumentType.integer(1, MOST_SECONDS))
                .executes(c -> crank(c, at, true, false))
                .then(Commands.literal("back").executes(c -> crank(c, at, true, true))));
        return node;
    }

    private static int crank(CommandContext<CommandSourceStack> c, boolean at, boolean timed, boolean back) throws CommandSyntaxException {
        BlockPos pos = at ? BlockPosArgument.getLoadedBlockPos(c, "pos") : null;
        int given = timed ? IntegerArgumentType.getInteger(c, "seconds") : 0;
        return each(c, "cranking", bot -> {
            Targets.Pick pick = Targets.find(bot.body(), EnumSet.of(Machine.HAND_CRANK), "hand crank", pos, null, c.getSource().getPlayer());
            if (pick.found() == null) return pick.why();
            int seconds = given > 0 ? given : clampSeconds((int) bot.number(CRANK_TIME));
            bot.start(new Crank(pick.options(), seconds * 20, back), "cranking");
            return null;
        });
    }

    private static int feed(CommandContext<CommandSourceStack> c, boolean named, boolean counted, boolean at) throws CommandSyntaxException {
        BlockPos pos = at ? BlockPosArgument.getLoadedBlockPos(c, "pos") : null;
        Item item = named ? ItemArgument.getItem(c, "item").getItem() : null;
        int count = counted ? IntegerArgumentType.getInteger(c, "count") : 0;
        return each(c, "feeding a machine", bot -> {
            Targets.Pick pick = Targets.find(bot.body(), item == null ? EnumSet.of(Machine.BLAZE_BURNER) : Machine.FED,
                    item == null ? "blaze burner" : "machine to put things in", pos, null, c.getSource().getPlayer());
            if (pick.found() == null) return pick.why();
            Predicate<ItemStack> what = item == null ? Fuel.ANY : s -> s.is(item);
            String asked = item == null ? "fuel" : Text.name(item);
            if (Hands.count(bot.body(), what) == 0) return "I've got no " + asked + " on me";
            bot.start(new Feed(pick.options(), what, asked, count > 0 ? count : defaultCount(bot, pick.found(), what)),
                    "feeding the " + pick.found().kind().name);
            return null;
        });
    }

    private static int collect(CommandContext<CommandSourceStack> c, boolean at) throws CommandSyntaxException {
        BlockPos pos = at ? BlockPosArgument.getLoadedBlockPos(c, "pos") : null;
        return each(c, "taking things out", bot -> {
            Targets.Pick pick = Targets.find(bot.body(), Machine.EMPTIED, "machine to take things out of", pos, null,
                    c.getSource().getPlayer());
            if (pick.found() == null) return pick.why();
            bot.start(new Collect(pick.options()), "taking things out of the " + pick.found().kind().name);
            return null;
        });
    }

    private static int stoke(CommandContext<CommandSourceStack> c, int given) throws CommandSyntaxException {
        return each(c, "keeping the blaze burners lit", bot -> {
            ServerPlayer body = bot.body();
            if (Fuel.pick(body).isEmpty()) return "I've got no fuel for the blaze burners";
            double range = given > 0 ? given : bot.number(STOKE_RANGE);
            bot.start(new Stoke(body, body.blockPosition(), range, pointedBurner(c.getSource().getPlayer(), body)),
                    "keeping the blaze burners lit");
            return null;
        });
    }

    /**
     * An order for each bot the command names that its source may order: {@code start} starts it, or
     * says why not. Answered as Tachyon answers: who went, or why none did.
     */
    static int each(CommandContext<CommandSourceStack> c, String doing, Function<Bot, String> start) throws CommandSyntaxException {
        List<Bot> them = TachyonCommands.bots(c);
        if (them.isEmpty()) return 0;           // it already said why
        List<Bot> went = new ArrayList<>();
        List<String> why = new ArrayList<>();
        for (Bot b : them) {
            String no = start.apply(b);
            if (no == null) went.add(b);
            else why.add(b.name() + ": " + no);
        }
        if (went.isEmpty()) return TachyonCommands.fail(c.getSource(), String.join("\n", why));
        if (!why.isEmpty()) TachyonCommands.say(c.getSource(), String.join("\n", why));
        return TachyonCommands.told(c, went, doing);
    }

    // --- the menu ----------------------------------------------------------------------------------------

    private void rows(Kit kit) {
        kit.botRow("crank_now").label("Turn a crank").icon("create:hand_crank").group(GROUP)
                .about("Turns the hand crank you look at, or the nearest one it sees, for its crank time.")
                .runs("crank");
        kit.botRow("feed_now").label("Feed a machine").icon("create:depot").group(GROUP)
                .about("Puts what you name into the machine you look at, or the nearest one it sees.")
                .asks("What goes in?", "An item and how many, like coal 8.", 40, "feed");
        kit.botRow("collect_now").label("Take out").icon("create:basin").group(GROUP)
                .about("Takes out what the machine you look at holds or made.")
                .runs("collect");
        kit.botRow("stoke_now").label("Keep burners lit").icon("create:blaze_burner").group(GROUP)
                .about("Keeps the blaze burners around it lit with fuel it carries, until you stop it.")
                .runs("stoke");
    }

    // --- helpers -----------------------------------------------------------------------------------------

    /**
     * How many go in when nobody said: fuel for a burner, what fits ({@link Fuel#refill}); a depot or a
     * deployer, a stack; anything else, all it carries.
     */
    static int defaultCount(Bot bot, Machine.Found f, Predicate<ItemStack> what) {
        return switch (f.kind()) {
            case BLAZE_BURNER -> {
                ItemStack fuel = Hands.first(bot.body(), what);
                yield Math.max(1, Fuel.refill(fuel));
            }
            case DEPOT, DEPLOYER -> 64;
            default -> Integer.MAX_VALUE;
        };
    }

    /** A burner the player points at, to be known from the start of a stoke; null for none. */
    private static BlockPos pointedBurner(ServerPlayer player, ServerPlayer body) {
        if (player == null || player.level() != body.level()) return null;
        BlockPos p = Sight.pointedAt(player);
        return p != null && Machine.kind(body.level().getBlockState(p)) == Machine.BLAZE_BURNER ? p : null;
    }

    private static int clampSeconds(int s) {
        return Math.max(5, Math.min(MOST_SECONDS, s));
    }

    /** What a bot remembers between looks: whether a Create machine was near, and when it last looked. */
    static final class Near {
        boolean near, looked;
        long at;
    }

    /**
     * Whether a Create machine is near the bot, so that its mind is offered these tools: looked at once
     * every {@link #LOOK_EVERY} ticks at most, among the block entities of the chunks around it.
     */
    static boolean nearMachines(Bot bot) {
        Near near = bot.slot(Near.class, Near::new);
        ServerPlayer body = bot.body();
        long now = body.level().getGameTime();
        if (near.looked && now - near.at < LOOK_EVERY) return near.near;
        near.looked = true;
        near.at = now;
        near.near = !Sight.blockEntities(body.serverLevel(), body.blockPosition(), NEAR,
                (BlockEntity be) -> {
                    Machine k = Machine.kind(be.getBlockState());
                    // A stock ticker or a mailbox is no machine for these tools: Post's own are offered there.
                    return k != null && k != Machine.STOCK_TICKER && k != Machine.MAILBOX;
                }).isEmpty();
        return near.near;
    }
}
