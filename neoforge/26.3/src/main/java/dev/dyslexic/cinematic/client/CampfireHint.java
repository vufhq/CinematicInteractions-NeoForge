package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dyslexic.cinematic.station.StationKind;
import javax.annotation.Nullable;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Using a campfire stays vanilla (food goes straight on). Looking at one shows a small invitation under the
 * crosshair, "[H] Sit by the fire"; that key, rebindable in the controls, sits the player down in the scene.
 */
public final class CampfireHint {
    private CampfireHint() {}

    public static final KeyMapping SIT = new KeyMapping("key.cinematic.sit", InputConstants.Type.KEYBOARD, InputConstants.KEY_H, CinematicKeys.CATEGORY);

    /** Below this alpha the HUD text is not drawn (Font.ALPHA_CUTOFF, which 1.21.6 dropped): the pill goes with it. */
    private static final int TEXT_ALPHA_CUTOFF = 8;
    /** How far the invitation is faded in. */
    private static double shown;
    @Nullable
    private static BlockPos checkedPos, checkedFrom;
    private static boolean checkedOk;

    /** The campfire under the crosshair, close enough to sit by, or null (also with its scene turned off). */
    @Nullable
    private static BlockPos looked() {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientOnly.serverHasMod() || Scene.active() || mc.gui.screen() != null || mc.player == null || mc.level == null || mc.player.isSpectator()
                || !Settings.scene(StationKind.CAMPFIRE) || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        if (!StationKind.CAMPFIRE.matches(mc.level.getBlockState(pos)) || mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) >= 4.5 * 4.5) {
            return null;
        }
        return pos;
    }

    /** The campfire under the crosshair that the player could sit by, or null. */
    @Nullable
    private static BlockPos target() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos pos = looked();
        if (pos == null || mc.player == null) {
            return null;
        }
        // only where the player could actually sit down by it (checked again when the player or the fire moves)
        if (!pos.equals(checkedPos) || !mc.player.blockPosition().equals(checkedFrom)) {
            checkedPos = pos.immutable();
            checkedFrom = mc.player.blockPosition();
            checkedOk = CampfireStation.sittable(mc.player, pos);
        }
        return checkedOk ? pos : null;
    }

    /**
     * Every client tick: the key starts the scene at the campfire looked at; where there is nowhere to sit, the scene
     * says why ({@link ReachNotice}).
     */
    public static void tick() {
        while (SIT.consumeClick()) {
            BlockPos pos = looked();
            if (pos != null) {
                Scene.begin(pos, StationKind.CAMPFIRE);
            }
        }
    }

    /**
     * Every frame, over the HUD: the invitation as a small glass pill under the crosshair, the key in a little cap,
     * fading and rising in while a campfire is looked at (over the fire itself its smoke would hide it).
     */
    public static void render(GuiGraphicsExtractor g) {
        boolean looking = Settings.campfireInvite() && target() != null;
        shown = Anim.approach(shown, looking ? 1 : 0, looking ? 9 : 12, Scene.frameDt);
        float alpha = (float) Anim.smoother(shown);
        // Font treats alpha 0..3 as an unspecified alpha and makes the text opaque again. Hide the whole pill
        // at the HUD text cutoff, using the eased opacity rather than the still-decaying animation progress.
        if (Math.round(alpha * 255) < TEXT_ALPHA_CUTOFF) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Component key = KeyLabels.of(SIT);
        Component text = Component.translatable("cinematic.campfire.sit");
        int keyW = Math.max(11, font.width(key) + 6);
        int w = 6 + keyW + 5 + font.width(text) + 7;
        int h = 15;
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() / 2 + 13 + Math.round((1 - alpha) * 5);
        g.fill(x, y, x + w, y + h, argb(0.5f * alpha, 12, 12, 16));
        g.fill(x, y + h - 1, x + w, y + h, argb(0.35f * alpha, 255, 196, 120));
        int kx = x + 6, ky = y + 2;
        g.fill(kx, ky, kx + keyW, ky + 11, argb(0.85f * alpha, 242, 234, 216));
        g.fill(kx + 1, ky + 1, kx + keyW - 1, ky + 10, argb(0.9f * alpha, 30, 28, 34));
        g.text(font, key, kx + (keyW - font.width(key)) / 2, ky + 2, argb(alpha, 255, 226, 150), false);
        g.text(font, text, kx + keyW + 5, y + 4, argb(alpha, 242, 234, 216), false);
    }

    private static int argb(float a, int r, int g, int b) {
        return Math.max(0, Math.min(255, Math.round(a * 255))) << 24 | r << 16 | g << 8 | b;
    }
}
