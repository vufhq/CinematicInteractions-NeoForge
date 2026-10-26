package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

/** Edits only local display geometry. The same scene and real menu keep running behind the panel. */
final class SpatialEditor implements LayoutEditor {
    private static final int W = 180, H = 362, ROW_Y = 108, ROW_H = 22;
    private static final String[] FIELDS = {"offset_x", "offset_z", "rotation", "width", "depth", "height", "item_size"};
    private static final int[] FIELDS_INDEX = {0, 1, 2, 3, 4, 5, 6};
    private final SpatialTarget target;
    private final ClientStation station;
    private SpatialAppearance[] drafts;
    private int profile, region = -1, slider = -1;
    private boolean open, saveError;
    private double shown, x, y, toggleX, toggleY, tooltipAt;
    private float scale;
    private int tooltipField = -1;
    private Vec3 dragFrom, dragNormal;
    private SpatialAppearance dragLayout;

    SpatialEditor(SpatialTarget target) { this.target = target; this.station = target.layoutStation(); layout(); }
    static Component text(String key, Object... args) { return Component.translatable("cinematic.spatial_editor." + key, args); }
    @Override public boolean editing() { return open; }
    @Override public boolean visible() { return open || shown > 0.01; }
    float presence() { return (float) shown; }
    private int[] fields() { return FIELDS_INDEX; }
    private static boolean percent(int field) { return field == 3 || field == 4 || field == 6; }
    private static double min(int field) { return percent(field) ? 50 : field == 2 ? -180 : field == 5 ? -2 : -6; }
    private static double max(int field) { return percent(field) ? 150 : -min(field); }
    private static double step(int field) { return percent(field) || field == 2 ? 1 : 0.125; }
    private String fieldKey(int field) {
        if (station.kind() == StationKind.LECTERN && field == 1) return "along_book";
        if (Math.abs(target.layoutFrame().normal().y) > 0.5 && station.kind() != StationKind.BREWING) return switch (field) {
            case 1 -> "towards"; default -> FIELDS[field]; };
        if (station.kind() != StationKind.BREWING) return switch (field) {
            case 1 -> "offset_y"; case 4 -> "area_height"; case 5 -> "outward"; default -> FIELDS[field]; };
        return FIELDS[field];
    }
    private Component regionName() {
        if (region < 0) return text("all");
        return switch (station.kind()) {
            case CRAFTER -> text("cell", region / 3 + 1, region % 3 + 1);
            case CHISELED_BOOKSHELF -> text("book", region + 1);
            case LOOM -> text(region == 0 ? "cloth" : region == 1 ? "dye" : "pattern");
            case ENCHANTING -> region < 2 ? text(region == 0 ? "enchant_item" : "lapis") : text("offer", region - 1);
            case ANVIL -> text(region == 0 ? "workpiece" : region == 1 ? "addition" : "nameplate");
            case GRINDSTONE -> text(region < 2 ? "input" : "grinding", region + 1);
            case STONECUTTER -> text(region == 0 ? "stone" : region == 1 ? "cutting" : "shapes");
            case CAMPFIRE -> text("food", region + 1);
            case LECTERN -> text("reading_book");
            case BEACON -> region == 0 ? text("payment") : text("power", region);
            case JUKEBOX -> text("record_slot");
            default -> region < 3 ? text("bottle", region + 1) : text(region == 3 ? "ingredient" : "fuel");
        };
    }

    private void layout() {
        var window = Minecraft.getInstance().getWindow();
        int width = window.getGuiScaledWidth(), height = window.getGuiScaledHeight();
        scale = Math.min(1f, Math.min(height / 470f, width / 700f));
        x = width - 8 - W * scale + (1 - shown) * (W + 12) * scale;
        y = height * 0.14;
        toggleX = width - 8 - 30 * scale; toggleY = height * 0.45;
    }
    private double lx(double mx) { return (mx - x) / scale; }
    private double ly(double my) { return (my - y) / scale; }
    private boolean inPanel(double mx, double my) { return visible() && lx(mx) >= 0 && lx(mx) < W && ly(my) >= 0 && ly(my) < H; }
    private boolean toggle(double mx, double my) {
        return Settings.developerMode() && !visible() && mx >= toggleX && mx < toggleX + 30 * scale && my >= toggleY && my < toggleY + 28 * scale;
    }
    @Override public boolean over(double mx, double my) { return open || inPanel(mx, my) || toggle(mx, my); }

    void frame() {
        if (open && (!Settings.developerMode() || Scene.phase != Scene.Phase.ACTIVE)) cancel();
        shown = Anim.approach(shown, open ? 1 : 0, 16, Scene.frameDt);
        layout();
        double[] mouse = Picking.mouseGui();
        int row = (int) ((ly(mouse[1]) - ROW_Y) / ROW_H);
        int field = open && inPanel(mouse[0], mouse[1]) && ly(mouse[1]) >= ROW_Y && row < fields().length
                && (ly(mouse[1]) - ROW_Y) % ROW_H < 11 && slider < 0 ? fields()[row] : -1;
        if (field != tooltipField) { tooltipField = field; tooltipAt = Scene.clock(); }
    }
    void open() {
        if (!Settings.developerMode() || station.busy() || Scene.phase != Scene.Phase.ACTIVE) return;
        drafts = SpatialLayouts.profiles(station.kind()); profile = SpatialLayouts.selected(station.kind());
        open = true; saveError = false;
        SpatialLayouts.preview(station.kind(), drafts[profile]); station.clearCursor();
    }
    void cancel() { open = false; release(); SpatialLayouts.cancelPreview(station.kind()); }
    private void save() { if (SpatialLayouts.save(station.kind(), drafts, profile)) cancel(); else saveError = true; }
    private void change(int field, double value) {
        drafts[profile] = drafts[profile].with(region, drafts[profile].region(region).with(field, value));
        SpatialLayouts.preview(station.kind(), drafts[profile]); saveError = false;
    }
    private void slide(double mx) {
        double t = Mth.clamp((lx(mx) - 11) / (W - 22), 0, 1);
        change(slider, Math.round(Mth.lerp(t, min(slider), max(slider)) / step(slider)) * step(slider));
    }
    @Override public boolean click(double mx, double my, int button) {
        layout();
        if (toggle(mx, my)) { if (button == 0) open(); return true; }
        if (!open) return inPanel(mx, my);
        if (button != 0) return true;
        if (inPanel(mx, my)) {
            double px = lx(mx), py = ly(my);
            if (py >= 29 && py <= 48) {
                profile = Math.floorMod(profile + (px < W / 2.0 ? -1 : 1), 3);
                SpatialLayouts.preview(station.kind(), drafts[profile]);
            } else if (py >= 55 && py <= 75) {
                region = Math.floorMod(region + 1 + (px < W / 2.0 ? -1 : 1), SpatialAppearance.count(station.kind()) + 1) - 1;
                release();
            } else if (py >= ROW_Y && py < ROW_Y + fields().length * ROW_H) {
                slider = fields()[(int) ((py - ROW_Y) / ROW_H)]; slide(mx);
            } else if (py >= 306 && py <= 322) {
                drafts[profile] = SpatialAppearance.VANILLA;
                SpatialLayouts.preview(station.kind(), drafts[profile]); saveError = false;
            } else if (py >= 334 && py <= 352) {
                if (px < W / 2.0) save(); else cancel();
            }
        } else {
            int hit = target.layoutPick();
            if (hit >= 0) {
                if (region >= 0) region = hit;
                Vec3 at = target.layoutPoint(hit);
                // A bowl at eye level cannot be dragged reliably on a horizontal plane. Drag it in view space.
                dragNormal = station.kind() == StationKind.BREWING || station.kind() == StationKind.ENCHANTING || station.kind() == StationKind.BEACON
                        ? Picking.camera().subtract(at).normalize() : target.layoutNormal(hit);
                dragFrom = Picking.cursorOnPlane(at, dragNormal); dragLayout = drafts[profile];
            }
        }
        return true;
    }
    @Override public boolean drag(double mx, double my, int button) {
        if (!open) return false;
        if (button != 0) return true;
        if (slider >= 0) slide(mx);
        else if (dragFrom != null) {
            Vec3 hit = Picking.cursorOnPlane(dragFrom, dragNormal);
            if (hit != null) {
                Vec3 delta = hit.subtract(dragFrom);
                SurfaceAppearance.Region base = dragLayout.region(region);
                Vec3 across = region < 0 ? target.layoutFrame().across() : dragLayout.axis(target.layoutFrame(), -1, true);
                Vec3 up = region < 0 ? target.layoutFrame().up() : dragLayout.axis(target.layoutFrame(), -1, false);
                double u = base.x() + delta.dot(across) * 16;
                double v = base.z() + delta.dot(up) * 16;
                var moved = base.with(0, u).with(1, v);
                if (station.kind() == StationKind.BREWING || station.kind() == StationKind.ENCHANTING || station.kind() == StationKind.BEACON)
                    moved = moved.with(5, base.height() + delta.dot(target.layoutFrame().normal()) * 16);
                drafts[profile] = dragLayout.with(region, moved);
                SpatialLayouts.preview(station.kind(), drafts[profile]); saveError = false;
            }
        }
        return true;
    }
    @Override public void release() { slider = -1; dragFrom = null; dragNormal = null; }
    @Override public boolean scroll(double mx, double my, double amount) {
        if (!open) return false;
        int row = (int) ((ly(my) - ROW_Y) / ROW_H);
        if (inPanel(mx, my) && ly(my) >= ROW_Y && row < fields().length) {
            int field = fields()[row];
            change(field, drafts[profile].region(region).value(field) + Math.signum(amount) * step(field));
        }
        return true;
    }
    @Override public boolean keyPressed(int key) {
        if (open) {
            if (key == InputConstants.KEY_ESCAPE) cancel();
            else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) save();
            return true;
        }
        if (Settings.developerMode() && key == InputConstants.KEY_G) { open(); return true; }
        return false;
    }
    List<Component> tooltip(double mx, double my) {
        if (toggle(mx, my)) return List.of(text("open"), TableGridEditor.text("shortcut"));
        if (open && tooltipField >= 0 && Scene.clock() - tooltipAt > 0.55) return List.of(text(fieldKey(tooltipField) + ".tooltip"));
        return null;
    }
    void render(GuiGraphicsExtractor g, Font font, float alpha) {
        layout();
        if (!Settings.developerMode() && !visible()) return;
        double[] mouse = Picking.mouseGui();
        if (!visible()) {
            g.pose().pushMatrix(); g.pose().translate((float) (toggleX), (float) (toggleY)); g.pose().scale(scale, scale);
            glass(g, 30, 28, alpha);
            if (toggle(mouse[0], mouse[1])) g.fill(1, 1, 29, 27, color(alpha * 0.12, 255, 255, 255));
            EditorIcon.draw(g, alpha);
            g.pose().popMatrix(); return;
        }
        float a = alpha * (float) shown;
        if (a * 255 < 8 || drafts == null) return;
        g.pose().pushMatrix(); g.pose().translate((float) (x), (float) (y)); g.pose().scale(scale, scale);
        glass(g, W, H, a);
        g.text(font, text("title." + station.kind().name().toLowerCase(Locale.ROOT)), 10, 10, color(a, 242, 234, 216), false);
        selector(g, font, 29, TableGridEditor.text("profile", profile + 1), a);
        selector(g, font, 55, regionName(), a);
        g.text(font, text("placement"), 10, 87, color(a * 0.65, 181, 195, 199), false);
        int[] fields = fields();
        for (int row = 0; row < fields.length; row++) {
            int field = fields[row], yy = ROW_Y + row * ROW_H;
            double value = drafts[profile].region(region).value(field);
            String number = field == 2 ? String.format(Locale.ROOT, "%+.0f°", value) : percent(field) ? String.format(Locale.ROOT, "%.0f%%", value) : String.format(Locale.ROOT, "%+.2f px", value);
            g.text(font, text(fieldKey(field)), 10, yy, color(a * 0.9, 224, 227, 226), false);
            g.text(font, number, W - 10 - font.width(number), yy, color(a, 103, 211, 231), false);
            int end = 11 + (int) Math.round((W - 22) * (value - min(field)) / (max(field) - min(field)));
            g.fill(11, yy + 15, W - 11, yy + 17, color(a * 0.14, 255, 255, 255));
            g.fill(11, yy + 15, end, yy + 17, color(a * 0.75, 103, 211, 231));
            g.fill(end - 2, yy + 12, end + 2, yy + 20, color(a, 196, 240, 245));
        }
        g.text(font, text(saveError ? "save_error" : "drag_hint"), 10, 286,
                color(a * 0.75, saveError ? 255 : 181, saveError ? 122 : 195, saveError ? 112 : 199), false);
        button(g, font, 9, 306, W - 18, 16, text("reset"), a, false);
        button(g, font, 9, 334, 78, 18, TableGridEditor.text("save"), a, true);
        button(g, font, 93, 334, 78, 18, TableGridEditor.text("cancel"), a, false);
        g.pose().popMatrix();
        if (open) for (int i = 0; i < SpatialAppearance.count(station.kind()); i++) {
            double[] at = Picking.toGui(target.layoutPoint(i));
            if (at != null && !inPanel(at[0], at[1])) {
                int px = (int) at[0], py = (int) at[1];
                g.fill(px - 4, py - 4, px + 4, py + 5, color(a * 0.6, 12, 16, 19));
                g.centeredText(font, Integer.toString(i + 1), px, py - 3, color(a * (region < 0 || i == region ? 1 : 0.45), 196, 240, 245));
            }
        }
    }
    private void selector(GuiGraphicsExtractor g, Font font, int yy, Component label, float a) {
        button(g, font, 9, yy, W - 18, 19, label, a, false);
        g.text(font, "‹", 15, yy + 6, color(a, 103, 211, 231), false);
        g.text(font, "›", W - 20, yy + 6, color(a, 103, 211, 231), false);
    }
    private void button(GuiGraphicsExtractor g, Font font, int bx, int by, int w, int h, Component label, float a, boolean accent) {
        double[] mouse = Picking.mouseGui();
        boolean hover = lx(mouse[0]) >= bx && lx(mouse[0]) < bx + w && ly(mouse[1]) >= by && ly(mouse[1]) < by + h;
        g.fill(bx, by, bx + w, by + h, color(a * (accent ? hover ? 0.3 : 0.2 : hover ? 0.14 : 0.07), 103, 211, 231));
        g.text(font, label, bx + (w - font.width(label)) / 2, by + (h - 8) / 2, color(a, 224, 238, 238), false);
    }
    private static void glass(GuiGraphicsExtractor g, int w, int h, float a) {
        g.fill(0, 0, w, h, color(a * 0.85, 12, 16, 19));
        g.fill(0, 0, w, 1, color(a * 0.55, 103, 211, 231));
        g.fill(0, h - 1, w, h, color(a * 0.25, 103, 211, 231));
        g.fill(0, 1, 1, h - 1, color(a * 0.18, 103, 211, 231));
        g.fill(w - 1, 1, w, h - 1, color(a * 0.18, 103, 211, 231));
    }
    private static int color(double a, int r, int g, int b) { return Mth.clamp((int) Math.round(a * 255), 0, 255) << 24 | r << 16 | g << 8 | b; }

    void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        if (!visible()) return;
        var frame = target.layoutFrame();
        for (int i = 0; i < SpatialAppearance.count(station.kind()); i++) {
            double[] half = target.geometry().half(frame, i);
            Vec3 at = target.layoutPoint(i).add(target.layoutNormal(i).scale(0.006));
            Vec3 across = target.layoutAxis(i, true), up = target.layoutAxis(i, false);
            double edge = 0.003;
            int alpha = (int) (shown * (region < 0 || i == region ? 190 : 65));
            patch(pose, buffers, camera, at, across, up, -half[0], half[0], -half[1], -half[1] + edge, alpha);
            patch(pose, buffers, camera, at, across, up, -half[0], half[0], half[1] - edge, half[1], alpha);
            patch(pose, buffers, camera, at, across, up, -half[0], -half[0] + edge, -half[1], half[1], alpha);
            patch(pose, buffers, camera, at, across, up, half[0] - edge, half[0], -half[1], half[1], alpha);
            if (target.layoutItem(i).isEmpty()) {
                // Additive sigils carry black texels, which must remain additive in the preview too.
                SceneBuffers ghost = station.kind() == StationKind.ENCHANTING && i >= 2
                        ? buffers : ItemPlacer.opacity(buffers, (float) shown * 0.4f);
                target.layoutGhost(pose, ghost, camera, i, light);
            }
        }
    }
    private static void patch(PoseStack pose, SceneBuffers buffers, Vec3 camera, Vec3 at, Vec3 across, Vec3 out,
            double x0, double x1, double z0, double z1, int alpha) {
        var vertex = buffers.getBuffer(Fx.HIGHLIGHT);
        var matrix = pose.last().pose();
        for (double[] p : new double[][]{{x0,z0},{x1,z0},{x1,z1},{x0,z1}}) {
            Vec3 v = at.add(across.scale(p[0])).add(out.scale(p[1])).subtract(camera);
            vertex.addVertex(matrix, (float) v.x, (float) v.y, (float) v.z).setColor(103, 211, 231, alpha);
        }
    }
    double[] togglePoint() { layout(); return new double[]{toggleX + 15 * scale, toggleY + 14 * scale}; }
    double[] controlPoint(double px, double py) { layout(); return new double[]{x + px * scale, y + py * scale}; }
    double[] sliderPoint(int row, double value) { int field = fields()[row]; return controlPoint(11 + (W - 22) * (value - min(field)) / (max(field) - min(field)), ROW_Y + row * ROW_H + 16); }
}
