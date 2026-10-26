package dev.dyslexic.cinematic.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.Vec3;

/** Shared editor integration for menu stations whose ingredients lie on a horizontal tabletop. */
abstract class SurfaceStation<M extends AbstractContainerMenu> extends MenuStation<M> {
    final SurfaceEditor gridEditor;
    SurfaceStation(BlockPos pos, Direction side, Class<M> menuType, int inputs) {
        super(pos, side, menuType, inputs);
        gridEditor = new SurfaceEditor(this);
    }
    SurfaceAppearance geometry() { return SurfaceLayouts.current(kind()); }
    @Override protected Vec3 restPoint(int slot) { return geometry().point(kind(), center(), side, slot).add(right().scale(denyShake())); }
    float surfaceScale(int slot) { return (float) geometry().region(slot).itemScale(); }
    float surfaceRotation(int slot) { return (float) geometry().region(slot).rotation(); }
    Vec3 surfaceRight(int slot) { return geometry().axis(side, slot, true); }
    Vec3 surfaceOut(int slot) { return geometry().axis(side, slot, false); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public List<Vec3> workSamples() {
        if (geometry().equals(SurfaceAppearance.VANILLA)) return super.workSamples();
        List<Vec3> points = new ArrayList<>(super.workSamples());
        for (int i = 0; i < SurfaceAppearance.count(kind()); i++) points.add(restPoint(i).add(0, 0.04, 0));
        return points;
    }
    /** Pick the independently raised, rotated spot closest to the camera. */
    int surfaceSlot(boolean occupiedOnly) {
        int selected = -1;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < SurfaceAppearance.count(kind()); i++) {
            if (occupiedOnly && slot(i).isEmpty()) continue;
            Vec3 at = restPoint(i), hit = Picking.cursorOnPlane(at.y);
            if (hit != null && geometry().contains(kind(), center(), side, i, hit)) {
                double d = hit.distanceToSqr(Picking.camera());
                if (d < distance) { distance = d; selected = i; }
            }
        }
        return selected;
    }
    float heldItemScale(int target) { return target < 0 ? 1f : surfaceScale(target); }
    abstract float heldItemScale();
}
