package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dyslexic.cinematic.table.TableGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import com.mojang.blaze3d.platform.InputConstants;

/** A small glass reference on the left; its diagram belongs only to the player's current scene. */
final class TableRecipeBook {
    private static final int WIDTH = 128, HEIGHT = 226, CELL = 27, COLS = 4, ROWS = 4;
    /** The "Lay out" button under the chosen recipe. */
    private static final int LAY_Y = 205, LAY_H = 15;
    private static final int GRID_X = 10, GRID_Y = 61, PAGE_SIZE = COLS * ROWS;
    private static final ItemStack BOOK = new ItemStack(Items.BOOK);
    private final TableStation station;
    final TableRecipeGuide guide;
    private final EditBox search;
    private List<TableRecipeGuide.Entry> filtered = List.of();
    private boolean open, craftableOnly;
    private double shown;
    private double selectedAt;
    private int revision = -1, page;
    private float scale = 1;
    private int x, y, toggleY;

    TableRecipeBook(TableStation station) {
        this.station = station;
        guide = new TableRecipeGuide(station);
        search = new EditBox(Minecraft.getInstance().font, 9, 29, WIDTH - 18, 12, text("search"));
        search.setMaxLength(96);
        search.setBordered(false);
        search.setTextColor(0xFFF2EAD8);
        search.setHint(text("search"));
        search.setResponder(value -> { page = 0; filter(); });
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("cinematic.recipes." + key, args);
    }

    void tick() {
        guide.tick();
        if (revision != guide.revision) {
            revision = guide.revision;
            filter();
        }
    }

    private void filter() {
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        filtered = guide.entries.stream().filter(e -> (!craftableOnly || e.craftable) && e.search.contains(query)).toList();
        page = Mth.clamp(page, 0, pages() - 1);
    }

    private int pages() { return Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE); }
    private double localX(double mx) { return (mx - x) / scale; }
    private double localY(double my) { return (my - y) / scale; }
    private boolean inPanel(double mx, double my) {
        return shown > 0.02 && localX(mx) >= 0 && localX(mx) < WIDTH && localY(my) >= 0 && localY(my) < HEIGHT;
    }
    private boolean inToggle(double mx, double my) {
        return !open && shown < 0.02 && mx >= 8 && mx < 8 + 32 * scale && my >= toggleY && my < toggleY + 28 * scale;
    }

    boolean over(double mx, double my) { return inPanel(mx, my) || inToggle(mx, my); }

    /** Select a recipe opened from the optional in-world field guide. */
    void show(TableRecipeGuide.Entry entry) {
        guide.select(entry);
        selectedAt = Scene.clock();
    }

    void fold() { open = false; search.setFocused(false); }

    private int entryAt(double mx, double my) {
        double lx = localX(mx) - GRID_X, ly = localY(my) - GRID_Y;
        if (lx < 0 || lx >= COLS * CELL || ly < 0 || ly >= ROWS * CELL) return -1;
        int index = page * PAGE_SIZE + (int) (ly / CELL) * COLS + (int) (lx / CELL);
        return index < filtered.size() ? index : -1;
    }

    void render(GuiGraphicsExtractor g, Font font, float alpha) {
        var window = Minecraft.getInstance().getWindow();
        int width = window.getGuiScaledWidth(), height = window.getGuiScaledHeight();
        // Keep the reference outside the expanded inventory and the table at narrow GUI sizes too.
        scale = Math.min(1f, Math.min(height / 300f, width / 530f));
        shown = Anim.approach(shown, open ? 1 : 0, 15, Scene.frameDt);
        x = (int) Math.round(8 - (1 - shown) * (WIDTH + 12) * scale);
        y = Math.round(height * 0.15f);
        toggleY = Math.round(height * 0.45f);
        double[] mouse = Picking.mouseGui();
        if (shown < 0.02) {
            g.pose().pushMatrix();
            g.pose().translate(8, toggleY);
            g.pose().scale(scale, scale);
            glass(g, 32, 28, alpha);
            if (inToggle(mouse[0], mouse[1])) g.fill(1, 1, 31, 27, color(alpha * 0.12f, 255, 226, 150));
            g.item(BOOK, 8, 6);
            g.pose().popMatrix();
            return;
        }
        float a = alpha * (float) shown;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        glass(g, WIDTH, HEIGHT, a);
        g.text(font, text("title"), 9, 9, color(a, 242, 234, 216), false);
        g.text(font, "−", WIDTH - 17, 8, color(a * 0.8f, 242, 234, 216), false);
        g.fill(8, 25, WIDTH - 8, 43, color(a * 0.08f, 255, 255, 255));
        search.setAlpha(a);
        search.extractRenderState(g, (int) localX(mouse[0]), (int) localY(mouse[1]), 0);
        g.fill(9, 48, 18, 57, color(a * 0.15f, 255, 255, 255));
        if (craftableOnly) g.fill(11, 50, 16, 55, color(a * 0.9f, 255, 226, 150));
        g.text(font, text("craftable"), 23, 49, color(a * 0.8f, 242, 234, 216), false);
        int hover = entryAt(mouse[0], mouse[1]);
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = page * PAGE_SIZE + slot;
            if (index >= filtered.size()) break;
            var entry = filtered.get(index);
            int sx = GRID_X + slot % COLS * CELL, sy = GRID_Y + slot / COLS * CELL;
            g.fill(sx, sy, sx + CELL - 2, sy + CELL - 2, color(a * (index == hover ? 0.19f : 0.06f), 255, 255, 255));
            if (entry == guide.selected) rim(g, sx, sy, CELL - 2, color(a * 0.85f, 255, 226, 150));
            // items cannot be tinted since 1.21.6: they show once the panel is mostly there (as in the inventory
            // drawer), and one that cannot be made yet sinks into the glass under a veil of it (drawn after the
            // item, so over it, as vanilla veils its ghost recipe items)
            if (a > 0.35f) {
                g.item(entry.result, sx + 4, sy + 3);
                g.itemDecorations(font, entry.result, sx + 4, sy + 3);
                if (!entry.craftable) g.fill(sx + 4, sy + 3, sx + 20, sy + 19, color(a * 0.4f, 12, 12, 16));
            }
            if (entry.craftable) g.fill(sx + 9, sy + CELL - 5, sx + 15, sy + CELL - 4, color(a * 0.8f, 143, 211, 176));
        }
        if (filtered.isEmpty()) {
            g.textWithWordWrap(font, text("empty"), 15, GRID_Y + 35, WIDTH - 30, color(a * 0.7f, 210, 206, 198));
        }
        g.text(font, "‹", 12, 171, color(a * (page > 0 ? 0.85f : 0.25f), 242, 234, 216), false);
        String counter = (page + 1) + " / " + pages();
        g.text(font, counter, (WIDTH - font.width(counter)) / 2, 171, color(a * 0.65f, 242, 234, 216), false);
        g.text(font, "›", WIDTH - 18, 171, color(a * (page < pages() - 1 ? 0.85f : 0.25f), 242, 234, 216), false);
        g.fill(8, 185, WIDTH - 8, 186, color(a * 0.12f, 255, 255, 255));
        if (guide.selected != null) {
            g.text(font, font.plainSubstrByWidth(guide.selected.result.getHoverName().getString(), WIDTH - 34), 9, 190,
                    color(a * 0.85f, 242, 234, 216), false);
            g.text(font, "×", WIDTH - 17, 190, color(a * 0.85f, 242, 234, 216), false);
        } else {
            g.text(font, text("choose"), 9, 190, color(a * 0.55f, 242, 234, 216), false);
        }
        drawLayButton(g, font, a, mouse);
        g.pose().popMatrix();
    }

    /** How many items "Lay out" would put down right now (0: nothing to do, or the hands are busy). */
    private int layable() {
        if (guide.selected == null || station.busy()) return 0;
        // the last recipe's leftovers in the way are taken back first; they may also belong in the new one
        return guide.takeBackMoves().isEmpty() ? guide.layoutMoves().size() : guide.layableAfterTakingBack();
    }

    private boolean inLayButton(double mx, double my) {
        double lx = localX(mx), ly = localY(my);
        return open && shown > 0.9 && guide.selected != null && lx >= 8 && lx < WIDTH - 8 && ly >= LAY_Y && ly < LAY_Y + LAY_H;
    }

    /** Shift + "Lay out": the most whole sets the inventory allows (nothing while the hands are busy). */
    private TableRecipeGuide.MaxLayout maxLayout() {
        return guide.selected == null || station.busy() ? new TableRecipeGuide.MaxLayout(0, 0, List.of()) : guide.maxLayout();
    }

    /** Lays out the chosen recipe: one set, or with {@code all} as many whole sets as possible. */
    void layOut(boolean all) {
        List<int[]> back = guide.selected == null || station.busy() ? List.of() : guide.takeBackMoves();
        if (!back.isEmpty()) {
            // take back what is in the way, then lay out from the inventory as it is after that
            if (all ? maxLayout().moves().isEmpty() : layable() == 0) return;
            List<int[]> moves = new ArrayList<>(back);
            moves.add(new int[]{-1, TableStation.REPLAN, all ? 1 : 0});
            station.layOut(moves);
            return;
        }
        List<int[]> moves = all ? maxLayout().moves() : layable() > 0 ? guide.layoutMoves() : List.of();
        if (!moves.isEmpty()) station.layOut(moves);
    }

    private void drawLayButton(GuiGraphicsExtractor g, Font font, float a, double[] mouse) {
        if (guide.selected == null) {
            return;
        }
        boolean all = net.minecraft.client.Minecraft.getInstance().hasShiftDown() && !station.busy();
        TableRecipeGuide.MaxLayout max = all ? maxLayout() : null;
        int count = all ? max.moves().size() : layable();
        boolean live = count > 0;
        boolean hover = live && inLayButton(mouse[0], mouse[1]);
        g.fill(8, LAY_Y, WIDTH - 8, LAY_Y + LAY_H, color(a * (hover ? 0.3f : live ? 0.16f : 0.06f), 255, 226, 150));
        g.fill(8, LAY_Y + LAY_H - 1, WIDTH - 8, LAY_Y + LAY_H, color(a * (live ? 0.6f : 0.15f), 255, 226, 150));
        Component label = all ? live ? text("lay_out_all", max.sets()) : text("no_more_sets")
                : live ? text("lay_out", count) : text(station.busy() ? "laying" : "nothing_to_lay");
        int w = font.width(label);
        g.text(font, label, (WIDTH - w) / 2, LAY_Y + 4, color(a * (live ? 0.95f : 0.45f), 242, 234, 216), false);
    }

    private static void glass(GuiGraphicsExtractor g, int w, int h, float alpha) {
        g.fill(0, 0, w, h, color(alpha * 0.62f, 12, 12, 16));
        g.fill(0, h - 1, w, h, color(alpha * 0.22f, 255, 255, 255));
        g.fill(0, 0, 1, h - 1, color(alpha * 0.08f, 255, 255, 255));
        g.fill(w - 1, 0, w, h - 1, color(alpha * 0.08f, 255, 255, 255));
    }

    private static void rim(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        g.fill(x, y, x + size, y + 1, color);
        g.fill(x, y + size - 1, x + size, y + size, color);
        g.fill(x, y, x + 1, y + size, color);
        g.fill(x + size - 1, y, x + size, y + size, color);
    }

    private static int color(float alpha, int r, int g, int b) {
        return Mth.clamp(Math.round(alpha * 255), 0, 255) << 24 | r << 16 | g << 8 | b;
    }

    boolean click(double mx, double my, int button) {
        if (inToggle(mx, my)) { open = true; return true; }
        if (!inPanel(mx, my)) { search.setFocused(false); return false; }
        if (button != 0 || !open) return true;
        double lx = localX(mx), ly = localY(my);
        search.setFocused(ly >= 25 && ly < 43 && lx >= 8 && lx < WIDTH - 8);
        if (search.isFocused()) { search.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(lx, ly, new net.minecraft.client.input.MouseButtonInfo(button, 0)), false); return true; }
        if (inLayButton(mx, my)) {
            layOut(net.minecraft.client.Minecraft.getInstance().hasShiftDown());
        } else if (ly < 23 && lx >= WIDTH - 25) open = false;
        else if (ly >= 46 && ly < 59) { craftableOnly = !craftableOnly; page = 0; filter(); }
        else if (ly >= 169 && ly < 184) {
            if (lx < 32) page = Math.max(0, page - 1);
            else if (lx > WIDTH - 32) page = Math.min(pages() - 1, page + 1);
        } else if (ly >= 186 && ly < LAY_Y && lx >= WIDTH - 25) guide.select(null);
        else {
            int index = entryAt(mx, my);
            if (index >= 0) { guide.select(filtered.get(index)); selectedAt = Scene.clock(); }
        }
        return true;
    }

    boolean scroll(double mx, double my, double amount) {
        if (!over(mx, my)) return false;
        if (open) page = Mth.clamp(page - (int) Math.signum(amount), 0, pages() - 1);
        return true;
    }

    boolean charTyped(char c, int modifiers) {
        return search.isFocused() && search.charTyped(new net.minecraft.client.input.CharacterEvent(c));
    }

    boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!search.isFocused()) return false;
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_RETURN || key == InputConstants.KEY_RETURN) search.setFocused(false);
        else search.keyPressed(new net.minecraft.client.input.KeyEvent(key, scanCode, modifiers));
        return true;
    }

    @Nullable List<Component> tooltip(double mx, double my) {
        if (inToggle(mx, my)) return List.of(text("title"));
        if (inPanel(mx, my)) {
            double lx = localX(mx), ly = localY(my);
            if (ly < 23 && lx >= WIDTH - 25) return List.of(text("hide"));
            if (inLayButton(mx, my)) {
                List<Component> lines = new ArrayList<>();
                lines.add(text(station.busy() ? "laying" : layable() > 0 ? "lay_out_tip" : "nothing_to_lay_tip"));
                if (guide.missingCount > 0) lines.add(text("lay_missing", guide.missingCount).copy().withStyle(ChatFormatting.RED));
                if (!station.busy() && !guide.takeBackMoves().isEmpty()) lines.add(text("lay_take_back", guide.takeBackMoves().size()).copy().withStyle(ChatFormatting.GRAY));
                if (!station.busy() && guide.stuck() > 0) lines.add(text("lay_no_room", guide.stuck()).copy().withStyle(ChatFormatting.RED));
                if (!station.busy()) {
                    TableRecipeGuide.MaxLayout max = maxLayout();
                    lines.add((max.moves().isEmpty()
                            ? text(max.current() > 0 ? "lay_all_full" : "lay_all_none")
                            : text("lay_all_tip", max.sets())).copy().withStyle(ChatFormatting.GRAY));
                }
                return lines;
            }
            if (ly >= 186 && ly < LAY_Y && guide.selected != null) return List.of(guide.selected.result.getHoverName(), text("clear"));
            int index = entryAt(mx, my);
            if (index < 0) return null;
            var entry = filtered.get(index);
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), entry.result));
            lines.add(text(entry.craftable ? "available" : "unavailable").copy()
                    .withStyle(entry.craftable ? ChatFormatting.GREEN : ChatFormatting.RED));
            lines.add(text("preview").copy().withStyle(ChatFormatting.GRAY));
            // Recipe ids distinguish alternate recipes for the same result in large modpacks.
            if (entry.holder != null && net.minecraft.client.Minecraft.getInstance().hasShiftDown())
                lines.add(Component.literal(entry.idText).withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }
        if (guide.selected == null || station.hoverCell < 0) return null;
        int cell = logicalCell(station.hoverCell);
        if (TableRecipeGuide.empty(guide.grid[cell])) return guide.wrong(cell) ? List.of(text("extra").copy().withStyle(ChatFormatting.RED)) : null;
        ItemStack expected = guide.placed[cell] ? ItemStack.EMPTY : guide.ghost(cell);
        if (expected.isEmpty()) return null;
        return List.of(expected.getHoverName(), text(guide.wrong(cell) ? "wrong" : guide.missing[cell] ? "missing" : "place")
                .copy().withStyle(guide.wrong(cell) || guide.missing[cell] ? ChatFormatting.RED : ChatFormatting.GRAY));
    }

    private int logicalCell(int world) {
        for (int i = 0; i < 9; i++) if (TableGrid.worldIndex(station.side, i / 3, i % 3) == world) return i;
        return 0;
    }

    void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 cam, int light) {
        if (guide.selected == null || station.craftProgress() >= 0) return;
        if (buffers instanceof SceneBuffers.BufferSource source) source.endBatch();
        float appear = (float) Anim.smoother((Scene.clock() - selectedAt) / 0.28);
        for (int logical = 0; logical < 9; logical++) {
            boolean wrong = guide.wrong(logical);
            if (TableRecipeGuide.empty(guide.grid[logical]) && !wrong) continue;
            int world = TableGrid.worldIndex(station.side, logical / 3, logical % 3);
            TableLayout layout = TableLayouts.current();
            Vec3 base = layout.cell(station.pos, world).add(0, 0.002, 0);
            boolean red = guide.missing[logical] || wrong;
            int r = red ? 255 : guide.placed[logical] ? 143 : 166;
            int g = red ? 106 : guide.placed[logical] ? 211 : 211;
            int b = red ? 98 : guide.placed[logical] ? 176 : 235;
            pose.pushPose();
            layout.cellPose(pose, station.pos, world, cam);
            drawRim(pose, buffers, Vec3.ZERO, r, g, b, appear * (guide.placed[logical] ? 0.3f : 0.7f));
            pose.popPose();
            if (!guide.placed[logical] && !wrong) {
                layout.renderItem(pose, buffers, cam, guide.ghost(logical), base, station.side, world, 1, 0, light, appear * 0.28f, false);
            }
        }
        if (buffers instanceof SceneBuffers.BufferSource source) source.endBatch();
    }

    private static void drawRim(PoseStack pose, SceneBuffers buffers, Vec3 at, int r, int g, int b, float alpha) {
        double h = TableGrid.CELL / 2 - 0.009, t = 0.004;
        VertexConsumer out = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.lightning());
        Matrix4f m = pose.last().pose();
        strip(out, m, at, -h, -h, h, -h + t, r, g, b, alpha);
        strip(out, m, at, -h, h - t, h, h, r, g, b, alpha);
        strip(out, m, at, -h, -h, -h + t, h, r, g, b, alpha);
        strip(out, m, at, h - t, -h, h, h, r, g, b, alpha);
    }

    private static void strip(VertexConsumer out, Matrix4f m, Vec3 at, double x0, double z0, double x1, double z1, int r, int g, int b, float a) {
        int alpha = Math.round(a * 255);
        out.addVertex(m, (float) (at.x + x0), (float) at.y, (float) (at.z + z0)).setColor(r, g, b, alpha);
        out.addVertex(m, (float) (at.x + x0), (float) at.y, (float) (at.z + z1)).setColor(r, g, b, alpha);
        out.addVertex(m, (float) (at.x + x1), (float) at.y, (float) (at.z + z1)).setColor(r, g, b, alpha);
        out.addVertex(m, (float) (at.x + x1), (float) at.y, (float) (at.z + z0)).setColor(r, g, b, alpha);
    }

    // Coordinates expose actual rendered controls to the scripted in-game walkthrough.
    double[] togglePoint() { return new double[]{24 * scale + 8, toggleY + 14 * scale}; }
    double[] layPoint() { return controlPoint(WIDTH / 2, LAY_Y + LAY_H / 2); }
    double[] controlPoint(int px, int py) { return new double[]{x + px * scale, y + py * scale}; }
    @Nullable double[] recipePoint(Identifier id) {
        for (int i = 0; i < filtered.size(); i++) if (filtered.get(i).holder != null && filtered.get(i).holder.id().identifier().equals(id)) {
            page = i / PAGE_SIZE;
            int slot = i % PAGE_SIZE;
            return controlPoint(GRID_X + slot % COLS * CELL + 12, GRID_Y + slot / COLS * CELL + 12);
        }
        return null;
    }
}
