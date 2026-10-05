package tachyoncreate;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import tachyon.api.Ability;
import tachyon.api.Bot;
import tachyon.api.Kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Create's trains, driven by a bot as a player drives one ({@link Drive}): to a station, round a
 * route of stops with what to wait for at each (its own schedule, kept at the wheel), or to the
 * station nearest a player to pick them up and back. Each is a tool for a bot's mind, a command
 * ({@code /bot <who> drive|route|fetch}) and a row in the menu's Create group.
 */
final class Rail implements Ability {

    /** Its setting's key: {@code /bot Ada set fetch_wait 300}. */
    static final String FETCH_WAIT = "fetch_wait";
    /** Its mail rounds' rest, in seconds: {@code /bot Ada set mail_rest 300}. */
    static final String MAIL_REST = "mail_rest";

    /** How near a train a bot must be (or riding one) for its mind to be offered these tools, and how often that is looked at. */
    private static final double NEAR = 48;
    private static final int LOOK_EVERY = 30 * 20;
    /** A wait at a stop: 2 hours at most. */
    private static final int MOST_SECONDS = 7200;

    @Override
    public String id() {
        return "rail";
    }

    @Override
    public void declare(Kit kit) {
        kit.number(FETCH_WAIT, 300, 30, MOST_SECONDS, "How long it waits at a station for the player it went to pick up, in seconds.")
                .label("Pick-up wait").group(Factory.GROUP).advanced();
        kit.number(MAIL_REST, 120, 10, 3600, "How long it rests at a station between its mail rounds, when it goes round, in seconds.")
                .label("Mail round rest").group(Factory.GROUP).advanced();
        tools(kit);
        mailTools(kit);
        words(kit);
        rows(kit);
    }

    // --- the mind's tools ------------------------------------------------------------------------------

    private void tools(Kit kit) {
        kit.tool("drive_train", "Drive the Create train you're on, or the nearest one with controls, to a station: you sit by"
                        + " its controls and take it there, then get off.")
                .param("station", "string", "The station's name, as its sign says")
                .rule("Create's trains: you drive them yourself, sitting at their controls (a player is never a train's conductor,"
                        + " but drives by hand). drive_train takes one to a station; train_route keeps one going round its stops,"
                        + " waiting at each as you say, until told to stop; fetch_player goes to the station nearest a player,"
                        + " waits for them to get on and brings them back; mail_run takes a train with chests round the stations with"
                        + " postboxes, where Create swaps the mail; train_packages puts packages in its chests or takes them out.")
                .offeredWhen(Rail::nearTrain)
                .run(call -> {
                    String station = call.text("station");
                    if (station == null || station.isBlank()) return "say which station";
                    Drive job = new Drive(List.of(Drive.Stop.at(station.trim())), false);
                    call.start(job, "driving a train");
                    return "started: getting on the train to drive it to " + station.trim() + "; not done yet";
                });

        kit.tool("train_route", "Drive a Create train round a route of stops, your own schedule, waiting at each as it says,"
                        + " until told to stop.")
                .param("stops", "string", "The stops in order, with commas; after a stop's name, what to wait for there: a time"
                        + " (30s, 2m), players N (until N players are aboard), until <player> (until they're aboard), and at most"
                        + " <time>. Like: Spawn 30s, Farm players 2 at most 5m, Mine")
                .optional("once", "boolean", "Go through them once, not round and round")
                .offeredWhen(Rail::nearTrain)
                .run(call -> {
                    List<Drive.Stop> stops = stops(call.text("stops"));
                    if (stops.isEmpty()) return "say its stops";
                    boolean once = call.flag("once", false);
                    call.start(new Drive(stops, !once), "driving a train route");
                    return "started: getting on the train to drive it round " + said(stops) + (once ? ", once" : ", until told to stop")
                            + "; not done yet";
                });

        kit.tool("fetch_player", "Drive a Create train to the station nearest a player, wait there for them to get on, and"
                        + " bring them back to where the train is now.")
                .param("player", "string", "Who to pick up")
                .optional("back", "string", "The station to bring them to; leave it out for where the train is now")
                .optional("wait", "integer", "How long to wait for them there, in seconds; leave it out for your own pick-up wait")
                .offeredWhen(Rail::nearTrain)
                .run(call -> {
                    String who = call.text("player");
                    if (who == null || who.isBlank()) return "say who to pick up";
                    ServerPlayer p = call.bot().body().server.getPlayerList().getPlayerByName(who.trim());
                    if (p == null) return who.trim() + " isn't in the game";
                    int wait = clamp(call.number("wait", (int) call.bot().number(FETCH_WAIT)));
                    call.start(fetch(call.bot(), p.getGameProfile().getName(), call.text("back"), wait), "picking up a player");
                    return "started: getting on the train to pick up " + p.getGameProfile().getName() + "; not done yet";
                });
    }

    /** Mail: a run (or rounds) taking packages to the stations they're for, and the train's packages loaded or unloaded. */
    private void mailTools(Kit kit) {
        kit.tool("mail_run", "Drive a Create train with chests round the stations with postboxes: Create swaps the mail where"
                        + " it stops (packages for a postbox off, the ones waiting there on). You stop at the stations the packages"
                        + " aboard are for, and, picking up, at every station with postboxes, the nearest first.")
                .optional("pick_up", "boolean", "Stop at every station with postboxes to take on what waits there; true when left out")
                .optional("loop", "boolean", "Go round again and again, resting between rounds, until told to stop")
                .offeredWhen(Rail::nearTrain)
                .run(call -> {
                    boolean loop = call.flag("loop", false);
                    Mail mail = new Mail(call.flag("pick_up", true), (int) call.bot().number(MAIL_REST) * 20);
                    call.start(new Drive(mail, loop), "running the mail train");
                    return "started: getting on the train to run the mail" + (loop ? ", round and round until told to stop" : "")
                            + "; not done yet";
                });

        kit.tool("train_packages", "Put the packages you carry into the chests of the Create train you're on or next to,"
                        + " or take packages out of them (all, or those for an address).")
                .param("action", "string", "load (from your bag into the train) or unload (from the train into your bag)")
                .optional("address", "string", "Only packages for this address; leave it out for all")
                .offeredWhen(Rail::nearTrain)
                .run(call -> cargo(call.bot().body(), call.text("action"), call.text("address")));
    }

    /** How near a carriage it must be to reach into its chests: a player's reach and the carriage's size. */
    private static final double CARGO_REACH = 6;

    /**
     * Packages from its bag into the chests of the train it rides or stands by ({@code load}), or out of
     * them into its bag ({@code unload}), all or those for {@code address}, as a player does at a train's
     * chest. What it did, in words.
     */
    static String cargo(ServerPlayer body, String action, String address) {
        boolean load = action != null && action.trim().equalsIgnoreCase("load");
        boolean unload = action != null && action.trim().equalsIgnoreCase("unload");
        if (!load && !unload) return "say load or unload";
        com.simibubi.create.content.trains.entity.CarriageContraptionEntity c = Rails.riding(body);
        if (c == null || c.getCarriage().storage == null) {
            List<com.simibubi.create.content.trains.entity.CarriageContraptionEntity> near = Rails.withCargo(body, CARGO_REACH);
            c = near.isEmpty() ? null : near.get(0);
        }
        if (c == null) return "I'm not by a train with chests";
        var cargo = Rails.cargo(c.getCarriage().train);
        if (cargo.isEmpty()) return "that train has no chests";
        String only = address == null || address.isBlank() ? null : address.trim();
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> mine = s -> com.simibubi.create.content.logistics.box.PackageItem.isPackage(s)
                && (only == null || com.simibubi.create.content.logistics.box.PackageItem.matchAddress(s, only));
        int moved = 0;
        boolean full = false;
        var inv = body.getInventory();
        if (load) {
            for (int i = 0; i < inv.items.size() && !full; i++) {
                net.minecraft.world.item.ItemStack s = inv.items.get(i);
                if (s.isEmpty() || !mine.test(s)) continue;
                int had = s.getCount();
                net.minecraft.world.item.ItemStack rest = s.copy();
                for (var h : cargo) {
                    rest = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(h, rest, false);
                    if (rest.isEmpty()) break;
                }
                inv.items.set(i, rest);
                moved += had - rest.getCount();
                full = !rest.isEmpty();
            }
            inv.setChanged();
            if (moved == 0) return full ? "the train's chests are full" : "I carry no packages" + (only == null ? "" : " for " + only);
            return "put " + moved + (moved == 1 ? " package" : " packages") + " in the train's chests" + (full ? "; they're full now" : "");
        }
        for (var h : cargo) {
            for (int i = 0; i < h.getSlots() && !full; i++) {
                net.minecraft.world.item.ItemStack s = h.getStackInSlot(i);
                if (s.isEmpty() || !mine.test(s)) continue;
                net.minecraft.world.item.ItemStack out = h.extractItem(i, s.getCount(), false);
                int n = out.getCount();
                if (!inv.add(out)) {
                    // What did not fit goes back.
                    if (!out.isEmpty()) h.insertItem(i, out, false);
                    full = true;
                }
                moved += n - out.getCount();
            }
        }
        if (moved == 0) return full ? "my bag is full" : "the train carries no packages" + (only == null ? "" : " for " + only);
        return "took " + moved + (moved == 1 ? " package" : " packages") + " out of the train's chests" + (full ? "; my bag is full now" : "");
    }

    /**
     * The errand to pick up {@code who}: to the station nearest them, waiting until they're aboard (for
     * {@code wait} seconds at most), then to {@code back}, or where the train stands when it sets off.
     */
    static Drive fetch(Bot bot, String who, String back, int wait) {
        List<Drive.Stop> stops = new ArrayList<>();
        stops.add(new Drive.Stop(null, who, 0, 0, who, wait, null));
        String home = back == null || back.isBlank() ? null : back.trim();
        if (home == null) home = Rails.stationHere(bot.body());
        if (home != null) stops.add(Drive.Stop.at(home));
        return new Drive(stops, false);
    }

    /**
     * A route's words as stops: "Spawn 30s, Farm players 2 at most 5m, Mine until Steve". What
     * comes after a stop's name is read from its end: a time, "players N", "until NAME", "at most TIME".
     */
    static List<Drive.Stop> stops(String words) {
        List<Drive.Stop> out = new ArrayList<>();
        if (words == null) return out;
        for (String part : words.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            String[] w = p.split("\\s+");
            int end = w.length, seconds = 0, players = 0, most = 0;
            String until = null;
            boolean more = true;
            while (more && end > 1) {
                more = false;
                String last = w[end - 1].toLowerCase(Locale.ROOT);
                int t = time(last);
                if (t >= 0 && end >= 3 && w[end - 2].equalsIgnoreCase("most") && w[end - 3].equalsIgnoreCase("at")) {
                    most = t;
                    end -= 3;
                    more = true;
                } else if (t >= 0) {
                    seconds = t;
                    end -= 1;
                    more = true;
                } else if (end >= 3 && w[end - 2].equalsIgnoreCase("players") && last.matches("\\d+")) {
                    players = Integer.parseInt(last);
                    end -= 2;
                    more = true;
                } else if (end >= 3 && w[end - 2].equalsIgnoreCase("until")) {
                    until = w[end - 1];
                    end -= 2;
                    more = true;
                }
            }
            String name = String.join(" ", java.util.Arrays.copyOfRange(w, 0, end));
            out.add(new Drive.Stop(name, null, clamp(seconds), Math.min(players, 100), until, clamp(most), null));
        }
        return out;
    }

    /** "30s", "2m", "90": seconds; -1 when it is no time. */
    static int time(String w) {
        if (w.matches("\\d+s")) return Integer.parseInt(w.substring(0, w.length() - 1));
        if (w.matches("\\d+m")) return Integer.parseInt(w.substring(0, w.length() - 1)) * 60;
        return -1;
    }

    private static int clamp(int s) {
        return Math.max(0, Math.min(MOST_SECONDS, s));
    }

    private static String said(List<Drive.Stop> stops) {
        List<String> w = new ArrayList<>();
        for (Drive.Stop s : stops) w.add(s.words());
        return String.join(", ", w);
    }

    // --- commands ----------------------------------------------------------------------------------------

    private void words(Kit kit) {
        // /bot <who> drive <station...>
        kit.botWord("drive", (word, context) -> word
                .then(Commands.argument("station", StringArgumentType.greedyString()).executes(Rail::drive)));
        // /bot <who> route <stops...>: round and round, until told to stop.
        kit.botWord("route", (word, context) -> word
                .then(Commands.argument("stops", StringArgumentType.greedyString()).executes(Rail::route)));
        // /bot <who> mail [loop]
        kit.botWord("mail", (word, context) -> word
                .executes(c -> mail(c, false))
                .then(Commands.literal("loop").executes(c -> mail(c, true))));
        // /bot <who> cargo load|unload [<address>]
        kit.botWord("cargo", (word, context) -> word
                .then(Commands.literal("load").executes(c -> cargo(c, "load", null))
                        .then(Commands.argument("address", StringArgumentType.greedyString()).executes(c -> cargo(c, "load", StringArgumentType.getString(c, "address")))))
                .then(Commands.literal("unload").executes(c -> cargo(c, "unload", null))
                        .then(Commands.argument("address", StringArgumentType.greedyString()).executes(c -> cargo(c, "unload", StringArgumentType.getString(c, "address"))))));
        // /bot <who> fetch <player> [<seconds>]
        kit.botWord("fetch", (word, context) -> word
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(c -> fetch(c, false))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(30, MOST_SECONDS))
                                .executes(c -> fetch(c, true)))));
    }

    private static int mail(CommandContext<CommandSourceStack> c, boolean loop) throws CommandSyntaxException {
        return Factory.each(c, loop ? "running the mail train round and round" : "running the mail train", bot -> {
            bot.start(new Drive(new Mail(true, (int) bot.number(MAIL_REST) * 20), loop), "running the mail train");
            return null;
        });
    }

    private static int cargo(CommandContext<CommandSourceStack> c, String action, String address) throws CommandSyntaxException {
        List<tachyon.api.Bot> them = tachyon.api.TachyonCommands.bots(c);
        if (them.isEmpty()) return 0;
        List<String> said = new ArrayList<>();
        for (tachyon.api.Bot b : them) said.add(b.name() + ": " + cargo(b.body(), action, address));
        tachyon.api.TachyonCommands.say(c.getSource(), String.join("\n", said));
        return them.size();
    }

    private static int drive(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String station = StringArgumentType.getString(c, "station").trim();
        return Factory.each(c, "driving a train to " + station, bot -> {
            bot.start(new Drive(List.of(Drive.Stop.at(station)), false), "driving a train");
            return null;
        });
    }

    private static int route(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        List<Drive.Stop> stops = stops(StringArgumentType.getString(c, "stops"));
        return Factory.each(c, "driving a train round " + said(stops), bot -> {
            if (stops.isEmpty()) return "say its stops";
            bot.start(new Drive(stops, true), "driving a train route");
            return null;
        });
    }

    private static int fetch(CommandContext<CommandSourceStack> c, boolean timed) throws CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        int given = timed ? IntegerArgumentType.getInteger(c, "seconds") : 0;
        String who = p.getGameProfile().getName();
        return Factory.each(c, "picking up " + who + " by train", bot -> {
            bot.start(fetch(bot, who, null, given > 0 ? given : (int) bot.number(FETCH_WAIT)), "picking up a player");
            return null;
        });
    }

    // --- the menu ----------------------------------------------------------------------------------------

    private void rows(Kit kit) {
        kit.botRow("drive_now").label("Drive a train").icon("create:controls").group(Factory.GROUP)
                .about("Gets on the nearest train with controls and drives it to the station you name.")
                .asks("To which station?", "Its name, as its sign says.", 60, "drive");
        kit.botRow("mail_now").label("Run the mail train").icon("create:white_postbox").group(Factory.GROUP)
                .about("Drives the nearest train with chests round the stations with postboxes, leaving and taking on the mail.")
                .runs("mail");
        kit.botRow("fetch_now").label("Pick me up by train").icon("create:track_station").group(Factory.GROUP)
                .about("Drives the nearest train to the station nearest you, waits for you to get on, and brings you back.")
                .asks("Who to pick up?", "A player's name.", 40, "fetch");
    }

    // --- getting off --------------------------------------------------------------------------------------

    @Override
    public int reflexEvery() {
        return 10;
    }

    /** A bot that let go of a train while it still rolled (told to stop on the way): off it once it stands. */
    @Override
    public void reflex(Bot bot, long now) {
        if (Drive.GETTING_OFF.isEmpty() || !Drive.GETTING_OFF.contains(bot.body().getUUID())) return;
        if (Drive.getOff(bot.body())) Drive.GETTING_OFF.remove(bot.body().getUUID());
    }

    // --- helpers -----------------------------------------------------------------------------------------

    /** What a bot remembers between looks: whether a train was near, and when it last looked. */
    static final class Near {
        boolean near, looked;
        long at;
    }

    /** Whether the bot rides a train, or one with controls is near it: looked at once every {@link #LOOK_EVERY} ticks at most. */
    static boolean nearTrain(Bot bot) {
        ServerPlayer body = bot.body();
        if (Rails.riding(body) != null) return true;
        Near near = bot.slot(Near.class, Near::new);
        long now = body.level().getGameTime();
        if (near.looked && now - near.at < LOOK_EVERY) return near.near;
        near.looked = true;
        near.at = now;
        near.near = !Rails.withControls(body, NEAR).isEmpty();
        return near.near;
    }
}
