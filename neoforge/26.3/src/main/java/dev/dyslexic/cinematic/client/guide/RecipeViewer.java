package dev.dyslexic.cinematic.client.guide;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * What the field guide asks of an installed recipe viewer. Nothing here names the viewer's own classes, so the guide
 * and the scenes load and run without it; {@link RecipeViewers} hands out the one adapter that does.
 */
public interface RecipeViewer {
    /** Items whose names (in the player's language) or ids contain {@code query}, in the viewer's own order. */
    List<ItemStack> search(String query, int limit);

    /** What the recipes worked at this block make, as the viewer lists them for the block as a catalyst. */
    List<ItemStack> stationOutputs(ItemStack station);

    /** The recipes worked at this block that make {@code output}. */
    List<StationRecipe> stationRecipes(ItemStack station, ItemStack output);

    /** The viewer's own pages: every way to get the item, or everything it is used in. */
    void showHowToGet(ItemStack stack);

    void showUses(ItemStack stack);

    /** Just Enough Resources' pages that show where the item comes from, or none without JER. */
    List<SourcePage> sources(ItemStack stack);

    /** Just Enough Professions' page for the villager working at this block, if JEP is installed and has one. */
    @Nullable
    Runnable professionPage(ItemStack station);

    /** Whether a key is the viewer's "show recipe" or "show uses" key. */
    boolean isShowRecipeKey(int key, int scanCode);

    boolean isShowUsesKey(int key, int scanCode);

    /** Those two keys' names, as the player has them bound. */
    Component keyNames();

    /** One recipe of the block, drawn by the viewer itself. */
    interface StationRecipe {
        /** The category's name, as the viewer titles it. */
        Component category();

        /** The id of the game recipe behind it, when it is one (vanilla and most modded categories). */
        @Nullable
        Identifier recipeId();

        /** The first ingredient choice of each input, in the recipe's order. */
        List<ItemStack> inputs();

        int width();

        int height();

        void draw(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY);

        /** This recipe on the viewer's own page. */
        void open();
    }

    /** One of JER's categories with the entries that produce an item. */
    record SourcePage(Component title, int entries, Runnable open, Icon icon) {}

    /** A 16x16 icon the viewer draws. */
    interface Icon {
        void draw(GuiGraphicsExtractor graphics, int x, int y);
    }
}
