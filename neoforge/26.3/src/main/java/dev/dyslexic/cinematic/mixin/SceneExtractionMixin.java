package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.SceneRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class SceneExtractionMixin {
    @Inject(method = "extract", at = @At("HEAD"))
    private void cinematic$begin(DeltaTracker tracker, boolean advance, CallbackInfo ci) {
        SceneRenderer.beginExtraction();
    }
    @Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V"))
    private void cinematic$extract(DeltaTracker tracker, boolean advance, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isGameLoadFinished() && advance && mc.level != null) SceneRenderer.extract();
    }
}
