package tachyoncreate;

import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A mail run, its stops chosen as it goes ({@link Drive.Planner}): the stations with postboxes the
 * packages aboard are for, and, picking up, every other station with postboxes too, once a round,
 * the nearest first. Create itself swaps the mail where the train stands (packages for a postbox's
 * address off, the others waiting in it on); the bot drives, stops long enough, and keeps count.
 *
 * <p>It knows what the train carries, as its driver who looks in its chests; of the postboxes, only
 * what a round past them shows: picking up, it goes by all of them, as a mail train does.
 */
final class Mail implements Drive.Planner {

    /** How long it stands at a stop: the mail is swapped as the train comes in, and each second after. */
    static final int STOP_SECONDS = 3;

    private final boolean pickUp;
    private final int restTicks;
    /** The stations stopped at this round, by id. */
    private final Set<UUID> visited = new HashSet<>();
    /**
     * How many packages for a station were still aboard as it left it (its postboxes full), by id: it
     * goes back there for them only once that changed (more taken on for it), never round and round.
     */
    private final Map<UUID, Integer> leftWith = new java.util.HashMap<>();
    /** Packages left and taken on, by station name, in all. */
    private final Map<String, Integer> left = new LinkedHashMap<>(), taken = new LinkedHashMap<>();
    private int aboardBefore, forHereBefore;
    private boolean started;

    Mail(boolean pickUp, int restTicks) {
        this.pickUp = pickUp;
        this.restTicks = restTicks;
    }

    @Override
    public Drive.Stop next(ServerPlayer body, Train t, GlobalStation here) {
        List<GlobalStation> mail = Rails.mailStations(t);
        if (!started) {
            started = true;
            // Where it stands, the mail was swapped already: no stop there this round.
            if (here != null && mail.contains(here)) visited.add(here.id);
        }
        GlobalStation best = null;
        double bestD = Double.MAX_VALUE;
        Vec3 at = body.position();
        for (GlobalStation s : mail) {
            if (s.getBlockEntityPos() == null || s == here) continue;
            // Mail aboard for it (taken on since it was last there, maybe at the station after it), or a round's pick-up.
            int forIt = Rails.packages(t, a -> Rails.takes(s, a));
            boolean deliver = forIt > 0 && forIt != leftWith.getOrDefault(s.id, -1);
            boolean collect = pickUp && !visited.contains(s.id);
            if (!deliver && !collect) continue;
            double d = Vec3.atCenterOf(s.getBlockEntityPos()).distanceToSqr(at);
            if (d < bestD && Rails.reachable(t, s)) {
                best = s;
                bestD = d;
            }
        }
        if (best == null) {
            // A round over: the next one, after a rest, goes by them all again.
            visited.clear();
            if (here != null && mail.contains(here)) visited.add(here.id);
            return null;
        }
        visited.add(best.id);
        GlobalStation s = best;
        aboardBefore = Rails.packages(t, null);
        forHereBefore = Rails.packages(t, a -> Rails.takes(s, a));
        return Drive.Stop.exactly(best, STOP_SECONDS);
    }

    @Override
    public void left(Train t, GlobalStation at) {
        int forHere = Rails.packages(t, a -> Rails.takes(at, a));
        leftWith.put(at.id, forHere);
        int dropped = Math.max(0, forHereBefore - forHere);
        int on = Math.max(0, Rails.packages(t, null) - aboardBefore + dropped);
        if (dropped > 0) left.merge(at.name, dropped, Integer::sum);
        if (on > 0) taken.merge(at.name, on, Integer::sum);
    }

    @Override
    public String said() {
        if (left.isEmpty() && taken.isEmpty()) return "no mail to carry: no packages aboard for a station with postboxes"
                + (pickUp ? ", and none waiting at them" : "");
        List<String> w = new ArrayList<>();
        if (!left.isEmpty()) w.add("left " + counts(left));
        if (!taken.isEmpty()) w.add("took on " + counts(taken));
        return "the mail run is done: " + String.join("; ", w);
    }

    /** "3 packages at Farm and 1 at Mine". */
    private static String counts(Map<String, Integer> by) {
        List<String> w = new ArrayList<>();
        by.forEach((station, n) -> w.add((w.isEmpty() ? n + (n == 1 ? " package" : " packages") : String.valueOf(n)) + " at " + station));
        return w.size() == 1 ? w.get(0) : String.join(", ", w.subList(0, w.size() - 1)) + " and " + w.get(w.size() - 1);
    }

    @Override
    public int rest() {
        return restTicks;
    }
}
