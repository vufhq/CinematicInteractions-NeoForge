package dev.dyslexic.cinematic;

import dev.dyslexic.cinematic.station.StationKind;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Hooks the client mod fills in, so common code never loads client classes on a dedicated server. */
public final class ClientBridge {
    private ClientBridge() {}

    /** The local player used a station; start its scene. */
    public static BiConsumer<BlockPos, StationKind> useStation = (pos, kind) -> {};
    /** True when a vanilla server should handle this use and may open its normal menu. */
    public static BiPredicate<BlockPos, StationKind> vanillaUse = (pos, kind) -> false;
    /** Where on the block the last use touched (a chiseled bookshelf: which book). */
    public static Vec3 useAt = Vec3.ZERO;
    /**
     * Set when a use started a scene: the hand swing vanilla adds for that use is skipped, as on NeoForge (a block's own
     * window opened instead swings the hand itself, Scene.openWindow). Cleared once that use is handled.
     */
    public static boolean swallowSwing;
}
