package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.table.TableGrid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/** Local recipe reference: never places items or asks the server to unlock/craft a recipe. */
final class TableRecipeGuide {
    static final class Entry {
        @Nullable final RecipeHolder<CraftingRecipe> holder;
        final ItemStack result;
        final List<Ingredient> ingredients;
        final List<Ingredient> pattern;
        final int width, height;
        final String idText;
        final String search;
        boolean craftable;

        Entry(RecipeHolder<CraftingRecipe> holder, ItemStack result) {
            this.holder = holder;
            this.result = result;
            ingredients = holder.value().placementInfo().ingredients();
            if (holder.value() instanceof ShapedRecipe shaped) {
                width = shaped.getWidth();
                height = shaped.getHeight();
                pattern = shaped.getIngredients().stream().map(optional -> optional.orElse(null)).toList();
            } else {
                width = height = 0;
                pattern = List.of();
            }
            idText = holder.id().toString();
            search = (result.getHoverName().getString() + " " + idText).toLowerCase(Locale.ROOT);
        }

        Entry(RecipeDisplayEntry vanilla, ItemStack result, net.minecraft.util.context.ContextMap context) {
            holder = null;
            this.result = result;
            if (vanilla.display() instanceof ShapedCraftingRecipeDisplay shaped) {
                width = shaped.width();
                height = shaped.height();
                pattern = shaped.ingredients().stream().map(slot -> ingredient(slot, context)).toList();
                ingredients = pattern.stream().filter(i -> i != null).toList();
            } else if (vanilla.display() instanceof ShapelessCraftingRecipeDisplay shapeless) {
                width = height = 0;
                pattern = List.of();
                ingredients = shapeless.ingredients().stream().map(slot -> ingredient(slot, context))
                        .filter(i -> i != null).toList();
            } else throw new IllegalArgumentException("Not a crafting display");
            idText = "#" + vanilla.id().index();
            search = (result.getHoverName().getString() + " " + idText).toLowerCase(Locale.ROOT);
        }
    }

    @Nullable private static Ingredient ingredient(SlotDisplay slot, net.minecraft.util.context.ContextMap context) {
        var items = slot.resolveForStacks(context).stream().filter(stack -> !stack.isEmpty()).map(ItemStack::getItem).distinct().toList();
        return items.isEmpty() ? null : Ingredient.of(items.stream());
    }

    final List<Entry> entries = new ArrayList<>();
    @Nullable Entry selected;
    final Ingredient[] grid = new Ingredient[9];
    final ItemStack[] display = new ItemStack[9];
    final boolean[] placed = new boolean[9];
    final boolean[] missing = new boolean[9];
    int missingCount;
    int revision;
    private final TableStation station;
    private List<ItemStack> snapshot = List.of();
    private int indexed = -1;
    static boolean empty(Ingredient ingredient) { return ingredient == null; }

    TableRecipeGuide(TableStation station) {
        this.station = station;
        Arrays.fill(grid, null);
        Arrays.fill(display, ItemStack.EMPTY);
    }

    @Nullable
    Entry entry(net.minecraft.resources.Identifier id) {
        for (Entry entry : entries) {
            if (entry.holder != null && entry.holder.id().identifier().equals(id)) return entry;
        }
        return null;
    }

    void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        List<RecipeDisplayEntry> vanilla = Scene.clientOnly() ? mc.player.getRecipeBook().getCollections().stream()
                .flatMap(collection -> collection.getRecipes().stream()).toList() : List.of();
        int source = Scene.clientOnly() ? vanilla.stream().mapToInt(entry -> entry.id().index()).reduce(1, (hash, id) -> 31 * hash + id)
                : ClientRecipes.revision;
        boolean reindexed = indexed != source;
        if (reindexed) {
            entries.clear();
            var context = SlotDisplayContext.fromLevel(mc.level);
            if (Scene.clientOnly()) {
                for (RecipeDisplayEntry display : vanilla) {
                    if (!(display.display() instanceof ShapedCraftingRecipeDisplay shaped
                            && shaped.width() <= 3 && shaped.height() <= 3)
                            && !(display.display() instanceof ShapelessCraftingRecipeDisplay)) continue;
                    ItemStack result = display.display().result().resolveForFirstStack(context);
                    if (!result.isEmpty()) {
                        Entry entry = new Entry(display, result.copy(), context);
                        if (!entry.ingredients.isEmpty()) entries.add(entry);
                    }
                }
            } else {
                for (var holder : ClientRecipes.crafting()) {
                    var recipe = holder.value();
                    // Dynamic special recipes have no fixed ingredient diagram; their vanilla GUI remains available.
                    if (recipe.isSpecial() || !(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)
                            || recipe instanceof ShapedRecipe shaped && (shaped.getWidth() > 3 || shaped.getHeight() > 3) || recipe.placementInfo().isImpossibleToPlace()) continue;
                    ItemStack result = recipe.display().getFirst().result().resolveForFirstStack(context);
                    if (!result.isEmpty()) entries.add(new Entry(holder, result.copy()));
                }
            }
            entries.sort(Comparator.comparing((Entry e) -> e.result.getHoverName().getString())
                    .thenComparing(e -> e.idText));
            selected = null;
            indexed = source;
        }
        List<ItemStack> next = new ArrayList<>();
        for (int i = 0; i < 36; i++) next.add(mc.player.getInventory().getItem(i).copy());
        next.add(mc.player.containerMenu.getCarried().copy());
        ClientTables.Entry table = ClientTables.get(station.pos);
        for (int i = 0; i < 9; i++) next.add(table == null ? ItemStack.EMPTY : table.items[i].copy());
        boolean changed = reindexed || next.size() != snapshot.size();
        for (int i = 0; !changed && i < next.size(); i++) changed = !ItemStack.matches(next.get(i), snapshot.get(i));
        if (!changed) return;
        snapshot = next;
        List<ItemStack> pool = units(next);
        for (Entry entry : entries) {
            int[] assignment = match(entry.ingredients, pool);
            entry.craftable = Arrays.stream(assignment).allMatch(i -> i >= 0);
        }
        refresh();
        revision++;
    }

    void select(@Nullable Entry entry) {
        selected = entry;
        Arrays.fill(grid, null);
        refresh();
        revision++;
    }

    private ItemStack actual(int logical) {
        ClientTables.Entry table = ClientTables.get(station.pos);
        return table == null ? ItemStack.EMPTY : table.items[TableGrid.worldIndex(station.side, logical / 3, logical % 3)];
    }

    private void refresh() {
        Arrays.fill(display, ItemStack.EMPTY);
        Arrays.fill(placed, false);
        Arrays.fill(missing, false);
        missingCount = 0;
        if (selected == null) return;
        layout();
        List<ItemStack> resources = new ArrayList<>();
        for (int i = 0; i < Math.min(37, snapshot.size()); i++) resources.add(snapshot.get(i).copy());
        List<Ingredient> needed = new ArrayList<>();
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = actual(i).copy();
            placed[i] = !TableRecipeGuide.empty(grid[i]) && grid[i].test(stack);
            if (placed[i]) stack.shrink(1);
            resources.add(stack);
            if (!TableRecipeGuide.empty(grid[i]) && !placed[i]) {
                needed.add(grid[i]);
                cells.add(i);
            }
        }
        List<ItemStack> pool = units(resources);
        int[] assignment = match(needed, pool);
        for (int n = 0; n < cells.size(); n++) {
            int cell = cells.get(n);
            missing[cell] = assignment[n] < 0;
            if (missing[cell]) missingCount++;
            // Available tag alternatives are shown literally; unavailable alternatives rotate in the renderer.
            if (!missing[cell]) display[cell] = pool.get(assignment[n]).copyWithCount(1);
        }
    }

    private void layout() {
        if (selected.width > 0) {
            int w = selected.width, h = selected.height;
            Ingredient[] best = shapedLayout(selected, w == 1 ? 1 : 0, h == 1 ? 1 : 0, false);
            int score = score(best);
            if (!gridEmpty() && score(grid) >= score) { best = grid.clone(); score = score(best); }
            for (int y = 0; y <= 3 - h; y++) for (int x = 0; x <= 3 - w; x++) for (int m = 0; m < 2; m++) {
                Ingredient[] candidate = shapedLayout(selected, x, y, m == 1);
                int value = score(candidate);
                if (value > score) { best = candidate; score = value; }
            }
            System.arraycopy(best, 0, grid, 0, 9);
        } else {
            Arrays.fill(grid, null);
            List<ItemStack> table = new ArrayList<>();
            for (int i = 0; i < 9; i++) table.add(actual(i));
            int[] assignment = match(selected.ingredients, table);
            for (int i = 0; i < assignment.length; i++) if (assignment[i] >= 0) grid[assignment[i]] = selected.ingredients.get(i);
            for (int i = 0; i < assignment.length; i++) if (assignment[i] < 0) {
                int cell = -1;
                for (int j = 0; j < 9; j++) if (TableRecipeGuide.empty(grid[j]) && actual(j).isEmpty()) { cell = j; break; }
                if (cell < 0) for (int j = 0; j < 9; j++) if (TableRecipeGuide.empty(grid[j])) { cell = j; break; }
                grid[cell] = selected.ingredients.get(i);
            }
        }
    }

    private boolean gridEmpty() {
        return Arrays.stream(grid).allMatch(TableRecipeGuide::empty);
    }

    private Ingredient[] shapedLayout(Entry recipe, int x, int y, boolean mirror) {
        Ingredient[] result = new Ingredient[9];
        Arrays.fill(result, null);
        for (int row = 0; row < recipe.height; row++) for (int col = 0; col < recipe.width; col++) {
            result[(row + y) * 3 + col + x] = recipe.pattern.get(row * recipe.width
                    + (mirror ? recipe.width - col - 1 : col));
        }
        return result;
    }

    private int score(Ingredient[] candidate) {
        int score = 0;
        for (int i = 0; i < 9; i++) if (!actual(i).isEmpty()) score += candidate[i] != null && candidate[i].test(actual(i)) ? 4 : -1;
        return score;
    }

    /**
     * The moves that lay out the selected recipe: for each empty cell of the diagram that the inventory (or the item in
     * hand) can fill, {world cell, inventory slot or -1}. Cells nothing can fill are left out (they stay see-through).
     */
    List<int[]> layoutMoves() {
        // worked out again only when the inventory, the table or the choice changes ({@link #revision}), not every frame
        if (movesRevision == revision && movesCache != null) {
            return movesCache;
        }
        movesRevision = revision;
        movesCache = workOutMoves();
        return movesCache;
    }

    @Nullable
    private List<int[]> movesCache;
    private int movesRevision = -1;

    private List<int[]> workOutMoves() {
        Minecraft mc = Minecraft.getInstance();
        List<int[]> moves = new ArrayList<>();
        if (selected == null || mc.player == null) {
            return moves;
        }
        List<ItemStack> units = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        for (int slot = -1; slot < 36; slot++) {
            ItemStack stack = slot < 0 ? mc.player.containerMenu.getCarried() : mc.player.getInventory().getItem(slot);
            for (int i = 0; i < Math.min(9, stack.getCount()); i++) {
                units.add(stack);
                slots.add(slot);
            }
        }
        List<Ingredient> needed = new ArrayList<>();
        List<Integer> cells = new ArrayList<>();
        for (int logical = 0; logical < 9; logical++) {
            if (!TableRecipeGuide.empty(grid[logical]) && !placed[logical] && actual(logical).isEmpty()) {
                needed.add(grid[logical]);
                cells.add(logical);
            }
        }
        int[] assignment = match(needed, units);
        for (int n = 0; n < cells.size(); n++) {
            if (assignment[n] >= 0) {
                int logical = cells.get(n);
                moves.add(new int[]{TableGrid.worldIndex(station.side, logical / 3, logical % 3), slots.get(assignment[n]), 1});
            }
        }
        return moves;
    }

    /**
     * What lies where the chosen recipe has no place for it (a cell outside the diagram, or the wrong item in one), and
     * fits back into the inventory: {world cell, {@link TableStation#TAKE}, count}. Laying out a recipe takes these back
     * first, so the last recipe's leftovers never block the new one; what does not fit stays ({@link #stuck}).
     */
    List<int[]> takeBackMoves() {
        if (takeRevision == revision && takeCache != null) {
            return takeCache;
        }
        takeRevision = revision;
        takeCache = new ArrayList<>();
        stuckCount = 0;
        Minecraft mc = Minecraft.getInstance();
        if (selected == null || mc.player == null) {
            return takeCache;
        }
        // the inventory as Inventory.add fills it: stacks of the same item first, then empty slots
        ItemStack[] room = new ItemStack[36];
        for (int slot = 0; slot < 36; slot++) room[slot] = mc.player.getInventory().getItem(slot).copy();
        for (int logical = 0; logical < 9; logical++) {
            if (!wrong(logical)) continue;
            ItemStack lying = actual(logical);
            if (fits(room, lying)) {
                takeCache.add(new int[]{TableGrid.worldIndex(station.side, logical / 3, logical % 3), TableStation.TAKE, lying.getCount()});
            } else {
                stuckCount++;
            }
        }
        return takeCache;
    }

    /** Cells holding the wrong item that the inventory has no room to take back (worked out by {@link #takeBackMoves}). */
    int stuck() {
        takeBackMoves();
        return stuckCount;
    }

    @Nullable
    private List<int[]> takeCache;
    private int takeRevision = -1;
    private int stuckCount;

    /** Whether all of {@code stack} goes into {@code room}; if it does, it is put there. */
    private static boolean fits(ItemStack[] room, ItemStack stack) {
        int left = stack.getCount();
        for (ItemStack slot : room) {
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, stack)) left -= Math.max(0, slot.getMaxStackSize() - slot.getCount());
        }
        for (ItemStack slot : room) {
            if (left > 0 && slot.isEmpty()) left -= stack.getMaxStackSize();
        }
        if (left > 0) return false;
        int put = stack.getCount();
        for (int i = 0; i < room.length && put > 0; i++) {
            if (!room[i].isEmpty() && ItemStack.isSameItemSameComponents(room[i], stack)) {
                int move = Math.min(put, room[i].getMaxStackSize() - room[i].getCount());
                if (move > 0) { room[i].grow(move); put -= move; }
            }
        }
        for (int i = 0; i < room.length && put > 0; i++) {
            if (room[i].isEmpty()) {
                int move = Math.min(put, stack.getMaxStackSize());
                room[i] = stack.copyWithCount(move);
                put -= move;
            }
        }
        return true;
    }

    /**
     * How many cells of the diagram one "Lay out" fills once what lies in the way has been taken back: the items taken
     * back count as being at hand, since they may belong in another cell of the new recipe.
     */
    int layableAfterTakingBack() {
        Minecraft mc = Minecraft.getInstance();
        if (selected == null || mc.player == null) return 0;
        List<int[]> back = takeBackMoves();
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = -1; slot < 36; slot++) stacks.add(slot < 0 ? mc.player.containerMenu.getCarried() : mc.player.getInventory().getItem(slot));
        List<Integer> cleared = new ArrayList<>();
        for (int[] move : back) {
            int logical = logicalOf(move[0]);
            cleared.add(logical);
            stacks.add(actual(logical));
        }
        List<Ingredient> needed = new ArrayList<>();
        for (int logical = 0; logical < 9; logical++) {
            if (!empty(grid[logical]) && !placed[logical] && (actual(logical).isEmpty() || cleared.contains(logical))) {
                needed.add(grid[logical]);
            }
        }
        return (int) Arrays.stream(match(needed, units(stacks))).filter(i -> i >= 0).count();
    }

    private int logicalOf(int world) {
        for (int logical = 0; logical < 9; logical++) {
            if (TableGrid.worldIndex(station.side, logical / 3, logical % 3) == world) return logical;
        }
        return -1;
    }

    /**
     * Shift + "Lay out": every cell of the diagram brought up to {@code sets} items, the most whole sets the items at
     * hand allow. Each cell holds a single kind of item, so a tag ingredient picks the kinds that go furthest, and
     * no cell passes its kind's stack limit. {@code current} is how many whole sets already lie on the table; when
     * {@code sets} is not more than that, there is nothing to lay. Cells holding a wrong item are left alone.
     */
    record MaxLayout(int sets, int current, List<int[]> moves) {}

    MaxLayout maxLayout() {
        if (maxRevision == revision && maxCache != null) {
            return maxCache;
        }
        maxRevision = revision;
        maxCache = workOutMax();
        return maxCache;
    }

    @Nullable
    private MaxLayout maxCache;
    private int maxRevision = -1;
    /** Gives up on a pathological assignment search instead of stalling a frame; real recipes take a few dozen steps. */
    private static final int SEARCH_STEPS = 20_000;

    private MaxLayout workOutMax() {
        Minecraft mc = Minecraft.getInstance();
        if (selected == null || mc.player == null) {
            return new MaxLayout(0, 0, List.of());
        }
        // The kinds of item at hand (same item and components), how many of each, and which slots hold them.
        List<ItemStack> kinds = new ArrayList<>();
        List<Integer> stock = new ArrayList<>();
        List<List<Integer>> kindSlots = new ArrayList<>();
        for (int slot = -1; slot < 36; slot++) {
            ItemStack stack = slot < 0 ? mc.player.containerMenu.getCarried() : mc.player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            int kind = kindOf(kinds, stack);
            if (kind < 0) {
                kinds.add(stack);
                stock.add(0);
                kindSlots.add(new ArrayList<>());
                kind = kinds.size() - 1;
            }
            stock.set(kind, stock.get(kind) + stack.getCount());
            kindSlots.get(kind).add(slot);
        }
        // what will be taken back first is at hand too (where it lands is known only after: the moves re-plan then)
        List<Integer> takenBack = new ArrayList<>();
        for (int[] move : takeBackMoves()) {
            int logical = logicalOf(move[0]);
            takenBack.add(logical);
            ItemStack stack = actual(logical);
            int kind = kindOf(kinds, stack);
            if (kind < 0) {
                kinds.add(stack);
                stock.add(0);
                kindSlots.add(new ArrayList<>());
                kind = kinds.size() - 1;
            }
            stock.set(kind, stock.get(kind) + stack.getCount());
            if (!kindSlots.get(kind).contains(TableStation.REPLAN)) kindSlots.get(kind).add(TableStation.REPLAN);
        }
        // The diagram's cells: those already holding the right item keep that kind; empty ones choose a kind.
        List<Integer> cells = new ArrayList<>();
        List<Integer> fixedKind = new ArrayList<>();
        List<Integer> have = new ArrayList<>();
        List<Integer> limit = new ArrayList<>();
        List<int[]> choices = new ArrayList<>();
        int current = Integer.MAX_VALUE;
        int upper = Integer.MAX_VALUE;
        for (int logical = 0; logical < 9; logical++) {
            if (empty(grid[logical])) continue;
            ItemStack lying = takenBack.contains(logical) ? ItemStack.EMPTY : actual(logical);
            if (!lying.isEmpty() && !grid[logical].test(lying)) continue;
            cells.add(logical);
            if (!lying.isEmpty()) {
                fixedKind.add(kindOf(kinds, lying));
                have.add(lying.getCount());
                limit.add(lying.getMaxStackSize());
                choices.add(new int[0]);
                current = Math.min(current, lying.getCount());
                upper = Math.min(upper, lying.getMaxStackSize());
            } else {
                List<Integer> accepted = new ArrayList<>();
                int best = 0;
                for (int k = 0; k < kinds.size(); k++) if (grid[logical].test(kinds.get(k))) {
                    accepted.add(k);
                    best = Math.max(best, kinds.get(k).getMaxStackSize());
                }
                fixedKind.add(-1);
                have.add(0);
                limit.add(best);
                choices.add(accepted.stream().mapToInt(Integer::intValue).toArray());
                current = 0;
                upper = Math.min(upper, best);
            }
        }
        if (cells.isEmpty()) {
            return new MaxLayout(0, 0, List.of());
        }
        int[] chosen = new int[cells.size()];
        for (int sets = upper; sets > current; sets--) {
            if (!assign(sets, cells.size(), fixedKind, have, limit, choices, kinds, stock, chosen)) continue;
            int[] left = new int[36 + 1];
            for (int slot = -1; slot < 36; slot++) {
                ItemStack stack = slot < 0 ? mc.player.containerMenu.getCarried() : mc.player.getInventory().getItem(slot);
                left[slot + 1] = stack.getCount();
            }
            List<int[]> moves = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                int need = sets - have.get(i);
                if (need <= 0) continue;
                int logical = cells.get(i);
                int world = TableGrid.worldIndex(station.side, logical / 3, logical % 3);
                for (int slot : kindSlots.get(chosen[i])) {
                    if (need <= 0) break;
                    if (slot == TableStation.REPLAN) {
                        // a stand-in for what is taken back: counted, never sent
                        moves.add(new int[]{world, slot, need});
                        need = 0;
                        break;
                    }
                    int take = Math.min(need, left[slot + 1]);
                    if (take <= 0) continue;
                    left[slot + 1] -= take;
                    need -= take;
                    moves.add(new int[]{world, slot, take});
                }
            }
            return new MaxLayout(sets, current, moves);
        }
        return new MaxLayout(current, current, List.of());
    }

    private static int kindOf(List<ItemStack> kinds, ItemStack stack) {
        for (int k = 0; k < kinds.size(); k++) if (ItemStack.isSameItemSameComponents(kinds.get(k), stack)) return k;
        return -1;
    }

    /** Whether every cell can reach {@code sets} items; {@code chosen} receives each cell's kind. */
    private static boolean assign(int sets, int count, List<Integer> fixedKind, List<Integer> have, List<Integer> limit,
            List<int[]> choices, List<ItemStack> kinds, List<Integer> stock, int[] chosen) {
        int[] left = stock.stream().mapToInt(Integer::intValue).toArray();
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int need = sets - have.get(i);
            if (fixedKind.get(i) >= 0 || have.get(i) > 0) {
                if (need <= 0) { chosen[i] = Math.max(0, fixedKind.get(i)); continue; }
                int kind = fixedKind.get(i);
                if (kind < 0 || sets > limit.get(i) || left[kind] < need) return false;
                left[kind] -= need;
                chosen[i] = kind;
            } else {
                if (choices.get(i).length == 0) return false;
                open.add(i);
            }
        }
        // The cells with the fewest usable kinds first: they decide fastest whether this many sets fit.
        open.sort(Comparator.comparingInt(i -> choices.get(i).length));
        int[] steps = {0};
        return place(0, open, sets, choices, kinds, left, chosen, steps);
    }

    private static boolean place(int at, List<Integer> open, int sets, List<int[]> choices, List<ItemStack> kinds, int[] left,
            int[] chosen, int[] steps) {
        if (at == open.size()) return true;
        if (++steps[0] > SEARCH_STEPS) return false;
        int cell = open.get(at);
        int[] options = choices.get(cell).clone();
        // the most plentiful kind first: it leaves the most for the cells still to come
        Integer[] order = Arrays.stream(options).boxed().sorted((a, b) -> left[b] - left[a]).toArray(Integer[]::new);
        for (int kind : order) {
            if (left[kind] < sets || kinds.get(kind).getMaxStackSize() < sets) continue;
            left[kind] -= sets;
            chosen[cell] = kind;
            if (place(at + 1, open, sets, choices, kinds, left, chosen, steps)) return true;
            left[kind] += sets;
        }
        return false;
    }

    ItemStack ghost(int logical) {
        if (!display[logical].isEmpty()) return display[logical];
        ItemStack[] choices = grid[logical] == null ? new ItemStack[0] : grid[logical].items().map(h -> new ItemStack(h.value())).toArray(ItemStack[]::new);
        return choices.length == 0 ? ItemStack.EMPTY : choices[(int) (Scene.clock() / 1.8) % choices.length].copyWithCount(1);
    }

    boolean wrong(int logical) {
        return !actual(logical).isEmpty() && !placed[logical];
    }

    private static List<ItemStack> units(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>();
        // No crafting recipe consumes more than nine units, even if the source stack contains thousands.
        for (ItemStack stack : stacks) for (int i = 0; i < Math.min(9, stack.getCount()); i++) result.add(stack);
        return result;
    }

    /** Maximum matching avoids spending a flexible tag alternative on an ingredient needed elsewhere. */
    static int[] match(List<Ingredient> ingredients, List<ItemStack> units) {
        int[] owner = new int[units.size()];
        Arrays.fill(owner, -1);
        for (int i = 0; i < ingredients.size(); i++) augment(i, ingredients, units, owner, new boolean[units.size()]);
        int[] result = new int[ingredients.size()];
        Arrays.fill(result, -1);
        for (int i = 0; i < owner.length; i++) if (owner[i] >= 0) result[owner[i]] = i;
        return result;
    }

    private static boolean augment(int ingredient, List<Ingredient> ingredients, List<ItemStack> units, int[] owner, boolean[] seen) {
        for (int i = 0; i < units.size(); i++) if (!seen[i] && ingredients.get(ingredient).test(units.get(i))) {
            seen[i] = true;
            if (owner[i] < 0 || augment(owner[i], ingredients, units, owner, seen)) {
                owner[i] = ingredient;
                return true;
            }
        }
        return false;
    }
}
