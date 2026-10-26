package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.KneeLayer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** KneeLayer submits the raised leg's armor through Minecraft's equipment renderer. */
@Mixin(HumanoidArmorLayer.class)
public abstract class CampfireArmorMixin {
    @Inject(method = "getArmorModel", at = @At("RETURN"))
    private void cinematic$hideStraightArmor(HumanoidRenderState state, EquipmentSlot slot,
            CallbackInfoReturnable<HumanoidModel<?>> cir) {
        if ((slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET)
                && state instanceof AvatarRenderState player) {
            cir.getReturnValue().rightLeg.visible = !KneeLayer.sitting(player.id);
        }
    }
}
