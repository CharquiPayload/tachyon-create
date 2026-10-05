package tachyoncreate;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import tachyon.api.Expansion;
import tachyon.api.Setup;
import tachyon.api.TachyonApi;

import java.util.Map;
import java.util.Set;

/**
 * What Tachyon: Create adds to Tachyon and makes fit: the factory's hands ({@link Factory}), the stock
 * networks' errands ({@link Post}), Create's things by the words players use for them, and Create's
 * tools kept as tools.
 */
final class CreateExpansion implements Expansion {

    /**
     * Create's tools: kept in the hotbar, never tossed as trash, never burnt. Goggles too, which a
     * bot keeps to put on (they show what a machine is doing).
     */
    static final Set<String> TOOLS = Set.of("create:wrench", "create:goggles", "create:extendo_grip", "create:potato_cannon",
            "create:wand_of_symmetry", "create:sand_paper", "create:red_sand_paper", "create:linked_controller",
            "create:schematic_and_quill", "create:clipboard");

    /** Create's blocks by other words, as players say them. */
    static final Map<String, String> BLOCKS = Map.of(
            "crank", "create:hand_crank",
            "burner", "create:blaze_burner",
            "cog", "create:cogwheel",
            "big_cog", "create:large_cogwheel",
            "large_cog", "create:large_cogwheel");

    @Override
    public void setup(Setup setup) {
        if (!TachyonApi.atLeast("1.1")) {
            LogUtils.getLogger().warn("[tachyon_create] Tachyon's API is {}, and this needs 1.1: nothing added", TachyonApi.VERSION);
            return;
        }
        setup.add(new Factory());
        setup.add(new Post());

        Carried.WORDS.forEach((word, id) -> setup.alias(Setup.Names.ITEM, word, id));
        BLOCKS.forEach((word, id) -> setup.alias(Setup.Names.BLOCK, word, id));
        setup.items(Setup.ItemKind.TOOL, stack -> TOOLS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()) ? Boolean.TRUE : null);
    }
}
