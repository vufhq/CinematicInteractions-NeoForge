package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.Cinematic;
import net.minecraft.client.Minecraft;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3f;
import org.joml.Quaternionf;

/** The owner's Blockbench hammer, baked and reloaded through Minecraft's normal model system. */
public final class HammerModel {
    private HammerModel() {}

    public static final net.minecraft.resources.Identifier LOCATION = Cinematic.id("prop/hammer");
    private static final float SCALE = 0.30f;
    private static final Vec3 GRIP = new Vec3(0.5, 0.18, 0.5);
    // The lower striking edge of the imported head, relative to the grip, at the -12 degree impact pose.
    static final double STRIKE_ALONG = (1.125 - GRIP.y) * SCALE;
    static final double STRIKE_ACROSS = 0.375 * SCALE;

    static net.minecraft.client.renderer.item.ItemStackRenderState model() {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
        stack.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, LOCATION);
        var state = new net.minecraft.client.renderer.item.ItemStackRenderState();
        var mc = Minecraft.getInstance();
        mc.getItemModelResolver().updateForTopItem(state, stack, net.minecraft.world.item.ItemDisplayContext.NONE, mc.level, null, 0);
        return state;
    }

    /** Seen from outside it is a little larger: the arm of the player model is straight, the hammer makes up the reach. */
    private static final float ARM_SCALE = SCALE * 1.3f;
    /**
     * In a player model's right hand, the handle runs on from the fist bent up by this much, so that with the arm swung
     * down at the anvil ({@code AnvilStation.armPitch}) the handle lies level and the head falls flat on the work.
     */
    private static final double WRIST = Math.toRadians(18);

    static void render(PoseStack pose, SceneBuffers buffers, Vec3 camera, Vec3 grip, Vec3 along, Vec3 headAxis, int light) {
        pose.pushPose();
        pose.translate(grip.x - camera.x, grip.y - camera.y, grip.z - camera.z);
        draw(pose, buffers, along, headAxis, SCALE, light);
        pose.popPose();
    }

    /**
     * In a player's right hand, as others see it: the pose stands in the frame of the model's arm, as held items are drawn.
     * A player's layers hand their models to the game's features since 1.21.9, so the hammer goes to them too.
     */
    static void renderInArm(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector features, double shown, int light) {
        pose.pushPose();
        // the fist, where held items sit
        pose.translate(-1 / 16f, 0.6f, -0.1f);
        orient(pose, new Vec3(0, Math.cos(WRIST), -Math.sin(WRIST)), new Vec3(0, -Math.sin(WRIST), -Math.cos(WRIST)), (float) (ARM_SCALE * shown));
        model().submit(pose, features, light, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
        pose.popPose();
    }

    /** The model at the pose's origin, gripped there: its handle along {@code along}, its striking face away from {@code headAxis}. */
    private static void draw(PoseStack pose, SceneBuffers buffers, Vec3 along, Vec3 headAxis, float scale, int light) {
        orient(pose, along, headAxis, scale);
        ItemModels.draw(model(), pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** Pushes a pose that puts the model's grip at the origin, turned and scaled (the caller pops it). */
    private static void orient(PoseStack pose, Vec3 along, Vec3 headAxis, float scale) {
        Vec3 z = headAxis.cross(along);
        Quaternionf rotation = new Matrix3f(
                (float) headAxis.x, (float) headAxis.y, (float) headAxis.z,
                (float) along.x, (float) along.y, (float) along.z,
                (float) z.x, (float) z.y, (float) z.z).getNormalizedRotation(new Quaternionf());
        pose.pushPose();
        pose.mulPose(new org.joml.Matrix4f().rotation(rotation));
        pose.scale(scale, scale, scale);
        pose.translate(-GRIP.x, -GRIP.y, -GRIP.z);
        // Item transforms now centre their geometry; preserve the imported model's original grip coordinates.
        pose.translate(0.5, 0.5, 0.5);
    }
}
