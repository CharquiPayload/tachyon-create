package tachyoncreate;

import com.simibubi.create.content.contraptions.actors.trainControls.ControlsServerHandler;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import tachyon.api.Bot;
import tachyon.api.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A bot at a train's controls, as a player drives one: it sits in the seat by them, takes them, and
 * takes the train from stop to stop, waiting at each as its stop says (a while, until some players
 * are aboard, or until someone is). Its own schedule, kept at the wheel: Create counts no player as
 * a conductor, but a player drives by hand, and so does it (the owner: "un jugador puede conducir a
 * mano, así que técnicamente puede hacer todo lo que hace un conductor").
 *
 * <p>The way to a stop is the one Create finds for the train on its tracks, switches and signals
 * kept, which is what its controls' approach does when held near a station: the bot holds it all the
 * way, as a player who knows the way. A route goes round and round, until told to stop; anything
 * else is over at its last stop, where it gets off.
 */
final class Drive extends Job {

    /**
     * A stop: a station by name, or the one nearest a player ({@code fetch}); and what it waits for
     * there: {@code seconds}, {@code players} more players aboard, or {@code until} someone is aboard,
     * those two for {@code most} seconds at most.
     */
    record Stop(String station, String fetch, int seconds, int players, String until, int most) {

        static Stop at(String station) {
            return new Stop(station, null, 0, 0, null, 0);
        }

        /** "Farm", "Farm (30s)", "Farm (until 2 players aboard, 5m at most)": for what it says. */
        String words() {
            String where = station != null ? station : "the station nearest " + fetch;
            List<String> w = new ArrayList<>();
            if (seconds > 0) w.add(Text.time(seconds * 20));
            if (players > 0) w.add("until " + players + (players == 1 ? " player is" : " players are") + " aboard");
            if (until != null) w.add("until " + until + " is aboard");
            if ((players > 0 || until != null) && most > 0) w.add(Text.time(most * 20) + " at most");
            return w.isEmpty() ? where : where + " (" + String.join(", ", w) + ")";
        }
    }

    private enum Phase { BOARD, CONTROLS, GO, WAIT }

    /** How long it may take to get on, and to get from one stop to the next. */
    private static final int BOARD_MAX = 20 * 60, LEG_MAX = 20 * 60 * 10;
    /** How often it presses its key again: Create lets go of one 30 ticks after its last press. */
    private static final int PRESS_EVERY = 10;
    /** The approach key of a train's controls (its jump key): held, the train goes on to where it is navigating. */
    private static final int APPROACH = 4;

    private final List<Stop> stops;
    private final boolean loop;
    private Phase phase = Phase.BOARD;
    private int index, tries, done;
    private long phaseAt = -1;
    private CarriageContraptionEntity carriage;
    private BlockPos controls, seat;
    private UUID trainId;
    /** The station it is going to, or waits at. */
    private GlobalStation station;
    private String said;
    private boolean pressing;

    Drive(List<Stop> stops, boolean loop) {
        this.stops = stops;
        this.loop = loop;
    }

    @Override
    public String think(Bot bot, long now) {
        ServerPlayer body = bot.body();
        if (phaseAt < 0) phaseAt = now;
        switch (phase) {
            case BOARD -> {
                return board(bot, body, now);
            }
            case CONTROLS -> {
                return take(body, now);
            }
            case GO -> {
                return go(body, now);
            }
            case WAIT -> {
                return await(body, now);
            }
        }
        return null;
    }

    /** To the seat by a train's controls, and in it. */
    private String board(Bot bot, ServerPlayer body, long now) {
        CarriageContraptionEntity mine = Rails.riding(body);
        if (mine != null && Rails.controls(mine) != null) {
            pick(mine);
            return next(Phase.CONTROLS, now);
        }
        if (now - phaseAt > BOARD_MAX) return "gave up: I couldn't get on the train";
        if (carriage == null || carriage.isRemoved()) {
            List<CarriageContraptionEntity> near = Rails.withControls(body, 48);
            if (near.isEmpty()) return "I see no train with controls near me";
            pick(near.get(0));
            seat = Rails.seat(carriage, controls);
            if (seat == null) return "no free seat by that train's controls";
        }
        if (carriage.getCarriage().train.speed != 0) return null;       // it gets on a train at a stop, not a moving one
        Vec3 at = Rails.world(carriage, seat);
        // Within a player's reach of the seat (a seat on a carriage is up off the ground, out of a step's reach).
        if (body.getEyePosition().distanceTo(at) > body.blockInteractionRange()) {
            if (!bot.walking()) bot.walkTo(BlockPos.containing(at), 1.5);
            if (bot.lost()) return "gave up: I can't get to the train";
            return null;
        }
        if (!Hands.emptyHand(body) || ++tries > 5) return "I couldn't sit down in the train";
        carriage.handlePlayerInteraction(body, seat, Direction.UP, InteractionHand.MAIN_HAND);
        return null;            // sitting, it is riding at the next look
    }

    /** The carriage it drives: its controls and its train. */
    private void pick(CarriageContraptionEntity c) {
        carriage = c;
        controls = Rails.controls(c);
        trainId = c.getCarriage().train.id;
    }

    /** Its hands on the controls: someone else's are not taken from them. */
    private String take(ServerPlayer body, long now) {
        if (Rails.riding(body) != carriage) return back(now);
        UUID at = carriage.getControllingPlayer().orElse(null);
        if (body.getUUID().equals(at)) return next(Phase.GO, now);
        if (at != null) {
            ServerPlayer who = body.server.getPlayerList().getPlayer(at);
            return (who == null ? "someone" : who.getGameProfile().getName()) + " is driving that train";
        }
        if (now - phaseAt > 100) return "I couldn't take the train's controls";
        carriage.handlePlayerInteraction(body, controls, Direction.UP, InteractionHand.MAIN_HAND);
        return null;
    }

    /** On the way to its stop: the way set, the approach key held (act). */
    private String go(ServerPlayer body, long now) {
        Train t = train(body);
        if (t == null) return over(body, "the train is gone");
        if (t.derailed) return over(body, "the train came off its tracks");
        if (Rails.riding(body) != carriage || !body.getUUID().equals(carriage.getControllingPlayer().orElse(null))) return back(now);
        Stop stop = stops.get(index);
        if (station == null) {
            station = resolve(body, t, stop);
            if (station == null) return over(body, said);
        }
        if (t.getCurrentStation() == station && t.navigation.destination == null) {
            pressing = false;
            return next(Phase.WAIT, now);
        }
        if (now - phaseAt > LEG_MAX) return over(body, "gave up: the train didn't get to " + station.name + " in " + Text.time(LEG_MAX));
        if (t.navigation.destination != station) {
            DiscoveredPath path = t.navigation.findPathTo(station, Double.MAX_VALUE);
            if (path == null || t.navigation.startNavigation(path) < 0) {
                return over(body, "the train finds no way to " + station.name + " on its tracks");
            }
        }
        pressing = true;
        return null;
    }

    /** The station a stop means, on this train's tracks; null, with why in {@link #said}. */
    private GlobalStation resolve(ServerPlayer body, Train t, Stop stop) {
        if (stop.fetch() != null) {
            ServerPlayer p = body.server.getPlayerList().getPlayerByName(stop.fetch());
            if (p == null) {
                said = stop.fetch() + " isn't in the game";
                return null;
            }
            GlobalStation s = Rails.nearest(t, p.position(), p.level().dimension());
            if (s == null) said = "this train's tracks lead to no station near " + stop.fetch();
            return s;
        }
        GlobalStation s = Rails.named(t, stop.station());
        if (s != null) return s;
        // There, but not to be got to from here: a station is stopped at going one way only, and a train with
        // controls at one end only goes forward (a dead end, or no loop to turn round on).
        said = Rails.exists(t, stop.station())
                ? "the train can't get to " + stop.station() + " from where it stands: a station is stopped at going one way, and"
                + " a train with controls at one end can't turn round without a loop"
                : "this train's tracks lead to no station called " + stop.station() + " (its stations: " + Rails.names(t) + ")";
        return null;
    }

    /** At its stop, waiting as it says; then on to the next, or over. */
    private String await(ServerPlayer body, long now) {
        Train t = train(body);
        if (t == null) return over(body, "the train is gone");
        Stop stop = stops.get(index);
        long waited = (now - phaseAt) / 20;
        boolean ready = waited >= stop.seconds();
        if (stop.players() > 0) ready &= t.countPlayerPassengers() - 1 >= stop.players();
        if (stop.until() != null) {
            ServerPlayer p = body.server.getPlayerList().getPlayerByName(stop.until());
            ready &= p != null && Rails.aboard(t, p);
        }
        if (!ready && (stop.players() > 0 || stop.until() != null) && stop.most() > 0 && waited >= stop.most()) ready = true;
        if (!ready) return null;
        done++;
        String here = station.name;
        station = null;
        if (++index < stops.size()) return next(Phase.GO, now);
        if (loop) {
            index = 0;
            return next(Phase.GO, now);
        }
        return over(body, (stops.size() == 1 ? "drove the train to " : "drove the train through " + stops.size() + " stops, to ")
                + here);
    }

    /** Off the controls or out of its seat (pushed, a reflex): back to getting on and taking them. */
    private String back(long now) {
        pressing = false;
        tries = 0;
        return next(Phase.BOARD, now);
    }

    private String next(Phase p, long now) {
        phase = p;
        phaseAt = now;
        tries = 0;
        return null;
    }

    /** Over, {@code how}: its hands off the controls, and out of its seat once the train stands. */
    private String over(ServerPlayer body, String how) {
        letGo(body);
        return how;
    }

    private Train train(ServerPlayer body) {
        return trainId == null ? null : Rails.train(trainId, body.level());
    }

    @Override
    public void act(Bot bot) {
        if (!pressing || carriage == null || controls == null) return;
        ServerPlayer body = bot.body();
        if (body.level().getGameTime() % PRESS_EVERY != 0) return;
        ControlsServerHandler.receivePressed(body.level(), carriage, controls, body.getUUID(), List.of(APPROACH), true);
    }

    @Override
    public void end(Bot bot) {
        letGo(bot.body());
    }

    /** Its key let go, its hands off the controls, and out of its seat if the train stands. */
    private void letGo(ServerPlayer body) {
        pressing = false;
        if (carriage == null || controls == null) return;
        ControlsServerHandler.receivePressed(body.level(), carriage, controls, body.getUUID(), List.of(APPROACH), false);
        if (body.getUUID().equals(carriage.getControllingPlayer().orElse(null))) carriage.stopControlling(controls);
        Train t = train(body);
        // Let go on the way, the train stops, as a player's does: its way is dropped with the key.
        if (t != null && t.navigation.destination != null) t.navigation.cancelNavigation();
        if (Rails.riding(body) != null && (t == null || t.speed == 0)) body.stopRiding();
    }

    @Override
    public String status() {
        Stop stop = stops.isEmpty() ? null : stops.get(Math.min(index, stops.size() - 1));
        return switch (phase) {
            case BOARD -> "getting on the train";
            case CONTROLS -> "taking the train's controls";
            case GO -> "driving the train to " + (station != null ? station.name : stop == null ? "its stop" : stop.words());
            case WAIT -> "waiting at " + (station != null ? station.name : "the station") + (stop == null ? "" : ": " + stop.words());
        };
    }

    @Override
    public int progress() {
        return done;
    }
}
