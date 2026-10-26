package dev.dyslexic.cinematic.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.client.ClientPayloads;
import dev.dyslexic.cinematic.client.KneeLayer;
import dev.dyslexic.cinematic.client.PropLayer;
import dev.dyslexic.cinematic.station.StationKind;
import net.minecraft.client.player.AbstractClientPlayer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class PlayerRendererMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void cinematic$layer(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        ((AvatarRenderer<?>) (Object) this).addLayer(new PropLayer((AvatarRenderer<?>) (Object) this));
        ((AvatarRenderer<?>) (Object) this).addLayer(new KneeLayer((AvatarRenderer<?>) (Object) this,
                context.getEquipmentRenderer()));
    }

    @Inject(method = "getRenderOffset(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
    private void cinematic$sit(net.minecraft.client.renderer.entity.state.AvatarRenderState player, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        if (ClientPayloads.atStation(player.id) == StationKind.CAMPFIRE) cir.setReturnValue(cir.getReturnValue().add(0, -0.62, 0));
    }
}
