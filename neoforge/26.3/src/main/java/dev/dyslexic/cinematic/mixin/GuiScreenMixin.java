package dev.dyslexic.cinematic.mixin;
import dev.dyslexic.cinematic.client.CinematicClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Gui.class)
abstract class GuiScreenMixin {
    @Inject(method="setScreen", at=@At("HEAD"), cancellable=true)
    private void cinematic$screen(Screen next, CallbackInfo ci) {
        if (CinematicClient.screenOpening(next)) ci.cancel();
    }
}
