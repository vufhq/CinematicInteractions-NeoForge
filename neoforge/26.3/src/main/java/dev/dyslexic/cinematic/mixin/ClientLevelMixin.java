package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.CinematicClient;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "disconnect", at = @At("HEAD"))
    private void cinematic$unload(CallbackInfo ci) { CinematicClient.clear(); }
}
