package tachyoncreate;

import net.minecraft.world.item.Item;
import tachyon.api.Bot;
import tachyon.api.Job;

import java.util.List;
import java.util.Map;

/**
 * An order and its pickup, one errand: it orders at a stock ticker ({@link Ask}), then walks to the
 * mailbox the order goes to and waits there for what it ordered ({@link Pickup}). Without a mailbox to
 * go to, the order is all it does.
 */
final class Order extends Job {

    private final Ask ask;
    /** The mailbox the order goes to, the nearest first; empty when none is near. */
    private final List<Machine.Found> mailbox;
    private final String address;
    private final int waitTicks;
    private Pickup pickup;
    private String ordered;

    Order(Ask ask, List<Machine.Found> mailbox, String address, int waitTicks) {
        this.ask = ask;
        this.mailbox = mailbox;
        this.address = address;
        this.waitTicks = waitTicks;
    }

    @Override
    public String think(Bot bot, long now) {
        if (pickup != null) {
            String over = pickup.think(bot, now);
            return over == null ? null : ordered + ", then " + over;
        }
        String over = ask.think(bot, now);
        if (over == null) return null;
        Map<Item, Integer> what = ask.ordered;
        if (what.isEmpty() || mailbox.isEmpty()) return over;
        ordered = over;
        pickup = new Pickup(mailbox, address, Map.copyOf(what), waitTicks, true);
        return null;
    }

    @Override
    public void act(Bot bot) {
        if (pickup != null) pickup.act(bot);
        else ask.act(bot);
    }

    @Override
    public void end(Bot bot) {
        if (pickup != null) pickup.end(bot);
        else ask.end(bot);
    }

    @Override
    public String status() {
        return pickup != null ? pickup.status() : ask.status();
    }

    @Override
    public int progress() {
        return ask.progress() + (pickup == null ? 0 : pickup.progress());
    }
}
