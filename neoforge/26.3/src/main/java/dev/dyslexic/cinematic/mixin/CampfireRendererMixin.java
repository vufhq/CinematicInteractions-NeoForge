package dev.dyslexic.cinematic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.client.StationDisplayLayouts;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.blockentity.CampfireRenderer;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CampfireRenderer.class)
public abstract class CampfireRendererMixin {
    @Inject(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/CampfireRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void cinematic$layout(net.minecraft.client.renderer.blockentity.state.CampfireRenderState fire, PoseStack pose,
            net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera, CallbackInfo ci) {
        if (StationDisplayLayouts.campfire(fire, pose, collector)) ci.cancel();
    }
}
