package dev.dyslexic.cinematic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.client.PropLayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A smith swinging the hammer at an anvil holds nothing else in that hand, as others see them ({@code PropLayer}). */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void cinematic$hammerInHand(ArmedEntityRenderState entity, ItemStackRenderState item, ItemStack stack,
            HumanoidArm arm, PoseStack pose, SubmitNodeCollector features, int light, CallbackInfo ci) {
        if (arm == HumanoidArm.RIGHT && (entity instanceof AvatarRenderState player ? PropLayer.hammer(player.id) : 0) > 0.001) {
            ci.cancel();
        }
    }
}
