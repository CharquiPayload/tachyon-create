package tachyoncreate;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Create 6's logistics as a player meets them: a network's stock, seen and ordered at a stock ticker
 * through the keeper sitting by it, and the mailboxes (frogports and postboxes) where what was ordered
 * arrives in packages, each with an address.
 *
 * <p>A bot goes by Create's own rules for a player: no keeper, no orders; a network locked by
 * someone else is not for it ({@link #why}). And it never writes an address on a package itself: a
 * player can't.
 */
final class Network {

    /** How far around it a bot knows its mailboxes, in blocks. */
    static final double MAILBOXES = 48;

    private Network() {
    }

    /** The stock ticker at a place, or null. */
    static StockTickerBlockEntity ticker(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StockTickerBlockEntity t ? t : null;
    }

    /** The mailbox (a frogport or a postbox) at a place, or null. */
    static PackagePortBlockEntity mailbox(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof PackagePortBlockEntity p ? p : null;
    }

    /**
     * Why a bot can't use a stock ticker as a player would, or null when it can: a keeper must sit by
     * it, and its network must not be locked by someone else.
     */
    static String why(StockTickerBlockEntity ticker, ServerPlayer body) {
        if (!ticker.isKeeperPresent()) return "nobody keeps that stock ticker: it needs a stock keeper sitting by it";
        if (!ticker.behaviour.mayInteract(body)) return "that stock network is locked, and it isn't mine to use";
        return null;
    }

    /** A mailbox's address, or null when it has none (it takes no packages of its own). */
    static String address(PackagePortBlockEntity box) {
        String a = box.addressFilter;
        return a == null || a.isBlank() ? null : a.trim();
    }

    /**
     * The mailboxes with an address within {@link #MAILBOXES} of the bot, the nearest first; only those
     * whose address takes packages for {@code address}, when it's given.
     */
    static List<PackagePortBlockEntity> mailboxes(ServerPlayer body, String address) {
        Vec3 at = body.position();
        List<PackagePortBlockEntity> out = new ArrayList<>();
        for (var be : Sight.blockEntities(body.serverLevel(), body.blockPosition(), MAILBOXES,
                be -> be instanceof PackagePortBlockEntity p && address(p) != null
                        && be.getBlockPos().distToCenterSqr(at) <= MAILBOXES * MAILBOXES)) {
            PackagePortBlockEntity p = (PackagePortBlockEntity) be;
            if (address == null || PackageItem.matchAddress(address, address(p))) out.add(p);
        }
        out.sort(java.util.Comparator.comparingDouble(p -> p.getBlockPos().distToCenterSqr(at)));
        return out;
    }

    /**
     * What a word names in a network's stock ("iron", "create:zinc_ingot", "brass"), as {@link Carried}
     * reads a name: the whole of it first, then a part, and of several parts the one it has most of.
     * Null for nothing in stock.
     */
    static BigItemStack find(InventorySummary stock, String word) {
        if (word == null || word.isBlank()) return null;
        String w = word.trim().toLowerCase(Locale.ROOT);
        String key = w.replace(' ', '_');
        if (Carried.WORDS.containsKey(key)) key = Carried.WORDS.get(key);
        BigItemStack part = null;
        for (BigItemStack s : stock.getStacksByCount()) {
            if (s.stack.isEmpty() || s.count <= 0) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.stack.getItem());
            String name = s.stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (id.toString().equals(key) || id.getPath().equals(key) || name.equals(w)) return s;
            // By count, the most first: the first part that matches is the one it has most of.
            if (part == null && (id.getPath().contains(key) || name.contains(w))) part = s;
        }
        return part;
    }

    /** A network's stock in words, the most first, at most {@code most} kinds: "2304 cobblestone, 64 iron ingot and 3 more". */
    static String say(InventorySummary stock, int most) {
        List<BigItemStack> all = stock.getStacksByCount();
        if (all.isEmpty()) return "nothing";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(most, all.size()); i++) {
            BigItemStack s = all.get(i);
            parts.add((s.isInfinite() ? "endless" : String.valueOf(s.count)) + " " + Text.name(s.stack.getItem()));
        }
        String out = parts.size() == 1 ? parts.get(0)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        int more = all.size() - parts.size();
        return more > 0 ? out + ", and " + more + " other kinds" : out;
    }

    /** How many of each item a package holds. */
    static Map<Item, Integer> contents(ItemStack box) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        var inside = PackageItem.getContents(box);
        for (int i = 0; i < inside.getSlots(); i++) {
            ItemStack s = inside.getStackInSlot(i);
            if (!s.isEmpty()) out.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return out;
    }
}
