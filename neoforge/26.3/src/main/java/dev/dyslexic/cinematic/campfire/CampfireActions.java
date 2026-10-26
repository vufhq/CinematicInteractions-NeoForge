package dev.dyslexic.cinematic.campfire;

import dev.dyslexic.cinematic.net.CampfireProgressPayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.Sessions.Session;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.Containers;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Sitting by a campfire: food is put on a chosen spot of the fire, or taken off it. Putting on follows the
 * campfire's own rules (only what it can cook, the recipe's cooking time, one piece per spot). Taking off hands
 * the piece back as it is, raw or half done; left alone it pops off cooked as in vanilla. The item in the
 * player's hand is the cursor stack of their inventory menu, as at the other stations without a menu.
 */
public final class CampfireActions {
    private CampfireActions() {}

    public static final int SPOTS = 4;
    private static final WeakHashMap<CampfireBlockEntity, CookingWatch> WATCHES = new WeakHashMap<>();

    private static final class CookingWatch {
        final int[] previous = {-1, -1, -1, -1};
        final int[] stalled = new int[SPOTS];
        final RecipeManager.CachedCheck<SingleRecipeInput, CampfireCookingRecipe> recipes =
                RecipeManager.createCheck(RecipeType.CAMPFIRE_COOKING);
        long lastTick = Long.MIN_VALUE;

        void reset(int spot) {
            previous[spot] = -1;
            stalled[spot] = 0;
        }
    }

    public static void handle(ServerPlayer player, ServerLevel level, Session session, StationActionPayload action) {
        int spot = action.index();
        if (action.action() != StationActionPayload.Action.CLICK || spot < 0 || spot >= SPOTS
                || !(level.getBlockEntity(session.pos()) instanceof CampfireBlockEntity fire)) {
            return;
        }
        AbstractContainerMenu menu = player.inventoryMenu;
        ItemStack carried = menu.getCarried();
        ItemStack there = fire.getItems().get(spot);
        if (!carried.isEmpty()) {
            Optional<RecipeHolder<CampfireCookingRecipe>> recipe = level.recipeAccess().getRecipeFor(RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(carried), level);
            if (!there.isEmpty() || recipe.isEmpty()) {
                return;
            }
            fire.cookingTime[spot] = recipe.get().value().cookingTime();
            fire.cookingProgress[spot] = 0;
            fire.getItems().set(spot, carried.consumeAndReturn(1, player));
            level.gameEvent(GameEvent.BLOCK_CHANGE, session.pos(), GameEvent.Context.of(player, fire.getBlockState()));
            player.awardStat(Stats.INTERACT_WITH_CAMPFIRE);
        } else {
            if (there.isEmpty()) {
                return;
            }
            fire.getItems().set(spot, ItemStack.EMPTY);
            fire.cookingProgress[spot] = 0;
            menu.setCarried(there);
        }
        fire.setChanged();
        CookingWatch watch = WATCHES.get(fire);
        if (watch != null) watch.reset(spot);
        BlockState state = fire.getBlockState();
        level.sendBlockUpdated(session.pos(), state, state, 3);
        menu.broadcastChanges();
        sendProgress(player, session);
    }

    /** Shift-click cookable food onto free spots, one piece per spot. */
    public static void quickMove(ServerPlayer player, ServerLevel level, Session session, int inventorySlot) {
        if (!(level.getBlockEntity(session.pos()) instanceof CampfireBlockEntity fire)) return;
        ItemStack source = player.getInventory().getItem(inventorySlot);
        var recipe = level.recipeAccess().getRecipeFor(RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(source), level);
        if (recipe.isEmpty()) return;
        int placed = 0;
        for (int spot = 0; spot < SPOTS && !source.isEmpty(); spot++) {
            if (!fire.getItems().get(spot).isEmpty()) continue;
            fire.cookingTime[spot] = recipe.get().value().cookingTime();
            fire.cookingProgress[spot] = 0;
            fire.getItems().set(spot, source.split(1));
            CookingWatch watch = WATCHES.get(fire);
            if (watch != null) watch.reset(spot);
            placed++;
        }
        if (placed == 0) return;
        player.getInventory().setChanged();
        level.gameEvent(GameEvent.BLOCK_CHANGE, session.pos(), GameEvent.Context.of(player, fire.getBlockState()));
        player.awardStat(Stats.INTERACT_WITH_CAMPFIRE);
        fire.setChanged();
        BlockState state = fire.getBlockState();
        level.sendBlockUpdated(session.pos(), state, state, 3);
        player.inventoryMenu.broadcastChanges();
        sendProgress(player, session);
    }

    /** Campfire block updates contain food but omit the four cooking timers. */
    public static void sendProgress(ServerPlayer player, Session session) {
        if (!(player.level().getBlockEntity(session.pos()) instanceof CampfireBlockEntity fire)) return;
        int[] progress = new int[SPOTS], total = new int[SPOTS];
        for (int i = 0; i < SPOTS; i++) {
            if (!fire.getItems().get(i).isEmpty()) {
                progress[i] = fire.cookingProgress[i];
                total[i] = fire.cookingTime[i];
            }
        }
        dev.dyslexic.cinematic.net.PacketDistributor.sendToPlayer(player,
                new CampfireProgressPayload(session.pos(), fire.getBlockState().getValue(CampfireBlock.LIT), progress, total));
    }

    /**
     * A scene may leave food on a campfire whose timer or block-entity ticker was lost. Repair an absent recipe
     * timer, and if one occupied slot stops advancing on a lit fire, apply the vanilla cooking rules to that
     * slot. This is keyed to the block entity and game tick, so two seated players cannot cook twice.
     */
    public static void tick(ServerLevel level, Session session) {
        if (!(level.getBlockEntity(session.pos()) instanceof CampfireBlockEntity fire)) return;
        BlockState state = level.getBlockState(session.pos());
        if (!(state.getBlock() instanceof CampfireBlock) || !state.getValue(CampfireBlock.LIT)) return;
        CookingWatch watch = WATCHES.computeIfAbsent(fire, unused -> new CookingWatch());
        long now = level.getGameTime();
        if (watch.lastTick == now) return;
        boolean consecutive = watch.lastTick == now - 1;
        watch.lastTick = now;
        boolean changed = false;
        for (int i = 0; i < SPOTS; i++) {
            ItemStack item = fire.getItems().get(i);
            if (item.isEmpty()) {
                watch.reset(i);
                continue;
            }
            if (fire.cookingTime[i] <= 0) {
                var recipe = level.recipeAccess().getRecipeFor(RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(item), level);
                if (recipe.isPresent()) {
                    fire.cookingTime[i] = recipe.get().value().cookingTime();
                    fire.cookingProgress[i] = 0;
                    watch.reset(i);
                    changed = true;
                }
            }
            if (!consecutive || watch.previous[i] != fire.cookingProgress[i]) watch.stalled[i] = 0;
            else watch.stalled[i]++;
            watch.previous[i] = fire.cookingProgress[i];
            if (fire.cookingTime[i] > 0 && watch.stalled[i] >= 5) {
                advanceStalledSlot(level, session.pos(), state, fire, watch, i);
                watch.previous[i] = fire.cookingProgress[i];
                changed = true;
            }
        }
        if (changed) fire.setChanged();
    }

    private static void advanceStalledSlot(ServerLevel level, BlockPos pos, BlockState state,
                                           CampfireBlockEntity fire, CookingWatch watch, int spot) {
        ItemStack item = fire.getItems().get(spot);
        fire.cookingProgress[spot]++;
        if (fire.cookingProgress[spot] >= fire.cookingTime[spot]) {
            SingleRecipeInput input = new SingleRecipeInput(item);
            ItemStack result = watch.recipes.getRecipeFor(input, level)
                    .map(recipe -> recipe.value().assemble(input)).orElse(item);
            if (result.isItemEnabled(level.enabledFeatures())) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), result);
                fire.getItems().set(spot, ItemStack.EMPTY);
                level.sendBlockUpdated(pos, state, state, 3);
                level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(state));
                watch.reset(spot);
            }
        }
    }
}
