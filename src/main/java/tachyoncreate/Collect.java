package tachyoncreate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import tachyon.api.Bot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Taking out what a machine holds or made, with a click of an empty hand, as a player does: a depot
 * gives its stack and what was made from it, a millstone what it ground (else what waits to be
 * ground), a basin all it holds, a deployer what is in its hand. What came out is what its bag gained.
 */
final class Collect extends MachineJob {

    private int took;

    Collect(java.util.List<Machine.Found> options) {
        super(options);
    }

    @Override
    double good(ServerPlayer body, Vec3 eye, BlockPos feet) {
        return reaches(Sight.hit(body, eye, target.pos(), target.kind().face(body.level().getBlockState(target.pos())), reach(body)) != null);
    }

    @Override
    void work(Bot bot) {
        ServerPlayer body = bot.body();
        if (!Hands.emptyHand(body)) {
            over = "my bag is full: I've no room to take anything out";
            return;
        }
        BlockHitResult hit = Sight.hit(body, body.getEyePosition(), target.pos(),
                target.kind().face(body.level().getBlockState(target.pos())), reach(body));
        if (hit == null) {
            again();
            return;
        }
        Map<Item, Integer> before = counts(body);
        InteractionResult r = Hands.click(body, hit);
        if (r == null) {
            over = "I'm not allowed to use the " + target.kind().name + " here";
            return;
        }
        Map<Item, Integer> gained = new LinkedHashMap<>();
        counts(body).forEach((item, n) -> {
            int more = n - before.getOrDefault(item, 0);
            if (more > 0) gained.put(item, more);
        });
        took = gained.values().stream().mapToInt(Integer::intValue).sum();
        over = took == 0 ? "the " + target.kind().name + " had nothing in it"
                : "took " + Text.some(gained) + " out of the " + target.kind().name;
    }

    /** How many of each item it carries, its hands' included. */
    private static Map<Item, Integer> counts(ServerPlayer body) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (ItemStack s : body.getInventory().items) {
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack off = body.getOffhandItem();
        if (!off.isEmpty()) out.merge(off.getItem(), off.getCount(), Integer::sum);
        return out;
    }

    @Override
    public String status() {
        return "taking things out of the " + target.kind().name;
    }

    @Override
    public int progress() {
        return took;
    }
}
