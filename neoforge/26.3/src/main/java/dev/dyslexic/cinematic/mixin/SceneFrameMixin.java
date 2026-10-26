package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.SceneFrameAccess;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelRenderState.class)
abstract class SceneFrameMixin implements SceneFrameAccess {
    @Unique private SubmitNodeStorage cinematic$scene;
    @Override public SubmitNodeStorage cinematic$getScene() { return cinematic$scene; }
    @Override public void cinematic$setScene(SubmitNodeStorage scene) { cinematic$scene = scene; }
}
