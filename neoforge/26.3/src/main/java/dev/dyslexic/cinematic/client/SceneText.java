package dev.dyslexic.cinematic.client;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
final class SceneText {
    private SceneText() {}
    static void draw(Font font, String text, float x, float y, int color, boolean shadow, Matrix4f matrix,
            SceneBuffers buffers, Font.DisplayMode mode, int background, int light) {
        draw(font, Component.literal(text).getVisualOrderText(), x, y, color, shadow, matrix, buffers, mode, background, light);
    }
    static void draw(Font font, Component text, float x, float y, int color, boolean shadow, Matrix4f matrix,
            SceneBuffers buffers, Font.DisplayMode mode, int background, int light) {
        draw(font, text.getVisualOrderText(), x, y, color, shadow, matrix, buffers, mode, background, light);
    }
    static void draw(Font font, FormattedCharSequence text, float x, float y, int color, boolean shadow, Matrix4f matrix,
            SceneBuffers buffers, Font.DisplayMode mode, int background, int light) {
        PoseStack pose = new PoseStack();
        pose.mulPose(matrix);
        SceneRenderer.collector().submitText(pose, x, y, text, shadow, mode, light, color, background, 0);
    }
}
