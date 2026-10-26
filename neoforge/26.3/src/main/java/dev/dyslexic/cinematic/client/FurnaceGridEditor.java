package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.furnace.FurnaceZones;
import dev.dyslexic.cinematic.furnace.FurnaceZones.Face;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

/** Edits only local display geometry. The same scene and real furnace keep running behind the panel. */
final class FurnaceGridEditor implements LayoutEditor {
    private static final int W = 180, H = 342, ROW_Y = 112, ROW_H = 24;
    private static final String[] FIELDS = {"offset_u", "offset_v", "width", "height", "out", "item_size",
            "click_width", "click_height", "item_u", "item_v"};
    private static final String[] REGIONS = {"input", "fuel", "result", "cooking", "burning"};
    private static final int[] BASIC = {0, 1, 2, 3, 4, 5}, ADVANCED = {6, 7, 8, 9}, GAUGE = {0, 1, 2, 3, 4};
    private final FurnaceStation station;
    private FurnaceAppearance[] drafts;
    private int profile, region, slider = -1;
    private boolean open, advanced, saveError;
    private double shown, x, y, toggleX, toggleY, tooltipAt;
    private float scale;
    private int tooltipField = -1;
    private Vec3 dragFrom;
    private FurnaceAppearance dragLayout;

    FurnaceGridEditor(FurnaceStation station) { this.station = station; layout(); }
    static Component text(String key, Object... args) { return Component.translatable("cinematic.furnace_editor." + key, args); }
    @Override public boolean editing() { return open; }
    @Override public boolean visible() { return open || shown > 0.01; }
    double amount() { return shown; }
    private int[] fields() { return region >= 3 ? GAUGE : advanced ? ADVANCED : BASIC; }
    private static boolean percent(int field) { return field == 2 || field == 3 || field == 5 || field == 6 || field == 7; }
    private static double min(int field) { return percent(field) ? 50 : field == 4 ? -2 : field >= 8 ? -3 : -6; }
    private static double max(int field) { return percent(field) ? 150 : -min(field); }
    private static double step(int field) { return percent(field) ? 1 : 0.125; }

    private void layout() {
        var window = Minecraft.getInstance().getWindow();
        int width = window.getGuiScaledWidth(), height = window.getGuiScaledHeight();
        scale = Math.min(1f, Math.min(height / 450f, width / 700f));
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
        drafts = FurnaceLayouts.profiles(station.kind()); profile = FurnaceLayouts.selected(station.kind());
        open = true; advanced = false; saveError = false;
        FurnaceLayouts.preview(station.kind(), drafts[profile]); station.clearCursor();
    }
    void cancel() { open = false; release(); FurnaceLayouts.cancelPreview(station.kind()); }
    private void save() { if (FurnaceLayouts.save(station.kind(), drafts, profile)) cancel(); else saveError = true; }
    private void change(int field, double value) {
        drafts[profile] = drafts[profile].with(region, drafts[profile].region(region).with(field, value));
        FurnaceLayouts.preview(station.kind(), drafts[profile]); saveError = false;
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
                FurnaceLayouts.preview(station.kind(), drafts[profile]);
            } else if (py >= 55 && py <= 75) {
                region = Math.floorMod(region + (px < W / 2.0 ? -1 : 1), 5);
                release();
            } else if (py >= 81 && py <= 99 && region < 3) advanced = !advanced;
            else if (py >= ROW_Y && py < ROW_Y + fields().length * ROW_H) {
                slider = fields()[(int) ((py - ROW_Y) / ROW_H)]; slide(mx);
            } else if (py >= 290 && py <= 306) {
                drafts[profile] = FurnaceAppearance.VANILLA;
                FurnaceLayouts.preview(station.kind(), drafts[profile]); saveError = false;
            } else if (py >= 314 && py <= 332) {
                if (px < W / 2.0) save(); else cancel();
            }
        } else {
            FurnaceStation.ZoneHit hit = station.zoneHit(true);
            if (hit != null) {
                region = hit.index(); dragFrom = hit.world(); dragLayout = drafts[profile];
            }
        }
        return true;
    }
    @Override public boolean drag(double mx, double my, int button) {
        if (!open) return false;
        if (button != 0) return true;
        if (slider >= 0) slide(mx);
        else if (dragFrom != null) {
            Face face = dragLayout.face(station.layout, region);
            Vec3 normal = face == Face.FRONT ? station.side() : new Vec3(0, 1, 0);
            Vec3 hit = Picking.cursorOnPlane(dragFrom, normal);
            if (hit != null) {
                Vec3 delta = hit.subtract(dragFrom);
                FurnaceAppearance.Region base = dragLayout.region(region);
                double u = base.u() + delta.dot(station.right()) * 16;
                double v = base.v() + (face == Face.FRONT ? delta.y : -delta.dot(station.side())) * 16;
                drafts[profile] = dragLayout.with(region, base.with(0, u).with(1, v));
                FurnaceLayouts.preview(station.kind(), drafts[profile]); saveError = false;
            }
        }
        return true;
    }
    @Override public void release() { slider = -1; dragFrom = null; }
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
        if (open && tooltipField >= 0 && Scene.clock() - tooltipAt > 0.55) return List.of(text(FIELDS[tooltipField] + ".tooltip"));
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
        g.text(font, text("title"), 10, 10, color(a, 242, 234, 216), false);
        selector(g, font, 29, TableGridEditor.text("profile", profile + 1), a);
        selector(g, font, 55, text(REGIONS[region]), a);
        button(g, font, 9, 81, W - 18, 18, text(region >= 3 ? "indicators" : advanced ? "basic" : "advanced"), a, advanced && region < 3);
        int[] fields = fields();
        for (int row = 0; row < fields.length; row++) {
            int field = fields[row], yy = ROW_Y + row * ROW_H;
            double value = drafts[profile].region(region).value(field);
            String number = percent(field) ? String.format(Locale.ROOT, "%.0f%%", value) : String.format(Locale.ROOT, "%+.2f px", value);
            g.text(font, text(FIELDS[field]), 10, yy, color(a * 0.9, 224, 227, 226), false);
            g.text(font, number, W - 10 - font.width(number), yy, color(a, 103, 211, 231), false);
            int end = 11 + (int) Math.round((W - 22) * (value - min(field)) / (max(field) - min(field)));
            g.fill(11, yy + 15, W - 11, yy + 17, color(a * 0.14, 255, 255, 255));
            g.fill(11, yy + 15, end, yy + 17, color(a * 0.75, 103, 211, 231));
            g.fill(end - 2, yy + 12, end + 2, yy + 20, color(a, 196, 240, 245));
        }
        g.text(font, text(saveError ? "save_error" : "drag_hint"), 10, 272,
                color(a * 0.75, saveError ? 255 : 181, saveError ? 122 : 195, saveError ? 112 : 199), false);
        button(g, font, 9, 290, W - 18, 16, text("reset"), a, false);
        button(g, font, 9, 314, 78, 18, TableGridEditor.text("save"), a, true);
        button(g, font, 93, 314, 78, 18, TableGridEditor.text("cancel"), a, false);
        g.pose().popMatrix();
        if (open) for (int i = 0; i < 5; i++) {
            double[] at = Picking.toGui(station.geometry().center(station.layout, station.pos, station.sideDirection(), i));
            if (at != null && !inPanel(at[0], at[1])) {
                int px = (int) at[0], py = (int) at[1];
                g.fill(px - 4, py - 4, px + 4, py + 5, color(a * 0.6, 12, 16, 19));
                g.centeredText(font, Integer.toString(i + 1), px, py - 3, color(a * (i == region ? 1 : 0.45), 196, 240, 245));
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
        FurnaceAppearance layout = station.geometry();
        int[] cyan = {103, 211, 231};
        for (int i = 0; i < 5; i++) {
            double[] r = layout.rect(station.layout, i, false);
            Face face = layout.face(station.layout, i);
            double out = layout.region(i).out() / 16 + 0.006;
            double t = Math.min(0.003, (r[3] - r[2]) / 3);
            int a = (int) (shown * (i == region ? 190 : 65));
            for (double[] edge : new double[][]{{r[0], r[1], r[2], r[2] + t}, {r[0], r[1], r[3] - t, r[3]},
                    {r[0], r[0] + t, r[2], r[3]}, {r[1] - t, r[1], r[2], r[3]}}) {
                FurnaceStation.quad(pose, buffers.getBuffer(Fx.HIGHLIGHT), camera, station.pos, station.sideDirection(), face, edge, out, cyan, cyan, a, false);
            }
            if (i == region && advanced && i < 3) {
                int[] gray = {180, 195, 199};
                FurnaceStation.quad(pose, buffers.getBuffer(Fx.HIGHLIGHT), camera, station.pos, station.sideDirection(), face,
                        layout.rect(station.layout, i, true), out + 0.001, gray, gray, (int) (shown * 18), false);
            }
        }
        for (var bed : station.layout.beds) if (ClientFurnaces.item(station.pos, bed.slot()).isEmpty()) {
            ItemStack demo = new ItemStack(bed.slot() == 1 ? Items.COAL : station.kind() == dev.dyslexic.cinematic.station.StationKind.SMOKER
                    ? bed.slot() == 0 ? Items.BEEF : Items.COOKED_BEEF : bed.slot() == 0 ? Items.RAW_IRON : Items.IRON_INGOT);
            Vec3 base = layout.point(station.layout, station.pos, station.sideDirection(), bed.slot(), (bed.u0() + bed.u1()) / 2,
                    bed.floor(), -bed.depth(), true);
            ItemPlacer.render(pose, buffers, camera, demo, base, station.sideDirection(), 91 + bed.slot(),
                    0.95f * (float) layout.region(bed.slot()).itemScale(), bed.tilt(), light, (float) shown * 0.45f);
        }
    }
    double[] togglePoint() { layout(); return new double[]{toggleX + 15 * scale, toggleY + 14 * scale}; }
    double[] controlPoint(double px, double py) { layout(); return new double[]{x + px * scale, y + py * scale}; }
    double[] sliderPoint(int row, double value) { int field = fields()[row]; return controlPoint(11 + (W - 22) * (value - min(field)) / (max(field) - min(field)), ROW_Y + row * ROW_H + 16); }
}
