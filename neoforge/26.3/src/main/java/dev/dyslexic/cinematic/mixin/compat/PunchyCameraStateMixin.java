package dev.dyslexic.cinematic.mixin.compat;

import dev.dyslexic.cinematic.client.Scene;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A skipped hand pass leaves Punchy's last camera offset cached; scenes must not inherit that offset. */
@Pseudo
@Mixin(targets = "punchy.client.render.CameraAnimationState", remap = false)
public abstract class PunchyCameraStateMixin {
    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true, require = 0)
    private static void cinematic$ownCameraDuringScene(CallbackInfoReturnable<Boolean> cir) {
        if (Scene.active()) cir.setReturnValue(false);
    }
}
