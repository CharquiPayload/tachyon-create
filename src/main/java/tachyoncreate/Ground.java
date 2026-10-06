package tachyoncreate;

import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import tachyon.api.Bot;
import tachyon.api.Job;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Packages lying on the ground near a few spots (a stock ticker, a mailbox, where it was told): some
 * shops drop what was ordered on a counter or the floor, not in a mailbox (M-L4BJ: a stock keeper's
 * order fell on a table by the ticker, while the bot looked for a mailbox it could not get to). A box on
 * the ground is Create's package entity (a dropped package item becomes one); it walks up to each box for
 * it and takes it with a right-click, as a player does (an item one, walked over), then opens it in its
 * hand as a player does, what was in it going into its bag. It may wait there for boxes still on their way.
 */
final class Ground extends Job {

    /** How far from a spot a box may lie, in blocks. */
    static final int RADIUS = 6;
    /** How long it tries to get to one box before it leaves it where it lies: 10 seconds. */
    private static final int FETCH = 200;
    /** How near a box it must be to take it with its hand, in blocks. */
    private static final double REACH = 2.5;

    private final List<BlockPos> spots;
    /** Only boxes for this address; null for any box. */
    private final String address;
    /** What it waits for; null to take what is there (or comes while it waits) and go. */
    private final Map<Item, Integer> expected;
    private final int waitTicks;
    /** Whether it opens the boxes, or keeps them closed. */
    private final boolean open;
    /** What came: what was in the boxes it opened, or the boxes it took. */
    private final Map<Item, Integer> got = new LinkedHashMap<>();
    /** Boxes it could not get to: left where they lie. */
    private final Set<Entity> left = Collections.newSetFromMap(new IdentityHashMap<>());
    private Entity fetching;
    /** Boxes for it its bag held when it started: not its to open. */
    private int before = -1;
    private int fetchTicks, waited, packages;

    Ground(List<BlockPos> spots, String address, Map<Item, Integer> expected, int waitTicks, boolean open) {
        this.spots = List.copyOf(spots);
        this.address = address;
        this.expected = expected;
        this.waitTicks = waitTicks;
        this.open = open;
    }

    /** Whether a box for it lies near one of {@code spots} now. */
    static boolean any(ServerPlayer body, List<BlockPos> spots, String address) {
        return !boxes(body, spots, address).isEmpty();
    }

    @Override
    public String think(Bot bot, long now) {
        ServerPlayer body = bot.body();
        if (before < 0) before = inBag(body);
        // A box it walked over is in its bag: opened there, one a tick.
        if (inBag(body) > before) {
            if (!take(body)) return said() + "; my bag is full: I left the rest closed in it";
            return null;
        }
        if (fetching != null && (fetching.isRemoved() || ++fetchTicks > FETCH)) {
            if (!fetching.isRemoved()) left.add(fetching);
            fetching = null;
            fetchTicks = 0;
        }
        if (fetching == null) fetching = nearest(body);
        if (fetching != null) {
            if (free(body) < 2) return (packages == 0 ? "" : said() + "; ") + "my bag is full: I left the rest on the ground";
            if (fetching instanceof PackageEntity box && box.distanceTo(body) <= REACH) {
                // Taken with a right-click, its hand empty, as a player does: it goes into its bag.
                if (Hands.emptyHand(body)) box.interact(body, InteractionHand.MAIN_HAND);
                return null;
            }
            if (!bot.walking()) bot.walkTo(fetching.blockPosition(), 0.3);
            return null;
        }
        if (all() || waited++ >= waitTicks) return said();
        return null;
    }

    /** Opens (or keeps) the next box for it in its bag; false when its bag has no room for what is in it. */
    private boolean take(ServerPlayer body) {
        var inv = body.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!mine(s, address)) continue;
            packages++;
            if (!open) {
                got.merge(s.getItem(), 1, Integer::sum);
                before++;
                return true;
            }
            if (free(body) < Network.contents(s).size() || !Hands.emptyHand(body)) return false;
            ItemStack one = s.split(1);
            Network.contents(one).forEach((item, n) -> got.merge(item, n, Integer::sum));
            body.setItemInHand(InteractionHand.MAIN_HAND, one);
            ((PackageItem) one.getItem()).open(body.level(), body, InteractionHand.MAIN_HAND);
            return true;
        }
        return true;
    }

    private Entity nearest(ServerPlayer body) {
        Entity best = null;
        for (Entity e : boxes(body, spots, address)) {
            if (left.contains(e)) continue;
            if (best == null || e.distanceToSqr(body) < best.distanceToSqr(body)) best = e;
        }
        return best;
    }

    private static List<Entity> boxes(ServerPlayer body, List<BlockPos> spots, String address) {
        AABB area = null;
        for (BlockPos p : spots) {
            AABB a = new AABB(p).inflate(RADIUS);
            area = area == null ? a : area.minmax(a);
        }
        if (area == null) return List.of();
        return body.level().getEntities((Entity) null, area, e -> e.isAlive() && mine(box(e), address)
                && spots.stream().anyMatch(p -> p.closerToCenterThan(e.position(), RADIUS + 1)));
    }

    /** The package a box on the ground is: Create's package entity, or a package item lying there; empty for any other. */
    private static ItemStack box(Entity e) {
        if (e instanceof PackageEntity p) return p.getBox();
        if (e instanceof ItemEntity i) return i.getItem();
        return ItemStack.EMPTY;
    }

    private static boolean mine(ItemStack s, String address) {
        return PackageItem.isPackage(s) && (address == null || PackageItem.matchAddress(s, address));
    }

    private int inBag(ServerPlayer body) {
        int n = 0;
        for (ItemStack s : body.getInventory().items) {
            if (mine(s, address)) n += s.getCount();
        }
        return n;
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
        if (packages == 0) return waitTicks == 0 ? "there were no packages for me on the ground"
                : "no packages for me came near it in " + Text.time(waitTicks);
        String what = (open ? "got " + Text.some(got) + " out of " : "took ") + packages
                + (packages == 1 ? " package" : " packages") + " on the ground";
        return all() || expected == null ? what : what + "; the rest didn't come in " + Text.time(waitTicks);
    }

    private static int free(ServerPlayer body) {
        int n = 0;
        for (ItemStack s : body.getInventory().items) {
            if (s.isEmpty()) n++;
        }
        return n;
    }

    @Override
    public String status() {
        return fetching != null ? "picking up a package from the ground" : "waiting for packages to come";
    }

    @Override
    public int progress() {
        return packages;
    }
}
