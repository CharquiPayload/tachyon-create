package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Which machine an order means, in this order: the one at the coordinates given; the nearest of the
 * kind named that the bot sees ("the basin", "the press": the depot under it); the one the player
 * giving the order points at ("this crank"); the nearest of the kinds the job is for that the bot
 * sees. A bot never picks one it can't see by itself.
 */
final class Targets {

    /** How far off a machine named by coordinates or pointed at may be: a walk across a base. */
    static final double FAR = 128;

    /**
     * The machines an order may mean, the one it means first and the others it may go to instead when
     * it can't get to that one (the next nearest it sees of the kind); or why there are none.
     */
    record Pick(List<Machine.Found> options, String why) {
        static Pick no(String why) {
            return new Pick(List.of(), why);
        }

        static Pick of(Machine.Found f) {
            return new Pick(List.of(f), null);
        }

        /** The machine it means, or null. */
        Machine.Found found() {
            return options.isEmpty() ? null : options.get(0);
        }
    }

    /** How many machines it may try in turn, when the one it means can't be got to. */
    private static final int OPTIONS = 4;

    private Targets() {
    }

    /**
     * @param kinds   the machines the job is for
     * @param what    words for them, for a refusal ("hand crank", "machine to put things in")
     * @param at      coordinates given, or null
     * @param word    a machine named ("basin"), or null
     * @param pointer the player giving the order, whose pointing counts, or null
     */
    static Pick find(ServerPlayer body, Set<Machine> kinds, String what, BlockPos at, String word, ServerPlayer pointer) {
        Level level = body.level();
        if (at != null) {
            if (at.distToCenterSqr(body.position()) > FAR * FAR) return Pick.no("that's too far from me");
            Machine.Found f = Machine.at(level, at);
            if (f == null) return Pick.no("there's no " + what + " at " + at.getX() + " " + at.getY() + " " + at.getZ());
            if (!kinds.contains(f.kind())) return Pick.no("I can't do that with the " + f.kind().name);
            return Pick.of(f);
        }
        if (word != null && !word.isBlank()) {
            boolean overhead = Machine.overhead(word);
            Machine named = Machine.named(word);
            if (!overhead && named == null) return Pick.no("I don't know a machine called " + word);
            if (named != null && !kinds.contains(named)) return Pick.no("I can't do that with a " + named.name);
            Set<Machine> wanted = overhead ? kinds : EnumSet.of(named);
            List<Machine.Found> seen = Sight.machines(body, wanted, Sight.LOOK, overhead);
            if (seen.isEmpty()) return Pick.no("I don't see a " + (named == null ? word.trim() : named.name) + " near me");
            return new Pick(first(seen), null);
        }
        if (pointer != null && pointer.level() == level) {
            BlockPos pointed = Sight.pointedAt(pointer);
            if (pointed != null && pointed.distToCenterSqr(body.position()) <= FAR * FAR) {
                Machine.Found f = Machine.at(level, pointed);
                if (f != null && kinds.contains(f.kind())) return Pick.of(f);
            }
        }
        List<Machine.Found> seen = Sight.machines(body, kinds, Sight.LOOK, false);
        if (seen.isEmpty()) return Pick.no("I don't see a " + what + " near me");
        return new Pick(first(seen), null);
    }

    private static List<Machine.Found> first(List<Machine.Found> seen) {
        return List.copyOf(seen.subList(0, Math.min(OPTIONS, seen.size())));
    }
}
