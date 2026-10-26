package dev.dyslexic.cinematic.furnace;

import dev.dyslexic.cinematic.furnace.FurnaceZones.Face;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Where things go on each furnace, read off its textures. Coordinates are 1/16 of a block per texture pixel.
 * Furnace: openings above and below a stone ledge. Smoker: a window over a grate. Blast furnace: charged from
 * the top, a small grate in the middle of the front, ingots out on the sill above it.
 */
public final class FurnaceLayout {
    /** A click target for one slot, with the part of the face that lights up on hover. */
    public record Zone(int slot, Face face, double u0, double u1, double v0, double v1, double[] highlight) {
        boolean contains(Face f, double u, double v) {
            return f == face && u >= u0 && u <= u1 && v >= v0 && v <= v1;
        }
    }

    /** Where a slot's items rest: spread across [u0, u1] on a floor at height {@code floor} (front) or sunk into the top. */
    public record Bed(int slot, Face face, double u0, double u1, double floor, double depth, int maxCopies, float tilt) {}

    /** A glowing rectangle when lit; {@code fire} ones flicker strongest, the others heat up while cooking. */
    public record Glow(Face face, double u0, double u1, double v0, double v1, boolean fire) {}

    public final List<Zone> zones;
    public final List<Bed> beds;
    public final List<Glow> glows;
    /** Front-face strips for the cooking and fuel gauges: u0, u1, v0, v1. */
    public final double[] cookGauge;
    public final double[] burnGauge;
    public final RecipeType<?> recipeType;

    private FurnaceLayout(List<Zone> zones, List<Bed> beds, List<Glow> glows, double[] cookGauge, double[] burnGauge, RecipeType<?> recipeType) {
        this.zones = zones;
        this.beds = beds;
        this.glows = glows;
        this.cookGauge = cookGauge;
        this.burnGauge = burnGauge;
        this.recipeType = recipeType;
    }

    private static double px(double pixels) {
        return pixels / 16.0;
    }

    public static final FurnaceLayout FURNACE = new FurnaceLayout(
            List.of(new Zone(FurnaceZones.INPUT, Face.FRONT, 0.11, 0.5, 0.5, 0.92, new double[]{px(3), 0.5, px(9), px(13)}),
                    new Zone(FurnaceZones.RESULT, Face.FRONT, 0.5, 0.89, 0.5, 0.92, new double[]{0.5, px(13), px(9), px(13)}),
                    new Zone(FurnaceZones.FUEL, Face.FRONT, 0.11, 0.89, 0.0, 0.39, new double[]{px(3), px(13), px(1), px(5)})),
            List.of(new Bed(FurnaceZones.INPUT, Face.FRONT, 0.22, 0.47, px(9), 0.035, 3, 0),
                    new Bed(FurnaceZones.RESULT, Face.FRONT, 0.53, 0.78, px(9), 0.035, 3, 0),
                    new Bed(FurnaceZones.FUEL, Face.FRONT, 0.26, 0.74, px(1), 0.035, 4, 0)),
            List.of(new Glow(Face.FRONT, px(3), px(13), px(1), px(5), true),
                    new Glow(Face.FRONT, px(3), px(13), px(9), px(13), false)),
            new double[]{px(3), px(13), 0.405, 0.43},
            new double[]{px(3), px(13), 0.012, 0.037},
            RecipeType.SMELTING);

    public static final FurnaceLayout SMOKER = new FurnaceLayout(
            List.of(new Zone(FurnaceZones.INPUT, Face.FRONT, 0.1, 0.47, 0.46, 0.86, new double[]{px(3), px(7.5), px(8), px(12)}),
                    new Zone(FurnaceZones.RESULT, Face.FRONT, 0.47, 0.84, 0.46, 0.86, new double[]{px(7.5), px(12), px(8), px(12)}),
                    new Zone(FurnaceZones.FUEL, Face.FRONT, 0.08, 0.92, 0.0, 0.46, new double[]{px(2), px(14), px(1), px(7)})),
            List.of(new Bed(FurnaceZones.INPUT, Face.FRONT, 0.24, 0.43, px(8), 0.035, 3, 0),
                    new Bed(FurnaceZones.RESULT, Face.FRONT, 0.5, 0.7, px(8), 0.035, 3, 0),
                    new Bed(FurnaceZones.FUEL, Face.FRONT, 0.22, 0.78, px(1), 0.035, 4, 0)),
            List.of(new Glow(Face.FRONT, px(2), px(14), px(1), px(7), true),
                    new Glow(Face.FRONT, px(3), px(12), px(8), px(12), false)),
            new double[]{px(3), px(13), 0.452, 0.482},
            new double[]{px(3), px(13), 0.012, 0.037},
            RecipeType.SMOKING);

    public static final FurnaceLayout BLAST_FURNACE = new FurnaceLayout(
            List.of(new Zone(FurnaceZones.INPUT, Face.TOP, 0.06, 0.94, 0.12, 0.94, new double[]{0.22, 0.78, 0.3, 0.86}),
                    new Zone(FurnaceZones.RESULT, Face.FRONT, 0.12, 0.88, 0.52, 0.86, new double[]{px(3), px(13), px(9), px(10.5)}),
                    new Zone(FurnaceZones.FUEL, Face.FRONT, 0.12, 0.88, 0.16, 0.52, new double[]{px(4), px(12), px(5), px(8)})),
            List.of(new Bed(FurnaceZones.INPUT, Face.TOP, 0.32, 0.68, 0.58, 0.045, 3, 62),
                    new Bed(FurnaceZones.RESULT, Face.FRONT, 0.3, 0.7, px(9), 0.035, 3, 0),
                    new Bed(FurnaceZones.FUEL, Face.FRONT, 0.32, 0.68, px(5), 0.035, 3, 0)),
            List.of(new Glow(Face.FRONT, px(4), px(12), px(5), px(8), true),
                    new Glow(Face.TOP, 0.26, 0.74, 0.34, 0.82, false)),
            new double[]{px(4), px(12), 0.24, 0.265},
            new double[]{px(4), px(12), 0.012, 0.037},
            RecipeType.BLASTING);

    @Nullable
    public static FurnaceLayout of(StationKind kind) {
        return switch (kind) {
            case FURNACE -> FURNACE;
            case SMOKER -> SMOKER;
            case BLAST_FURNACE -> BLAST_FURNACE;
            default -> null;
        };
    }

    /** The zone under a point of a face, or -1. */
    public int zoneAt(Face face, double u, double v) {
        for (Zone zone : zones) {
            if (zone.contains(face, u, v)) {
                return zone.slot();
            }
        }
        return -1;
    }

    @Nullable
    public Zone zone(int slot) {
        for (Zone zone : zones) {
            if (zone.slot() == slot) {
                return zone;
            }
        }
        return null;
    }

    public boolean usesTop() {
        for (Zone zone : zones) {
            if (zone.face() == Face.TOP) {
                return true;
            }
        }
        return false;
    }

    /** Centre of a zone's highlight, in its face's (u, v). */
    public double[] center(int slot) {
        Zone zone = zone(slot);
        double[] h = zone.highlight();
        return new double[]{(h[0] + h[1]) / 2, (h[2] + h[3]) / 2};
    }
}
