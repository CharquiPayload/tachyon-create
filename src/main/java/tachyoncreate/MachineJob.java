package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import tachyon.api.Bot;
import tachyon.api.Job;

/**
 * A job at one machine: it walks to where its hands can do it ({@link Stand}), then works there a
 * tick at a time ({@link #work}, the hands, after the walk). Pushed away meanwhile, or set aside for a
 * while (a creeper, a meal), it walks back first. Over when the machine is gone.
 */
abstract class MachineJob extends Job {

    /** The machine it works at: the first of its options it can get to. */
    Machine.Found target;
    private final java.util.List<Machine.Found> options;
    private int option;
    /** How it went, once it is over: said by the next tick's {@link #think}. */
    String over;
    private Stand stand;
    private boolean there;
    private int agains;

    /** At the first of {@code options} it can get to (the nearest it sees first). */
    MachineJob(java.util.List<Machine.Found> options) {
        this.options = options;
        this.target = options.get(0);
    }

    /** How well its hands can do the job from a spot, its eyes there, its feet's block ({@link Stand.Good}). */
    abstract double good(ServerPlayer body, Vec3 eye, BlockPos feet);

    /** A click's {@link #good}: it reaches or not. */
    static double reaches(boolean yes) {
        return yes ? 1 : 0;
    }

    /** A tick of the job, once there: its hands. Sets {@link #over} when it is done. */
    abstract void work(Bot bot);

    /** Whether it did something there already: then it sticks to that machine. */
    boolean started() {
        return progress() > 0;
    }

    /** What it says when the machine went away before it was done. */
    String gone() {
        return "the " + target.kind().name + " " + target.kind().is() + " gone";
    }

    @Override
    public String think(Bot bot, long now) {
        if (over != null) return over;
        Machine.Found still = Machine.at(bot.body().level(), target.pos());
        if (still == null || still.kind() != target.kind()) return gone();
        if (!there) {
            if (stand == null) {
                ServerPlayer body = bot.body();
                stand = new Stand(target.pos(), (eye, feet) -> good(body, eye, feet));
            }
            switch (stand.tick(bot)) {
                case WALKING -> {
                    return null;
                }
                case NO_WAY -> {
                    // The next of the same kind it saw, if there is one; else it gives up.
                    if (option + 1 < options.size() && !started()) {
                        target = options.get(++option);
                        stand = null;
                        return null;
                    }
                    return "gave up: I can't get to the " + target.kind().name;
                }
                case THERE -> {
                    stand.stop(bot);
                    there = true;
                }
            }
        }
        return null;
    }

    @Override
    public void act(Bot bot) {
        if (there && over == null) work(bot);
        else if (stand != null) stand.step(bot);
    }

    @Override
    public void end(Bot bot) {
        // Set aside or over: if it comes back to it, it walks to a spot again from wherever it is then.
        if (stand != null) stand.stop(bot);
        again();
    }

    /**
     * Back to walking: it was pushed away, or can't do it from where it stands any more. Ten times
     * over and the job is given up: something keeps it from staying where it works.
     */
    void again() {
        if (there && ++agains >= 10 && over == null) over = "gave up: I can't stay where I can reach the " + target.kind().name;
        there = false;
        stand = null;
    }

    /** How far its hands reach: a player's. */
    static double reach(ServerPlayer body) {
        return body.blockInteractionRange();
    }

    /** "the millstone at 10 64 -3": a machine and where it is, for a tool's answer. */
    String where() {
        BlockPos p = target.pos();
        return "the " + target.kind().name + " at " + p.getX() + " " + p.getY() + " " + p.getZ();
    }
}
