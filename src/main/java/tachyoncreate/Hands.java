package tachyoncreate;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Predicate;

/**
 * A bot's hands on Create's machines, each thing done as a player does it: what it holds is chosen
 * from its hotbar or brought there from its bag (as a player does in the inventory screen), a click
 * is the game's own use of a block with the server's checks, and a throw is the game's own toss.
 *
 * <p>A click goes through {@code gameMode.useItemOn}, as Tachyon's own clicks do: the right-click
 * event that protection mods listen to is posted, so a claim that refuses a player refuses a bot.
 * All of it on the server's thread.
 */
final class Hands {

    private Hands() {
    }

    /** How many of what passes {@code what} it carries. */
    static int count(ServerPlayer p, Predicate<ItemStack> what) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && what.test(s)) n += s.getCount();
        }
        return n;
    }

    /** The first stack it carries that passes {@code what}, or empty. */
    static ItemStack first(ServerPlayer p, Predicate<ItemStack> what) {
        for (ItemStack s : p.getInventory().items) {
            if (!s.isEmpty() && what.test(s)) return s;
        }
        return ItemStack.EMPTY;
    }

    /**
     * {@code n} of what passes {@code what} alone in its main hand, as a player splits a stack into
     * an empty slot of the hotbar and picks that slot. A stack of just that many already in the
     * hotbar is picked as it is. False when it carries none, or has no room to split it into.
     */
    static boolean holdSome(ServerPlayer p, Predicate<ItemStack> what, int n) {
        Inventory inv = p.getInventory();
        ItemStack held = inv.getSelected();
        if (!held.isEmpty() && what.test(held) && held.getCount() <= n) return true;
        int from = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && what.test(s)) {
                // A stack that is all it needs, already in the hotbar, is simply picked.
                if (Inventory.isHotbarSlot(i) && s.getCount() <= n) {
                    inv.selected = i;
                    return true;
                }
                if (from < 0) from = i;
            }
        }
        if (from < 0) return false;
        ItemStack source = inv.items.get(from);
        if (source.getCount() <= n) return hold(p, s -> s == source);
        if (!emptyHand(p)) return false;
        inv.items.set(inv.selected, source.split(n));
        inv.setChanged();
        return true;
    }

    /** What passes {@code what} in its main hand: its hotbar slot picked, or the stack brought there from its bag. */
    static boolean hold(ServerPlayer p, Predicate<ItemStack> what) {
        Inventory inv = p.getInventory();
        if (what.test(inv.getSelected()) && !inv.getSelected().isEmpty()) return true;
        int at = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && what.test(s)) {
                at = i;
                if (Inventory.isHotbarSlot(i)) break;
            }
        }
        if (at < 0) return false;
        if (Inventory.isHotbarSlot(at)) {
            inv.selected = at;
            return true;
        }
        // From the bag to the hotbar's picked slot, what was there going where it came from.
        ItemStack bag = inv.items.get(at);
        inv.items.set(at, inv.getSelected());
        inv.items.set(inv.selected, bag);
        inv.setChanged();
        return true;
    }

    /**
     * An empty main hand: an empty slot of the hotbar picked, else what it holds put away in an empty
     * slot of its bag. False when every slot is taken.
     */
    static boolean emptyHand(ServerPlayer p) {
        Inventory inv = p.getInventory();
        if (inv.getSelected().isEmpty()) return true;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.selected = i;
                return true;
            }
        }
        for (int i = Inventory.getSelectionSize(); i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) {
                inv.items.set(i, inv.getSelected());
                inv.items.set(inv.selected, ItemStack.EMPTY);
                inv.setChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * A right-click on a block with what is in the main hand, at a spot it sees ({@link Sight#hit}),
     * with the checks the game makes of a player's click: within reach, below the build limit, where
     * the game lets it (spawn protection, the world's border). Then the game's own use of the block.
     *
     * @return what the game answered, or null when no click was made
     */
    static InteractionResult click(ServerPlayer p, BlockHitResult hit) {
        ServerLevel level = p.serverLevel();
        BlockPos pos = hit.getBlockPos();
        if (!p.canInteractWithBlock(pos, 1.0)) return null;
        if (pos.getY() >= level.getMaxBuildHeight()) return null;
        if (!level.mayInteract(p, pos)) return null;
        ItemStack stack = p.getMainHandItem();
        if (!stack.isItemEnabled(level.enabledFeatures())) return null;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
        InteractionResult r = p.gameMode.useItemOn(p, level, stack, InteractionHand.MAIN_HAND, hit);
        if (r.shouldSwing()) p.swing(InteractionHand.MAIN_HAND, true);
        return r;
    }

    /**
     * Throws {@code n} of what is in its main hand, facing {@code yaw} and looking {@code pitch} (down
     * is positive), as a player's toss: the game's own, with its spread. Null when nothing went (an
     * empty hand, a mod refusing the toss: what was in hand stays there).
     */
    static ItemEntity toss(ServerPlayer p, float yaw, float pitch, int n) {
        Inventory inv = p.getInventory();
        if (inv.getSelected().isEmpty() || n <= 0) return null;
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.setXRot(pitch);
        ItemStack part = inv.removeItem(inv.selected, n);
        ItemEntity thrown = p.drop(part, true);
        if (thrown == null) {
            // Refused: it goes back in hand, or anywhere in the bag.
            ItemStack held = inv.getSelected();
            if (held.isEmpty()) inv.items.set(inv.selected, part);
            else if (ItemStack.isSameItemSameComponents(held, part)) held.grow(part.getCount());
            else inv.add(part);
            return null;
        }
        p.swing(InteractionHand.MAIN_HAND, true);
        return thrown;
    }
}
