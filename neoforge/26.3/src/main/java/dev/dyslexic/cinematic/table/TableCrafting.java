package dev.dyslexic.cinematic.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;

/** Reads the table top as a 3x3 crafting grid from the player's side and performs crafts like the vanilla result slot. */
public final class TableCrafting {
    private TableCrafting() {}

    public record Match(RecipeHolder<CraftingRecipe> recipe, CraftingInput.Positioned input, ItemStack result) {}

    /** The recipe the table currently spells for a player on {@code side}; the result is a fresh copy. */
    public static Optional<Match> match(Level level, List<ItemStack> world, Direction side) {
        return match(level, world, side, null);
    }

    public static Optional<Match> match(Level level, List<ItemStack> world, Direction side,
            @javax.annotation.Nullable net.minecraft.resources.Identifier chosen) {
        CraftingInput.Positioned positioned = CraftingInput.ofPositioned(3, 3, TableGrid.oriented(world, side));
        CraftingInput input = positioned.input();
        if (input.isEmpty()) {
            return Optional.empty();
        }
        Optional<RecipeHolder<CraftingRecipe>> found = Optional.empty();
        if (chosen != null && level instanceof ServerLevel) {
            found = dev.dyslexic.cinematic.station.RecipeChoices.matching(level, RecipeType.CRAFTING, input).stream()
                    .filter(holder -> holder.id().identifier().equals(chosen)).findFirst();
        }
        if (found.isEmpty()) found = level instanceof ServerLevel server
                ? server.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level)
                : dev.dyslexic.cinematic.client.ClientRecipes.match(input, level);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ItemStack result = found.get().value().assemble(input);
        if (result.isEmpty() || !result.isItemEnabled(level.enabledFeatures())) {
            return Optional.empty();
        }
        return Optional.of(new Match(found.get(), positioned, result));
    }

    /**
     * Crafts once. The result goes into the cursor stack, or into the inventory when {@code toInventory}.
     * Returns the consumed ingredients (for particles), or null when nothing was crafted.
     */
    public static List<ItemStack> craftOnce(ServerPlayer player, ServerLevel level, TableStore.Table table, Direction side,
            boolean toInventory) {
        Optional<Match> found = match(level, table.items, side, dev.dyslexic.cinematic.station.RecipeChoices.chosen(player));
        if (found.isEmpty()) {
            return null;
        }
        Match match = found.get();
        RecipeHolder<CraftingRecipe> recipe = match.recipe();
        if (!recipe.value().isSpecial() && level.getGameRules().get(GameRules.LIMITED_CRAFTING)
                && !player.getRecipeBook().contains(recipe.id())) {
            return null;
        }
        ItemStack result = match.result();
        ItemStack carried = player.containerMenu.getCarried();
        if (toInventory) {
            if (!canFit(player.getInventory(), result)) {
                return null;
            }
        } else if (!carried.isEmpty() && !(ItemStack.isSameItemSameComponents(carried, result)
                && carried.getCount() + result.getCount() <= carried.getMaxStackSize())) {
            return null;
        }

        List<ItemStack> grid = TableGrid.oriented(table.items, side);
        List<ItemStack> snapshot = new ArrayList<>(grid.size());
        for (ItemStack stack : grid) {
            snapshot.add(stack.copy());
        }
        result.onCraftedBy(player, result.getCount());
        player.triggerRecipeCrafted(recipe, snapshot);
        if (!recipe.value().isSpecial()) {
            player.awardRecipes(Collections.singleton(recipe));
        }

        CraftingInput input = match.input().input();
        NonNullList<ItemStack> remaining = recipe.value().getRemainingItems(input);

        List<ItemStack> consumed = new ArrayList<>();
        for (int row = 0; row < input.height(); row++) {
            for (int col = 0; col < input.width(); col++) {
                int world = TableGrid.worldIndex(side, row + match.input().top(), col + match.input().left());
                ItemStack stack = table.items.get(world);
                ItemStack leftover = remaining.get(col + row * input.width());
                if (!stack.isEmpty()) {
                    consumed.add(stack.copyWithCount(1));
                    stack.shrink(1);
                    if (stack.isEmpty()) {
                        table.items.set(world, ItemStack.EMPTY);
                    }
                    stack = table.items.get(world);
                }
                if (!leftover.isEmpty()) {
                    if (stack.isEmpty()) {
                        table.items.set(world, leftover);
                    } else if (ItemStack.isSameItemSameComponents(stack, leftover)) {
                        leftover.grow(stack.getCount());
                        table.items.set(world, leftover);
                    } else if (!player.getInventory().add(leftover)) {
                        player.drop(leftover, false, net.minecraft.util.Prediction.SERVER_ONLY);
                    }
                }
            }
        }

        if (toInventory) {
            if (!player.getInventory().add(result)) {
                player.drop(result, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
        } else if (carried.isEmpty()) {
            player.containerMenu.setCarried(result);
        } else {
            carried.grow(result.getCount());
        }
        return consumed;
    }

    private static boolean canFit(Inventory inventory, ItemStack stack) {
        return inventory.getSlotWithRemainingSpace(stack) != -1 || inventory.getFreeSlot() != -1;
    }
}
