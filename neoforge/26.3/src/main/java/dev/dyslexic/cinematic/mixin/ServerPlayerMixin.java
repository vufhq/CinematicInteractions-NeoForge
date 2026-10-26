package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.table.TableActions;
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.CraftingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    /** Any crafting window, however it was opened (our fallback, a sneaking use, another mod): what lies on the table goes into it. */
    // NeoForge routes openMenu(MenuProvider) through this overload, so every window passes here once.
    @Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;", at = @At("RETURN"))
    private void cinematic$openedMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> extraData, CallbackInfoReturnable<OptionalInt> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (cir.getReturnValue().isPresent() && self.containerMenu instanceof CraftingMenu menu) {
            TableActions.intoWindow(self, menu);
        }
    }
}
