package dev.dyslexic.cinematic.station;

import dev.dyslexic.cinematic.api.AddonStations;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** The blocks the player can step up to. Each one is a scene of its own. */
public enum StationKind {
    /** Items are laid out on the 3x3 top and crafted there. */
    TABLE,
    /** Fuel goes into the lower opening of the front, things to smelt into the upper one. */
    FURNACE,
    /** Food into the window, fuel behind the grate below it. */
    SMOKER,
    /** Ore is charged from the top, fuel goes behind the front grate, ingots come out on the sill. */
    BLAST_FURNACE,
    /** The item floats over the table, lapis rests in a corner socket, offers hang in the air as runes. */
    ENCHANTING,
    /** The item lies on the anvil, what goes into it lies on top; a hammer forges them into one. */
    ANVIL,
    /** The item rests on the wheel; ground against it, its enchantments flake away in sparks. */
    GRINDSTONE,
    /** Template, gear and material laid on the plate; the template is pressed on, the material melts in. */
    SMITHING,
    /** Stone is laid by the saw; the shapes it can be cut into hang round the blade like a dial. */
    STONECUTTER,
    /** The banner is stretched over the warp; the patterns hang beside the loom on a glass board; a shuttle weaves. */
    LOOM,
    /** The map lies on the table, what changes it beside; paper, glass or an empty map are worked into it. */
    CARTOGRAPHY,
    /** Bottles stand on the stand's feet, the ingredient sits in the bowl on top, blaze powder by the foot. */
    BREWING,
    /** Items are laid into the window of the crafter's grid; it crafts on a redstone signal, as in vanilla. */
    CRAFTER,
    /** Not opened by using it: a key sits the player down by the fire, where food is put on and taken off. */
    CAMPFIRE,
    /** The book lying open on it is read page by page; a leaf is turned by its corner. Only with a book on it. */
    LECTERN,
    /** The powers hang in a crown over the beacon; payment floats on top; lit, the light bursts out over the pyramid. */
    BEACON,
    /** A short scene: a record is lowered on its edge into the slot on top, or the one playing rises out and is taken. */
    JUKEBOX,
    /**
     * Crouched at its front: books are drawn out by the spine and slid back in, and a book with writing in it is opened
     * and read right there. Only its front is used, as in vanilla.
     */
    CHISELED_BOOKSHELF,
    /** A registered menu-backed block provided by another mod. */
    ADDON;

    private static final List<Direction> ANY_SIDE = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    @Nullable
    public static StationKind of(BlockState state) {
        if (state.is(Blocks.CRAFTING_TABLE)) {
            return TABLE;
        }
        if (state.is(Blocks.FURNACE)) {
            return FURNACE;
        }
        if (state.is(Blocks.SMOKER)) {
            return SMOKER;
        }
        if (state.is(Blocks.BLAST_FURNACE)) {
            return BLAST_FURNACE;
        }
        if (state.is(Blocks.ENCHANTING_TABLE)) {
            return ENCHANTING;
        }
        if (state.is(BlockTags.ANVIL)) {
            return ANVIL;
        }
        if (state.is(Blocks.GRINDSTONE)) {
            return GRINDSTONE;
        }
        if (state.is(Blocks.SMITHING_TABLE)) {
            return SMITHING;
        }
        if (state.is(Blocks.STONECUTTER)) {
            return STONECUTTER;
        }
        if (state.is(Blocks.LOOM)) {
            return LOOM;
        }
        if (state.is(Blocks.CARTOGRAPHY_TABLE)) {
            return CARTOGRAPHY;
        }
        if (state.is(Blocks.BREWING_STAND)) {
            return BREWING;
        }
        if (state.is(Blocks.CRAFTER)) {
            return CRAFTER;
        }
        if (state.is(BlockTags.CAMPFIRES)) {
            return CAMPFIRE;
        }
        if (state.is(Blocks.LECTERN)) {
            return LECTERN;
        }
        if (state.is(Blocks.BEACON)) {
            return BEACON;
        }
        if (state.is(Blocks.JUKEBOX)) {
            return JUKEBOX;
        }
        if (state.is(Blocks.CHISELED_BOOKSHELF)) {
            return CHISELED_BOOKSHELF;
        }
        return AddonStations.forBlock(state) == null ? null : ADDON;
    }

    public boolean matches(BlockState state) {
        return of(state) == this;
    }

    /** One of the three vanilla furnaces, all driven by the furnace block entity. */
    public boolean isFurnace() {
        return this == FURNACE || this == SMOKER || this == BLAST_FURNACE;
    }

    /** Worked through the block's own vanilla menu, which the server opens without its window. */
    public boolean usesMenu() {
        return switch (this) {
            case ENCHANTING, ANVIL, GRINDSTONE, SMITHING, STONECUTTER, LOOM, CARTOGRAPHY, BREWING, CRAFTER, LECTERN, BEACON, ADDON -> true;
            default -> false;
        };
    }

    /**
     * Whether using the block starts the scene. A campfire keeps its vanilla use (food goes straight on): its
     * scene starts on a key of its own.
     */
    public boolean startsOnUse() {
        return this != CAMPFIRE;
    }

    /**
     * The sides the player may work from: a furnace only from its front, an anvil from its long sides, a
     * grindstone facing the running face of the wheel, a stonecutter facing the blade, a loom from the bench at its
     * front, a crafter facing its grid (none when the grid faces the floor), a lectern and a chiseled bookshelf from
     * their fronts.
     */
    public List<Direction> sides(BlockState state) {
        return switch (this) {
            case ADDON -> {
                AddonStations.Definition definition = AddonStations.forBlock(state);
                yield definition == null ? List.of() : definition.usableSides(state);
            }
            case FURNACE, SMOKER, BLAST_FURNACE -> List.of(state.getValue(AbstractFurnaceBlock.FACING));
            case ANVIL -> {
                Direction facing = state.getValue(AnvilBlock.FACING);
                yield List.of(facing.getClockWise(), facing.getCounterClockWise());
            }
            case GRINDSTONE -> {
                Direction facing = state.getValue(GrindstoneBlock.FACING);
                yield List.of(facing, facing.getOpposite());
            }
            case STONECUTTER -> {
                Direction facing = state.getValue(StonecutterBlock.FACING);
                yield List.of(facing, facing.getOpposite());
            }
            case LOOM -> List.of(state.getValue(LoomBlock.FACING));
            case LECTERN -> List.of(state.getValue(LecternBlock.FACING));
            case CHISELED_BOOKSHELF -> List.of(state.getValue(HorizontalDirectionalBlock.FACING));
            // the slot on top runs north to south: a record in it shows its face to the east and the west
            case JUKEBOX -> List.of(Direction.EAST, Direction.WEST);
            case CRAFTER -> {
                Direction grid = state.getValue(BlockStateProperties.ORIENTATION).top();
                yield grid == Direction.UP ? ANY_SIDE : grid == Direction.DOWN ? List.of() : List.of(grid);
            }
            default -> ANY_SIDE;
        };
    }

    /**
     * Whether using the block goes to the mod at all: a lectern needs a book on it (without one, using it puts the book
     * in hand on it, as in vanilla), and a crafter needs its grid turned to a side it can be worked from. Whether there is
     * room to stand and the work can be reached is the client's to judge ({@code Reach}): it plays the scene, opens the
     * block's own window or says why not, as the player chose.
     */
    public boolean usable(BlockGetter level, BlockPos pos, BlockState state) {
        if (this == LECTERN && !state.getValue(LecternBlock.HAS_BOOK)) {
            return false;
        }
        return !sides(state).isEmpty();
    }

    /**
     * Whether using this face of the block goes to the mod: a chiseled bookshelf is only used by its front (its sides
     * take a block placed against them, as in vanilla).
     */
    public boolean usedOn(BlockState state, Direction face) {
        return this != CHISELED_BOOKSHELF || face == state.getValue(HorizontalDirectionalBlock.FACING);
    }

    /**
     * Room for a player to stand at {@code stand}: something low underfoot (a carpet, a thin layer of snow, a closed
     * trapdoor) is stood on.
     */
    public static boolean roomAt(BlockGetter level, BlockPos stand) {
        return level.getBlockState(stand).getCollisionShape(level, stand).max(Direction.Axis.Y) <= 0.2
                && level.getBlockState(stand.above()).getCollisionShape(level, stand.above()).isEmpty();
    }
}
