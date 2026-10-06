package tachyoncreate;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import tachyon.api.Bot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * At a mailbox (a frogport or a postbox), as a player at its screen: it takes out the packages that
 * came, one a tick, and opens each in its hand as a player does (a right-click), what was in it going
 * into its bag. It may wait there for packages still on their way: for what it ordered, until all of
 * it came, or for a while.
 */
final class Pickup extends MachineJob {

    /** Only packages for this address; null for all the mailbox holds. */
    private final String address;
    /** What it waits for, what it ordered; null to take what is there (or comes while it waits) and go. */
    private final Map<Item, Integer> expected;
    /** How long it may wait for packages, in ticks. */
    private final int waitTicks;
    /** Whether it opens the packages, or keeps them closed. */
    private final boolean open;
    /** What came: what was in the packages it opened, or the packages it took. */
    private final Map<Item, Integer> got = new LinkedHashMap<>();
    private int packages, waited;

    Pickup(List<Machine.Found> options, String address, Map<Item, Integer> expected, int waitTicks, boolean open) {
        super(options);
        this.address = address;
        this.expected = expected;
        this.waitTicks = waitTicks;
        this.open = open;
    }

    @Override
    double good(ServerPlayer body, Vec3 eye, BlockPos feet) {
        return reaches(Sight.hit(body, eye, target.pos(), null, reach(body)) != null);
    }

    @Override
    void work(Bot bot) {
        ServerPlayer body = bot.body();
        PackagePortBlockEntity box = Network.mailbox(body.level(), target.pos());
        if (box == null) {
            over = gone();
            return;
        }
        int slot = next(box);
        if (slot < 0) {
            if (all() || waited++ >= waitTicks) over = said();
            return;
        }
        ItemStack one = box.inventory.getStackInSlot(slot);
        // Room for what is in it, and for the package in its hand first when what it holds must be put away.
        int room = (open ? Network.contents(one).size() : 1) + (body.getMainHandItem().isEmpty() ? 0 : 1);
        if (free(body) < room || !Hands.emptyHand(body)) {
            over = (packages == 0 ? "" : said() + "; ") + "my bag is full: I left the rest in the " + target.kind().name;
            return;
        }
        ItemStack taken = box.inventory.extractItem(slot, 1, false);
        if (taken.isEmpty()) return;
        packages++;
        if (open && taken.getItem() instanceof PackageItem p) {
            Network.contents(taken).forEach((item, n) -> got.merge(item, n, Integer::sum));
            body.setItemInHand(InteractionHand.MAIN_HAND, taken);
            p.open(body.level(), body, InteractionHand.MAIN_HAND);
        } else {
            body.getInventory().placeItemBackInInventory(taken);
            got.merge(taken.getItem(), 1, Integer::sum);
        }
    }

    /** The slot of the next package for it in the mailbox, or -1. */
    private int next(PackagePortBlockEntity box) {
        for (int i = 0; i < box.inventory.getSlots(); i++) {
            ItemStack s = box.inventory.getStackInSlot(i);
            if (PackageItem.isPackage(s) && (address == null || PackageItem.matchAddress(s, address))) return i;
        }
        return -1;
    }

    /** Whether it has all it waits for: when it waits for nothing in particular, what is there now is all. */
    boolean all() {
        if (expected == null) return waitTicks == 0 || packages > 0;
        for (Map.Entry<Item, Integer> e : expected.entrySet()) {
            if (got.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    /** How it went, in words. */
    private String said() {
        if (packages == 0) return waitTicks == 0 ? "the " + target.kind().name + " had no packages for me"
                : "no packages came to the " + target.kind().name + " in " + Text.time(waitTicks);
        String what = open ? "got " + Text.some(got) + " out of " + packages + (packages == 1 ? " package" : " packages")
                : "took " + packages + (packages == 1 ? " package" : " packages");
        return all() || expected == null ? what : what + "; the rest didn't come in " + Text.time(waitTicks);
    }

    /** How many empty slots its bag has. */
    private static int free(ServerPlayer body) {
        int n = 0;
        for (ItemStack s : body.getInventory().items) {
            if (s.isEmpty()) n++;
        }
        return n;
    }

    @Override
    public String status() {
        return packages == 0 && waited > 0 ? "waiting for packages at the " + target.kind().name
                : "taking packages out of the " + target.kind().name;
    }

    @Override
    public int progress() {
        return packages;
    }
}
