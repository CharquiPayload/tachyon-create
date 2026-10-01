package tachyoncreate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What of the things a bot carries a word names, for a mind's tool ("iron ore", "create:zinc_ore",
 * "cog"): its id, its path, its name as players read it, or another word for it ({@link #WORDS}, the
 * same words the expansion gives Tachyon). The whole of a name first, then a part of one; of several
 * things that match a part, the one it carries most of. "fuel" is any fuel a blaze burner takes.
 */
final class Carried {

    /** Other words for Create's things, as players say them. */
    static final Map<String, String> WORDS = Map.ofEntries(
            Map.entry("cog", "create:cogwheel"),
            Map.entry("small_cog", "create:cogwheel"),
            Map.entry("big_cog", "create:large_cogwheel"),
            Map.entry("large_cog", "create:large_cogwheel"),
            Map.entry("belt", "create:belt_connector"),
            Map.entry("alloy", "create:andesite_alloy"),
            Map.entry("brass", "create:brass_ingot"),
            Map.entry("zinc", "create:zinc_ingot"),
            Map.entry("crank", "create:hand_crank"),
            Map.entry("burner", "create:blaze_burner"),
            Map.entry("glue", "create:super_glue"),
            Map.entry("casing", "create:andesite_casing"),
            Map.entry("tube", "create:electron_tube"),
            Map.entry("mechanism", "create:precision_mechanism"));

    private Carried() {
    }

    /** What the word names among what it carries, or null for nothing it carries. */
    static Predicate<ItemStack> match(ServerPlayer body, String word) {
        if (word == null || word.isBlank()) return null;
        String w = word.trim().toLowerCase(Locale.ROOT);
        if (w.equals("fuel")) return Fuel.ANY;
        String key = w.replace(' ', '_');
        if (WORDS.containsKey(key)) key = WORDS.get(key);
        Item whole = null, part = null;
        int partCount = 0;
        for (ItemStack s : body.getInventory().items) {
            if (s.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            String name = s.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (id.toString().equals(key) || id.getPath().equals(key) || name.equals(w)) {
                whole = s.getItem();
                break;
            }
            if (id.getPath().contains(key) || name.contains(w)) {
                int n = Hands.count(body, x -> x.is(s.getItem()));
                if (n > partCount) {
                    part = s.getItem();
                    partCount = n;
                }
            }
        }
        Item found = whole != null ? whole : part;
        return found == null ? null : s -> s.is(found);
    }
}
