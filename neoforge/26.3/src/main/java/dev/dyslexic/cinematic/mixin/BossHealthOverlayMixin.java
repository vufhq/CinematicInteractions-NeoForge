package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.Scene;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boss bars are one more HUD layer a scene hides (as on NeoForge, where every layer but chat and the like is). */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void cinematic$hide(CallbackInfo ci) { if (Scene.active()) ci.cancel(); }
}
