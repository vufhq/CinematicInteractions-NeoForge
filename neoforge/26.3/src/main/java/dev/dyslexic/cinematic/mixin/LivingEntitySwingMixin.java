package dev.dyslexic.cinematic.mixin;
import dev.dyslexic.cinematic.ClientBridge;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LivingEntity.class)
abstract class LivingEntitySwingMixin {
    @Inject(method="swing", at=@At("HEAD"), cancellable=true)
    private void cinematic$noSwing(CallbackInfoReturnable<Boolean> ci) {
        if ((Object) this instanceof LocalPlayer && ClientBridge.swallowSwing) {
            ClientBridge.swallowSwing = false;
            ci.setReturnValue(false);
        }
    }
}
