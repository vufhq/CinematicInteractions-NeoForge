package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.client.guide.RecipeViewer;
import dev.dyslexic.cinematic.client.guide.RecipeViewers;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * A small field guide that opens to the right of the station when JEI is installed: a dark green cover with light
 * pages, a search, what this block makes, and the way into JEI's own pages ("how to get", "where used"; JER's sources
 * and JEP's profession when those are installed). Picking a recipe shows its ingredients where the hands would put
 * them (the table's see-through diagram, the furnace's raw item) only where the scene can show that faithfully.
 * Looking a recipe up never gives or moves an item; JEI's pages are opened over the scene, which holds still beneath.
 */
final class FieldGuide {
    private static final int W = 260, H = 168, COVER = 7, SPINE = 6;
    private static final int PAGE_W = (W - 2 * COVER - SPINE) / 2;
    private static final int COLS = 6, ROWS = 4, CELL = 18, PAGE_SIZE = COLS * ROWS;
    private static final int GRID_Y = 42;
    private static final int TOGGLE_W = 30, TOGGLE_H = 28, CLOSE_W = 14;

    private enum Tab {
        RECIPES, SOURCES, PROFESSION;
        /** Where the ribbon was drawn last, for clicks. */
        int x, w;
    }

    private final EditBox search;
    private boolean open;
    private double shown;
    private Tab tab = Tab.RECIPES;
    private List<ItemStack> items = List.of();
    private String query = "";
    private int page;
    @Nullable
    private ItemStack selected;
    private List<RecipeViewer.StationRecipe> recipes = List.of();
    private int recipeIndex;
    private List<RecipeViewer.SourcePage> sources = List.of();
    @Nullable
    private ClientStation station;
    private ItemStack stationItem = ItemStack.EMPTY;
    @Nullable
    private Component profession;
    @Nullable
    private Runnable professionPage;
    private float scale = 1;
    private int x, y, toggleY;

    FieldGuide() {
        search = new EditBox(Minecraft.getInstance().font, 0, 0, PAGE_W - 12, 11, text("search"));
        search.setMaxLength(64);
        search.setBordered(false);
        search.setTextColor(0xFFF6EFDB);
        search.setHint(text("search").copy().withStyle(s -> s.withColor(0xADC8B4)));
        search.setResponder(value -> {
            page = 0;
            query = value;
            refresh();
        });
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("cinematic.guide." + key, args);
    }

    @Nullable
    private static RecipeViewer viewer() {
        return RecipeViewers.get();
    }

    /** Whether the guide is there at all: a recipe viewer is installed and ready, and the scene is at work. */
    boolean present() {
        return viewer() != null && Scene.phase == Scene.Phase.ACTIVE && Scene.station != null && !FreeLook.looking();
    }

    void tick() {
        if (Scene.station != station) {
            // a new station (or the one moved over to): what it makes, and who works it
            station = Scene.station;
            stationItem = station == null || Minecraft.getInstance().level == null ? ItemStack.EMPTY
                    : new ItemStack(Minecraft.getInstance().level.getBlockState(station.pos).getBlock().asItem());
            profession = professionOf(station);
            professionPage = null;
            select(null);
            refresh();
        }
    }

    private void refresh() {
        RecipeViewer viewer = viewer();
        if (viewer == null || stationItem.isEmpty()) {
            items = List.of();
            return;
        }
        items = query.isBlank() ? viewer.stationOutputs(stationItem) : viewer.search(query, 240);
        page = Mth.clamp(page, 0, pages() - 1);
        if (professionPage == null && profession != null) professionPage = viewer.professionPage(stationItem);
    }

    private void select(@Nullable ItemStack stack) {
        selected = stack;
        recipeIndex = 0;
        RecipeViewer viewer = viewer();
        recipes = stack == null || viewer == null ? List.of() : viewer.stationRecipes(stationItem, stack);
        sources = stack == null || viewer == null ? List.of() : viewer.sources(stack);
        if (tab == Tab.SOURCES && stack == null) tab = Tab.RECIPES;
    }

    /** The villager profession whose job site this block is (vanilla's own data, modded professions included). */
    @Nullable
    private static Component professionOf(@Nullable ClientStation station) {
        var level = Minecraft.getInstance().level;
        if (station == null || level == null) return null;
        var poi = PoiTypes.forState(level.getBlockState(station.pos));
        if (poi.isEmpty()) return null;
        for (VillagerProfession profession : BuiltInRegistries.VILLAGER_PROFESSION) {
            if (!BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).equals(VillagerProfession.NONE.identifier())
                    && profession.heldJobSite().test(poi.get())) {
                Identifier key = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
                String name = key.getNamespace().equals("minecraft") ? key.getPath() : key.getNamespace() + "." + key.getPath();
                return Component.translatable("entity.minecraft.villager." + name);
            }
        }
        return null;
    }

    private boolean showsSources() {
        return selected != null && !sources.isEmpty();
    }

    private boolean showsProfession() {
        return profession != null && professionPage != null;
    }

    private int pages() {
        return Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    // ---- where it is ---------------------------------------------------------------------------------

    private void place() {
        var window = Minecraft.getInstance().getWindow();
        int width = window.getGuiScaledWidth(), height = window.getGuiScaledHeight();
        scale = Math.min(1f, Math.min(height / 300f, width / 560f));
        int right = width - 8;
        // keep clear of the work: the book never reaches over the block's own outline on screen
        double workRight = 0;
        if (station != null) {
            for (Vec3 sample : station.workSamples()) {
                double[] at = Picking.toGui(sample);
                if (at != null) workRight = Math.max(workRight, at[0]);
            }
        }
        float room = (float) ((right - workRight - 10) / W);
        if (workRight > 0 && room < scale) scale = Math.max(0.62f, room);
        double slide = (1 - Anim.outCubic(shown)) * (W + 16) * scale;
        x = (int) Math.round(right - W * scale + slide);
        y = Mth.clamp(Math.round(height * 0.16f), 8, Math.max(8, height - (int) (H * scale) - 30));
        toggleY = Math.round(height * 0.45f);
        // the layout editor's gear sits on the same spot in developer mode: the guide's spine moves just above it
        if (Settings.developerMode() && SceneScreen.editor() != null) toggleY -= Math.round(TOGGLE_H * scale) + 6;
    }

    private double lx(double mx) { return (mx - x) / scale; }
    private double ly(double my) { return (my - y) / scale; }
    private int leftPage() { return COVER; }
    private int rightPage() { return COVER + PAGE_W + SPINE; }

    private boolean inBook(double mx, double my) {
        return shown > 0.05 && lx(mx) >= 0 && lx(mx) < W && ly(my) >= -14 && ly(my) < H;
    }

    private boolean inToggle(double mx, double my) {
        var window = Minecraft.getInstance().getWindow();
        int width = window.getGuiScaledWidth();
        return !open && shown < 0.05 && mx >= width - 8 - TOGGLE_W * scale && mx < width - 8 && my >= toggleY && my < toggleY + TOGGLE_H * scale;
    }

    boolean over(double mx, double my) {
        return present() && (inBook(mx, my) || inToggle(mx, my));
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    void render(GuiGraphicsExtractor g, Font font, float alpha) {
        if (!present()) {
            shown = 0;
            return;
        }
        shown = Anim.approach(shown, open ? 1 : 0, 9, Scene.frameDt);
        place();
        double[] mouse = Picking.mouseGui();
        if (shown < 0.05) {
            drawToggle(g, alpha, inToggle(mouse[0], mouse[1]));
            return;
        }
        float a = alpha * (float) Anim.smoother(shown * 1.4);
        int mx = (int) lx(mouse[0]), my = (int) ly(mouse[1]);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        drawTabs(g, font, a, mx, my);
        drawCover(g, a);
        // the pages unfold from the spine as the book opens
        float unfold = (float) Anim.smoother((shown - 0.25) / 0.75);
        if (unfold > 0.02f) {
            int spine = COVER + PAGE_W;
            int reach = Math.round(PAGE_W * unfold);
            g.fill(spine - reach, COVER - 2, spine, H - COVER + 2, color(a, 0xEDE3C8));
            g.fill(spine + SPINE, COVER - 2, spine + SPINE + reach, H - COVER + 2, color(a, 0xF2EAD2));
            if (unfold > 0.6f) {
                float pa = a * (unfold - 0.6f) / 0.4f;
                drawLeftPage(g, font, pa, mx, my);
                switch (tab) {
                    case RECIPES -> drawRecipePage(g, font, pa, mx, my, mouse);
                    case SOURCES -> drawSourcesPage(g, font, pa, mx, my);
                    case PROFESSION -> drawProfessionPage(g, font, pa, mx, my);
                }
            }
        }
        g.pose().popMatrix();
    }

    private void drawToggle(GuiGraphicsExtractor g, float alpha, boolean hover) {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        g.pose().pushMatrix();
        g.pose().translate(width - 8 - TOGGLE_W * scale, toggleY);
        g.pose().scale(scale, scale);
        // a closed little book seen from its fore-edge: pages on the left, the green cover around them
        g.fill(0, 0, TOGGLE_W, TOGGLE_H, color(alpha * 0.62f, 0x0C0C10));
        g.fill(5, 4, TOGGLE_W - 4, TOGGLE_H - 4, color(alpha, 0x1F3B2C));
        g.fill(5, 4, 8, TOGGLE_H - 4, color(alpha, 0x142519));
        g.fill(10, 7, TOGGLE_W - 7, TOGGLE_H - 7, color(alpha, hover ? 0xF6EFDB : 0xEDE3C8));
        g.fill(10, 11, TOGGLE_W - 7, 12, color(alpha * 0.5f, 0xB9A47C));
        g.fill(10, 15, TOGGLE_W - 9, 16, color(alpha * 0.5f, 0xB9A47C));
        g.fill(TOGGLE_W - 11, 4, TOGGLE_W - 8, 12, color(alpha, 0xB5402E));
        g.pose().popMatrix();
    }

    private void drawCover(GuiGraphicsExtractor g, float a) {
        g.fill(-2, -2, W + 2, H + 2, color(a * 0.35f, 0x05080A));
        g.fill(0, 0, W, H, color(a, 0x1F3B2C));
        g.fill(0, 0, W, 1, color(a, 0x2E5240));
        g.fill(0, H - 1, W, H, color(a, 0x15291F));
        int spine = COVER + PAGE_W;
        g.fill(spine, 0, spine + SPINE, H, color(a, 0x142519));
        g.fill(spine + SPINE / 2, 2, spine + SPINE / 2 + 1, H - 2, color(a * 0.6f, 0x2E5240));
    }

    private void drawTabs(GuiGraphicsExtractor g, Font font, float a, int mx, int my) {
        // from the left edge: three ribbons would run off a book at the screen's edge if they started over the right page
        int tx = leftPage() + 6;
        for (Tab t : Tab.values()) {
            if (t == Tab.SOURCES && !showsSources() || t == Tab.PROFESSION && !showsProfession()) continue;
            Component label = text("tab." + t.name().toLowerCase(java.util.Locale.ROOT));
            int w = font.width(label) + 10;
            boolean active = t == tab;
            boolean hover = mx >= tx && mx < tx + w && my >= -12 && my < 2;
            int body = t == Tab.RECIPES ? 0x2E6B47 : t == Tab.SOURCES ? 0x8A5A2B : 0x5B3F7A;
            g.fill(tx, active ? -13 : -10, tx + w, 2, color(a, active || hover ? lighter(body) : body));
            g.text(font, label, tx + 5, active ? -10 : -8, color(a, 0xF6EFDB), false);
            t.x = tx;
            t.w = w;
            tx += w + 3;
        }
        boolean hover = overClose(mx, my);
        g.fill(W - CLOSE_W - 2, hover ? -11 : -9, W - 2, 2, color(a, hover ? lighter(0x1F3B2C) : 0x1F3B2C));
        g.text(font, "×", W - 2 - (CLOSE_W + font.width("×")) / 2, hover ? -8 : -7, color(a, 0xF6EFDB), false);
    }

    /** The little ribbon at the cover's top right corner that folds the book away, whatever page is open. */
    private static boolean overClose(int mx, int my) {
        return mx >= W - CLOSE_W - 2 && mx < W - 2 && my >= -12 && my < 2;
    }

    private void drawLeftPage(GuiGraphicsExtractor g, Font font, float a, int mx, int my) {
        int px = leftPage();
        g.fill(px + 4, 5, px + PAGE_W - 4, 21, color(a, search.isFocused() ? 0x72B58B : 0xB9A47C));
        g.fill(px + 5, 6, px + PAGE_W - 5, 20, color(a, 0x1F3B2C));
        search.setX(px + 8);
        search.setY(9);
        search.setWidth(PAGE_W - 16);
        search.setAlpha(a);
        search.extractRenderState(g, mx, my, 0);
        Component head = query.isBlank() ? text("made_here", items.size()) : text("found", items.size());
        g.text(font, head, px + 6, 27, color(a, 0x7A6A55), false);
        int hover = entryAt(mx, my);
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = page * PAGE_SIZE + slot;
            if (index >= items.size()) break;
            ItemStack stack = items.get(index);
            int sx = gridX() + slot % COLS * CELL, sy = GRID_Y + slot / COLS * CELL;
            g.fill(sx, sy, sx + CELL - 1, sy + CELL - 1, color(a * (index == hover ? 0.28f : 0.1f), 0x8C7B62));
            if (selected != null && ItemStack.isSameItemSameComponents(selected, stack)) rim(g, sx, sy, CELL - 1, color(a, 0x2E6B47));
            g.item(stack, sx + 1, sy + 1);
        }
        if (items.isEmpty()) {
            g.textWithWordWrap(font, text(query.isBlank() ? "nothing_here" : "nothing_found"), px + 8, GRID_Y + 22, PAGE_W - 16, color(a, 0x7A6A55));
        }
        int py = GRID_Y + ROWS * CELL + 6;
        g.text(font, "‹", px + 8, py, color(a * (page > 0 ? 1 : 0.3f), 0x3A2E22), false);
        String counter = (page + 1) + " / " + pages();
        g.text(font, counter, px + (PAGE_W - font.width(counter)) / 2, py, color(a, 0x7A6A55), false);
        g.text(font, "›", px + PAGE_W - 12, py, color(a * (page < pages() - 1 ? 1 : 0.3f), 0x3A2E22), false);
        RecipeViewer viewer = viewer();
        if (viewer != null) {
            var lines = font.split(text("keys_hint", viewer.keyNames()), PAGE_W - 12);
            int ly = H - COVER - 2 - Math.min(2, lines.size()) * 9;
            for (int i = 0; i < Math.min(2, lines.size()); i++) g.text(font, lines.get(i), px + 6, ly + i * 9, color(a * 0.8f, 0x8C7B62), false);
        }
    }

    private int gridX() { return leftPage() + (PAGE_W - COLS * CELL) / 2; }

    private int entryAt(int mx, int my) {
        int gx = mx - gridX(), gy = my - GRID_Y;
        if (gx < 0 || gx >= COLS * CELL || gy < 0 || gy >= ROWS * CELL) return -1;
        int index = page * PAGE_SIZE + gy / CELL * COLS + gx / CELL;
        return index < items.size() ? index : -1;
    }

    /** The buttons under the selected item: {label key, enabled}; their boxes are worked out in one place. */
    private List<Button> recipeButtons() {
        List<Button> buttons = new ArrayList<>();
        if (selected == null) return buttons;
        RecipeViewer.StationRecipe recipe = recipes.isEmpty() ? null : recipes.get(recipeIndex);
        if (recipe != null && canHint(recipe)) buttons.add(new Button(station instanceof TableStation ? "show_table" : "show_furnace", () -> hint(recipe)));
        if (recipe != null) buttons.add(new Button("open_recipe", () -> Scene.consult(recipe::open)));
        buttons.add(new Button("how_to_get", () -> Scene.consult(() -> viewer().showHowToGet(selected))));
        buttons.add(new Button("uses", () -> Scene.consult(() -> viewer().showUses(selected))));
        int bx = rightPage() + 6, bw = PAGE_W - 12, by = H - COVER - 4 - buttons.size() * 14;
        for (Button b : buttons) {
            b.x = bx;
            b.y = by;
            b.w = bw;
            by += 14;
        }
        return buttons;
    }

    private void drawRecipePage(GuiGraphicsExtractor g, Font font, float a, int mx, int my, double[] mouse) {
        int px = rightPage();
        if (selected == null) {
            g.textWithWordWrap(font, text("choose"), px + 10, 40, PAGE_W - 20, color(a, 0x7A6A55));
            return;
        }
        g.item(selected, px + 6, 7);
        g.text(font, fit(font, selected.getHoverName().getString(), PAGE_W - 34), px + 26, 11, color(a, 0x3A2E22), false);
        g.fill(px + 5, 26, px + PAGE_W - 5, 27, color(a * 0.5f, 0xB9A47C));
        List<Button> buttons = recipeButtons();
        int top = 31, bottom = buttons.isEmpty() ? H - COVER - 4 : buttons.get(0).y - 3;
        if (recipes.isEmpty()) {
            g.textWithWordWrap(font, text("not_here"), px + 8, top + 4, PAGE_W - 16, color(a, 0x7A6A55));
        } else {
            RecipeViewer.StationRecipe recipe = recipes.get(recipeIndex);
            String count = recipes.size() > 1 ? (recipeIndex + 1) + "/" + recipes.size() + " · " : "";
            g.text(font, fit(font, count + recipe.category().getString(), PAGE_W - 30), px + 8, top, color(a, 0x5A4A37), false);
            if (recipes.size() > 1) {
                g.text(font, "‹", px + PAGE_W - 22, top, color(a, 0x3A2E22), false);
                g.text(font, "›", px + PAGE_W - 12, top, color(a, 0x3A2E22), false);
            }
            // JEI draws the recipe itself, fitted to the page
            int areaTop = top + 11, areaH = bottom - areaTop, areaW = PAGE_W - 10;
            float fit = Math.min(1f, Math.min(areaW / (float) Math.max(1, recipe.width()), areaH / (float) Math.max(1, recipe.height())));
            if (fit > 0.3f) {
                g.pose().pushMatrix();
                g.pose().translate(px + 5 + (areaW - recipe.width() * fit) / 2, areaTop + (areaH - recipe.height() * fit) / 2);
                g.pose().scale(fit, fit);
                recipe.draw(g, 0, 0, -1, -1);
                g.pose().popMatrix();
            }
        }
        for (Button b : buttons) drawButton(g, font, b, a, mx, my);
    }

    private void drawSourcesPage(GuiGraphicsExtractor g, Font font, float a, int mx, int my) {
        int px = rightPage();
        g.text(font, text("sources_title"), px + 8, 9, color(a, 0x3A2E22), false);
        g.fill(px + 5, 20, px + PAGE_W - 5, 21, color(a * 0.5f, 0xB9A47C));
        int ly = 26;
        for (RecipeViewer.SourcePage source : sources) {
            boolean hover = mx >= px + 5 && mx < px + PAGE_W - 5 && my >= ly && my < ly + 19;
            g.fill(px + 5, ly, px + PAGE_W - 5, ly + 18, color(a * (hover ? 0.3f : 0.1f), 0x8C7B62));
            source.icon().draw(g, px + 7, ly + 1);
            // JER's titles are long ("Mob drops", "Dungeon chests" and the like in longer languages): two lines, not a cut word
            String title = source.title().getString();
            int w = PAGE_W - 50;
            if (font.width(title) <= w) {
                g.text(font, title, px + 26, ly + 5, color(a, 0x3A2E22), false);
            } else {
                String first = font.plainSubstrByWidth(title, w);
                int cut = first.lastIndexOf(' ');
                if (cut > 0) first = first.substring(0, cut);
                g.text(font, first, px + 26, ly + 1, color(a, 0x3A2E22), false);
                g.text(font, fit(font, title.substring(first.length()).strip(), w), px + 26, ly + 10, color(a, 0x3A2E22), false);
            }
            String n = String.valueOf(source.entries());
            g.text(font, n, px + PAGE_W - 9 - font.width(n), ly + 5, color(a, 0x8A5A2B), false);
            ly += 20;
            if (ly > H - 30) break;
        }
        g.textWithWordWrap(font, text("sources_hint"), px + 8, H - COVER - 20, PAGE_W - 16, color(a * 0.9f, 0x8C7B62));
    }

    private void drawProfessionPage(GuiGraphicsExtractor g, Font font, float a, int mx, int my) {
        int px = rightPage();
        g.text(font, text("profession_title"), px + 8, 9, color(a, 0x7A6A55), false);
        g.text(font, profession, px + 8, 24, color(a, 0x3A2E22), false);
        g.fill(px + 5, 36, px + PAGE_W - 5, 37, color(a * 0.5f, 0xB9A47C));
        g.textWithWordWrap(font, text("profession_text"), px + 8, 44, PAGE_W - 16, color(a, 0x5A4A37));
        Button open = professionButton();
        drawButton(g, font, open, a, mx, my);
    }

    private Button professionButton() {
        Button b = new Button("open_jep", () -> Scene.consult(professionPage));
        b.x = rightPage() + 6;
        b.y = H - COVER - 18;
        b.w = PAGE_W - 12;
        return b;
    }

    private void drawButton(GuiGraphicsExtractor g, Font font, Button b, float a, int mx, int my) {
        boolean hover = b.over(mx, my);
        g.fill(b.x, b.y, b.x + b.w, b.y + 12, color(a * (hover ? 0.95f : 0.8f), hover ? 0x3B8A5C : 0x2E6B47));
        g.fill(b.x, b.y + 11, b.x + b.w, b.y + 12, color(a, 0x1F4A31));
        Component label = text(b.key);
        g.text(font, label, b.x + (b.w - font.width(label)) / 2, b.y + 2, color(a, 0xF6EFDB), false);
    }

    // ---- what a recipe shows at the block ----------------------------------------------------------

    /** Whether the scene can show this recipe at the block itself, faithfully. */
    private boolean canHint(RecipeViewer.StationRecipe recipe) {
        Identifier id = recipe.recipeId();
        if (id == null) return false;
        if (station instanceof TableStation table) return table.recipeBook.guide.entry(id) != null;
        if (station instanceof FurnaceStation furnace) return furnace.cookingInput(recipe.inputs()) != null;
        return false;
    }

    private void hint(RecipeViewer.StationRecipe recipe) {
        Identifier id = recipe.recipeId();
        if (id == null) return;
        if (station instanceof TableStation table) {
            var entry = table.recipeBook.guide.entry(id);
            if (entry != null) table.recipeBook.show(entry);
        } else if (station instanceof FurnaceStation furnace) {
            furnace.showHint(furnace.cookingInput(recipe.inputs()));
        }
    }

    // ---- input -------------------------------------------------------------------------------------

    boolean click(double mouseX, double mouseY, int button) {
        if (!present()) return false;
        if (inToggle(mouseX, mouseY)) {
            open = true;
            refresh();
            return true;
        }
        if (!inBook(mouseX, mouseY)) {
            search.setFocused(false);
            return false;
        }
        if (button != 0 || shown < 0.9) return true;
        int mx = (int) lx(mouseX), my = (int) ly(mouseY);
        for (Tab t : Tab.values()) {
            if (t.w > 0 && mx >= t.x && mx < t.x + t.w && my >= -13 && my < 2
                    && (t != Tab.SOURCES || showsSources()) && (t != Tab.PROFESSION || showsProfession())) {
                tab = t;
                return true;
            }
        }
        if (overClose(mx, my)) {
            fold();
            return true;
        }
        boolean inSearch = mx >= leftPage() + 4 && mx < leftPage() + PAGE_W - 4 && my >= 6 && my < 21;
        search.setFocused(inSearch);
        if (inSearch) return true;
        int entry = entryAt(mx, my);
        if (entry >= 0) {
            select(items.get(entry).copy());
            tab = Tab.RECIPES;
            return true;
        }
        int py = GRID_Y + ROWS * CELL + 6;
        if (my >= py - 3 && my < py + 10) {
            if (mx < leftPage() + 24) page = Math.max(0, page - 1);
            else if (mx > leftPage() + PAGE_W - 24) page = Math.min(pages() - 1, page + 1);
            return true;
        }
        if (tab == Tab.RECIPES) {
            if (recipes.size() > 1 && my >= 29 && my < 41) {
                if (mx >= rightPage() + PAGE_W - 24 && mx < rightPage() + PAGE_W - 14) recipeIndex = (recipeIndex + recipes.size() - 1) % recipes.size();
                else if (mx >= rightPage() + PAGE_W - 14) recipeIndex = (recipeIndex + 1) % recipes.size();
                return true;
            }
            for (Button b : recipeButtons()) if (b.over(mx, my)) { b.action.run(); return true; }
        } else if (tab == Tab.SOURCES) {
            int ly = 26;
            for (RecipeViewer.SourcePage source : sources) {
                if (mx >= rightPage() + 5 && mx < rightPage() + PAGE_W - 5 && my >= ly && my < ly + 19) {
                    Scene.consult(source.open());
                    return true;
                }
                ly += 20;
            }
        } else if (tab == Tab.PROFESSION && professionButton().over(mx, my)) {
            professionButton().action.run();
        }
        return true;
    }

    boolean scroll(double mouseX, double mouseY, double amount) {
        if (!over(mouseX, mouseY)) return false;
        if (open) page = Mth.clamp(page - (int) Math.signum(amount), 0, pages() - 1);
        return true;
    }

    boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        char c = (char) event.codepoint();
        int modifiers = 0;
        return present() && search.isFocused() && search.charTyped(new net.minecraft.client.input.CharacterEvent(c));
    }

    boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        int scanCode = event.keycode();
        int modifiers = 0;
        if (!present() || !search.isFocused()) return false;
        if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_RETURN || key == InputConstants.KEY_RETURN) search.setFocused(false);
        else search.keyPressed(new net.minecraft.client.input.KeyEvent(key, scanCode, modifiers));
        return true;
    }

    /** JEI's own "recipe" and "uses" keys over an item of the guide or the inventory panel. */
    boolean viewerKey(int key, int scanCode, @Nullable ItemStack hovered) {
        RecipeViewer viewer = viewer();
        if (viewer == null || Scene.phase != Scene.Phase.ACTIVE) return false;
        double[] mouse = Picking.mouseGui();
        ItemStack stack = inBook(mouse[0], mouse[1]) ? hoveredEntry(mouse) : hovered;
        if (stack == null || stack.isEmpty()) return false;
        if (viewer.isShowRecipeKey(key, scanCode)) Scene.consult(() -> viewer.showHowToGet(stack));
        else if (viewer.isShowUsesKey(key, scanCode)) Scene.consult(() -> viewer.showUses(stack));
        else return false;
        return true;
    }

    @Nullable
    private ItemStack hoveredEntry(double[] mouse) {
        int entry = entryAt((int) lx(mouse[0]), (int) ly(mouse[1]));
        return entry < 0 ? null : items.get(entry);
    }

    @Nullable
    List<Component> tooltip(double mouseX, double mouseY) {
        if (!present()) return null;
        if (inToggle(mouseX, mouseY)) return List.of(text("title"), text("toggle_tip").copy().withStyle(ChatFormatting.GRAY));
        if (!inBook(mouseX, mouseY) || shown < 0.9) return null;
        int mx = (int) lx(mouseX), my = (int) ly(mouseY);
        if (overClose(mx, my)) return List.of(text("close"));
        int entry = entryAt(mx, my);
        if (entry >= 0) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), items.get(entry)));
            lines.add(text("entry_tip").copy().withStyle(ChatFormatting.GRAY));
            return lines;
        }
        if (tab == Tab.RECIPES) {
            for (Button b : recipeButtons()) if (b.over(mx, my)) return List.of(text(b.key + "_tip"));
        }
        if (tab == Tab.PROFESSION && professionButton().over(mx, my)) return List.of(text("open_jep_tip"));
        if (tab == Tab.SOURCES && my >= 26 && mx >= rightPage() && (my - 26) / 20 < sources.size()) {
            return List.of(sources.get((my - 26) / 20).title(), text("open_jer_tip").copy().withStyle(ChatFormatting.GRAY));
        }
        return null;
    }

    void fold() {
        open = false;
        search.setFocused(false);
    }

    // ---- small parts ---------------------------------------------------------------------------------

    private static final class Button {
        final String key;
        final Runnable action;
        int x, y, w;

        Button(String key, Runnable action) {
            this.key = key;
            this.action = action;
        }

        boolean over(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + 12;
        }
    }

    private static void rim(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        g.fill(x, y, x + size, y + 1, color);
        g.fill(x, y + size - 1, x + size, y + size, color);
        g.fill(x, y, x + 1, y + size, color);
        g.fill(x + size - 1, y, x + size, y + size, color);
    }

    /** A line cut to the width with an ellipsis, so a shortened word never looks like a whole one. */
    private static String fit(Font font, String line, int width) {
        if (font.width(line) <= width) return line;
        return font.plainSubstrByWidth(line, width - font.width("…")).stripTrailing() + "…";
    }

    private static int lighter(int rgb) {
        int r = Math.min(255, (rgb >> 16 & 255) + 28), gr = Math.min(255, (rgb >> 8 & 255) + 28), b = Math.min(255, (rgb & 255) + 28);
        return r << 16 | gr << 8 | b;
    }

    private static int color(float alpha, int rgb) {
        return Mth.clamp(Math.round(alpha * 255), 0, 255) << 24 | rgb & 0xFFFFFF;
    }

    // Coordinates of real controls for the scripted walkthrough.
    double[] togglePoint() {
        int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        return new double[]{width - 8 - TOGGLE_W * scale / 2, toggleY + TOGGLE_H * scale / 2};
    }

    double[] point(int px, int py) { return new double[]{x + px * scale, y + py * scale}; }
    double[] searchPoint() { return point(leftPage() + PAGE_W / 2, 13); }
    double[] entryPoint(int slot) { return point(gridX() + slot % COLS * CELL + 9, GRID_Y + slot / COLS * CELL + 9); }
    double[] tabPoint(String name) {
        for (Tab t : Tab.values()) if (t.name().equalsIgnoreCase(name)) return point(t.x + t.w / 2, -5);
        return point(0, 0);
    }
    @Nullable double[] buttonPoint(String key) {
        for (Button b : tab == Tab.PROFESSION ? List.of(professionButton()) : recipeButtons())
            if (b.key.equals(key)) return point(b.x + b.w / 2, b.y + 6);
        return null;
    }
    double[] closePoint() { return point(W - 2 - CLOSE_W / 2, -5); }
    double[] sourcePoint(int index) { return point(rightPage() + PAGE_W / 2, 26 + index * 20 + 9); }
    boolean opened() { return open && shown > 0.95; }
    @Nullable ItemStack selected() { return selected; }
    int recipeCount() { return recipes.size(); }
    int sourceCount() { return sources.size(); }
    @Nullable Component profession() { return profession; }
    boolean professionPageReady() { return professionPage != null; }
    String tabName() { return tab.name(); }
    int itemCount() { return items.size(); }
    /** Where an item sits on the page shown, or -1. */
    int indexOf(net.minecraft.world.item.Item item) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).is(item)) return i - page * PAGE_SIZE;
        return -1;
    }
}
