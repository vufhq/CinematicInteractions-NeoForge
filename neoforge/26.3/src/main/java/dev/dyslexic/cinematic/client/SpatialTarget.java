package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Adapter shared by menu stations and the bookshelf, which deliberately has no container menu. */
interface SpatialTarget {
    default ClientStation layoutStation() { return (ClientStation) this; }
    SpatialEditor layoutEditor();
    default SpatialAppearance.Frame layoutFrame() {
        ClientStation station = layoutStation();
        return SpatialAppearance.Frame.of(station.kind(), station.pos, station.sideDirection(), net.minecraft.core.Direction.UP);
    }
    default ItemStack layoutItem(int slot) { return RemainingLayouts.item(layoutStation(), slot); }
    default SpatialAppearance geometry() { return SpatialLayouts.current(layoutStation().kind()); }
    default Vec3 layoutPoint(int slot) { return geometry().point(layoutFrame(), slot); }
    default Vec3 layoutNormal(int slot) {
        return RemainingLayouts.billboard(layoutStation().kind(), slot) ? Picking.camera().subtract(layoutPoint(slot)).normalize() : layoutFrame().normal();
    }
    default Vec3 layoutAxis(int slot, boolean across) {
        if (!RemainingLayouts.billboard(layoutStation().kind(), slot)) return geometry().axis(layoutFrame(), slot, across);
        Vec3 n = layoutNormal(slot), a = new Vec3(0, 1, 0).cross(n).normalize(), b = n.cross(a);
        double angle = Math.toRadians(geometry().rotation(slot));
        return across ? a.scale(Math.cos(angle)).add(b.scale(Math.sin(angle))) : b.scale(Math.cos(angle)).subtract(a.scale(Math.sin(angle)));
    }
    default List<Vec3> layoutSamples(List<Vec3> original) {
        if (geometry().equals(SpatialAppearance.VANILLA)) return original;
        List<Vec3> points = new ArrayList<>(original);
        for (int i = 0; i < SpatialAppearance.count(layoutStation().kind()); i++)
            points.add(layoutPoint(i).add(layoutFrame().normal().scale(0.04)));
        return points;
    }
    default int layoutPick() {
        if (layoutStation().kind() == StationKind.BREWING) return volumePick();
        int best = -1; double distance = Double.MAX_VALUE;
        for (int i = 0; i < SpatialAppearance.count(layoutStation().kind()); i++) {
            Vec3 hit = Picking.cursorOnPlane(layoutPoint(i), layoutNormal(i));
            double[] half = geometry().half(layoutFrame(), i);
            Vec3 rel = hit == null ? null : hit.subtract(layoutPoint(i));
            if (rel != null && Math.abs(rel.dot(layoutAxis(i, true))) <= half[0]
                    && Math.abs(rel.dot(layoutAxis(i, false))) <= half[1]) {
                double d = RemainingLayouts.supports(layoutStation().kind())
                        ? Math.pow(rel.dot(layoutAxis(i, true)) / half[0], 2) + Math.pow(rel.dot(layoutAxis(i, false)) / half[1], 2)
                                + hit.distanceToSqr(Picking.camera()) * 1e-7
                        : hit.distanceToSqr(Picking.camera());
                if (d < distance) { best = i; distance = d; }
            }
        }
        return best;
    }
    /** The bowl can be level with the eyes: a zero-thickness horizontal target is unstable there. */
    private int volumePick() {
        Vec3[] ray = Picking.cursorRay();
        if (ray == null) return -1;
        int best = -1; double nearest = Double.MAX_VALUE;
        var frame = layoutFrame(); var layout = geometry();
        for (int i = 0; i < 5; i++) {
            Vec3 origin = ray[0].subtract(layoutPoint(i));
            double[] half = layout.half(frame, i);
            double[] lengths = {half[0], half[1], (i < 3 ? BrewingStation.BOTTLE_TOUCH_HALF_HEIGHT : BrewingStation.LOOSE_TOUCH_HALF_HEIGHT) * layout.scale(i)};
            Vec3[] axes = {layout.axis(frame, i, true), layout.axis(frame, i, false), frame.normal()};
            double enter = 0, leave = Double.MAX_VALUE;
            for (int a = 0; a < 3; a++) {
                double p = origin.dot(axes[a]), d = ray[1].dot(axes[a]);
                if (Math.abs(d) < 1e-9) {
                    if (Math.abs(p) > lengths[a]) { leave = -1; break; }
                } else {
                    double t0 = (-lengths[a] - p) / d, t1 = (lengths[a] - p) / d;
                    enter = Math.max(enter, Math.min(t0, t1)); leave = Math.min(leave, Math.max(t0, t1));
                }
            }
            if (leave >= enter && enter < nearest) { nearest = enter; best = i; }
        }
        return best;
    }
    default float layoutHeldScale() { return RemainingLayouts.supports(layoutStation().kind()) ? geometry().scale(0) : 1f; }
    default void layoutGhost(PoseStack pose, SceneBuffers buffers, Vec3 camera, int slot, int light) {
        var kind = layoutStation().kind();
        if (RemainingLayouts.supports(kind)) { RemainingLayouts.ghost(this, pose, buffers, camera, slot, light); return; }
        ItemStack item = new ItemStack(kind == StationKind.CRAFTER ? Items.OAK_PLANKS
                : slot < 3 ? Items.GLASS_BOTTLE : slot == 3 ? Items.NETHER_WART : Items.BLAZE_POWDER);
        // Use the actual station renderer so a preview has the same size, rotation and contact as a real item.
        if (kind == StationKind.CRAFTER) {
            List<ItemStack> items = new ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
            items.set(slot, item);
            CrafterStation.drawGrid(pose, buffers, camera, layoutStation().pos, layoutFrame().grid(), layoutStation().sideDirection(), items, -1, light);
        } else if (slot < 3) {
            BrewingBottles.draw(pose, buffers, camera, camera, item, layoutPoint(slot), 1f, geometry().scale(slot),
                    BrewingStation.itemRotation(slot), light);
        } else {
            List<ItemStack> items = new ArrayList<>(java.util.Collections.nCopies(5, ItemStack.EMPTY));
            items.set(slot, item);
            BrewingStation.draw(pose, buffers, net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera(),
                    layoutStation().pos, layoutStation().sideDirection(), items, -1, -1, light);
        }
    }
}
