package tachyoncreate;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsInteractionBehaviour;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.station.GlobalStation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Create's trains as a player at their controls knows them: the carriages near it, where a
 * carriage's controls and seats are, the stations of a train's network by name, and whether
 * someone is aboard. A bot drives sitting down: a player's footing on a moving train is worked out
 * by its client, which a bot has none of, and standing it is left behind.
 */
final class Rails {

    /** How near the controls a driver must stay (Create's own reach for them), and how near them its seat is looked for. */
    static final double CONTROLS_REACH = 8, SEAT_NEAR = 6;

    private Rails() {
    }

    /** The carriages within {@code radius} of the bot that have controls, the nearest first. */
    static List<CarriageContraptionEntity> withControls(ServerPlayer body, double radius) {
        List<CarriageContraptionEntity> out = new ArrayList<>();
        for (CarriageContraptionEntity c : body.level().getEntitiesOfClass(CarriageContraptionEntity.class,
                new AABB(body.blockPosition()).inflate(radius))) {
            if (c.getCarriage() != null && controls(c) != null) out.add(c);
        }
        out.sort(Comparator.comparingDouble(c -> c.distanceToSqr(body)));
        return out;
    }

    /** The carriage the bot sits in, or null. */
    static CarriageContraptionEntity riding(ServerPlayer body) {
        return body.getVehicle() instanceof CarriageContraptionEntity c && c.getCarriage() != null ? c : null;
    }

    /** Where a carriage's controls are, in the carriage's own blocks; null when it has none. */
    static BlockPos controls(CarriageContraptionEntity c) {
        for (Map.Entry<BlockPos, ?> e : c.getContraption().getInteractors().entrySet()) {
            if (e.getValue() instanceof ControlsInteractionBehaviour) return e.getKey();
        }
        return null;
    }

    /** Where a block of a carriage is in the world now. */
    static Vec3 world(CarriageContraptionEntity c, BlockPos local) {
        return c.toGlobalVector(Vec3.atCenterOf(local), 1);
    }

    /** The free seat nearest a carriage's controls, within {@value #SEAT_NEAR} of them; null when none. */
    static BlockPos seat(CarriageContraptionEntity c, BlockPos controls) {
        BlockPos best = null;
        double bestD = SEAT_NEAR * SEAT_NEAR;
        List<BlockPos> seats = c.getContraption().getSeats();
        for (int i = 0; i < seats.size(); i++) {
            BlockPos s = seats.get(i);
            double d = s.distSqr(controls);
            if (d > bestD || taken(c, i)) continue;
            best = s;
            bestD = d;
        }
        return best;
    }

    /** Whether a player sits in a carriage's seat (a mob there is moved off it by whoever sits down). */
    private static boolean taken(CarriageContraptionEntity c, int seat) {
        for (Map.Entry<UUID, Integer> e : c.getContraption().getSeatMapping().entrySet()) {
            if (e.getValue() != seat) continue;
            for (Entity p : c.getPassengers()) {
                if (p.getUUID().equals(e.getKey()) && p instanceof Player) return true;
            }
        }
        return false;
    }

    /** Whether someone is aboard a train: sitting in any of its carriages. */
    static boolean aboard(Train t, Entity who) {
        return who.getVehicle() instanceof CarriageContraptionEntity c && c.getCarriage() != null && c.getCarriage().train == t;
    }

    /** The stations of a train's network. */
    static List<GlobalStation> stations(Train t) {
        if (t.graph == null) return List.of();
        return new ArrayList<>(t.graph.getPoints(EdgePointType.STATION));
    }

    /**
     * The station of a train's network a name means, the train can get to: its whole name first (in
     * any case), else one whose name holds it, the nearest of those. Null for none.
     */
    static GlobalStation named(Train t, String name) {
        String w = name.trim().toLowerCase(Locale.ROOT);
        GlobalStation part = null;
        for (GlobalStation s : stations(t)) {
            if (s.name == null) continue;
            String n = s.name.toLowerCase(Locale.ROOT);
            if (n.equals(w) && reachable(t, s)) return s;
            if (part == null && n.contains(w) && reachable(t, s)) part = s;
        }
        return part;
    }

    /** Whether a train's network has a station a name means, reachable or not. */
    static boolean exists(Train t, String name) {
        String w = name.trim().toLowerCase(Locale.ROOT);
        for (GlobalStation s : stations(t)) {
            if (s.name != null && s.name.toLowerCase(Locale.ROOT).contains(w)) return true;
        }
        return false;
    }

    /** The station of a train's network nearest a place in a world, it can get to; null for none. */
    static GlobalStation nearest(Train t, Vec3 at, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim) {
        GlobalStation best = null;
        double bestD = Double.MAX_VALUE;
        for (GlobalStation s : stations(t)) {
            if (s.getBlockEntityPos() == null || !dim.equals(s.getBlockEntityDimension())) continue;
            double d = Vec3.atCenterOf(s.getBlockEntityPos()).distanceToSqr(at);
            if (d < bestD && reachable(t, s)) {
                best = s;
                bestD = d;
            }
        }
        return best;
    }

    /** Whether a train finds a way to a station on its tracks (it is there now, or a path leads there). */
    static boolean reachable(Train t, GlobalStation s) {
        return t.getCurrentStation() == s || t.navigation.findPathTo(s, Double.MAX_VALUE) != null;
    }

    /** The names of the stations of a train's network, for a refusal: "Spawn, Farm and Mine". */
    static String names(Train t) {
        List<String> n = new ArrayList<>();
        for (GlobalStation s : stations(t)) if (s.name != null && !n.contains(s.name)) n.add(s.name);
        if (n.isEmpty()) return "none";
        if (n.size() > 8) return String.join(", ", n.subList(0, 8)) + " and " + (n.size() - 8) + " more";
        return n.size() == 1 ? n.get(0) : String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
    }

    /**
     * The station the train a bot would drive stands at (the one it rides, else the nearest with
     * controls), or the nearest to it on that train's tracks; null when there is none.
     */
    static String stationHere(ServerPlayer body) {
        CarriageContraptionEntity c = riding(body);
        if (c == null) {
            List<CarriageContraptionEntity> near = withControls(body, 48);
            if (near.isEmpty()) return null;
            c = near.get(0);
        }
        Train t = c.getCarriage().train;
        GlobalStation s = t.getCurrentStation();
        if (s == null) s = nearest(t, c.position(), c.level().dimension());
        return s == null ? null : s.name;
    }

    // --- the mail ---------------------------------------------------------------------------------------

    /** What a train carries: each of its carriages' chests and vaults, as one handler each; none for none. */
    static List<net.neoforged.neoforge.items.IItemHandlerModifiable> cargo(Train t) {
        List<net.neoforged.neoforge.items.IItemHandlerModifiable> out = new ArrayList<>();
        for (var c : t.carriages) {
            var inv = c.storage == null ? null : c.storage.getAllItems();
            if (inv != null && inv.getSlots() > 0) out.add(inv);
        }
        return out;
    }

    /** How many packages a train carries, of those whose address {@code which} takes (null: all). */
    static int packages(Train t, java.util.function.Predicate<String> which) {
        int n = 0;
        for (var inv : cargo(t)) {
            for (int i = 0; i < inv.getSlots(); i++) {
                var s = inv.getStackInSlot(i);
                if (com.simibubi.create.content.logistics.box.PackageItem.isPackage(s)
                        && (which == null || which.test(com.simibubi.create.content.logistics.box.PackageItem.getAddress(s)))) {
                    n += s.getCount();
                }
            }
        }
        return n;
    }

    /** Whether a package for {@code address} is left at a station: one of its postboxes takes that address. */
    static boolean takes(GlobalStation s, String address) {
        for (var port : s.connectedPorts.values()) {
            if (port.address != null && com.simibubi.create.content.logistics.box.PackageItem.matchAddress(address, port.address)) return true;
        }
        return false;
    }

    /** The stations of a train's network with postboxes: where it takes mail and leaves it. */
    static List<GlobalStation> mailStations(Train t) {
        List<GlobalStation> out = new ArrayList<>();
        for (GlobalStation s : stations(t)) if (!s.connectedPorts.isEmpty()) out.add(s);
        return out;
    }

    /** The carriages within {@code radius} that carry something (chests, vaults), the nearest first. */
    static List<CarriageContraptionEntity> withCargo(ServerPlayer body, double radius) {
        List<CarriageContraptionEntity> out = new ArrayList<>();
        for (CarriageContraptionEntity c : body.level().getEntitiesOfClass(CarriageContraptionEntity.class,
                new AABB(body.blockPosition()).inflate(radius))) {
            if (c.getCarriage() != null && c.getCarriage().storage != null && c.getCarriage().storage.getAllItems() != null
                    && c.getCarriage().storage.getAllItems().getSlots() > 0) out.add(c);
        }
        out.sort(Comparator.comparingDouble(c -> c.distanceToSqr(body)));
        return out;
    }

    /** A train by its id, while it is on the tracks; null once it is gone (taken apart). */
    static Train train(UUID id, net.minecraft.world.level.Level level) {
        return Create.RAILWAYS.sided(level).trains.get(id);
    }
}
