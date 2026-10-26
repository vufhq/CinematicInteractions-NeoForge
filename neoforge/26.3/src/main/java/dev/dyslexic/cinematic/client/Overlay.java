package dev.dyslexic.cinematic.client;

import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;


/**
 * Hides the HUD during the scene, draws thin letterbox bars while the camera travels and the station's flashes (both can
 * be turned off in the settings), and over the HUD the campfire invitation and the notice of a scene that cannot be played.
 */
public final class Overlay {
    private Overlay() {}

    /** 0..1: how far the bars are in. */
    private static double bars() {
        return switch (Scene.phase) {
            case INTRO -> {
                if (Scene.transition || Scene.quick) {
                    // moving over to a neighbouring station, or straight back to this one: no bars
                    yield 0;
                }
                double u = Scene.introProgress();
                yield Anim.inOutSine(Anim.window(u, 0, 0.3)) * (1 - Anim.inOutSine(Anim.window(u, 0.72, 1.0)));
            }
            case OUTRO -> {
                double u = Scene.time / Scene.OUTRO_LENGTH;
                yield Anim.inOutSine(Anim.window(u, 0, 0.25)) * (1 - Anim.inOutSine(Anim.window(u, 0.7, 1.0)));
            }
            // looking round from behind: thin bars, like a film
            case ACTIVE -> 0.55 * FreeLook.amount();
            default -> 0;
        };
    }

    public static void render(GuiGraphicsExtractor graphics) {
        CampfireHint.render(graphics);
        ReachNotice.render(graphics);
        ClientStation station = Scene.active() ? Scene.station : null;
        if (station != null && station.flash() > 0.003 && Settings.screenFlash()) {
            int a = (int) (Math.min(1, station.flash()) * 255);
            graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), a << 24 | station.flashColor());
        }
        double k = Settings.letterbox() ? bars() : 0;
        if (k <= 0.001) {
            return;
        }
        int h = (int) Math.round(graphics.guiHeight() * 0.085 * k);
        graphics.fill(0, 0, graphics.guiWidth(), h, 0xFF000000);
        graphics.fill(0, graphics.guiHeight() - h, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000);
    }
}
