package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import tachyon.api.Bot;

/**
 * Turning a hand crank, as a player holds the click on it. A crank turns for 10 ticks after each
 * click, so a click every {@link #EVERY} keeps it going without a stop. Backwards is the same with
 * sneaking, and then the game wants both hands empty (a sneaking click with something in hand never
 * gets to the block).
 *
 * <p>A crank makes its turner hungry, as it does a player: Tachyon's own reflex eats when it is
 * hungry (setting this aside for the meal). With no food left and little hunger to spare, it stops.
 */
final class Crank extends MachineJob {

    /** Every how many ticks it clicks: under the 10 a click keeps a crank turning. */
    static final int EVERY = 8;
    /** The hunger at which, with nothing to eat, it stops: sprinting's own limit. */
    static final int HUNGRY = 6;

    private final int ticks;
    private final boolean back;
    private int turned;

    /** For {@code ticks} ticks (0: until told to stop), backwards when {@code back}. */
    Crank(java.util.List<Machine.Found> options, int ticks, boolean back) {
        super(options);
        this.ticks = ticks;
        this.back = back;
    }

    @Override
    double good(ServerPlayer body, Vec3 eye, BlockPos feet) {
        return reaches(Sight.hit(body, eye, target.pos(), null, reach(body)) != null);
    }

    @Override
    void work(Bot bot) {
        ServerPlayer body = bot.body();
        if (ticks > 0 && turned >= ticks) {
            over = "cranked for " + Text.time(turned);
            return;
        }
        if (body.getFoodData().getFoodLevel() <= HUNGRY && !hasFood(bot)) {
            over = (turned > 0 ? "stopped after " + Text.time(turned) + ": " : "") + "too hungry to crank, and I've got no food";
            return;
        }
        if (back && !emptyHands(body)) {
            over = "my hands are full: I can't turn it backwards";
            return;
        }
        body.setShiftKeyDown(back);
        BlockHitResult hit = Sight.hit(body, body.getEyePosition(), target.pos(), null, reach(body));
        if (hit == null) {
            again();
            return;
        }
        if (turned % EVERY == 0) {
            InteractionResult r = Hands.click(body, hit);
            if (r == null || !r.consumesAction()) {
                over = turned > 0 ? "stopped after " + Text.time(turned) + ": it won't turn any more" : "it won't turn for me here";
                return;
            }
        }
        turned++;
    }

    @Override
    public void end(Bot bot) {
        super.end(bot);
        if (bot.inGame()) bot.body().setShiftKeyDown(false);
    }

    @Override
    public String status() {
        return "cranking" + (back ? " backwards" : "") + (ticks > 0 ? ", " + Text.time(Math.max(0, ticks - turned)) + " to go" : "");
    }

    @Override
    public int progress() {
        return turned / 20;
    }

    /** Whether it carries something it would eat: food, not on its list of food it doesn't eat. */
    static boolean hasFood(Bot bot) {
        ServerPlayer body = bot.body();
        for (ItemStack s : body.getInventory().items) {
            if (!s.isEmpty() && s.getFoodProperties(body) != null && !bot.onList("food", Text.id(s.getItem()))) return true;
        }
        return !body.getOffhandItem().isEmpty() && body.getOffhandItem().getFoodProperties(body) != null;
    }

    /**
     * Both hands empty, for a sneaking click: an empty hotbar slot picked, and what the off hand held
     * put away in the bag. False when there is no room for it.
     */
    private static boolean emptyHands(ServerPlayer body) {
        if (!Hands.emptyHand(body)) return false;
        ItemStack off = body.getOffhandItem();
        if (off.isEmpty()) return true;
        Inventory inv = body.getInventory();
        for (int i = Inventory.getSelectionSize(); i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, off);
                inv.offhand.set(0, ItemStack.EMPTY);
                inv.setChanged();
                return true;
            }
        }
        return false;
    }
}
