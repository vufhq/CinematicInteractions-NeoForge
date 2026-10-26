package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.CameraRig;
import dev.dyslexic.cinematic.client.Scene;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the workbench scene place the camera after vanilla has set it up at the player's eyes. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow @Final private Vector3f up;
    @Shadow @Final private Vector3f left;

    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void cinematic$scene(float partialTick,
            CallbackInfo ci) {
        Camera self = (Camera) (Object) this;
        // Keep the same interpolation as the original: the scene steers the body per tick, but the camera moves per frame.
        float yaw = self.yRot();
        float pitch = self.xRot();
        if (Scene.active() && net.minecraft.client.Minecraft.getInstance().getCameraEntity() instanceof LocalPlayer player && !player.isPassenger()) {
            yaw = Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
            pitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
        }
        CameraRig.Pose pose = CameraRig.override(self.position(), yaw, pitch);
        if (pose != null) {
            setRotation(pose.yaw(), pose.pitch());
            self.rotation().rotateZ((float) Math.toRadians(-pose.roll()));
            up.set(0, 1, 0).rotate(self.rotation());
            left.set(-1, 0, 0).rotate(self.rotation());
            setPosition(pose.pos());
        }
    }
    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void cinematic$fov(float tick, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        if (Scene.active()) cir.setReturnValue((float) CameraRig.fov(cir.getReturnValue()));
    }
}
