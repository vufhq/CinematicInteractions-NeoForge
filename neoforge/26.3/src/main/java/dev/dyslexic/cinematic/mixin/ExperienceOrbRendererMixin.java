package dev.dyslexic.cinematic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.client.Scene;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ExperienceOrbRenderer;
import net.minecraft.client.renderer.entity.state.ExperienceOrbRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Experience from a grindstone flies from the block straight into the player's eyes, which in a scene are right
 * behind the camera: the last stretch of that flight would fill the frame with a green blot. During a scene an
 * orb that close to the camera is not drawn; it is still picked up as usual.
 */
@Mixin(ExperienceOrbRenderer.class)
public abstract class ExperienceOrbRendererMixin {
    private static final double HIDE_WITHIN_SQR = 1.0;

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ExperienceOrbRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void cinematic$notInTheLens(ExperienceOrbRenderState orb, PoseStack pose, SubmitNodeCollector features, CameraRenderState camera,
            CallbackInfo ci) {
        if (Scene.active() && new Vec3(orb.x, orb.y, orb.z).distanceToSqr(Minecraft.getInstance().gameRenderer.mainCamera().position())
                < HIDE_WITHIN_SQR) {
            ci.cancel();
        }
    }
}
