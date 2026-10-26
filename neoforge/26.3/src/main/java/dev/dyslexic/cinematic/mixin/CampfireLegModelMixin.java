package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.KneeLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hide the straight player leg only while KneeLayer draws its two bent halves. */
@Mixin(value = HumanoidModel.class, priority = 900)
public abstract class CampfireLegModelMixin {
    @Shadow @Final public ModelPart rightLeg;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void cinematic$campfireLeg(HumanoidRenderState state, CallbackInfo ci) {
        if (state instanceof AvatarRenderState player) {
            rightLeg.visible = !KneeLayer.sitting(player.id);
        }
    }
}
