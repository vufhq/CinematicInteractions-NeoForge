package dev.dyslexic.cinematic.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** In client-only mode, send a vanilla Shift-click only if a station slot can accept the stack. */
final class QuickMoves {
    private QuickMoves() {}

    static void sendVanilla(Minecraft mc, AbstractContainerMenu menu, int index) {
        if (index < 0 || index >= menu.slots.size() || mc.gameMode == null || mc.player == null) return;
        ItemStack stack = menu.getSlot(index).getItem();
        if (stack.isEmpty()) return;
        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory() || !slot.mayPlace(stack)) continue;
            ItemStack there = slot.getItem();
            if (!there.isEmpty() && (!ItemStack.isSameItemSameComponents(there, stack)
                    || there.getCount() >= slot.getMaxStackSize(stack))) continue;
            mc.gameMode.handleContainerInput(menu.containerId, index, 0, ContainerInput.QUICK_MOVE, mc.player);
            return;
        }
    }
}
