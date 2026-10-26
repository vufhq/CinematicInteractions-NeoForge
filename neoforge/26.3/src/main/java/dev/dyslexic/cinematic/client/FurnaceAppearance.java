package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.furnace.FurnaceLayout;
import dev.dyslexic.cinematic.furnace.FurnaceZones;
import dev.dyslexic.cinematic.furnace.FurnaceZones.Face;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Local offsets use vanilla texture pixels. Logical slots and the shared furnace layout stay untouched. */
record FurnaceAppearance(FurnaceAppearance.Region input, FurnaceAppearance.Region fuel,
        FurnaceAppearance.Region result, FurnaceAppearance.Region cooking, FurnaceAppearance.Region burning) {
    static final FurnaceAppearance VANILLA = new FurnaceAppearance(Region.VANILLA, Region.VANILLA,
            Region.VANILLA, Region.VANILLA, Region.VANILLA);

    FurnaceAppearance {
        input = input == null ? Region.VANILLA : input;
        fuel = fuel == null ? Region.VANILLA : fuel;
        result = result == null ? Region.VANILLA : result;
        cooking = cooking == null ? Region.VANILLA : cooking;
        burning = burning == null ? Region.VANILLA : burning;
    }

    record Region(double u, double v, double width, double height, double out,
            double itemU, double itemV, double itemScale, double clickWidth, double clickHeight) {
        static final Region VANILLA = new Region(0, 0, 1, 1, 0, 0, 0, 1, 1, 1);
        Region {
            u = finite(u, 0, -6, 6); v = finite(v, 0, -6, 6);
            width = finite(width, 1, 0.5, 1.5); height = finite(height, 1, 0.5, 1.5);
            out = finite(out, 0, -2, 2);
            itemU = finite(itemU, 0, -3, 3); itemV = finite(itemV, 0, -3, 3);
            itemScale = finite(itemScale, 1, 0.5, 1.5);
            clickWidth = finite(clickWidth, 1, 0.5, 1.5); clickHeight = finite(clickHeight, 1, 0.5, 1.5);
        }
        double value(int field) {
            return switch (field) {
                case 0 -> u; case 1 -> v; case 2 -> width * 100; case 3 -> height * 100;
                case 4 -> out; case 5 -> itemScale * 100; case 6 -> clickWidth * 100;
                case 7 -> clickHeight * 100; case 8 -> itemU; case 9 -> itemV;
                default -> throw new IllegalArgumentException("Furnace field " + field);
            };
        }
        Region with(int field, double value) {
            return new Region(field == 0 ? value : u, field == 1 ? value : v,
                    field == 2 ? value / 100 : width, field == 3 ? value / 100 : height, field == 4 ? value : out,
                    field == 8 ? value : itemU, field == 9 ? value : itemV, field == 5 ? value / 100 : itemScale,
                    field == 6 ? value / 100 : clickWidth, field == 7 ? value / 100 : clickHeight);
        }
        Region blend(Region target, double t) {
            return new Region(Mth.lerp(t, u, target.u), Mth.lerp(t, v, target.v), Mth.lerp(t, width, target.width),
                    Mth.lerp(t, height, target.height), Mth.lerp(t, out, target.out), Mth.lerp(t, itemU, target.itemU),
                    Mth.lerp(t, itemV, target.itemV), Mth.lerp(t, itemScale, target.itemScale),
                    Mth.lerp(t, clickWidth, target.clickWidth), Mth.lerp(t, clickHeight, target.clickHeight));
        }
    }

    private static double finite(double value, double fallback, double min, double max) {
        return Mth.clamp(Double.isFinite(value) ? value : fallback, min, max);
    }

    Region region(int index) {
        return switch (index) { case 0 -> input; case 1 -> fuel; case 2 -> result; case 3 -> cooking; case 4 -> burning;
            default -> throw new IllegalArgumentException("Furnace region " + index); };
    }
    FurnaceAppearance with(int index, Region value) {
        return new FurnaceAppearance(index == 0 ? value : input, index == 1 ? value : fuel, index == 2 ? value : result,
                index == 3 ? value : cooking, index == 4 ? value : burning);
    }
    FurnaceAppearance blend(FurnaceAppearance target, double t) {
        return new FurnaceAppearance(input.blend(target.input, t), fuel.blend(target.fuel, t), result.blend(target.result, t),
                cooking.blend(target.cooking, t), burning.blend(target.burning, t));
    }

    Face face(FurnaceLayout layout, int region) { return region < 3 ? layout.zone(region).face() : Face.FRONT; }
    double[] original(FurnaceLayout layout, int region) {
        return region < 3 ? layout.zone(region).highlight() : region == 3 ? layout.cookGauge : layout.burnGauge;
    }

    /** One affine transform for highlights, beds, click targets and the hand's contact plane. */
    private double[] transform(FurnaceLayout layout, int index) {
        double[] rect = original(layout, index);
        Region r = region(index);
        double cx = (rect[0] + rect[1]) / 2, cy = (rect[2] + rect[3]) / 2;
        double w = Math.min(r.width, 0.96 / (rect[1] - rect[0]));
        double h = Math.min(r.height, 0.96 / (rect[3] - rect[2]));
        double hw = (rect[1] - rect[0]) * w / 2, hh = (rect[3] - rect[2]) * h / 2;
        double u = Mth.clamp(cx + r.u / 16, 0.02 + hw, 0.98 - hw);
        double v = Mth.clamp(cy + r.v / 16, 0.02 + hh, 0.98 - hh);
        // Preserve every original coordinate at the vanilla profile, including a gauge close to the bottom edge.
        if (r.u == 0 && r.width == 1) u = cx;
        if (r.v == 0 && r.height == 1) v = cy;
        return new double[]{cx, cy, u, v, w, h};
    }

    double[] uv(FurnaceLayout layout, int index, double u, double v, boolean item) {
        if (region(index).equals(Region.VANILLA)) return new double[]{u, v};
        double[] t = transform(layout, index);
        Region r = region(index);
        return new double[]{t[2] + (u - t[0]) * t[4] + (item ? r.itemU / 16 : 0),
                t[3] + (v - t[1]) * t[5] + (item ? r.itemV / 16 : 0)};
    }

    double[] rect(FurnaceLayout layout, int index, boolean click) {
        double[] source = original(layout, index);
        if (!click && region(index).equals(Region.VANILLA)) return source;
        if (click && index < 3) {
            FurnaceLayout.Zone z = layout.zone(index);
            source = new double[]{z.u0(), z.u1(), z.v0(), z.v1()};
        }
        double[] low = uv(layout, index, source[0], source[2], false), high = uv(layout, index, source[1], source[3], false);
        if (click) {
            Region r = region(index);
            double cx = (low[0] + high[0]) / 2, cy = (low[1] + high[1]) / 2;
            double hw = (high[0] - low[0]) * r.clickWidth / 2, hh = (high[1] - low[1]) * r.clickHeight / 2;
            return new double[]{Mth.clamp(cx - hw, 0, 1), Mth.clamp(cx + hw, 0, 1),
                    Mth.clamp(cy - hh, 0, 1), Mth.clamp(cy + hh, 0, 1)};
        }
        return new double[]{low[0], high[0], low[1], high[1]};
    }

    Vec3 point(FurnaceLayout layout, BlockPos pos, Direction side, int index, double u, double v, double out, boolean item) {
        if (region(index).equals(Region.VANILLA)) return FurnaceZones.point(pos, side, face(layout, index), u, v, out);
        double[] at = uv(layout, index, u, v, item);
        return FurnaceZones.point(pos, side, face(layout, index), at[0], at[1], out + region(index).out / 16);
    }
    Vec3 center(FurnaceLayout layout, BlockPos pos, Direction side, int index) {
        double[] r = original(layout, index);
        return point(layout, pos, side, index, (r[0] + r[1]) / 2, (r[2] + r[3]) / 2, 0.004, false);
    }

    /** Shared cooking light is split before transforming, so moving input cannot spill light past output. */
    double[] glowRect(FurnaceLayout layout, FurnaceLayout.Glow glow, int index) {
        double u0 = glow.u0(), u1 = glow.u1(), v0 = glow.v0(), v1 = glow.v1();
        if (equals(VANILLA)) return new double[]{u0, u1, v0, v1};
        double[] bounds = original(layout, index);
        u0 = Math.max(u0, bounds[0]); u1 = Math.min(u1, bounds[1]);
        v0 = Math.max(v0, bounds[2]); v1 = Math.min(v1, bounds[3]);
        if (u0 >= u1 || v0 >= v1) return null;
        double[] low = uv(layout, index, u0, v0, false), high = uv(layout, index, u1, v1, false);
        return new double[]{low[0], high[0], low[1], high[1]};
    }
    static boolean contains(double[] r, double u, double v) { return u >= r[0] && u <= r[1] && v >= r[2] && v <= r[3]; }
}
