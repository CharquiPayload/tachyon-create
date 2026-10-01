package tachyoncreate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.Locale;
import java.util.Map;

/** Words players read: an item and how many ("16 iron ore"), a time ("1m 30s"). */
final class Text {

    private Text() {
    }

    /** An item's name as a player reads it, in lower case: "iron ore". */
    static String name(ItemLike item) {
        return new ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT);
    }

    /** How many of an item: "16 iron ore". */
    static String some(int n, ItemLike item) {
        return n + " " + name(item);
    }

    /** What a list of counts says, "3 crushed raw iron and 1 gravel", or "nothing". */
    static String some(Map<Item, Integer> counts) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (e.getValue() > 0) parts.add(some(e.getValue(), e.getKey()));
        }
        if (parts.isEmpty()) return "nothing";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** A time in ticks, as "45s" or "2m 5s". */
    static String time(int ticks) {
        int s = Math.max(0, ticks / 20);
        return s < 60 ? s + "s" : s / 60 + "m" + (s % 60 == 0 ? "" : " " + s % 60 + "s");
    }

    /** An item's full id: "minecraft:coal". */
    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
