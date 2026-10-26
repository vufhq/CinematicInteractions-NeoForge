package dev.dyslexic.cinematic.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/** One pixel silhouette, drawn at the same size in every station's editor button. */
final class EditorIcon {
    private EditorIcon() {}
    private static final String[] GEAR = {
        ".....####.....", ".....####.....", "..##.####.##..", "..##########..",
        "...########...", "##############", "#####....#####", "#####....#####",
        "##############", "...########...", "..##########..", "..##.####.##..",
        ".....####.....", ".....####....."
    };
    static void draw(GuiGraphicsExtractor g, float alpha) {
        int color = Mth.clamp(Math.round(alpha * 230), 0, 255) << 24 | 0x67D3E7;
        for (int y = 0; y < GEAR.length; y++) for (int x = 0; x < GEAR[y].length(); x++)
            if (GEAR[y].charAt(x) == '#') g.fill(8 + x, 7 + y, 9 + x, 8 + y, color);
    }
}
