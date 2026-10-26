package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.enchant.EnchantActions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {
    @Inject(method = "clickMenuButton", at = @At("RETURN"))
    private void cinematic$enchanted(Player player, int button, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && player instanceof ServerPlayer serverPlayer) EnchantActions.onEnchant(serverPlayer);
    }
}
