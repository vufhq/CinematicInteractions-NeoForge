package dev.dyslexic.cinematic.furnace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Coordinates on a furnace's faces. On the front, (u, v) run from the viewer's left to right and from bottom
 * to top; on the top, u runs left to right and v from the front edge (0) to the back edge (1).
 */
public final class FurnaceZones {
    private FurnaceZones() {}

    /** Zone ids are the furnace slot indices. */
    public static final int INPUT = 0;
    public static final int FUEL = 1;
    public static final int RESULT = 2;

    public enum Face { FRONT, TOP }

    /** The viewer's right while facing a furnace that faces {@code facing}. */
    public static Direction right(Direction facing) {
        return facing.getOpposite().getClockWise();
    }

    /** A point on the front face; {@code out} > 0 lies in front of it, < 0 inside the block. */
    public static Vec3 point(BlockPos pos, Direction facing, double u, double v, double out) {
        Direction right = right(facing);
        return new Vec3(
                pos.getX() + 0.5 + facing.getStepX() * (0.5 + out) + right.getStepX() * (u - 0.5),
                pos.getY() + v,
                pos.getZ() + 0.5 + facing.getStepZ() * (0.5 + out) + right.getStepZ() * (u - 0.5));
    }

    /** A point on the top face; {@code up} > 0 lies above it, < 0 inside the block. */
    public static Vec3 top(BlockPos pos, Direction facing, double u, double v, double up) {
        Direction right = right(facing);
        return new Vec3(
                pos.getX() + 0.5 + facing.getStepX() * (0.5 - v) + right.getStepX() * (u - 0.5),
                pos.getY() + 1.0 + up,
                pos.getZ() + 0.5 + facing.getStepZ() * (0.5 - v) + right.getStepZ() * (u - 0.5));
    }

    public static Vec3 point(BlockPos pos, Direction facing, Face face, double u, double v, double out) {
        return face == Face.FRONT ? point(pos, facing, u, v, out) : top(pos, facing, u, v, out);
    }
}
