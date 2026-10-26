package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.station.StationKind;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Independent spots on a tabletop, in the working player's right/towards axes. Offsets are texture pixels. */
record SurfaceAppearance(SurfaceAppearance.Region first, SurfaceAppearance.Region second, SurfaceAppearance.Region third) {
    static final SurfaceAppearance VANILLA = new SurfaceAppearance(Region.VANILLA, Region.VANILLA, Region.VANILLA);
    SurfaceAppearance {
        first = first == null ? Region.VANILLA : first;
        second = second == null ? Region.VANILLA : second;
        third = third == null ? Region.VANILLA : third;
    }
    record Region(double x, double z, double rotation, double width, double depth, double height, double itemScale) {
        static final Region VANILLA = new Region(0, 0, 0, 1, 1, 0, 1);
        Region {
            x = finite(x, 0, -6, 6); z = finite(z, 0, -6, 6); rotation = finite(rotation, 0, -180, 180);
            width = finite(width, 1, 0.5, 1.5); depth = finite(depth, 1, 0.5, 1.5);
            height = finite(height, 0, -2, 2); itemScale = finite(itemScale, 1, 0.5, 1.5);
        }
        double value(int field) {
            return switch (field) { case 0 -> x; case 1 -> z; case 2 -> rotation; case 3 -> width * 100;
                case 4 -> depth * 100; case 5 -> height; case 6 -> itemScale * 100;
                default -> throw new IllegalArgumentException("Surface field " + field); };
        }
        Region with(int field, double value) {
            return new Region(field == 0 ? value : x, field == 1 ? value : z, field == 2 ? value : rotation,
                    field == 3 ? value / 100 : width, field == 4 ? value / 100 : depth,
                    field == 5 ? value : height, field == 6 ? value / 100 : itemScale);
        }
        Region blend(Region target, double t) {
            return new Region(Mth.lerp(t, x, target.x), Mth.lerp(t, z, target.z), Mth.lerp(t, rotation, target.rotation),
                    Mth.lerp(t, width, target.width), Mth.lerp(t, depth, target.depth), Mth.lerp(t, height, target.height), Mth.lerp(t, itemScale, target.itemScale));
        }
    }
    private static double finite(double value, double fallback, double min, double max) { return Mth.clamp(Double.isFinite(value) ? value : fallback, min, max); }
    Region region(int slot) {
        return switch (slot) { case 0 -> first; case 1 -> second; case 2 -> third;
            default -> throw new IllegalArgumentException("Surface slot " + slot); };
    }
    SurfaceAppearance with(int slot, Region value) { return new SurfaceAppearance(slot == 0 ? value : first, slot == 1 ? value : second, slot == 2 ? value : third); }
    SurfaceAppearance blend(SurfaceAppearance target, double t) { return new SurfaceAppearance(first.blend(target.first, t), second.blend(target.second, t), third.blend(target.third, t)); }
    static int count(StationKind kind) { return kind == StationKind.SMITHING ? 3 : 2; }
    static double baseX(StationKind kind, int slot) { return (kind == StationKind.SMITHING ? SmithingStation.LAYOUT : CartographyStation.LAYOUT)[slot][0]; }
    static double baseZ(StationKind kind, int slot) { return (kind == StationKind.SMITHING ? SmithingStation.LAYOUT : CartographyStation.LAYOUT)[slot][1]; }
    static double radius(StationKind kind, int slot) {
        return kind == StationKind.SMITHING ? SmithingStation.SIZES[slot] * 0.55 : slot == 0 ? 0.28 : 0.132;
    }
    /** The whole rotated click rectangle stays on the tabletop, even with a large map. */
    Vec3 point(StationKind kind, Vec3 top, Direction side, int slot) {
        Region r = region(slot);
        Vec3 out = Vec3.atLowerCornerOf(side.getUnitVec3i()), right = Vec3.atLowerCornerOf(side.getOpposite().getClockWise().getUnitVec3i());
        if (r.equals(Region.VANILLA)) return top.add(right.scale(baseX(kind, slot))).add(out.scale(baseZ(kind, slot)));
        double a = Math.toRadians(r.rotation), c = Math.abs(Math.cos(a)), s = Math.abs(Math.sin(a));
        double[] half = halfExtents(kind, slot);
        double extentX = half[0] * c + half[1] * s, extentZ = half[0] * s + half[1] * c;
        double x = Mth.clamp(baseX(kind, slot) + r.x / 16, -Math.max(0, 0.49 - extentX), Math.max(0, 0.49 - extentX));
        double z = Mth.clamp(baseZ(kind, slot) + r.z / 16, -Math.max(0, 0.49 - extentZ), Math.max(0, 0.49 - extentZ));
        return top.add(right.scale(x)).add(out.scale(z)).add(0, r.height / 16, 0);
    }
    Vec3 axis(Direction side, int slot, boolean across) {
        Vec3 basis = Vec3.atLowerCornerOf((across ? side.getOpposite().getClockWise() : side).getUnitVec3i());
        return basis.yRot((float) Math.toRadians(region(slot).rotation));
    }
    double[] halfExtents(StationKind kind, int slot) {
        Region r = region(slot);
        double x = radius(kind, slot) * r.width, z = radius(kind, slot) * r.depth;
        double a = Math.toRadians(r.rotation), c = Math.abs(Math.cos(a)), s = Math.abs(Math.sin(a));
        double fit = Math.min(1, 0.48 / Math.max(x * c + z * s, x * s + z * c));
        return new double[]{x * fit, z * fit};
    }
    boolean contains(StationKind kind, Vec3 top, Direction side, int slot, Vec3 hit) {
        Vec3 delta = hit.subtract(point(kind, top, side, slot));
        double[] half = halfExtents(kind, slot);
        return Math.abs(delta.dot(axis(side, slot, true))) <= half[0]
                && Math.abs(delta.dot(axis(side, slot, false))) <= half[1];
    }
}
