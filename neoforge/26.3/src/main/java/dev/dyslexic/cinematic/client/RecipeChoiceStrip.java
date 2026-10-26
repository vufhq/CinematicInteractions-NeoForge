package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.net.PacketDistributor;
import dev.dyslexic.cinematic.net.StationActionPayload;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The results a station's input could give when several recipes compete for it (Polymorph): a small glass strip low
 * in the middle of the screen, the one in use framed. A click picks one; the server then makes exactly that one
 * (RecipeChoices). Shown only with two or more results, so without Polymorph or conflicts nothing changes.
 */
final class RecipeChoiceStrip {
    private static final int CELL = 20;
    private static final int PAD = 4;
    private static final int TITLE = 11;

    private final List<ItemStack> outputs = new ArrayList<>();
    private int selected = -1;
    private int x, y, width, height;
    private boolean shown;

    /** The competing results, and which is in use (-1: not known yet). */
    void set(List<ItemStack> results, int inUse) {
        outputs.clear();
        outputs.addAll(results);
        selected = inUse;
    }

    void clear() {
        outputs.clear();
        selected = -1;
    }

    boolean active() {
        return outputs.size() >= 2;
    }

    int selected() {
        return selected;
    }

    ItemStack selectedResult() {
        return selected >= 0 && selected < outputs.size() ? outputs.get(selected).copy() : ItemStack.EMPTY;
    }

    void render(GuiGraphicsExtractor g, Font font, float alpha) {
        shown = active() && alpha > 0.05f;
        if (!shown) return;
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth(), screenH = mc.getWindow().getGuiScaledHeight();
        Component title = Component.translatable("cinematic.choices.title");
        width = Math.max(outputs.size() * CELL + PAD * 2, font.width(title) + PAD * 2);
        height = TITLE + CELL + PAD * 2;
        x = (screenW - width) / 2;
        // low in the frame, clear of the hint line, inside the 15-85% band of things to click
        y = Math.min((int) (screenH * 0.85) - height, screenH - 34 - height);
        int a = Math.round(alpha * 255);
        g.fill(x, y, x + width, y + height, (Math.round(alpha * 0.62f * 255) << 24) | 0x0C0C10);
        g.fill(x, y + height - 1, x + width, y + height, (Math.round(alpha * 0.22f * 255) << 24) | 0xFFFFFF);
        g.text(font, title, x + (width - font.width(title)) / 2, y + PAD - 1, (a << 24) | 0xD8D8D8, false);
        double[] mouse = Picking.mouseGui();
        int hover = at(mouse[0], mouse[1]);
        int left = x + (width - outputs.size() * CELL) / 2;
        for (int i = 0; i < outputs.size(); i++) {
            int cx = left + i * CELL, cy = y + PAD + TITLE;
            if (i == hover) g.fill(cx, cy, cx + CELL, cy + CELL, (Math.round(alpha * 0.18f * 255) << 24) | 0xFFFFFF);
            if (i == selected) {
                int rim = (a << 24) | 0x8CC4EE;
                g.fill(cx, cy, cx + CELL, cy + 1, rim);
                g.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, rim);
                g.fill(cx, cy, cx + 1, cy + CELL, rim);
                g.fill(cx + CELL - 1, cy, cx + CELL, cy + CELL, rim);
            }
            g.item(outputs.get(i), cx + 2, cy + 2);
            g.itemDecorations(font, outputs.get(i), cx + 2, cy + 2);
        }
    }

    boolean over(double mx, double my) {
        return shown && mx >= x && mx < x + width && my >= y && my < y + height;
    }

    /** The result under the cursor, or -1. */
    int at(double mx, double my) {
        if (!shown) return -1;
        int left = x + (width - outputs.size() * CELL) / 2, top = y + PAD + TITLE;
        if (my < top || my >= top + CELL || mx < left) return -1;
        int i = (int) ((mx - left) / CELL);
        return i < outputs.size() ? i : -1;
    }

    /** A click on the strip: the newly picked result's index, -1 for a click on the strip that changes nothing, -2 off it. */
    int click(double mx, double my) {
        if (!over(mx, my)) return -2;
        int i = at(mx, my);
        if (i < 0 || i == selected) return -1;
        selected = i;
        Scene.sound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.25f, 1.4f);
        return i;
    }

    /** Tells the server which of the competing recipes to use at the station. */
    static void send(net.minecraft.core.BlockPos pos, dev.dyslexic.cinematic.station.StationKind kind, int index) {
        PacketDistributor.sendToServer(new StationActionPayload(pos, kind, StationActionPayload.Action.CHOOSE, index, 0, false, 0));
    }

    @Nullable
    List<Component> tooltip(double mx, double my) {
        int i = at(mx, my);
        if (i < 0) return null;
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), outputs.get(i)));
        lines.add(Component.translatable(i == selected ? "cinematic.choices.in_use" : "cinematic.choices.pick")
                .withStyle(i == selected ? net.minecraft.ChatFormatting.AQUA : net.minecraft.ChatFormatting.GRAY));
        return lines;
    }
}
