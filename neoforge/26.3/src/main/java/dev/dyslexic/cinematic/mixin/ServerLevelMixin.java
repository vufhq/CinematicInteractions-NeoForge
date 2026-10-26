package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.station.StationEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where NeoForge fires its NeighborNotifyEvent: a block at {@code pos} changed and tells its neighbours. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", at = @At("HEAD"))
    private void cinematic$neighbours(BlockPos pos, Block block, CallbackInfo ci) {
        StationEvents.neighbourNotify((ServerLevel) (Object) this, pos);
    }
}
