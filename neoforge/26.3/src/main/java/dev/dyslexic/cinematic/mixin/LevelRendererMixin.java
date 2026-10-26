package dev.dyslexic.cinematic.mixin;
import dev.dyslexic.cinematic.client.Scene;
import dev.dyslexic.cinematic.client.SceneRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private SubmitNodeStorage submitNodeStorage;
    @Inject(method="submitFeatures", at=@At("HEAD"))
    private void cinematic$outline(LevelRenderState state, SubmitNodeCollector collector, boolean outline, CallbackInfo ci) {
        if (Scene.active()) state.blockOutlineRenderState = null;
    }
    @Inject(method="submitFeatures", at=@At("TAIL"))
    private void cinematic$scene(LevelRenderState state, SubmitNodeCollector collector, boolean outline, CallbackInfo ci) {
        SceneRenderer.append(state, submitNodeStorage);
    }
}
