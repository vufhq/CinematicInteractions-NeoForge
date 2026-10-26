package dev.dyslexic.cinematic.client.compat.jei;

import com.mojang.blaze3d.platform.InputConstants;
import dev.dyslexic.cinematic.client.guide.RecipeViewer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * The field guide's view of JEI, through JEI's public API only. Just Enough Resources and Just Enough Professions are
 * reached the same way: by their JEI recipe categories, whose pages JEI itself opens. Nothing here gives, moves or
 * cheats in items, and JEI knows only the recipes the server sent this client.
 */
public final class JeiRecipeViewer implements RecipeViewer {
    /** JER's categories, by the ids JER registers them with (absent without JER). */
    private static final List<Identifier> SOURCE_TYPES = List.of(
            Identifier.fromNamespaceAndPath("jeresources", "worldgen"), Identifier.fromNamespaceAndPath("jeresources", "mob"),
            Identifier.fromNamespaceAndPath("jeresources", "plant"), Identifier.fromNamespaceAndPath("jeresources", "dungeon"),
            Identifier.fromNamespaceAndPath("jeresources", "villager"), Identifier.fromNamespaceAndPath("jeresources", "enchantment"));
    private static final Identifier PROFESSIONS = Identifier.fromNamespaceAndPath("justenoughprofessions", "professions");
    /** The mods whose catalyst links are pages of their own, not recipes worked at the block. */
    private static final List<String> NOT_STATION_RECIPES = List.of("jeresources", "justenoughprofessions");
    private static final int MAX_OUTPUTS = 4000;

    @Nullable
    private static JeiRecipeViewer instance;

    private final IJeiRuntime jei;
    private final Map<Item, List<ItemStack>> outputs = new HashMap<>();

    private JeiRecipeViewer(IJeiRuntime jei) {
        this.jei = jei;
    }

    static void available(@Nullable IJeiRuntime runtime) {
        instance = runtime == null ? null : new JeiRecipeViewer(runtime);
    }

    @Nullable
    public static RecipeViewer current() {
        return instance;
    }

    private IRecipeManager recipes() {
        return jei.getRecipeManager();
    }

    private IFocus<ItemStack> focus(RecipeIngredientRole role, ItemStack stack) {
        return jei.getJeiHelpers().getFocusFactory().createFocus(role, VanillaTypes.ITEM_STACK, stack);
    }

    /** The categories JEI links to this block as their catalyst, without the pages of JER and JEP. */
    private List<IRecipeCategory<?>> stationCategories(ItemStack station) {
        return recipes().createRecipeCategoryLookup().limitFocus(List.of(focus(RecipeIngredientRole.CRAFTING_STATION, station))).get()
                .filter(c -> !NOT_STATION_RECIPES.contains(c.getRecipeType().getUid().getNamespace())).toList();
    }

    @Override
    public List<ItemStack> search(String query, int limit) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        // "iron ingot" finds minecraft:iron_ingot too, whatever language the names are in
        String id = q.replace(' ', '_');
        List<ItemStack> found = new ArrayList<>();
        if (q.isEmpty()) return found;
        var visibility = jei.getJeiHelpers().getIngredientVisibility();
        for (ItemStack stack : jei.getIngredientManager().getAllItemStacks()) {
            if (!visibility.isIngredientVisible(VanillaTypes.ITEM_STACK, stack)) continue;
            if (stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                    || BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains(id)) {
                found.add(stack);
                if (found.size() >= limit) break;
            }
        }
        return found;
    }

    @Override
    public List<ItemStack> stationOutputs(ItemStack station) {
        return outputs.computeIfAbsent(station.getItem(), item -> {
            List<ItemStack> made = new ArrayList<>();
            for (IRecipeCategory<?> category : stationCategories(station)) collectOutputs(category, made);
            made.sort(Comparator.comparing((ItemStack s) -> s.getHoverName().getString()));
            return List.copyOf(made);
        });
    }

    private <T> void collectOutputs(IRecipeCategory<T> category, List<ItemStack> made) {
        recipes().createRecipeLookup(category.getRecipeType()).get().forEach(recipe -> {
            if (made.size() >= MAX_OUTPUTS) return;
            for (var output : recipes().getRecipeIngredients(category, recipe).getIngredients(RecipeIngredientRole.OUTPUT)) {
                output.getItemStack().filter(s -> !s.isEmpty() && made.stream().noneMatch(m -> ItemStack.isSameItemSameComponents(m, s)))
                        .ifPresent(s -> made.add(s.copyWithCount(1)));
            }
        });
    }

    @Override
    public List<StationRecipe> stationRecipes(ItemStack station, ItemStack output) {
        IFocus<ItemStack> made = focus(RecipeIngredientRole.OUTPUT, output);
        List<StationRecipe> found = new ArrayList<>();
        for (IRecipeCategory<?> category : stationCategories(station)) collectRecipes(category, made, found);
        return found;
    }

    private <T> void collectRecipes(IRecipeCategory<T> category, IFocus<ItemStack> made, List<StationRecipe> found) {
        IFocusGroup focuses = jei.getJeiHelpers().getFocusFactory().createFocusGroup(List.of(made));
        recipes().createRecipeLookup(category.getRecipeType()).limitFocus(List.of(made)).get()
                .forEach(recipe -> found.add(new JeiStationRecipe<>(category, recipe, made, focuses)));
    }

    @Override
    public void showHowToGet(ItemStack stack) {
        jei.getRecipesGui().show(focus(RecipeIngredientRole.OUTPUT, stack));
    }

    @Override
    public void showUses(ItemStack stack) {
        jei.getRecipesGui().show(focus(RecipeIngredientRole.INPUT, stack));
    }

    @Override
    public List<SourcePage> sources(ItemStack stack) {
        IFocus<ItemStack> made = focus(RecipeIngredientRole.OUTPUT, stack);
        List<SourcePage> pages = new ArrayList<>();
        for (Identifier id : SOURCE_TYPES) {
            recipes().getRecipeType(id).ifPresent(type -> sourcePage(type, made).ifPresent(pages::add));
        }
        return pages;
    }

    private <T> Optional<SourcePage> sourcePage(IRecipeType<T> type, IFocus<ItemStack> made) {
        IRecipeCategory<T> category = recipes().getRecipeCategory(type);
        List<T> entries = recipes().createRecipeLookup(type).limitFocus(List.of(made)).get().toList();
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(new SourcePage(category.getTitle(), entries.size(),
                () -> jei.getRecipesGui().showRecipes(category, entries, List.of(made)),
                (graphics, x, y) -> category.getIcon().draw(graphics, x, y)));
    }

    @Nullable
    @Override
    public Runnable professionPage(ItemStack station) {
        return recipes().getRecipeType(PROFESSIONS).map(type -> professionPage(type, station)).orElse(null);
    }

    @Nullable
    private <T> Runnable professionPage(IRecipeType<T> type, ItemStack station) {
        IRecipeCategory<T> category = recipes().getRecipeCategory(type);
        // JEP 4.x shows the job site in its output slot; the other roles keep older and newer layouts working
        for (RecipeIngredientRole role : new RecipeIngredientRole[]{RecipeIngredientRole.OUTPUT, RecipeIngredientRole.INPUT,
                RecipeIngredientRole.CRAFTING_STATION}) {
            IFocus<ItemStack> block = focus(role, station);
            List<T> entries = recipes().createRecipeLookup(type).limitFocus(List.of(block)).get().toList();
            if (!entries.isEmpty()) return () -> jei.getRecipesGui().showRecipes(category, entries, List.of(block));
        }
        return null;
    }

    @Override
    public boolean isShowRecipeKey(int key, int scanCode) {
        return jei.getKeyMappings().getShowRecipe().isActiveAndMatches(InputConstants.getKey(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)));
    }

    @Override
    public Component keyNames() {
        return Component.empty().append(jei.getKeyMappings().getShowRecipe().getTranslatedKeyMessage()).append(" / ")
                .append(jei.getKeyMappings().getShowUses().getTranslatedKeyMessage());
    }

    @Override
    public boolean isShowUsesKey(int key, int scanCode) {
        return jei.getKeyMappings().getShowUses().isActiveAndMatches(InputConstants.getKey(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)));
    }

    /** A recipe drawn by JEI's own layout; the layout is made the first time the guide draws it. */
    private final class JeiStationRecipe<T> implements StationRecipe {
        private final IRecipeCategory<T> category;
        private final T recipe;
        private final IFocus<ItemStack> made;
        private final IFocusGroup focuses;
        @Nullable
        private IRecipeLayoutDrawable<T> layout;
        private boolean tried;

        JeiStationRecipe(IRecipeCategory<T> category, T recipe, IFocus<ItemStack> made, IFocusGroup focuses) {
            this.category = category;
            this.recipe = recipe;
            this.made = made;
            this.focuses = focuses;
        }

        @Nullable
        private IRecipeLayoutDrawable<T> layout() {
            if (!tried) {
                tried = true;
                layout = recipes().createRecipeLayoutDrawable(category, recipe, focuses).orElse(null);
            }
            return layout;
        }

        @Override
        public Component category() {
            return category.getTitle();
        }

        @Nullable
        @Override
        public Identifier recipeId() {
            return recipe instanceof RecipeHolder<?> holder ? holder.id().identifier() : category.getRegistryName(recipe);
        }

        @Override
        public List<ItemStack> inputs() {
            IRecipeLayoutDrawable<T> drawn = layout();
            if (drawn == null) return List.of();
            List<ItemStack> inputs = new ArrayList<>();
            for (var slot : drawn.getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT)) {
                inputs.add(slot.getItemStacks().findFirst().orElse(ItemStack.EMPTY));
            }
            return inputs;
        }

        @Override
        public int width() {
            IRecipeLayoutDrawable<T> drawn = layout();
            return drawn == null ? category.getWidth() : drawn.getRect().getWidth();
        }

        @Override
        public int height() {
            IRecipeLayoutDrawable<T> drawn = layout();
            return drawn == null ? category.getHeight() : drawn.getRect().getHeight();
        }

        @Override
        public void draw(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
            IRecipeLayoutDrawable<T> drawn = layout();
            if (drawn == null) return;
            drawn.setPosition(x, y);
            drawn.drawRecipe(graphics, mouseX, mouseY);
        }

        @Override
        public void open() {
            jei.getRecipesGui().showRecipes(category, List.of(recipe), List.of(made));
        }
    }
}
