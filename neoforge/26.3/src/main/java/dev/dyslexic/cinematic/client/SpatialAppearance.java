package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** A transform of the whole layout, followed by independent transforms of its slots, in surface pixels. */
record SpatialAppearance(SurfaceAppearance.Region group, List<SurfaceAppearance.Region> slots) {
    private static final double FLOATING_BOUND = 0.8;
    static final SpatialAppearance VANILLA = new SpatialAppearance(SurfaceAppearance.Region.VANILLA,
            Collections.nCopies(9, SurfaceAppearance.Region.VANILLA));
    SpatialAppearance {
        if (group == null) group = SurfaceAppearance.Region.VANILLA;
        List<SurfaceAppearance.Region> safe = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) safe.add(slots != null && i < slots.size() && slots.get(i) != null
                ? slots.get(i) : SurfaceAppearance.Region.VANILLA);
        slots = List.copyOf(safe);
    }
    SurfaceAppearance.Region region(int slot) { return slot < 0 ? group : slots.get(slot); }
    SpatialAppearance with(int slot, SurfaceAppearance.Region value) {
        if (slot < 0) return new SpatialAppearance(value, slots);
        List<SurfaceAppearance.Region> next = new ArrayList<>(slots); next.set(slot, value);
        return new SpatialAppearance(group, next);
    }
    SpatialAppearance blend(SpatialAppearance other, double k) {
        List<SurfaceAppearance.Region> next = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) next.add(region(i).blend(other.region(i), k));
        return new SpatialAppearance(group.blend(other.group, k), next);
    }
    static int count(StationKind kind) {
        return switch (kind) { case CRAFTER -> 9; case CHISELED_BOOKSHELF -> 6; case BREWING, ENCHANTING -> 5; case LOOM, ANVIL, GRINDSTONE, STONECUTTER -> 3; case CAMPFIRE -> 4;
            case LECTERN, JUKEBOX -> 1; case BEACON -> 8;
            default -> throw new IllegalArgumentException("Not a spatial layout: " + kind); };
    }

    /** Up is the second surface axis; on a horizontal work surface it points away from the player. */
    record Frame(StationKind kind, BlockPos pos, Direction side, Direction grid, Vec3 origin, Vec3 across, Vec3 up, Vec3 normal) {
        static Frame of(StationKind kind, BlockPos pos, Direction side, Direction grid) {
            Vec3 out = Vec3.atLowerCornerOf(side.getUnitVec3i()), right = ShelfBooks.right(side);
            return switch (kind) {
                case CRAFTER -> {
                    Vec3 normal = Vec3.atLowerCornerOf(grid.getUnitVec3i());
                    yield new Frame(kind, pos, side, grid, Vec3.atCenterOf(pos).add(normal.scale(0.5)), right,
                            grid == Direction.UP ? out.scale(-1) : new Vec3(0, 1, 0), normal);
                }
                case CHISELED_BOOKSHELF -> new Frame(kind, pos, side, side, Vec3.atCenterOf(pos).add(out.scale(0.5)), right,
                        new Vec3(0, 1, 0), out);
                case BREWING -> new Frame(kind, pos, side, Direction.UP, Vec3.atBottomCenterOf(pos).add(0, 0.45, 0),
                        new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 1, 0));
                case LOOM, ENCHANTING -> new Frame(kind, pos, side, Direction.UP,
                        Vec3.atBottomCenterOf(pos).add(0, kind == StationKind.LOOM ? 1 : 0.75, 0),
                        right, out, new Vec3(0, 1, 0));
                default -> RemainingLayouts.frame(kind, pos, side);
            };
        }
        Vec3 base(int slot) {
            if (RemainingLayouts.supports(kind)) return RemainingLayouts.base(this, slot);
            if (kind == StationKind.LOOM) return origin.add(across.scale(LoomStation.CORNERS[slot][0]))
                    .add(up.scale(LoomStation.CORNERS[slot][1]));
            if (kind == StationKind.ENCHANTING) {
                if (slot == 0) return EnchantFx.itemCenter(pos, side, 0);
                if (slot == 1) return EnchantFx.lapisCorner(pos, side);
                int off = slot - 3;
                return origin.add(0, off == 0 ? EnchantStation.OFFER_HIGH : EnchantStation.OFFER_LOW, 0)
                        .add(up.scale(off == 0 ? EnchantStation.OFFER_FAR : EnchantStation.OFFER_NEAR))
                        .add(across.scale(off * EnchantStation.OFFER_SPREAD));
            }
            if (kind == StationKind.CRAFTER) return origin.add(across.scale((slot % 3 - 1) * CrafterStation.PITCH))
                    .add(up.scale((1 - slot / 3) * CrafterStation.PITCH));
            if (kind == StationKind.CHISELED_BOOKSHELF) {
                int[] r = ShelfBooks.SPINES[slot];
                return origin.add(across.scale((r[0] + r[2]) / 32.0 - 0.5))
                        .add(up.scale(0.5 - (r[1] + r[3]) / 32.0)).add(normal.scale(ShelfBooks.PROUD));
            }
            if (slot < 3) return new Vec3(pos.getX() + BrewingStation.FEET[slot][0] / 16,
                    pos.getY() + BrewingStation.BELLY, pos.getZ() + BrewingStation.FEET[slot][1] / 16);
            return slot == 3 ? Vec3.atBottomCenterOf(pos).add(0, 14 / 16.0 + 0.01, 0)
                    : Vec3.atBottomCenterOf(pos).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.1)).add(0, 0.16, 0);
        }
    }
    Vec3 axis(Frame frame, int slot, boolean across) {
        double angle = Math.toRadians(group.rotation() + (slot < 0 ? 0 : region(slot).rotation()));
        return across ? frame.across.scale(Math.cos(angle)).add(frame.up.scale(Math.sin(angle)))
                : frame.up.scale(Math.cos(angle)).subtract(frame.across.scale(Math.sin(angle)));
    }
    double rotation(int slot) { return group.rotation() + region(slot).rotation(); }
    float scale(int slot) { return (float) (group.itemScale() * region(slot).itemScale()); }
    double[] half(Frame frame, int slot) {
        if (RemainingLayouts.supports(frame.kind)) {
            double[] half = RemainingLayouts.half(frame.kind, slot);
            return new double[]{half[0] * group.width() * region(slot).width(), half[1] * group.depth() * region(slot).depth()};
        }
        if (frame.kind == StationKind.LOOM || frame.kind == StationKind.ENCHANTING) {
            double u = frame.kind == StationKind.LOOM ? slot == 0 ? LoomStation.CLOTH_WIDTH / 2 : 0.1
                    : slot == 1 ? EnchantFx.LAPIS_RADIUS : 0.15;
            double v = frame.kind == StationKind.LOOM && slot == 0 ? LoomStation.CLOTH_LENGTH / 2 : u;
            return new double[]{u * group.width() * region(slot).width(), v * group.depth() * region(slot).depth()};
        }
        double u = frame.kind == StationKind.CRAFTER ? CrafterStation.PITCH / 2 : frame.kind == StationKind.BREWING ? 0.1
                : (slot % 3 == 0 ? 6 : 5) / 32.0;
        double v = frame.kind == StationKind.CRAFTER ? CrafterStation.PITCH / 2 : frame.kind == StationKind.BREWING ? 0.1 : 0.25;
        u *= group.width() * region(slot).width(); v *= group.depth() * region(slot).depth();
        double angle = Math.toRadians(rotation(slot)), c = Math.abs(Math.cos(angle)), s = Math.abs(Math.sin(angle));
        double fit = Math.min(1, 0.48 / Math.max(u * c + v * s, u * s + v * c));
        return new double[]{u * fit, v * fit};
    }
    Vec3 point(Frame frame, int slot) {
        if (group.equals(SurfaceAppearance.Region.VANILLA) && region(slot).equals(SurfaceAppearance.Region.VANILLA)) return frame.base(slot);
        Vec3 rel = frame.base(slot).subtract(frame.origin);
        var r = region(slot);
        Vec3 moved = axis(frame, -1, true).scale(rel.dot(frame.across) * group.width() + r.x() / 16)
                .add(axis(frame, -1, false).scale(rel.dot(frame.up) * group.depth() + r.z() / 16))
                .add(frame.across.scale(group.x() / 16)).add(frame.up.scale(group.z() / 16));
        // The full click rectangle stays on the face, even when rotated or enlarged.
        double[] half = half(frame, slot);
        if (frame.kind == StationKind.CHISELED_BOOKSHELF) {
            int[] spine = ShelfBooks.SPINES[slot];
            half = new double[]{(spine[2] - spine[0]) / 32.0 * scale(slot), (spine[3] - spine[1]) / 32.0 * scale(slot)};
        }
        Vec3 a = axis(frame, slot, true), b = axis(frame, slot, false);
        double hu = Math.min(0.48, Math.abs(a.dot(frame.across)) * half[0] + Math.abs(b.dot(frame.across)) * half[1]);
        double hv = Math.min(0.48, Math.abs(a.dot(frame.up)) * half[0] + Math.abs(b.dot(frame.up)) * half[1]);
        // Floating enchanting targets intentionally extend beyond the tabletop, clearing the vanilla book.
        double bound = RemainingLayouts.supports(frame.kind) ? 1.2 : frame.kind == StationKind.ENCHANTING ? FLOATING_BOUND : 0.49;
        hu = Math.min(bound - 0.01, hu); hv = Math.min(bound - 0.01, hv);
        double u = Mth.clamp(moved.dot(frame.across), -bound + hu, bound - hu);
        double v = Mth.clamp(moved.dot(frame.up), -bound + hv, bound - hv);
        double n = rel.dot(frame.normal) + (group.height() + r.height()) / 16;
        if (frame.kind == StationKind.BREWING && slot < 3) n += 7 / 16.0 * BrewingBottles.SIZE * (scale(slot) - 1);
        n = frame.kind == StationKind.BREWING ? Mth.clamp(n, 0.12 - 0.45, 1.125 - 0.45)
                : frame.kind == StationKind.LOOM || RemainingLayouts.supports(frame.kind) ? n
                : Math.max(frame.kind == StationKind.CHISELED_BOOKSHELF ? ShelfBooks.PROUD : 0, n);
        return frame.origin.add(frame.across.scale(u)).add(frame.up.scale(v)).add(frame.normal.scale(n));
    }
    boolean contains(Frame frame, int slot, Vec3 hit) {
        Vec3 rel = hit.subtract(point(frame, slot)); double[] half = half(frame, slot);
        return Math.abs(rel.dot(axis(frame, slot, true))) <= half[0] && Math.abs(rel.dot(axis(frame, slot, false))) <= half[1];
    }
}
