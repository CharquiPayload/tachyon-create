package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import tachyon.api.Bot;
import tachyon.api.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An order and its pickup, one errand: it orders at a stock ticker ({@link Ask}), then walks to the
 * mailbox the order goes to and waits there for what it ordered ({@link Pickup}). Some shops drop it on
 * the ground instead (M-L4BJ: on a counter by the ticker): with no mailbox it can get to, it waits for it
 * by the ticker and picks it up there ({@link Ground}); with what it waited for not all come to the
 * mailbox, it looks on the ground too, once.
 */
final class Order extends Job {

    private final Ask ask;
    /** The mailbox the order goes to, the nearest first; empty when none is near. */
    private final List<Machine.Found> mailbox;
    private final String address;
    private final int waitTicks;
    private Pickup pickup;
    private Ground ground;
    private String ordered, picked;

    Order(Ask ask, List<Machine.Found> mailbox, String address, int waitTicks) {
        this.ask = ask;
        this.mailbox = mailbox;
        this.address = address;
        this.waitTicks = waitTicks;
    }

    @Override
    public String think(Bot bot, long now) {
        if (ground != null) {
            String over = ground.think(bot, now);
            if (over == null) return null;
            return ordered + ", then " + (picked == null ? over : picked + "; " + over);
        }
        if (pickup != null) {
            String over = pickup.think(bot, now);
            if (over == null) return null;
            if (pickup.all()) return ordered + ", then " + over;
            // Not all of it came to the mailbox: it may lie on the ground, by the ticker or the mailbox. Waited
            // for there if it never got to the mailbox; else only what is there now.
            boolean away = over.startsWith("gave up");
            if (!away && !Ground.any(bot.body(), spots(), address)) return ordered + ", then " + over;
            picked = over;
            ground = new Ground(spots(), address, Map.copyOf(ask.ordered), away ? waitTicks : 0, true);
            return null;
        }
        String over = ask.think(bot, now);
        if (over == null) return null;
        Map<Item, Integer> what = ask.ordered;
        if (what.isEmpty()) return over;
        ordered = over;
        if (mailbox.isEmpty()) ground = new Ground(spots(), address, Map.copyOf(what), waitTicks, true);
        else pickup = new Pickup(mailbox, address, Map.copyOf(what), waitTicks, true);
        return null;
    }

    /** Where what it ordered may lie on the ground: by the ticker it ordered at, and by the mailboxes it goes to. */
    private List<BlockPos> spots() {
        List<BlockPos> out = new ArrayList<>();
        if (ask.target != null) out.add(ask.target.pos());
        for (Machine.Found m : mailbox) out.add(m.pos());
        return out;
    }

    @Override
    public void act(Bot bot) {
        if (ground != null) return;             // it only walks
        if (pickup != null) pickup.act(bot);
        else ask.act(bot);
    }

    @Override
    public void end(Bot bot) {
        if (ground != null) ground.end(bot);
        if (pickup != null) pickup.end(bot);
        else ask.end(bot);
    }

    @Override
    public String status() {
        return ground != null ? ground.status() : pickup != null ? pickup.status() : ask.status();
    }

    @Override
    public int progress() {
        return ask.progress() + (pickup == null ? 0 : pickup.progress()) + (ground == null ? 0 : ground.progress());
    }
}
