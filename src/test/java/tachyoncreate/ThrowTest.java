package tachyoncreate;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrowTest {

    /** A bot's toss start (eyes less 0.3) standing on the floor: its feet at y 64. */
    private static final Vec3 FROM = new Vec3(0.5, 64 + Stand.EYES - 0.3, 0.5);

    @Test
    void aThrowWithNoSpreadComesDownWhereTheGameWould() {
        // Straight ahead, level: it starts 0.1 up, then falls. One tick: up 0.06, ahead 0.3.
        Vec3 at = Throw.landing(FROM, FROM.y - 1, 0, 1, 0, 0, 0, 0);
        assertNotNull(at);
        assertTrue(at.z > FROM.z + 0.9 && at.z < FROM.z + 3, "lands a block or two ahead: " + at);
        assertEquals(FROM.x, at.x, 1e-9);
    }

    @Test
    void aThrowFromUnderTheTopNeverLands() {
        assertNull(Throw.landing(FROM, FROM.y + 3, 0, 1, 45, 0, 0, 0));
    }

    @Test
    void aBasinOnTheFloorBesideItIsHitMostOfTheTime() {
        // A basin's top is a block up from the floor; its opening a quarter block each way of the middle.
        Vec3 basin = new Vec3(0.5, 65, 1.5);
        Throw.Aim aim = Throw.best(FROM, basin, 0.25, (a, b) -> true);
        assertNotNull(aim);
        assertTrue(aim.chance() >= 0.7, "chance " + aim.chance());
    }

    @Test
    void aMillstoneABlockDownIsHitAlmostAlways() {
        Vec3 millstone = new Vec3(0.5, 64, 1.5);
        Throw.Aim aim = Throw.best(FROM, millstone, 0.4, (a, b) -> true);
        assertNotNull(aim);
        assertTrue(aim.chance() >= 0.95, "chance " + aim.chance());
    }

    @Test
    void aCeilingTakesTheHighThrowsAway() {
        // Nothing above 66: a lob that goes higher is no throw, and another one is found.
        Throw.Aim open = Throw.best(FROM, new Vec3(0.5, 65, 1.5), 0.25, (a, b) -> true);
        Throw.Aim low = Throw.best(FROM, new Vec3(0.5, 65, 1.5), 0.25, (a, b) -> a.y < 66 && b.y < 66);
        assertNotNull(open);
        assertNotNull(low);
        assertTrue(low.pitch() >= 0 || low.chance() <= open.chance(), "a low throw: " + low);
    }

    @Test
    void theYawFacesTheGamesWay() {
        // The game's yaw: 0 faces south (+z), -90 east (+x), 90 west, 180 north.
        assertEquals(0, Throw.yaw(new Vec3(0, 0, 0), new Vec3(0, 0, 1)), 1e-4);
        assertEquals(-90, Throw.yaw(new Vec3(0, 0, 0), new Vec3(1, 0, 0)), 1e-4);
        assertEquals(90, Throw.yaw(new Vec3(0, 0, 0), new Vec3(-1, 0, 0)), 1e-4);
    }
}
