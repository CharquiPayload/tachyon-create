package tachyoncreate;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import tachyon.api.RegisterExpansionsEvent;

/**
 * The mod: registers the expansion with Tachyon as the game loads, and nothing else. Tachyon then
 * asks it what it adds ({@link CreateExpansion#setup}).
 */
@Mod(TachyonCreate.ID)
public final class TachyonCreate {

    static final String ID = "tachyon_create";

    public TachyonCreate(IEventBus modBus) {
        modBus.addListener(RegisterExpansionsEvent.class, e -> e.register(new CreateExpansion()));
        DevBrain.registerIfAsked();
    }
}
