package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.Scene;
import dev.dyslexic.cinematic.client.Showcase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    /** Showcase drives MouseHandler coordinates itself; desktop movement must not overwrite its input. */
    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void cinematic$showcaseCursor(CallbackInfo ci) { if (Showcase.ENABLED) ci.cancel(); }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void cinematic$turn(CallbackInfo ci) { if (Scene.active()) ci.cancel(); }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void cinematic$scroll(CallbackInfo ci) {
        if (Scene.active() && Minecraft.getInstance().gui.screen() == null) ci.cancel();
    }
}
