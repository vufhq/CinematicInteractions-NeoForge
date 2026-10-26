package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.Showcase;
import dev.dyslexic.cinematic.client.ClientOnlySmoke;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "init(II)V", at = @At("TAIL"))
    private void cinematic$initialized(int width, int height, CallbackInfo ci) {
        Showcase.onScreen((Screen) (Object) this);
        ClientOnlySmoke.onScreen((Screen) (Object) this);
    }
}
