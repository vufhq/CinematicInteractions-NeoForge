package dev.dyslexic.cinematic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.client.LecternStation;
import dev.dyslexic.cinematic.client.StationDisplayLayouts;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.blockentity.LecternRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.LecternRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** While the player reads at a lectern, the scene draws the open book itself: the lectern's own book is left out. */
@Mixin(LecternRenderer.class)
public abstract class LecternRendererMixin {
    @WrapMethod(method = "submit(Lnet/minecraft/client/renderer/blockentity/state/LecternRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
    private void cinematic$readBook(LecternRenderState lectern, PoseStack pose, SubmitNodeCollector features,
            CameraRenderState camera, Operation<Void> original) {
        if (LecternStation.drawsBookAt(lectern.blockPos)) return;
        pose.pushPose();
        try {
            if (StationDisplayLayouts.customLectern()) StationDisplayLayouts.lectern(lectern, pose);
            original.call(lectern, pose, features, camera);
        } finally {
            pose.popPose();
        }
    }
}
