package tachyoncreate;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import tachyon.api.Ability;
import tachyon.api.Bot;
import tachyon.api.Kit;
import tachyon.api.ToolCall;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Create 6's logistics, as a player uses them: a look at a network's stock at a stock ticker, an
 * order from it sent to a mailbox and picked up there, and the packages that came to a mailbox taken
 * out and opened. Each is a tool for a bot's mind, a command ({@code /bot <who> stock|order|packages})
 * and a row in the menu's Create group.
 *
 * <p>An order goes to the address it's given, else to the nearest mailbox's; and the bot waits at that
 * mailbox for it, up to its wait for packages.
 */
final class Post implements Ability {

    /** Its setting's key: {@code /bot Ada set package_wait 60}. */
    static final String PACKAGE_WAIT = "package_wait";

    /** How near a stock ticker or a mailbox a bot must be for its mind to be offered these tools, and how often that is looked at. */
    private static final double NEAR = 32;
    private static final int LOOK_EVERY = 30 * 20;
    /** The most of one thing an order may ask for: what a bag holds. */
    private static final int MOST = 64 * 36;

    @Override
    public String id() {
        return "post";
    }

    @Override
    public void declare(Kit kit) {
        kit.number(PACKAGE_WAIT, 120, 0, 600, "How long it waits at a mailbox for what it ordered, in seconds.")
                .label("Package wait").group(Factory.GROUP).advanced();
        tools(kit);
        words(kit);
        rows(kit);
    }

    // --- the mind's tools ------------------------------------------------------------------------------

    private void tools(Kit kit) {
        kit.tool("check_stock", "Look at what a Create stock network has, at a stock ticker with a stock keeper by it.")
                .optional("x", "integer", "The stock ticker's x, if you know it")
                .optional("y", "integer", "Its y")
                .optional("z", "integer", "Its z")
                .rule("Create's stock networks: check_stock tells what a network has; order_stock orders from it at a stock"
                        + " ticker and picks it up at the mailbox (frogport or postbox) it goes to; collect_packages takes out"
                        + " and opens the packages that came to a mailbox. Order only what you need, and only from networks your"
                        + " owner lets you use.")
                .offeredWhen(Post::nearNetwork)
                .run(call -> {
                    Targets.Pick pick = target(call, Machine.STOCK_TICKER, "stock ticker");
                    if (pick.found() == null) return pick.why();
                    Ask job = new Ask(pick.options());
                    call.start(job, "looking at the stock");
                    return "started walking to " + job.where() + " to look at its stock; not done yet";
                });

        kit.tool("order_stock", "Order things from a Create stock network at a stock ticker, as a player does at its keeper,"
                        + " then wait at the mailbox it goes to and open the packages there.")
                .param("item", "string", "What to order, as an id or a name; several with commas, each with its count: iron ingot 32, coal 16")
                .optional("count", "integer", "How many, for one thing; leave it out for a stack")
                .optional("address", "string", "Where it goes; leave it out for the nearest mailbox's address")
                .optional("x", "integer", "The stock ticker's x, if you know it")
                .optional("y", "integer", "Its y")
                .optional("z", "integer", "Its z")
                .offeredWhen(Post::nearNetwork)
                .run(call -> {
                    List<Ask.Want> wants = wants(call.text("item"), call.number("count", 0));
                    if (wants.isEmpty()) return "say what to order";
                    Targets.Pick pick = target(call, Machine.STOCK_TICKER, "stock ticker");
                    if (pick.found() == null) return pick.why();
                    Order job = order(call.bot(), pick.options(), wants, call.text("address"));
                    if (job == null) return NO_ADDRESS;
                    call.start(job, "ordering from the stock network");
                    return "started walking to the stock ticker to order; not done yet";
                });

        kit.tool("collect_packages", "Take the packages out of a Create mailbox (a frogport or a postbox) and open them; with no"
                        + " mailbox there, it picks up the packages lying on the ground there (some shops drop them on a counter).")
                .optional("wait", "integer", "How long to wait there for packages on their way, in seconds; leave it out to take what's there")
                .optional("open", "boolean", "Open them; true when left out")
                .optional("x", "integer", "The mailbox's x (or where the packages lie), if you know it")
                .optional("y", "integer", "Its y")
                .optional("z", "integer", "Its z")
                .offeredWhen(Post::nearNetwork)
                .run(call -> {
                    Targets.Pick pick = target(call, Machine.MAILBOX, "mailbox");
                    int wait = Math.max(0, Math.min(600, call.number("wait", 0)));
                    if (pick.found() == null) {
                        // No mailbox: the packages lying on the ground there, or by it (M-L4BJ).
                        BlockPos at = call.args().has("x") && call.args().has("y") && call.args().has("z")
                                ? new BlockPos(call.number("x", 0), call.number("y", 0), call.number("z", 0))
                                : call.bot().body().blockPosition();
                        if (!Ground.any(call.bot().body(), List.of(at), null)) return pick.why() + ", and no packages lie on the ground there";
                        call.start(new Ground(List.of(at), null, null, wait * 20, call.flag("open", true)), "picking up packages");
                        return "started picking up the packages on the ground there; not done yet";
                    }
                    Pickup job = new Pickup(pick.options(), null, null, wait * 20, call.flag("open", true));
                    call.start(job, "picking up packages");
                    return "started walking to " + job.where() + " to take out its packages; not done yet";
                });
    }

    private static final String NO_ADDRESS = "I don't know where to send it: say an address, or there must be a mailbox with an address near me";

    /** The ticker or mailbox a tool's call means ({@link Targets}), or why none. */
    private static Targets.Pick target(ToolCall call, Machine kind, String what) {
        BlockPos at = null;
        if (call.args().has("x") && call.args().has("y") && call.args().has("z")) {
            at = new BlockPos(call.number("x", 0), call.number("y", 0), call.number("z", 0));
        }
        return Targets.find(call.bot().body(), EnumSet.of(kind), what, at, null, call.speaker());
    }

    /**
     * An order of {@code wants} at the tickers of {@code options}, to {@code address} (the nearest
     * mailbox's when null), picked up at the nearest mailbox it goes to. Null when it has nowhere to go.
     */
    static Order order(Bot bot, List<Machine.Found> options, List<Ask.Want> wants, String address) {
        ServerPlayer body = bot.body();
        if (address == null || address.isBlank()) {
            List<PackagePortBlockEntity> near = Network.mailboxes(body, null);
            if (near.isEmpty()) return null;
            address = Network.address(near.get(0));
        }
        address = address.trim();
        List<Machine.Found> boxes = new ArrayList<>();
        for (PackagePortBlockEntity p : Network.mailboxes(body, address)) {
            if (boxes.size() < 4) boxes.add(new Machine.Found(Machine.MAILBOX, p.getBlockPos().immutable()));
        }
        int wait = (int) bot.number(PACKAGE_WAIT) * 20;
        return new Order(new Ask(options, wants, address), boxes, address, wait);
    }

    /**
     * What an order's words ask for: "iron ingot 32, coal 16", or one thing with {@code count} (0 for a
     * stack). A number at the end of a part is how many of it.
     */
    static List<Ask.Want> wants(String words, int count) {
        List<Ask.Want> out = new ArrayList<>();
        if (words == null) return out;
        String[] parts = words.split(",");
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int n = 0;
            int space = p.lastIndexOf(' ');
            if (space > 0) {
                try {
                    n = Integer.parseInt(p.substring(space + 1).trim());
                    p = p.substring(0, space).trim();
                } catch (NumberFormatException ignored) {
                    // No count: the whole part is the name.
                }
            }
            if (n <= 0 && parts.length == 1) n = count;
            out.add(new Ask.Want(p, Math.max(0, Math.min(MOST, n))));
        }
        return out;
    }

    // --- commands ----------------------------------------------------------------------------------------

    private void words(Kit kit) {
        // /bot <who> stock [at <pos>]
        kit.botWord("stock", (word, context) -> word
                .executes(c -> stock(c, false))
                .then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> stock(c, true)))));

        // /bot <who> order <item> [<count>] [to <address>]
        kit.botWord("order", (word, context) -> word
                .then(Commands.argument("item", ItemArgument.item(context))
                        .executes(c -> order(c, false, false))
                        .then(Commands.literal("to").then(Commands.argument("address", StringArgumentType.string())
                                .executes(c -> order(c, false, true))))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MOST))
                                .executes(c -> order(c, true, false))
                                .then(Commands.literal("to").then(Commands.argument("address", StringArgumentType.string())
                                        .executes(c -> order(c, true, true)))))));

        // /bot <who> packages [at <pos>]
        kit.botWord("packages", (word, context) -> word
                .executes(c -> packages(c, false))
                .then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(c -> packages(c, true)))));
    }

    private static int stock(CommandContext<CommandSourceStack> c, boolean at) throws CommandSyntaxException {
        BlockPos pos = at ? BlockPosArgument.getLoadedBlockPos(c, "pos") : null;
        return Factory.each(c, "looking at the stock", bot -> {
            Targets.Pick pick = Targets.find(bot.body(), EnumSet.of(Machine.STOCK_TICKER), "stock ticker", pos, null,
                    c.getSource().getPlayer());
            if (pick.found() == null) return pick.why();
            bot.start(new Ask(pick.options()), "looking at the stock");
            return null;
        });
    }

    private static int order(CommandContext<CommandSourceStack> c, boolean counted, boolean addressed) throws CommandSyntaxException {
        String item = BuiltInRegistries.ITEM.getKey(ItemArgument.getItem(c, "item").getItem()).toString();
        int count = counted ? IntegerArgumentType.getInteger(c, "count") : 0;
        String address = addressed ? StringArgumentType.getString(c, "address") : null;
        return Factory.each(c, "ordering from the stock network", bot -> {
            Targets.Pick pick = Targets.find(bot.body(), EnumSet.of(Machine.STOCK_TICKER), "stock ticker", null, null,
                    c.getSource().getPlayer());
            if (pick.found() == null) return pick.why();
            Order job = order(bot, pick.options(), List.of(new Ask.Want(item, count)), address);
            if (job == null) return NO_ADDRESS;
            bot.start(job, "ordering from the stock network");
            return null;
        });
    }

    private static int packages(CommandContext<CommandSourceStack> c, boolean at) throws CommandSyntaxException {
        BlockPos pos = at ? BlockPosArgument.getLoadedBlockPos(c, "pos") : null;
        return Factory.each(c, "picking up packages", bot -> {
            Targets.Pick pick = Targets.find(bot.body(), EnumSet.of(Machine.MAILBOX), "mailbox", pos, null, c.getSource().getPlayer());
            if (pick.found() == null) return pick.why();
            bot.start(new Pickup(pick.options(), null, null, 0, true), "picking up packages");
            return null;
        });
    }

    // --- the menu ----------------------------------------------------------------------------------------

    private void rows(Kit kit) {
        kit.botRow("stock_now").label("Check stock").icon("create:stock_ticker").group(Factory.GROUP)
                .about("Tells what the stock network has, at the stock ticker you look at or the nearest one it sees.")
                .runs("stock");
        kit.botRow("order_now").label("Order").icon("create:stock_ticker").group(Factory.GROUP)
                .about("Orders from the stock network and picks it up at the nearest mailbox.")
                .asks("What to order?", "An item and how many, like iron_ingot 32.", 60, "order");
        kit.botRow("packages_now").label("Pick up packages").icon("create:package_frogport").group(Factory.GROUP)
                .about("Takes the packages out of the mailbox you look at, or the nearest one it sees, and opens them.")
                .runs("packages");
    }

    // --- helpers -----------------------------------------------------------------------------------------

    /** What a bot remembers between looks: whether a stock ticker or a mailbox was near, and when it last looked. */
    static final class Near {
        boolean near, looked;
        long at;
    }

    /**
     * Whether a stock ticker or a mailbox is near the bot, so that its mind is offered these tools:
     * looked at once every {@link #LOOK_EVERY} ticks at most.
     */
    static boolean nearNetwork(Bot bot) {
        Near near = bot.slot(Near.class, Near::new);
        ServerPlayer body = bot.body();
        long now = body.level().getGameTime();
        if (near.looked && now - near.at < LOOK_EVERY) return near.near;
        near.looked = true;
        near.at = now;
        near.near = !Sight.blockEntities(body.serverLevel(), body.blockPosition(), NEAR, (BlockEntity be) -> {
            Machine k = Machine.kind(be.getBlockState());
            return k == Machine.STOCK_TICKER || k == Machine.MAILBOX;
        }).isEmpty();
        return near.near;
    }
}
