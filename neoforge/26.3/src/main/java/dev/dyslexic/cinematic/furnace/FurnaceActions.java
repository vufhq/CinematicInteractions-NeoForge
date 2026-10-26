package dev.dyslexic.cinematic.furnace;

import dev.dyslexic.cinematic.net.FurnaceStatePayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.Sessions.Session;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import dev.dyslexic.cinematic.net.PacketDistributor;

/**
 * What the hand does at a furnace front: feeds the fire box, loads the upper opening, takes out what is done.
 * Slot rules are the furnace's own, and taking results pays out experience exactly like the result slot.
 */
public final class FurnaceActions {
    private FurnaceActions() {}

    private enum Outcome { NONE, PLACED, TAKEN, REFUSED }

    public static void handle(ServerPlayer player, ServerLevel level, Session session, StationActionPayload action) {
        if (action.action() != StationActionPayload.Action.CLICK || action.index() < 0 || action.index() > 2) {
            return;
        }
        if (!(level.getBlockEntity(session.pos()) instanceof AbstractFurnaceBlockEntity furnace)) {
            return;
        }
        Outcome outcome = action.index() == FurnaceZones.RESULT
                ? takeResult(player, level, furnace, action.button(), action.shift())
                : load(player, furnace, action.index(), action.button(), action.shift());
        if (outcome == Outcome.NONE) {
            return;
        }
        BlockPos pos = session.pos();
        if (outcome != Outcome.REFUSED) {
            furnace.setChanged();
            FurnaceWatch.sync(level, pos, furnace);
            sendState(player, session);
        }
        player.inventoryMenu.broadcastChanges();
        float pitch = 0.9f + level.getRandom().nextFloat() * 0.2f;
        switch (outcome) {
            case PLACED -> level.playSound(null, pos, action.index() == FurnaceZones.FUEL ? SoundEvents.STONE_HIT : SoundEvents.DEEPSLATE_HIT,
                    SoundSource.PLAYERS, 0.4f, pitch * 1.2f);
            case TAKEN -> level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f, pitch * 1.4f);
            default -> level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_OFF, SoundSource.PLAYERS, 0.3f, 0.6f);
        }
    }

    /** The upper opening (slot 0) or the fire box (slot 1), with the same clicks as an inventory slot. */
    private static Outcome load(ServerPlayer player, AbstractFurnaceBlockEntity furnace, int slot, int button, boolean shift) {
        AbstractContainerMenu menu = player.inventoryMenu;
        ItemStack carried = menu.getCarried();
        ItemStack inside = furnace.getItem(slot);
        Outcome outcome = Outcome.NONE;
        if (shift && button == 0) {
            if (!inside.isEmpty()) {
                ItemStack moving = inside.copy();
                player.getInventory().add(moving);
                if (moving.getCount() != inside.getCount()) {
                    furnace.setItem(slot, moving.isEmpty() ? ItemStack.EMPTY : moving);
                    outcome = Outcome.TAKEN;
                }
            }
        } else if (carried.isEmpty()) {
            if (!inside.isEmpty()) {
                int take = button == 0 ? inside.getCount() : (inside.getCount() + 1) / 2;
                menu.setCarried(furnace.removeItem(slot, take));
                outcome = Outcome.TAKEN;
            }
        } else if (!furnace.canPlaceItem(slot, carried)) {
            outcome = Outcome.REFUSED;
        } else if (inside.isEmpty()) {
            int count = button == 0 ? Math.min(carried.getCount(), Math.min(furnace.getMaxStackSize(), carried.getMaxStackSize())) : 1;
            furnace.setItem(slot, carried.split(count));
            outcome = Outcome.PLACED;
        } else if (ItemStack.isSameItemSameComponents(carried, inside)) {
            int room = Math.min(furnace.getMaxStackSize(), inside.getMaxStackSize()) - inside.getCount();
            int move = Math.min(button == 0 ? carried.getCount() : 1, room);
            if (move > 0) {
                inside.grow(move);
                carried.shrink(move);
                outcome = Outcome.PLACED;
            }
        } else {
            furnace.setItem(slot, carried);
            menu.setCarried(inside);
            outcome = Outcome.PLACED;
        }
        if (menu.getCarried().isEmpty()) {
            menu.setCarried(ItemStack.EMPTY);
        }
        return outcome;
    }

    /** Shift-click a stack into the cooking or fuel slot using the furnace's own placement rules. */
    public static void quickMove(ServerPlayer player, ServerLevel level, Session session, int inventorySlot) {
        if (!(level.getBlockEntity(session.pos()) instanceof AbstractFurnaceBlockEntity furnace)) return;
        ItemStack source = player.getInventory().getItem(inventorySlot);
        var recipes = level.recipeAccess().propertySet(switch (session.kind()) {
            case SMOKER -> net.minecraft.world.item.crafting.RecipePropertySet.SMOKER_INPUT;
            case BLAST_FURNACE -> net.minecraft.world.item.crafting.RecipePropertySet.BLAST_FURNACE_INPUT;
            default -> net.minecraft.world.item.crafting.RecipePropertySet.FURNACE_INPUT;
        });
        int slot = recipes.test(source) ? FurnaceZones.INPUT
                : source.has(net.minecraft.core.component.DataComponents.COOKING_FUEL) ? FurnaceZones.FUEL : -1;
        BlockPos pos = session.pos();
        if (slot < 0 || !furnace.canPlaceItem(slot, source)) {
            level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_OFF, SoundSource.PLAYERS, 0.3f, 0.6f);
            return;
        }
        ItemStack inside = furnace.getItem(slot);
        int limit = Math.min(furnace.getMaxStackSize(source), source.getMaxStackSize());
        int move;
        if (inside.isEmpty()) {
            move = Math.min(source.getCount(), limit);
            furnace.setItem(slot, source.split(move));
        } else if (ItemStack.isSameItemSameComponents(source, inside)) {
            move = Math.max(0, Math.min(source.getCount(), Math.min(limit, inside.getMaxStackSize()) - inside.getCount()));
            inside.grow(move);
            source.shrink(move);
        } else move = 0;
        if (move <= 0) {
            level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_OFF, SoundSource.PLAYERS, 0.3f, 0.6f);
            return;
        }
        player.getInventory().setChanged();
        furnace.setChanged();
        FurnaceWatch.sync(level, pos, furnace);
        sendState(player, session);
        player.inventoryMenu.broadcastChanges();
        level.playSound(null, pos, slot == FurnaceZones.FUEL ? SoundEvents.STONE_HIT : SoundEvents.DEEPSLATE_HIT,
                SoundSource.PLAYERS, 0.4f, 1.1f);
    }

    /** Taking smelted items: into the hand, or with shift straight into the inventory. */
    private static Outcome takeResult(ServerPlayer player, ServerLevel level, AbstractFurnaceBlockEntity furnace, int button, boolean shift) {
        AbstractContainerMenu menu = player.inventoryMenu;
        ItemStack carried = menu.getCarried();
        ItemStack inside = furnace.getItem(FurnaceZones.RESULT);
        if (inside.isEmpty()) {
            return carried.isEmpty() ? Outcome.NONE : Outcome.REFUSED;
        }
        ItemStack taken;
        if (shift) {
            ItemStack moving = inside.copy();
            player.getInventory().add(moving);
            int moved = inside.getCount() - moving.getCount();
            if (moved <= 0) {
                return Outcome.NONE;
            }
            taken = furnace.removeItem(FurnaceZones.RESULT, moved);
        } else if (carried.isEmpty()) {
            taken = furnace.removeItem(FurnaceZones.RESULT, button == 0 ? inside.getCount() : (inside.getCount() + 1) / 2);
            menu.setCarried(taken.copy());
        } else if (ItemStack.isSameItemSameComponents(carried, inside)) {
            int move = Math.min(inside.getCount(), carried.getMaxStackSize() - carried.getCount());
            if (move <= 0) {
                return Outcome.REFUSED;
            }
            taken = furnace.removeItem(FurnaceZones.RESULT, move);
            carried.grow(move);
        } else {
            return Outcome.REFUSED;
        }
        // what the vanilla result slot does when a player takes from it
        taken.onCraftedBy(player, taken.getCount());
        furnace.awardUsedRecipesAndPopExperience(player);
        return Outcome.TAKEN;
    }

    public static void sendState(ServerPlayer player, Session session) {
        if (player.level().getBlockEntity(session.pos()) instanceof AbstractFurnaceBlockEntity furnace) {
            PacketDistributor.sendToPlayer(player, new FurnaceStatePayload(session.pos(), furnace.litTimeRemaining, furnace.litTotalTime,
                    furnace.cookingTimer, furnace.cookingTotalTime));
        }
    }
}
