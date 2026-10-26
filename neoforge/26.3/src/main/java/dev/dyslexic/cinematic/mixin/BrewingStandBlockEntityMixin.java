package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.brewing.BrewingWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches what stands in a brewing stand, so its bottles can be drawn as the real potions. It adds no methods to the
 * block entity beyond its own fingerprint: the contents travel in the mod's own packet ({@link BrewingWatch}), and
 * other mods may give the stand an update packet of their own.
 */
@Mixin(BrewingStandBlockEntity.class)
public abstract class BrewingStandBlockEntityMixin extends BaseContainerBlockEntity implements BrewingWatch.Fingerprinted {
    @Unique
    private int cinematic$fingerprint;

    protected BrewingStandBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public int cinematic$fingerprint() {
        return cinematic$fingerprint;
    }

    @Override
    public void cinematic$setFingerprint(int fingerprint) {
        cinematic$fingerprint = fingerprint;
    }

    @Inject(method = "serverTick", at = @At("RETURN"))
    private static void cinematic$watch(ServerLevel level, BlockPos pos, BlockState state, BrewingStandBlockEntity stand, CallbackInfo ci) {
        BrewingWatch.afterTick(level, pos, state, stand);
    }
}
