package tachyoncreate;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.WiFiEffectPacket;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import tachyon.api.Bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * At a stock ticker, as a player at its keeper: a look at what the network has, or an order of some of
 * it sent to an address, as the keeper's screen sends one. What it ordered ({@link #ordered}) is what a
 * pickup after it waits for.
 */
final class Ask extends MachineJob {

    /** What it wants: a word for an item and how many (0 for a stack). */
    record Want(String word, int count) {
    }

    /** How many kinds of things a look tells. */
    private static final int TELLS = 12;

    /** What it orders; empty for a look at the stock. */
    private final List<Want> wants;
    /** Where what it orders goes. */
    private final String address;
    /** What it ordered, once it has: what it asked for, as much as there was of it. */
    final Map<Item, Integer> ordered = new LinkedHashMap<>();
    private boolean done;

    /** A look at the stock. */
    Ask(List<Machine.Found> options) {
        this(options, List.of(), null);
    }

    /** An order of {@code wants}, to {@code address}. */
    Ask(List<Machine.Found> options, List<Want> wants, String address) {
        super(options);
        this.wants = wants;
        this.address = address;
    }

    @Override
    double good(ServerPlayer body, Vec3 eye, BlockPos feet) {
        return reaches(Sight.hit(body, eye, target.pos(), null, reach(body)) != null);
    }

    @Override
    void work(Bot bot) {
        ServerPlayer body = bot.body();
        StockTickerBlockEntity ticker = Network.ticker(body.level(), target.pos());
        if (ticker == null) {
            over = gone();
            return;
        }
        String no = Network.why(ticker, body);
        if (no != null) {
            over = no;
            return;
        }
        done = true;
        InventorySummary stock = ticker.getAccurateSummary();
        if (wants.isEmpty()) {
            over = "the network has " + Network.say(stock, TELLS);
            return;
        }
        List<BigItemStack> order = new ArrayList<>();
        List<String> missing = new ArrayList<>(), less = new ArrayList<>();
        for (Want w : wants) {
            BigItemStack have = Network.find(stock, w.word());
            if (have == null) {
                missing.add(w.word().trim());
                continue;
            }
            int want = w.count() > 0 ? w.count() : have.stack.getMaxStackSize();
            int n = have.isInfinite() ? want : Math.min(want, have.count);
            if (n < want) less.add(Text.some(n, have.stack.getItem()) + " of the " + want + " I wanted");
            order.add(new BigItemStack(have.stack.copyWithCount(1), n));
            ordered.merge(have.stack.getItem(), n, Integer::sum);
        }
        if (order.isEmpty()) {
            over = "the network has no " + String.join(" or ", missing);
            return;
        }
        // As the keeper's screen sends an order: its sound, its signal, then the order itself.
        AllSoundEvents.STOCK_TICKER_REQUEST.playOnServer(body.level(), target.pos());
        WiFiEffectPacket.send(body.level(), target.pos());
        if (!ticker.broadcastPackageRequest(LogisticallyLinkedBehaviour.RequestType.PLAYER, PackageOrderWithCrafts.simple(order),
                null, address)) {
            ordered.clear();
            over = "the network couldn't send it: no packager there can pack it";
            return;
        }
        String said = "ordered " + Text.some(ordered) + " to \"" + address + "\"";
        if (!less.isEmpty()) said += " (only " + String.join(", ", less) + ")";
        if (!missing.isEmpty()) said += "; the network has no " + String.join(" or ", missing);
        over = said;
    }

    @Override
    public String status() {
        return wants.isEmpty() ? "looking at the stock at the " + target.kind().name : "ordering at the " + target.kind().name;
    }

    @Override
    public int progress() {
        return done ? 1 : 0;
    }
}
