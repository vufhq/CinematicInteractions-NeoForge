package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dyslexic.cinematic.table.TableGrid;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.InputConstants;

/** An in-scene glass panel. Editing consumes all input so a drag can never move actual ingredients. */
final class TableGridEditor implements LayoutEditor {
    private static final int W = 164, H = 290, ROW_Y = 64, ROW_H = 25;
    private static final String[] FIELDS = {"offset_x", "offset_z", "rotation", "width", "depth", "height", "item_size"};
    private static final double[] MIN = {-6, -6, -180, 50, 50, -2, 50};
    private static final double[] MAX = {6, 6, 180, 170, 170, 2, 150};
    private final TableStation station;
    private TableLayout[] drafts;
    private int profile;
    private boolean open, saveError;
    private double shown;
    private int slider = -1;
    private Vec3 dragFrom;
    private TableLayout dragLayout;
    private int tooltipField = -1;
    private double tooltipAt;
    private float scale;
    private double x, y, toggleX, toggleY;

    TableGridEditor(TableStation station) { this.station = station; layout(); }
    static Component text(String key, Object... args) { return Component.translatable("cinematic.grid_editor." + key, args); }
    public boolean editing() { return open; }
    public boolean visible() { return open || shown > 0.01; }

    private void layout() {
        var window = Minecraft.getInstance().getWindow();
        int width = window.getGuiScaledWidth(), height = window.getGuiScaledHeight();
        scale = Math.min(1f, Math.min(height / 380f, width / 650f));
        x = width - 8 - W * scale + (1 - shown) * (W + 12) * scale;
        y = height * 0.14;
        toggleX = width - 8 - 30 * scale;
        toggleY = height * 0.45;
    }

    void frame() {
        if (open && (!Settings.developerMode() || Scene.phase != Scene.Phase.ACTIVE)) cancel();
        shown = Anim.approach(shown, open ? 1 : 0, 16, Scene.frameDt);
        layout();
        double[] mouse = Picking.mouseGui();
        int field = open && inPanel(mouse[0], mouse[1]) && ly(mouse[1]) >= ROW_Y && ly(mouse[1]) < ROW_Y + 7 * ROW_H
                && (ly(mouse[1]) - ROW_Y) % ROW_H < 11 && slider < 0 ? (int) ((ly(mouse[1]) - ROW_Y) / ROW_H) : -1;
        if (field != tooltipField) { tooltipField = field; tooltipAt = Scene.clock(); }
    }

    private double lx(double mx) { return (mx - x) / scale; }
    private double ly(double my) { return (my - y) / scale; }
    private boolean inPanel(double mx, double my) { return visible() && lx(mx) >= 0 && lx(mx) < W && ly(my) >= 0 && ly(my) < H; }
    private boolean toggle(double mx, double my) {
        return Settings.developerMode() && !visible() && mx >= toggleX && mx < toggleX + 30 * scale && my >= toggleY && my < toggleY + 28 * scale;
    }
    public boolean over(double mx, double my) { return open || inPanel(mx, my) || toggle(mx, my); }

    void open() {
        if (!Settings.developerMode() || station.busy() || Scene.phase != Scene.Phase.ACTIVE) return;
        station.release(1);
        station.recipeBook.fold();
        drafts = TableLayouts.profiles();
        profile = TableLayouts.selected();
        open = true;
        saveError = false;
        TableLayouts.preview(drafts[profile]);
        station.clearCursor();
    }

    void cancel() {
        open = false;
        slider = -1;
        dragFrom = null;
        TableLayouts.cancelPreview();
    }

    private void save() {
        if (TableLayouts.save(drafts, profile)) cancel();
        else saveError = true;
    }

    private void change(int field, double value) {
        drafts[profile] = drafts[profile].with(field, value);
        TableLayouts.preview(drafts[profile]);
        saveError = false;
    }

    private void slide(double mx) {
        double t = Mth.clamp((lx(mx) - 11) / (W - 22), 0, 1);
        double step = slider == 0 || slider == 1 || slider == 5 ? 0.125 : 1;
        change(slider, Math.round(Mth.lerp(t, MIN[slider], MAX[slider]) / step) * step);
    }

    public boolean click(double mx, double my, int button) {
        layout();
        if (toggle(mx, my)) { if (button == 0) open(); return true; }
        if (!open) return inPanel(mx, my);
        if (button != 0) return true;
        if (inPanel(mx, my)) {
            double px = lx(mx), py = ly(my);
            if (py >= 29 && py <= 48) {
                profile = Math.floorMod(profile + (px < W / 2.0 ? -1 : 1), 3);
                TableLayouts.preview(drafts[profile]);
            } else if (py >= ROW_Y && py < ROW_Y + ROW_H * 7) {
                slider = (int) ((py - ROW_Y) / ROW_H);
                slide(mx);
            } else if (py >= 241 && py <= 257) {
                drafts[profile] = TableLayout.VANILLA;
                TableLayouts.preview(drafts[profile]);
            } else if (py >= 264 && py <= 282) {
                if (px < W / 2.0) save(); else cancel();
            }
        } else {
            Vec3 hit = Picking.cursorOnPlane(TableLayouts.current().surfaceY(station.pos));
            if (hit != null && TableLayouts.current().cellAt(station.pos, hit) >= 0) {
                dragFrom = hit;
                dragLayout = drafts[profile];
            }
        }
        return true;
    }

    public boolean drag(double mx, double my, int button) {
        if (!open) return false;
        if (button != 0) return true;
        if (slider >= 0) slide(mx);
        else if (dragFrom != null) {
            Vec3 hit = Picking.cursorOnPlane(dragLayout.surfaceY(station.pos));
            if (hit != null) {
                Vec3 delta = hit.subtract(dragFrom).scale(16);
                drafts[profile] = new TableLayout(dragLayout.x() + delta.x, dragLayout.z() + delta.z,
                        dragLayout.rotation(), dragLayout.width(), dragLayout.depth(), dragLayout.height(), dragLayout.itemScale());
                TableLayouts.preview(drafts[profile]);
            }
        }
        return true;
    }

    public void release() { slider = -1; dragFrom = null; }

    public boolean scroll(double mx, double my, double amount) {
        if (!open) return false;
        if (inPanel(mx, my)) {
            int field = (int) ((ly(my) - ROW_Y) / ROW_H);
            if (ly(my) >= ROW_Y && field >= 0 && field < 7) {
                change(field, drafts[profile].value(field) + Math.signum(amount) * (field < 2 || field == 5 ? 0.125 : 1));
            }
        }
        return true;
    }

    public boolean keyPressed(int key) {
        if (open) {
            if (key == InputConstants.KEY_ESCAPE) cancel();
            else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) save();
            return true;
        }
        if (Settings.developerMode() && key == InputConstants.KEY_G) { open(); return true; }
        return false;
    }

    List<Component> tooltip(double mx, double my) {
        if (toggle(mx, my)) return List.of(text("open"), text("shortcut"));
        if (open && tooltipField >= 0 && Scene.clock() - tooltipAt > 0.55)
            return List.of(text(FIELDS[tooltipField] + ".tooltip"));
        return null;
    }

    void render(GuiGraphicsExtractor g, Font font, float alpha) {
        layout();
        if (!Settings.developerMode() && !visible()) return;
        double[] mouse = Picking.mouseGui();
        if (!visible()) {
            g.pose().pushMatrix();
            g.pose().translate((float) (toggleX), (float) (toggleY));
            g.pose().scale(scale, scale);
            glass(g, 30, 28, alpha);
            if (toggle(mouse[0], mouse[1])) g.fill(1, 1, 29, 27, color(alpha * 0.12, 255, 255, 255));
            EditorIcon.draw(g, alpha);
            g.pose().popMatrix();
            return;
        }
        float a = alpha * (float) shown;
        if (a * 255 < 8 || drafts == null) return;
        g.pose().pushMatrix();
        g.pose().translate((float) (x), (float) (y));
        g.pose().scale(scale, scale);
        glass(g, W, H, a);
        g.text(font, text("title"), 10, 10, color(a, 242, 234, 216), false);
        button(g, font, 9, 29, W - 18, 19, text("profile", profile + 1), a, false);
        g.text(font, "‹", 15, 35, color(a, 103, 211, 231), false);
        g.text(font, "›", W - 20, 35, color(a, 103, 211, 231), false);
        g.text(font, text(saveError ? "save_error" : "drag_hint"), 10, 53, color(a * 0.75, saveError ? 255 : 181, saveError ? 122 : 195, saveError ? 112 : 199), false);
        for (int i = 0; i < 7; i++) {
            int yy = ROW_Y + i * ROW_H;
            double value = drafts[profile].value(i);
            String number = i == 2 ? String.format(Locale.ROOT, "%.0f°", value)
                    : i == 3 || i == 4 || i == 6 ? String.format(Locale.ROOT, "%.0f%%", value)
                    : String.format(Locale.ROOT, "%+.2f px", value);
            g.text(font, text(FIELDS[i]), 10, yy, color(a * 0.9, 224, 227, 226), false);
            g.text(font, number, W - 10 - font.width(number), yy, color(a, 103, 211, 231), false);
            int end = 11 + (int) Math.round((W - 22) * (value - MIN[i]) / (MAX[i] - MIN[i]));
            g.fill(11, yy + 15, W - 11, yy + 17, color(a * 0.14, 255, 255, 255));
            g.fill(11, yy + 15, end, yy + 17, color(a * 0.75, 103, 211, 231));
            g.fill(end - 2, yy + 12, end + 2, yy + 20, color(a, 196, 240, 245));
        }
        button(g, font, 9, 241, W - 18, 16, text("reset"), a, false);
        button(g, font, 9, 264, 70, 18, text("save"), a, true);
        button(g, font, 85, 264, 70, 18, text("cancel"), a, false);
        g.pose().popMatrix();
        if (open) {
            for (int logical = 0; logical < 9; logical++) {
                int world = TableGrid.worldIndex(station.sideDirection(), logical / 3, logical % 3);
                double[] at = Picking.toGui(TableLayouts.current().cell(station.pos, world).add(0, 0.025, 0));
                if (at == null || inPanel(at[0], at[1])) continue;
                int px = (int) at[0], py = (int) at[1];
                g.fill(px - 4, py - 4, px + 4, py + 5, color(a * 0.6, 12, 16, 19));
                g.centeredText(font, Integer.toString(logical + 1), px, py - 3, color(a, 196, 240, 245));
            }
        }
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

    private static int color(double a, int r, int g, int b) {
        return Mth.clamp((int) Math.round(a * 255), 0, 255) << 24 | r << 16 | g << 8 | b;
    }

    void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera) {
        if (!visible()) return;
        TableLayout layout = TableLayouts.current();
        for (int i = 0; i < 9; i++) {
            pose.pushPose();
            layout.cellPose(pose, station.pos, i, camera);
            double half = TableGrid.CELL / 2 - 0.006, t = 0.003;
            VertexConsumer out = buffers.getBuffer(Fx.HIGHLIGHT);
            Matrix4f m = pose.last().pose();
            quad(out, m, -half, -half, half, half, (int) (shown * 10));
            quad(out, m, -half, -half, half, -half + t, (int) (shown * 160));
            quad(out, m, -half, half - t, half, half, (int) (shown * 160));
            quad(out, m, -half, -half, -half + t, half, (int) (shown * 160));
            quad(out, m, half - t, -half, half, half, (int) (shown * 160));
            pose.popPose();
        }
    }

    private static void quad(VertexConsumer out, Matrix4f m, double x0, double z0, double x1, double z1, int a) {
        out.addVertex(m, (float) x0, 0, (float) z0).setColor(103, 211, 231, a);
        out.addVertex(m, (float) x0, 0, (float) z1).setColor(103, 211, 231, a);
        out.addVertex(m, (float) x1, 0, (float) z1).setColor(103, 211, 231, a);
        out.addVertex(m, (float) x1, 0, (float) z0).setColor(103, 211, 231, a);
    }

    // The showcase clicks the same screen controls as a player.
    double[] togglePoint() { layout(); return new double[]{toggleX + 15 * scale, toggleY + 14 * scale}; }
    double[] controlPoint(double px, double py) { layout(); return new double[]{x + px * scale, y + py * scale}; }
    double[] sliderPoint(int field, double value) { return controlPoint(11 + (W - 22) * (value - MIN[field]) / (MAX[field] - MIN[field]), ROW_Y + field * ROW_H + 16); }
}
