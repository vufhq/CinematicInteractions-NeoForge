package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.furnace.FurnaceWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin implements FurnaceWatch.Fingerprinted {
    @Unique private int cinematic$fingerprint;
    @Override public int cinematic$fingerprint() { return cinematic$fingerprint; }
    @Override public void cinematic$setFingerprint(int value) { cinematic$fingerprint = value; }
    @Inject(method = "serverTick", at = @At("RETURN"))
    private static void cinematic$watch(ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity furnace, CallbackInfo ci) {
        FurnaceWatch.afterTick(level, pos, state, furnace);
    }
}
