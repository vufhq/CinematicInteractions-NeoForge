package dev.dyslexic.cinematic.client;

import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Says why a scene cannot be played where the player stands, when the height logic is off ({@link Settings#heightLogic})
 * and no window opens in its place: one short line ("Too high", "In the way: Stone Slab") on a small glass strip above
 * the hotbar, where the game's own action bar messages appear. It fades in, stays a moment and fades out.
 */
public final class ReachNotice {
    private ReachNotice() {}

    private static final double FADE_IN = 0.12, HOLD = 1.9, FADE_OUT = 0.4;
    /** Below this alpha the HUD text is not drawn (Font.ALPHA_CUTOFF, which 1.21.6 dropped). */
    private static final int TEXT_ALPHA_CUTOFF = 8;

    /** Above the hotbar, the hearts and the armour, like the vanilla action bar. */
    private static final int FROM_BOTTOM = 70;

    @Nullable
    private static Component line;
    private static double shownAt = -100;

    /** @param blocker what is in the way, for {@link Reach.Reason#COVERED} */
    public static void show(Reach.Reason reason, @Nullable Component blocker) {
        String key = "cinematic.notice." + reason.name().toLowerCase(Locale.ROOT);
        line = reason == Reach.Reason.COVERED && blocker != null ? Component.translatable(key, blocker) : Component.translatable(key);
        shownAt = Scene.clock();
    }

    /** What the notice says right now, or null when it is not up (for the automated walkthrough). */
    @Nullable
    public static String text() {
        return alpha() > 0 && line != null ? line.getString() : null;
    }

    private static float alpha() {
        double age = Scene.clock() - shownAt;
        if (age < 0 || age > FADE_IN + HOLD + FADE_OUT) {
            return 0;
        }
        double k = age < FADE_IN ? age / FADE_IN : age > FADE_IN + HOLD ? 1 - (age - FADE_IN - HOLD) / FADE_OUT : 1;
        return (float) Anim.smoother(k);
    }

    /** Every frame, over the HUD. */
    public static void render(GuiGraphicsExtractor g) {
        float alpha = alpha();
        // below the HUD text cutoff the font would draw the text opaque again
        if (line == null || Math.round(alpha * 255) < TEXT_ALPHA_CUTOFF) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int textW = font.width(line);
        int w = textW + 17;
        int h = 13;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() - FROM_BOTTOM + Math.round((1 - alpha) * 3);
        g.fill(x, y, x + w, y + h, argb(0.5f * alpha, 12, 12, 16));
        g.fill(x, y + h - 1, x + w, y + h, argb(0.45f * alpha, 255, 150, 110));
        // a small warm mark before the words
        g.fill(x + 5, y + 5, x + 8, y + 8, argb(0.9f * alpha, 255, 170, 120));
        g.text(font, line, x + 12, y + 3, argb(alpha, 242, 234, 216), false);
    }

    private static int argb(float a, int r, int g, int b) {
        return Math.max(0, Math.min(255, Math.round(a * 255))) << 24 | r << 16 | g << 8 | b;
    }
}
