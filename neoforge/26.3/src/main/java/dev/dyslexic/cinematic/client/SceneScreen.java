package dev.dyslexic.cinematic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * An invisible screen over the scene while the hands are at work. It frees the cursor and draws a glass drawer
 * with the inventory at the top of the frame: the hotbar always, the rest when the cursor goes up there.
 * Clicks elsewhere go to the station. A station may also ask for a line of text (the anvil's new name).
 */
public final class SceneScreen extends Screen {
    private static final int SLOT = 18;
    private static final int PAD = 5;
    private static final int ROW_GAP = 4;
    private static final int INNER = 9 * SLOT;

    private static final int FIELD_W = 170;

    private final InventoryEffects inventoryEffects = new InventoryEffects();
    final FieldGuide guide = new FieldGuide();
    private double expand;
    private float scale = 1f;
    private int panelX, panelY, panelW, panelH;

    @Nullable
    private EditBox field;
    @Nullable
    private Consumer<String> commit;

    public SceneScreen() {
        super(Component.translatable("cinematic.scene"));
    }

    @Override protected void init() {
        super.init();
        Scene.consulted();
    }

    @Override
    public void tick() {
        super.tick();
        inventoryEffects.tick();
        guide.tick();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // the scene itself is the background
    }

    /** 0..1: sliding in after the hands settle, sliding away when stepping back. */
    private static float presence() {
        if (Scene.transition && Scene.phase == Scene.Phase.INTRO) {
            // moving over to a neighbouring station: the drawer stays where it is
            return 1;
        }
        if (Scene.phase == Scene.Phase.OUTRO) {
            return (float) (1 - Anim.inOutSine(Scene.time / Scene.UI_FADE_OUT));
        }
        return (float) Anim.outCubic(Scene.time / 0.45);
    }

    private static boolean working() {
        return Scene.phase == Scene.Phase.ACTIVE && Scene.station != null;
    }

    static LayoutEditor editor() {
        if (Scene.station instanceof TableStation table) return table.gridEditor;
        if (Scene.station instanceof FurnaceStation furnace) return furnace.gridEditor;
        if (Scene.station instanceof SurfaceStation<?> surface) return surface.gridEditor;
        if (Scene.station instanceof SpatialTarget spatial) return spatial.layoutEditor();
        return null;
    }

    // ---- a line of text -----------------------------------------------------------------------------

    /** Opens a line to type into over the scene; Enter (or a click elsewhere) keeps it, Esc drops it. */
    public static void edit(String initial, int maxLength, Consumer<String> onCommit) {
        if (Minecraft.getInstance().gui.screen() instanceof SceneScreen screen) {
            screen.openField(initial, maxLength, onCommit);
        }
    }

    /** The text being typed, or null when no line is open. */
    @Nullable
    public static String editing() {
        return Minecraft.getInstance().gui.screen() instanceof SceneScreen screen && screen.field != null ? screen.field.getValue() : null;
    }

    /** Keeps what was typed, as Enter would. */
    public static void commitEdit(String value) {
        if (Minecraft.getInstance().gui.screen() instanceof SceneScreen screen && screen.field != null) {
            screen.field.setValue(value);
            screen.closeField(true);
        }
    }

    private void openField(String initial, int maxLength, Consumer<String> onCommit) {
        EditBox box = new EditBox(font, 0, 0, FIELD_W, 12, Component.translatable("cinematic.scene.text"));
        box.setMaxLength(maxLength);
        box.setBordered(false);
        box.setTextColor(0xFFF2EAD8);
        box.setValue(initial);
        box.setFocused(true);
        box.moveCursorToEnd(false);
        box.setHighlightPos(0);
        field = box;
        commit = onCommit;
    }

    private void closeField(boolean keep) {
        EditBox box = field;
        Consumer<String> then = commit;
        if (box != null) box.setFocused(false);
        field = null;
        commit = null;
        if (keep && box != null && then != null) {
            then.accept(box.getValue());
        }
    }

    private void drawField(GuiGraphicsExtractor g, float alpha) {
        if (field == null) {
            return;
        }
        int x = (width - FIELD_W) / 2;
        int y = height - 52;
        g.fill(x - 8, y - 15, x + FIELD_W + 8, y + 14, argb(0.62f * alpha, 12, 12, 16));
        g.fill(x - 8, y + 13, x + FIELD_W + 8, y + 14, argb(0.45f * alpha, 255, 224, 122));
        g.text(font, Component.translatable("cinematic.scene.text"), x, y - 11, argb(0.7f * alpha, 200, 192, 176), false);
        field.setX(x);
        field.setY(y + 1);
        field.extractRenderState(g, 0, 0, 0);
    }

    // ---- layout -------------------------------------------------------------------------------------

    private void layout() {
        scale = Mth.clamp(height * 0.068f / SLOT, 0.6f, 1.25f);
        float appear = presence();
        panelW = Math.round((INNER + PAD * 2) * scale);
        int full = PAD * 2 + SLOT + (int) Math.round(expand * (ROW_GAP + 3 * SLOT));
        panelH = Math.round(full * scale);
        panelX = (width - panelW) / 2;
        panelY = Math.round(6 - (1 - appear) * (panelH + 10));
    }

    private boolean overPanel(double mx, double my) {
        int reach = Math.round((PAD * 2 + SLOT + (expand > 0.5 ? ROW_GAP + 3 * SLOT : 0)) * scale);
        return mx >= panelX - 4 && mx <= panelX + panelW + 4 && my >= 0 && my <= panelY + Math.max(panelH, reach) + 4;
    }

    /** Inventory-menu slot under the cursor (hotbar 36..44, main 9..35), or -1. */
    private int slotAt(double mx, double my) {
        double lx = (mx - panelX) / scale - PAD;
        double ly = (my - panelY) / scale - PAD;
        if (lx < 0 || lx >= INNER) {
            return -1;
        }
        int col = (int) (lx / SLOT);
        if (ly >= 0 && ly < SLOT) {
            return 36 + col;
        }
        double below = ly - SLOT - ROW_GAP;
        if (expand > 0.9 && below >= 0 && below < 3 * SLOT) {
            return 9 + (int) (below / SLOT) * 9 + col;
        }
        return -1;
    }

    private static int inventoryIndex(int menuSlot) {
        return menuSlot >= 36 ? menuSlot - 36 : menuSlot;
    }

    /**
     * The open menu's slot for a drawer slot: the drawer is laid out like the inventory menu (hotbar 36..44,
     * main 9..35), while the enchanting table's menu numbers the same inventory slots differently.
     */
    private static int menuSlot(LocalPlayer player, int drawerSlot) {
        int index = inventoryIndex(drawerSlot);
        var slots = player.containerMenu.slots;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).container == player.getInventory() && slots.get(i).getContainerSlot() == index) {
                return i;
            }
        }
        return -1;
    }

    // ---- drawing ------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientStation station = Scene.station;
        if (player == null || station == null) {
            return;
        }
        boolean drawer = station.showsInventory();
        // looking round from behind, the panels give way to the view
        float look = (float) FreeLook.amount();
        boolean over = working() && drawer && !FreeLook.looking() && overPanel(mouseX, mouseY);
        Scene.overPanel = over || station.choices().over(mouseX, mouseY) || (station instanceof TableStation table && table.recipeBook.over(mouseX, mouseY))
                || (editor() != null && editor().over(mouseX, mouseY)) || guideShown() && guide.over(mouseX, mouseY);
        expand = Anim.approach(expand, over ? 1 : 0, 13, Scene.frameDt);
        layout();
        float alpha = (working() ? (float) Anim.smoother(Scene.time / 0.4) : presence()) * (1 - look);
        int hovered = over ? slotAt(mouseX, mouseY) : -1;
        inventoryEffects.beginFrame(working() && !FreeLook.looking() && field == null
                && !(editor() != null && editor().visible()));

        if (alpha > 0.01f) {
            station.renderScreen(graphics, font, alpha);
            station.choices().render(graphics, font, alpha);
            if (guideShown()) guide.render(graphics, font, alpha);
            if (drawer) {
                drawPanel(graphics, player, hovered, alpha);
            }
            drawHints(graphics, station.hint(), alpha);
        }
        if (look > 0.01f) {
            drawLookHint(graphics, look);
        }
        if (FreeLook.looking()) {
            return;
        }
        if (working() && Scene.neighbor != null) {
            drawMoveOver(graphics, mouseX, mouseY);
        }
        if (!working()) {
            closeField(false);
        }
        drawField(graphics, alpha);
        if (working()) {
            drawTooltip(graphics, player, station, hovered, mouseX, mouseY);
        }

        ItemStack carried = player.containerMenu.getCarried();
        inventoryEffects.endFrame(Scene.overPanel);
        // during a special move the station draws what is in hand itself
        if (working() && !carried.isEmpty() && !Hands.itemInHand() && station.handsOverride() == null
                && !(editor() != null && editor().visible())) {
            graphics.nextStratum();
            inventoryEffects.carried(graphics, carried, mouseX, mouseY, (x, y) -> {
                graphics.item(carried, x, y);
                graphics.itemDecorations(font, carried, x, y);
            });
        }
    }

    private void drawPanel(GuiGraphicsExtractor g, LocalPlayer player, int hovered, float alpha) {
        int bg = argb(0.46f * alpha, 12, 12, 16);
        int edge = argb(0.20f * alpha, 255, 255, 255);
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, bg);
        g.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, edge);
        g.fill(panelX, panelY, panelX + 1, panelY + panelH - 1, argb(0.08f * alpha, 255, 255, 255));
        g.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH - 1, argb(0.08f * alpha, 255, 255, 255));

        g.enableScissor(panelX, Math.max(0, panelY), panelX + panelW, panelY + panelH);
        g.pose().pushMatrix();
        g.pose().translate(panelX, panelY);
        g.pose().scale(scale, scale);
        int selected = player.getInventory().getSelectedSlot();
        for (int col = 0; col < 9; col++) {
            drawSlot(g, player, 36 + col, PAD + col * SLOT, PAD, hovered, col == selected, alpha);
        }
        if (expand > 0.02) {
            float rows = (float) Anim.smoother(expand);
            int dividerY = PAD + SLOT + ROW_GAP / 2;
            g.fill(PAD + 2, dividerY, PAD + INNER - 2, dividerY + 1, argb(0.12f * alpha * rows, 255, 255, 255));
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 9; col++) {
                    drawSlot(g, player, 9 + row * 9 + col, PAD + col * SLOT, PAD + SLOT + ROW_GAP + row * SLOT, hovered, false, alpha * rows);
                }
            }
        } else {
            // a small grip under the hotbar hints that the drawer opens
            int gx = PAD + INNER / 2;
            g.fill(gx - 9, PAD + SLOT + 2, gx + 9, PAD + SLOT + 3, argb(0.35f * alpha, 255, 255, 255));
        }
        g.pose().popMatrix();
        g.disableScissor();
    }

    private void drawSlot(GuiGraphicsExtractor g, LocalPlayer player, int menuSlot, int x, int y, int hovered, boolean selected, float alpha) {
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, argb(0.13f * alpha, 255, 255, 255));
        if (selected) {
            int c = argb(0.75f * alpha, 255, 226, 150);
            g.fill(x, y, x + SLOT, y + 1, c);
            g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, c);
            g.fill(x, y, x + 1, y + SLOT, c);
            g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, c);
        }
        if (menuSlot == hovered) {
            g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, argb(0.35f * alpha, 255, 255, 255));
        }
        if (alpha > 0.35f) {
            ItemStack stack = player.getInventory().getItem(inventoryIndex(menuSlot));
            double cx = panelX + (x + 9) * scale, cy = panelY + (y + 9) * scale;
            if (cy >= Math.max(0, panelY) && cy < panelY + panelH) {
                inventoryEffects.drawer(inventoryIndex(menuSlot), stack, cx, cy, menuSlot == hovered);
            }
            g.item(stack, x + 1, y + 1);
            g.itemDecorations(font, stack, x + 1, y + 1);
        }
    }

    private void drawHints(GuiGraphicsExtractor g, Component hint, float alpha) {
        if (!Settings.controlHints()) {
            return;
        }
        double fade = working() ? 1 - 0.55 * Anim.smoother((Scene.time - 7) / 2) : 0.45;
        int a = (int) (alpha * fade * 200);
        if (a < 8) {
            return;
        }
        int w = font.width(hint);
        float s = Math.min(1f, (width - 24f) / w);
        g.pose().pushMatrix();
        g.pose().translate(width / 2f, height - 6f - 11f * s);
        g.pose().scale(s, s);
        g.fill(-w / 2 - 6, -4, w / 2 + 6, 11, (int) (a * 0.45) << 24);
        g.text(font, hint, -w / 2, 0, (a << 24) | 0xF2EAD8, false);
        g.pose().popMatrix();
    }

    /** Looking round from behind: how to get back to work, small at the bottom. */
    private void drawLookHint(GuiGraphicsExtractor g, float look) {
        Component hint = FreeLook.hint();
        int a = Math.round(look * 190);
        if (a < 8) {
            return;
        }
        int w = font.width(hint);
        int y = height - (int) Math.round(height * 0.085 * 0.55) - 14;
        g.fill(width / 2 - w / 2 - 6, y - 3, width / 2 + w / 2 + 6, y + 11, (int) (a * 0.45) << 24);
        g.text(font, hint, width / 2 - w / 2, y, (a << 24) | 0xF2EAD8, false);
    }

    /** Beside the cursor over a neighbouring station: its name and the click that moves over to it. */
    private void drawMoveOver(GuiGraphicsExtractor g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || Scene.neighbor == null) {
            return;
        }
        Component name = mc.level.getBlockState(Scene.neighbor).getBlock().getName();
        Component how = Component.translatable("cinematic.move_over");
        int nameW = font.width(name), howW = font.width(how);
        int w = nameW + 9 + howW + 12, h = 13;
        int x = Math.min(mx + 10, width - w - 2), y = Math.min(my + 10, height - h - 2);
        g.nextStratum();
        g.fill(x, y, x + w, y + h, argb(0.62f, 12, 12, 16));
        g.fill(x, y + h - 1, x + w, y + h, argb(0.5f, 255, 226, 150));
        g.text(font, name, x + 6, y + 3, 0xFFFFE2A0, false);
        g.text(font, "·", x + 6 + nameW + 2, y + 3, 0xFF8A8478, false);
        g.text(font, how, x + 6 + nameW + 9, y + 3, 0xFFF2EAD8, false);
    }

    private void drawTooltip(GuiGraphicsExtractor g, LocalPlayer player, ClientStation station, int hovered, int mx, int my) {
        ItemStack carried = player.containerMenu.getCarried();
        List<Component> guideLines = guideShown() && carried.isEmpty() ? guide.tooltip(mx, my) : null;
        if (guideLines != null) {
            g.setTooltipForNextFrame(font, guideLines.stream().map(Component::getVisualOrderText).toList(), mx, my);
            return;
        }
        if (hovered >= 0) {
            ItemStack stack = player.getInventory().getItem(inventoryIndex(hovered));
            if (carried.isEmpty() && !stack.isEmpty()) {
                g.setTooltipForNextFrame(font, stack, mx, my);
            }
            return;
        }
        List<Component> choiceLines = station.choices().tooltip(mx, my);
        if (choiceLines != null) {
            g.setTooltipForNextFrame(font, choiceLines.stream().map(Component::getVisualOrderText).toList(), mx, my);
            return;
        }
        List<Component> lines = station.tooltip(carried);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        List<FormattedCharSequence> seq = new ArrayList<>(lines.size());
        for (Component line : lines) {
            if (editor() != null && editor().editing()) seq.addAll(font.split(line, Math.min(210, width / 2)));
            else seq.add(line.getVisualOrderText());
        }
        g.setTooltipForNextFrame(font, seq, mx, my);
    }

    private static int argb(float a, int r, int g, int b) {
        int alpha = Mth.clamp(Math.round(a * 255), 0, 255);
        return alpha << 24 | r << 16 | g << 8 | b;
    }

    private boolean guideShown() {
        return working() && !(editor() != null && editor().visible());
    }

    @Nullable private ItemStack hoveredPanelItem() {
        LocalPlayer player = Minecraft.getInstance().player;
        double[] mouse = Picking.mouseGui();
        if (player == null || Scene.station == null || !Scene.station.showsInventory() || !overPanel(mouse[0], mouse[1])) return null;
        int slot = slotAt(mouse[0], mouse[1]);
        return slot < 0 ? null : player.getInventory().getItem(inventoryIndex(slot));
    }

    // ---- input --------------------------------------------------------------------------------------

    // since 1.21.9 keys and the mouse come as events; they are handled as before

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return mouseClicked(event.x(), event.y(), logicalButton(event.buttonInfo().button()));
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return charTyped((char) event.codepoint(), 0);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return mouseDragged(event.x(), event.y(), logicalButton(event.buttonInfo().button()), dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return mouseReleased(event.x(), event.y(), logicalButton(event.buttonInfo().button()));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return keyPressed(event.key(), event.keycode(), event.modifiers());
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        return keyReleased(event.key(), event.keycode(), event.modifiers());
    }

    static int logicalButton(int button) {
        return button == InputConstants.MOUSE_BUTTON_LEFT ? 0 : button == InputConstants.MOUSE_BUTTON_RIGHT ? 1 : 2;
    }
    static int physicalButton(int button) {
        return button == 0 ? InputConstants.MOUSE_BUTTON_LEFT : button == 1 ? InputConstants.MOUSE_BUTTON_RIGHT : InputConstants.MOUSE_BUTTON_MIDDLE;
    }

    private boolean mouseClicked(double mx, double my, int button) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || (button != 0 && button != 1) || !working() || FreeLook.looking()) {
            return true;
        }
        if (editor() != null && editor().click(mx, my, button)) return true;
        if (field != null) {
            if (!field.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(mx, my, new net.minecraft.client.input.MouseButtonInfo(physicalButton(button), 0)), false)) {
                closeField(true);
            }
            return true;
        }
        inventoryEffects.action(mc.hasShiftDown() ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP);
        if (guideShown() && guide.click(mx, my, button)) return true;
        if (Scene.station instanceof TableStation table && table.recipeBook.click(mx, my, button)) {
            table.release(button);
            return true;
        }
        if (Scene.station.showsInventory() && overPanel(mx, my)) {
            int slot = slotAt(mx, my);
            int target = slot >= 0 ? menuSlot(player, slot) : -1;
            if (target >= 0) {
                if (mc.hasShiftDown() && Scene.station instanceof TableStation table) {
                    table.quickMove(inventoryIndex(slot));
                } else if (mc.hasShiftDown() && (Scene.station.kind().usesMenu() || Scene.station.kind().isFurnace()
                        || Scene.station.kind() == dev.dyslexic.cinematic.station.StationKind.CAMPFIRE
                        || Scene.station.kind() == dev.dyslexic.cinematic.station.StationKind.JUKEBOX
                        || Scene.station.kind() == dev.dyslexic.cinematic.station.StationKind.CHISELED_BOOKSHELF)) {
                    Scene.station.quickMove(inventoryIndex(slot), target);
                } else {
                    mc.gameMode.handleContainerInput(player.containerMenu.containerId, target, button,
                            mc.hasShiftDown() ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, player);
                }
            }
            return true;
        }
        int picked = Scene.station.choices().click(mx, my);
        if (picked != -2) {
            if (picked >= 0) Scene.station.chose(picked);
            return true;
        }
        if (button == 1 && Scene.neighbor != null) {
            Scene.moveOver();
            return true;
        }
        Scene.station.click(button, net.minecraft.client.Minecraft.getInstance().hasShiftDown());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (FreeLook.looking()) {
            FreeLook.scroll(scrollY);
            return true;
        }
        if (working() && editor() != null && editor().scroll(mx, my, scrollY)) return true;
        if (working() && guideShown() && guide.scroll(mx, my, scrollY)) return true;
        if (working() && Scene.station instanceof TableStation table && table.recipeBook.scroll(mx, my, scrollY)) return true;
        if (working() && !(Scene.station.showsInventory() && overPanel(mx, my))) {
            Scene.station.scroll(scrollY);
        }
        return true;
    }

    private boolean charTyped(char c, int modifiers) {
        if (working() && editor() != null && editor().editing()) return true;
        if (working() && guideShown() && guide.charTyped(new CharacterEvent(c))) return true;
        if (working() && Scene.station instanceof TableStation table && table.recipeBook.charTyped(c, modifiers)) return true;
        return field != null ? field.charTyped(new net.minecraft.client.input.CharacterEvent(c)) : super.charTyped(new net.minecraft.client.input.CharacterEvent(c));
    }

    private boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (FreeLook.looking()) {
            return true;
        }
        if (working() && editor() != null && editor().drag(mx, my, button)) return true;
        if (working() && guideShown() && guide.over(mx, my)) {
            if (button == 1 && Scene.station instanceof TableStation table) table.release(button);
            return true;
        }
        if (working() && Scene.station instanceof TableStation table && table.recipeBook.over(mx, my)) {
            table.release(button);
            return true;
        }
        if (working()) {
            inventoryEffects.action(Minecraft.getInstance().hasShiftDown() ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP);
            Scene.station.drag(button);
        }
        return true;
    }

    private boolean mouseReleased(double mx, double my, int button) {
        if (editor() != null) editor().release();
        if (Scene.station != null) {
            Scene.station.release(button);
        }
        return true;
    }

    private boolean keyPressed(int key, int scanCode, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        var options = mc.options;
        if (!working()) {
            return true;
        }
        if (editor() != null && editor().editing()) {
            editor().keyPressed(key);
            return true;
        }
        if (field == null && FreeLook.keyPressed(key, scanCode)) {
            return true;
        }
        if (FreeLook.looking()) {
            // back to work first: the keys that step away do so only from the work itself
            if (key == InputConstants.KEY_ESCAPE || options.keyInventory.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)) || options.keyUp.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))
                    || options.keyDown.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)) || options.keyLeft.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))
                    || options.keyRight.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)) || options.keyJump.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))) {
                FreeLook.stop();
            }
            return true;
        }
        if (field != null) {
            if (key == InputConstants.KEY_ESCAPE) {
                closeField(false);
            } else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                closeField(true);
            } else {
                field.keyPressed(new net.minecraft.client.input.KeyEvent(key, scanCode, modifiers));
            }
            return true;
        }
        inventoryEffects.action(mc.hasShiftDown() ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP);
        if (guideShown() && guide.keyPressed(new net.minecraft.client.input.KeyEvent(key, scanCode, modifiers))) return true;
        if (Scene.station instanceof TableStation table && table.recipeBook.keyPressed(key, scanCode, modifiers)) return true;
        if (guideShown() && guide.viewerKey(key, scanCode, hoveredPanelItem())) return true;
        if (editor() != null && editor().keyPressed(key)) return true;
        if (Scene.station.keyPressed(key)) {
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE || options.keyInventory.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)) || options.keyUp.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))
                || options.keyDown.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)) || options.keyLeft.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))
                || options.keyRight.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)) || options.keyJump.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))) {
            Scene.leave();
            return true;
        }
        if (player != null && mc.gameMode != null) {
            double[] mouse = Picking.mouseGui();
            int slot = Scene.station.showsInventory() && overPanel(mouse[0], mouse[1]) ? menuSlot(player, slotAt(mouse[0], mouse[1])) : -1;
            if (slot >= 0) {
                for (int i = 0; i < 9; i++) {
                    if (options.keyHotbarSlots[i].matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))) {
                        inventoryEffects.action(ContainerInput.SWAP);
                        mc.gameMode.handleContainerInput(player.containerMenu.containerId, slot, i, ContainerInput.SWAP, player);
                        return true;
                    }
                }
                if (options.keyDrop.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))) {
                    inventoryEffects.action(ContainerInput.THROW);
                    mc.gameMode.handleContainerInput(player.containerMenu.containerId, slot, net.minecraft.client.Minecraft.getInstance().hasControlDown() ? 1 : 0,
                            ContainerInput.THROW, player);
                    return true;
                }
            }
        }
        return super.keyPressed(new net.minecraft.client.input.KeyEvent(key, scanCode, modifiers));
    }

    private boolean keyReleased(int key, int scanCode, int modifiers) {
        if (FreeLook.keyReleased(key, scanCode)) {
            return true;
        }
        return super.keyReleased(new net.minecraft.client.input.KeyEvent(key, scanCode, modifiers));
    }

    @Override
    public void onClose() {
        Scene.leave();
    }
}
