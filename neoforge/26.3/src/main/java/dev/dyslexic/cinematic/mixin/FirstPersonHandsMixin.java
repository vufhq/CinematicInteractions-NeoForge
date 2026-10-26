package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.Scene;
import com.mojang.blaze3d.vertex.PoseStack;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;

/** Scene hands own the complete hand pass, including renderers injected before the vanilla body. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsMixin {
    @WrapMethod(method = "submitHandsWithItems")
    private void cinematic$sceneHands(float partialTick, PoseStack pose, SubmitNodeCollector collector,
            PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands, Operation<Void> original) {
        if (!Scene.active()) original.call(partialTick, pose, collector, player, hands);
    }
}
