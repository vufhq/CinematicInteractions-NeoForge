package dev.dyslexic.cinematic.table;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Geometry of the 3x3 grid painted on the crafting table top. Cells are stored in world orientation
 * (index = x + z * 3), so items lie where they were put; the recipe grid is read from the side the
 * crafting player stands on, with row 0 being the far edge.
 */
public final class TableGrid {
    private TableGrid() {}

    public static final int CELLS = 9;
    /** Cell pitch on the texture: lines every 3 px. */
    public static final double CELL = 3.0 / 16.0;
    /**
     * The one-pixel grid lines occupy texels 3, 6, 9 and 12 on both axes. Split their centres, not their
     * leading edges, so the cell centres land at texture coordinates 5, 8 and 11 and the middle is (0.5, 0.5).
     */
    public static final double GRID_MIN = 3.5 / 16.0;
    public static final double GRID_MAX = GRID_MIN + 3 * CELL;

    public static boolean isTable(BlockState state) {
        return state.is(Blocks.CRAFTING_TABLE);
    }

    /** Cell centre inside the block, 0..1. */
    public static double cellX(int index) {
        return GRID_MIN + CELL * (index % 3 + 0.5);
    }

    public static double cellZ(int index) {
        return GRID_MIN + CELL * (index / 3 + 0.5);
    }

    /** World cell under a point given in block-local coordinates, or -1. */
    public static int cellAt(double localX, double localZ) {
        if (localX < GRID_MIN || localZ < GRID_MIN || localX >= GRID_MAX || localZ >= GRID_MAX) {
            return -1;
        }
        double gx = (localX - GRID_MIN) / CELL;
        double gz = (localZ - GRID_MIN) / CELL;
        return (int) gx + (int) gz * 3;
    }

    /** World cell holding recipe slot (row, col) for a player standing on {@code side}. */
    public static int worldIndex(Direction side, int row, int col) {
        Direction forward = side.getOpposite();
        Direction right = forward.getClockWise();
        int dx = forward.getStepX() * (1 - row) + right.getStepX() * (col - 1);
        int dz = forward.getStepZ() * (1 - row) + right.getStepZ() * (col - 1);
        return (1 + dx) + (1 + dz) * 3;
    }

    /** Recipe-ordered view of the world cells (the same stack instances). */
    public static List<ItemStack> oriented(List<ItemStack> world, Direction side) {
        List<ItemStack> grid = new ArrayList<>(CELLS);
        for (int i = 0; i < CELLS; i++) {
            grid.add(world.get(worldIndex(side, i / 3, i % 3)));
        }
        return grid;
    }

    /** The side of the table a viewer at (x, z) is standing on. */
    public static Direction sideFacing(BlockPos pos, double x, double z) {
        double dx = x - (pos.getX() + 0.5);
        double dz = z - (pos.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
