package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.StationKind;
import dev.dyslexic.cinematic.table.TableCrafting;
import dev.dyslexic.cinematic.table.TableGrid;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import dev.dyslexic.cinematic.net.PacketDistributor;
import org.joml.Matrix4f;

/**
 * The crafting table scene: lean over the top, lay items into the painted 3x3 grid, click the result that
 * floats over the far edge to craft (the hands press the ingredients together while the server crafts).
 */
public final class TableStation extends ClientStation {
    static final double HAND_Y = 0.062;
    static final double REST_BACK = 0.4;
    static final double REST_SIDE = 0.24;
    /** How far in front of the fist a held item hangs. */
    static final double ITEM_REACH = 0.075;

    public static final double LEAN_BACK = 0.86;
    public static final double LEAN_UP = 0.72;
    /** The point looked at, measured from the top centre towards the player (negative: beyond the centre). */
    public static final double LOOK_BACK = -0.04;

    public static final double CRAFT_LENGTH = 0.5;
    public static final double CRAFT_MID = 0.2;
    private static final float RESULT_SIZE = 0.2f;
    private static final float OCCLUDED_OPACITY = 0.28f;
    private static final double FADE_RATE = 14;

    public final Vec3 top;
    final TableRecipeBook recipeBook;
    final TableGridEditor gridEditor;
    public int hoverCell = -1;
    public boolean hoverResult;

    public ItemStack result = ItemStack.EMPTY;
    public double resultChangedAt = -10;
    private int resultVersion = -1;

    void refreshResult() { resultVersion = Integer.MIN_VALUE; }

    /** The ingredients as they lay when crafting was clicked, gathered while the server crafts. */
    public double craftAt = -10;
    @Nullable
    public ItemStack[] craftItems;
    @Nullable
    public byte[] craftFacing;

    private boolean painting;
    private final boolean[] painted = new boolean[TableGrid.CELLS];
    private final float[] itemOpacity = new float[TableGrid.CELLS];
    private int spreadStart = -1;
    private int spreadMask;
    private ItemStack spreadStack = ItemStack.EMPTY;
    private double spreadSince;

    public TableStation(BlockPos pos, Direction side) {
        super(pos, side);
        top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        Arrays.fill(itemOpacity, 1f);
        recipeBook = new TableRecipeBook(this);
        gridEditor = new TableGridEditor(this);
    }

    @Override
    public StationKind kind() {
        return StationKind.TABLE;
    }

    @Override
    public Vec3 center() {
        return top;
    }

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(top.x + side.getStepX() * 0.86, player.getY(), top.z + side.getStepZ() * 0.86);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public boolean busy() {
        return craftProgress() >= 0 || laying != null || gridEditor.editing();
    }

    @Override
    public boolean showsInventory() { return !gridEditor.visible(); }

    @Override
    public Vec3 workPoint() { return TableLayouts.current().center(pos); }

    @Override
    public List<Vec3> workSamples() {
        TableLayout layout = TableLayouts.current();
        if (layout.equals(TableLayout.VANILLA)) return super.workSamples();
        double low = TableGrid.GRID_MIN, high = TableGrid.GRID_MAX;
        return List.of(layout.center(pos).add(0, 0.04, 0), layout.point(pos, low, low).add(0, 0.04, 0),
                layout.point(pos, high, low).add(0, 0.04, 0), layout.point(pos, low, high).add(0, 0.04, 0),
                layout.point(pos, high, high).add(0, 0.04, 0));
    }

    @Override
    public void onEnd() { gridEditor.cancel(); cancelSpread(); }

    double surfaceY() { return TableLayouts.current().surfaceY(pos); }

    // ---- laying out a recipe -------------------------------------------------------------------------

    /** Seconds one item takes: from the side, over the cell, down onto it, and the hand lifts. */
    static final double LAY_MOVE = 0.36;
    /** When in a move the item is let go of (the server is asked to put it down). */
    private static final double LAY_DROP = 0.64;

    /** A move's "slot" for taking back what lies on the cell into the inventory, rather than laying onto it. */
    static final int TAKE = -2;
    /**
     * A move's "slot" for working out the rest of the layout once the cells taken back are clear ({cell -1, REPLAN,
     * 1 for every whole set}): what came back to the inventory may be laid out again, in its new slot.
     */
    static final int REPLAN = -3;
    /** How long a re-plan waits for the server to clear the cells taken back (seconds) before it lays what it can. */
    private static final double REPLAN_WAIT = 1.5;

    /**
     * The moves still to make, {world cell, inventory slot or -1, how many}; the one under way is {@link #laying}. A
     * move carries a whole handful, so laying out many sets takes no longer than laying out one.
     */
    private final java.util.ArrayDeque<int[]> layQueue = new java.util.ArrayDeque<>();
    @Nullable
    private int[] laying;
    private ItemStack layItem = ItemStack.EMPTY;
    private double layAt;
    private boolean layDropped;
    /** The cells taken back for a re-plan, which the server is to empty first. */
    private final List<Integer> takenBack = new java.util.ArrayList<>();

    /** Lays out the selected recipe from the inventory, one cell after another. */
    void layOut(List<int[]> moves) {
        if (busy() || moves.isEmpty()) {
            return;
        }
        layQueue.addAll(moves);
        nextLay();
    }

    /** Shift-click in the scene drawer lays the stack on a suitable table cell. */
    void quickMove(int inventorySlot) {
        Minecraft mc = Minecraft.getInstance();
        if (busy() || mc.player == null || inventorySlot < 0 || inventorySlot >= 36) return;
        ItemStack stack = mc.player.getInventory().getItem(inventorySlot);
        if (stack.isEmpty()) return;
        ClientTables.Entry entry = ClientTables.get(pos);
        TableRecipeGuide guide = recipeBook.guide;
        int target = -1;
        for (int logical = 0; logical < TableGrid.CELLS && target < 0 && guide.selected != null; logical++) {
            int world = TableGrid.worldIndex(side, logical / 3, logical % 3);
            if (!TableRecipeGuide.empty(guide.grid[logical]) && !guide.placed[logical]
                    && onCell(entry, world).isEmpty() && guide.grid[logical].test(stack)) target = world;
        }
        for (int logical = 0; logical < TableGrid.CELLS && target < 0; logical++) {
            int world = TableGrid.worldIndex(side, logical / 3, logical % 3);
            ItemStack there = onCell(entry, world);
            if (!there.isEmpty() && ItemStack.isSameItemSameComponents(there, stack)
                    && there.getCount() < there.getMaxStackSize()) target = world;
        }
        for (int logical = 0; logical < TableGrid.CELLS && target < 0; logical++) {
            int world = TableGrid.worldIndex(side, logical / 3, logical % 3);
            if (onCell(entry, world).isEmpty()) target = world;
        }
        if (target < 0) {
            Scene.sound(net.minecraft.sounds.SoundEvents.WOOD_HIT, 0.25f, 0.7f);
            return;
        }
        ItemStack there = onCell(entry, target);
        int count = Math.min(stack.getCount(), there.isEmpty() ? stack.getMaxStackSize()
                : there.getMaxStackSize() - there.getCount());
        if (count > 0) layOut(List.of(new int[]{target, inventorySlot, count}));
    }

    private static ItemStack onCell(@Nullable ClientTables.Entry entry, int world) {
        return entry == null ? ItemStack.EMPTY : entry.items[world];
    }

    private void nextLay() {
        laying = layQueue.poll();
        layDropped = false;
        layAt = Scene.clock();
        if (laying == null) {
            takenBack.clear();
        }
        if (laying != null && laying[1] == REPLAN) {
            layItem = ItemStack.EMPTY;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ItemStack source = laying == null || mc.player == null ? ItemStack.EMPTY
                : laying[1] == TAKE ? onCell(ClientTables.get(pos), laying[0])
                : laying[1] < 0 ? mc.player.containerMenu.getCarried() : mc.player.getInventory().getItem(laying[1]);
        layItem = laying == null ? ItemStack.EMPTY : source.copyWithCount(Math.min(source.getCount(), laying[2]));
        if (laying != null && layItem.isEmpty()) {
            // the inventory changed meanwhile: skip it
            nextLay();
        }
    }

    /** 0..1 through the move under way. */
    private double layProgress() {
        return Anim.clamp01((Scene.clock() - layAt) / LAY_MOVE);
    }

    /** Where the hand is on the laying path: taking back runs the same path the other way, from the cell to the hand. */
    private double layPath() {
        return laying != null && laying[1] == TAKE ? 1 - layProgress() : layProgress();
    }

    /** The rest of the layout, once the server has cleared the cells taken back (or it took too long). */
    private void replan() {
        boolean clear = true;
        ClientTables.Entry entry = ClientTables.get(pos);
        for (int cell : takenBack) clear &= onCell(entry, cell).isEmpty();
        double waited = Scene.clock() - layAt;
        if (waited < 0.1 || !clear && waited < REPLAN_WAIT) {
            return;
        }
        boolean all = laying[2] == 1;
        TableRecipeGuide guide = recipeBook.guide;
        guide.tick();
        for (int[] move : all ? guide.maxLayout().moves() : guide.layoutMoves()) {
            if (move[1] >= -1) layQueue.add(move);
        }
        nextLay();
    }

    @Override
    public void beforeFrame() {
        gridEditor.frame();
        if (laying == null || Scene.phase != Scene.Phase.ACTIVE) {
            if (Scene.phase != Scene.Phase.ACTIVE) {
                layQueue.clear();
                laying = null;
            }
            return;
        }
        if (laying[1] == REPLAN) {
            replan();
            return;
        }
        double t = layProgress();
        if (laying[1] == TAKE) {
            if (!layDropped && t >= 1 - LAY_DROP) {
                layDropped = true;
                Hands.tap();
                takenBack.add(laying[0]);
                // the server's own shift-click: into the inventory as far as it has room, the rest stays on the cell
                if (Scene.clientOnly()) clickVanilla(laying[0], 0, true);
                else PacketDistributor.sendToServer(new StationActionPayload(pos, StationKind.TABLE, StationActionPayload.Action.CLICK,
                        laying[0], 0, true, 0));
            }
            if (t >= 1) {
                nextLay();
            }
            return;
        }
        if (!layDropped && t >= LAY_DROP) {
            layDropped = true;
            Hands.tap();
            if (Scene.clientOnly()) placeVanilla(laying[0], laying[1], layItem.getCount());
            else PacketDistributor.sendToServer(new StationActionPayload(pos, StationKind.TABLE, StationActionPayload.Action.PLACE,
                    laying[0], laying[1], false, layItem.getCount()));
        }
        if (t >= 1) {
            nextLay();
        }
    }

    /** The middle of a cell on the top. */
    Vec3 cellPoint(int cell) {
        return TableLayouts.current().cell(pos, cell);
    }

    /**
     * The hand on the cell's side: back a little towards the player (taking the item), over the cell, down onto it, and
     * up again; the other hand rests.
     */
    @Nullable
    private Vec3[] layHands() {
        if (laying == null) {
            return null;
        }
        double t = layProgress();
        Vec3 cell = cellPoint(laying[0]);
        boolean right = lateral(cell) >= 0;
        Vec3 rest = rest(right);
        Vec3 take = rest.add(side().scale(0.14)).add(0, 0.03, 0);
        Vec3 toward = new Vec3(cell.x - rest.x, 0, cell.z - rest.z).normalize();
        Vec3 over = cell.add(0, HAND_Y + 0.07, 0).subtract(toward.scale(ITEM_REACH));
        Vec3 down = over.add(0, -0.06, 0);
        Vec3 hand;
        if (t < 0.28) {
            hand = Anim.lerp(rest, take, Anim.smoother(t / 0.28));
        } else if (t < LAY_DROP - 0.06) {
            double k = Anim.inOutCubic((t - 0.28) / (LAY_DROP - 0.06 - 0.28));
            hand = Anim.lerp(take, over, k).add(0, 0.05 * Anim.hump(k), 0);
        } else if (t < LAY_DROP + 0.06) {
            hand = Anim.lerp(over, down, Anim.hump((t - (LAY_DROP - 0.06)) / 0.12));
        } else {
            hand = Anim.lerp(over, rest, Anim.smoother((t - LAY_DROP - 0.06) / (1 - LAY_DROP - 0.06)));
        }
        Vec3 other = rest(!right);
        return right ? new Vec3[]{hand, other} : new Vec3[]{other, hand};
    }

    /** The item in the hand while it is carried over (until the table has it). */
    private void drawLayItem(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        if (laying == null || layItem.isEmpty()) {
            return;
        }
        double t = layProgress();
        if (t < 0.24 || t > LAY_DROP + 0.04) {
            return;
        }
        Vec3 cell = cellPoint(laying[0]);
        boolean right = lateral(cell) >= 0;
        Vec3 hand = Hands.position(right);
        Vec3 toward = new Vec3(cell.x - hand.x, 0, cell.z - hand.z);
        toward = toward.lengthSqr() < 1e-6 ? forward() : toward.normalize();
        Vec3 at = hand.add(toward.scale(ITEM_REACH)).add(0, -HAND_Y + 0.012, 0);
        float grow = (float) Anim.smoother((t - 0.24) / 0.08);
        TableLayouts.current().renderItem(pose, buffers, camera, layItem, at, side, 91L, grow, 14f, light, 1, true);
    }

    @Override
    public boolean worksAtAnAngle() {
        return true;
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 at = top.add(side().scale(LEAN_BACK)).add(0, LEAN_UP, 0);
        float pitch = (float) Math.toDegrees(Math.atan2(LEAN_UP, LEAN_BACK - LOOK_BACK));
        return new CameraRig.Pose(at, side.getOpposite().toYRot(), pitch, 0);
    }

    @Override
    public Vec3 rest(boolean right) {
        return top.add(side().scale(REST_BACK)).add(right().scale(right ? REST_SIDE : -REST_SIDE))
                .add(0, HAND_Y + TableLayouts.current().height() / 16, 0);
    }

    // ---- cursor ----------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        double[] mouse = Picking.mouseGui();
        overPanel |= recipeBook.over(mouse[0], mouse[1]);
        overPanel |= gridEditor.over(mouse[0], mouse[1]);
        hoverResult = false;
        if (!result.isEmpty() && !overPanel) {
            double[] at = Picking.toGui(resultPosition());
            if (at != null) {
                double radius = resultRadiusGui();
                hoverResult = (mouse[0] - at[0]) * (mouse[0] - at[0]) + (mouse[1] - at[1]) * (mouse[1] - at[1]) < radius * radius;
            }
        }
        Vec3 hit = overPanel || hoverResult ? null : Picking.cursorOnPlane(surfaceY());
        hoverCell = -1;
        aim = null;
        if (hit == null) {
            return;
        }
        double lx = hit.x - pos.getX();
        double lz = hit.z - pos.getZ();
        if (lx > -0.02 && lx < 1.02 && lz > -0.02 && lz < 1.02) {
            hoverCell = TableLayouts.current().cellAt(pos, hit);
            aim = new Vec3(Math.max(pos.getX() + 0.07, Math.min(pos.getX() + 0.93, hit.x)), surfaceY(),
                    Math.max(pos.getZ() + 0.07, Math.min(pos.getZ() + 0.93, hit.z)));
        }
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        hoverCell = -1;
        hoverResult = false;
    }

    /** How far the camera must really move (blocks) before the items in the way are worked out again. */
    private static final double OCCLUSION_MOVE = 0.06;
    /** Points of the hovered cell the camera must see (fractions of a cell from its middle): its middle and four inside it. */
    private static final double[][] CELL_SAMPLES = {{0, 0}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}};
    private final boolean[] inTheWay = new boolean[TableGrid.CELLS];
    private int occlusionCell = Integer.MIN_VALUE;
    private int occlusionVersion = Integer.MIN_VALUE;
    @Nullable
    private Vec3 occlusionCamera;

    /**
     * The tabletop remains pickable through ingredients; only the ones in front of the hovered cell fade. Which ones is
     * worked out for the cell itself (rays to its middle and four points in it), and kept while the same cell stays under
     * the cursor: from the point under the cursor it changed with every small move of the mouse and every breath of the
     * camera, and the items at the edge of that line faded in and out.
     */
    void updateOcclusion(Vec3 camera) {
        ClientTables.Entry entry = ClientTables.get(pos);
        int cell = Scene.phase == Scene.Phase.ACTIVE && craftProgress() < 0 ? hoverCell : -1;
        int version = entry == null ? -1 : entry.version;
        if (cell != occlusionCell || version != occlusionVersion || occlusionCamera == null
                || camera.distanceToSqr(occlusionCamera) > OCCLUSION_MOVE * OCCLUSION_MOVE) {
            occlusionCell = cell;
            occlusionVersion = version;
            occlusionCamera = camera;
            TableLayout layout = TableLayouts.current();
            for (int i = 0; i < TableGrid.CELLS; i++) {
                ItemStack stack = entry == null ? ItemStack.EMPTY : entry.items[i];
                inTheWay[i] = false;
                if (cell < 0 || i == cell || stack.isEmpty()) continue;
                Vec3 base = cellPoint(i);
                net.minecraft.world.phys.AABB bounds = (layout.equals(TableLayout.VANILLA) ? ItemPlacer.restingBounds(stack, base) : layout.bounds(stack, base)).inflate(0.003);
                Vec3 target = cellPoint(cell);
                for (double[] at : CELL_SAMPLES) {
                    Vec3 point = new Vec3(target.x + at[0] * TableGrid.CELL, surfaceY(), target.z + at[1] * TableGrid.CELL);
                    if (bounds.clip(camera, point).isPresent()) {
                        inTheWay[i] = true;
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < TableGrid.CELLS; i++) {
            ItemStack stack = entry == null ? ItemStack.EMPTY : entry.items[i];
            float target = inTheWay[i] ? OCCLUDED_OPACITY : 1f;
            itemOpacity[i] = stack.isEmpty() ? 1f : (float) Anim.approach(itemOpacity[i], target, FADE_RATE, Scene.frameDt);
            if (Math.abs(itemOpacity[i] - target) < 0.001f) {
                itemOpacity[i] = target;
            }
        }
    }

    boolean[] inTheWayCopy() {
        return inTheWay.clone();
    }

    public float itemOpacity(int cell) {
        return cell >= 0 && cell < itemOpacity.length ? itemOpacity[cell] : 1f;
    }

    boolean hasFadedItems() {
        for (float opacity : itemOpacity) {
            if (opacity < 0.999f) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        Vec3 reach = new Vec3(aim.x - shoulder.x, 0, aim.z - shoulder.z).normalize();
        double hover = carrying ? 0.075 : 0.045;
        return new Vec3(aim.x, surfaceY() + HAND_Y + hover - 0.06 * tap, aim.z).subtract(reach.scale(ITEM_REACH));
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        Vec3 reach = new Vec3(hand.x - shoulder.x, 0, hand.z - shoulder.z).normalize();
        return hand.add(reach.scale(ITEM_REACH)).add(0, -HAND_Y + 0.012, 0);
    }

    @Nullable
    @Override
    public Vec3[] handsOverride() {
        Vec3[] lay = layHands();
        if (lay != null) {
            return lay;
        }
        double craft = craftProgress();
        if (craft < 0) {
            return null;
        }
        Vec3 middle = TableLayouts.current().center(pos).add(side().scale(0.04));
        double y = HAND_Y + 0.05 - 0.07 * Anim.hump(Anim.window(craft, 0.08, 0.62));
        return new Vec3[]{middle.add(right().scale(0.085)).add(0, y, 0), middle.add(right().scale(-0.085)).add(0, y, 0)};
    }

    // ---- actions ---------------------------------------------------------------------------------------

    @Override
    public void click(int button, boolean shift) {
        if (laying != null) {
            return;
        }
        if (hoverResult && button == 0) {
            craft(shift);
            return;
        }
        if (hoverCell < 0) {
            return;
        }
        boolean carrying = !Hands.carried().isEmpty();
        if (button == 0 && !shift && carrying) {
            spreadStart = hoverCell;
            spreadMask = spreadable(hoverCell) ? 1 << hoverCell : 0;
            spreadStack = Hands.carried().copy();
            spreadSince = Scene.clock();
            return;
        }
        send(StationActionPayload.Action.CLICK, hoverCell, button, shift);
        if (button == 1 && carrying) {
            painting = true;
            Arrays.fill(painted, false);
            painted[hoverCell] = true;
        }
    }

    /** Right-drag across cells drops one item into each. */
    @Override
    public void drag(int button) {
        if (painting && button == 1) {
            Scene.updateCursor();
            if (hoverCell >= 0 && !painted[hoverCell]) {
                painted[hoverCell] = true;
                send(StationActionPayload.Action.PAINT, hoverCell, 1, false);
            }
        }
        if (spreadStart >= 0 && button == 0) {
            Scene.updateCursor();
            if (hoverCell >= 0 && (spreadMask & 1 << hoverCell) == 0 && spreadable(hoverCell)
                    && spreadStack.getCount() > Integer.bitCount(spreadMask)) {
                spreadMask |= 1 << hoverCell;
                Scene.sound(net.minecraft.sounds.SoundEvents.WOOD_HIT, 0.12f, 1.6f + 0.05f * Integer.bitCount(spreadMask));
            }
        }
    }

    @Override
    public void release(int button) {
        if (button == 1) {
            painting = false;
        }
        if (button == 0 && spreadStart >= 0) {
            int start = spreadStart, mask = spreadMask;
            cancelSpread();
            if (Integer.bitCount(mask) > 1) spread(mask);
            else send(StationActionPayload.Action.CLICK, start, 0, false);
        }
    }

    private void cancelSpread() {
        spreadStart = -1;
        spreadMask = 0;
    }

    private boolean spreadable(int cell) {
        ClientTables.Entry entry = ClientTables.get(pos);
        ItemStack onCell = entry == null ? ItemStack.EMPTY : entry.items[cell];
        ItemStack held = Hands.carried();
        return !held.isEmpty() && (onCell.isEmpty() || ItemStack.isSameItemSameComponents(held, onCell));
    }

    private int spreadCells() {
        return spreadStart >= 0 && Integer.bitCount(spreadMask) > 1 ? spreadMask : 0;
    }

    private int spreadShare() {
        int cells = Integer.bitCount(spreadCells());
        return cells == 0 ? 0 : Hands.carried().getCount() / cells;
    }

    private void spread(int mask) {
        Hands.tap();
        if (!Scene.clientOnly()) {
            PacketDistributor.sendToServer(new StationActionPayload(pos, StationKind.TABLE, StationActionPayload.Action.SPREAD,
                    mask, 0, false, 0));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        CraftingMenu menu = vanillaMenu();
        if (menu == null || mc.gameMode == null || mc.player == null) return;
        int type = 0;
        mc.gameMode.handleContainerInput(menu.containerId, -999,
                net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftMask(0, type), ContainerInput.QUICK_CRAFT, mc.player);
        for (int cell = 0; cell < TableGrid.CELLS; cell++) {
            if ((mask & 1 << cell) != 0 && menuCell(cell) > 0)
                mc.gameMode.handleContainerInput(menu.containerId, menuCell(cell),
                        net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftMask(1, type), ContainerInput.QUICK_CRAFT, mc.player);
        }
        mc.gameMode.handleContainerInput(menu.containerId, -999,
                net.minecraft.world.inventory.AbstractContainerMenu.getQuickcraftMask(2, type), ContainerInput.QUICK_CRAFT, mc.player);
    }

    private void send(StationActionPayload.Action action, int cell, int button, boolean shift) {
        Hands.tap();
        if (Scene.clientOnly()) clickVanilla(cell, button, shift);
        else PacketDistributor.sendToServer(new StationActionPayload(pos, StationKind.TABLE, action, cell, button, shift, 0));
    }

    @Nullable
    private CraftingMenu vanillaMenu() {
        var player = Minecraft.getInstance().player;
        return player != null && player.containerMenu instanceof CraftingMenu menu ? menu : null;
    }

    private int menuCell(int world) {
        for (int logical = 0; logical < TableGrid.CELLS; logical++)
            if (TableGrid.worldIndex(side, logical / 3, logical % 3) == world) return logical + 1;
        return -1;
    }

    private void clickVanilla(int world, int button, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        CraftingMenu menu = vanillaMenu();
        int slot = menuCell(world);
        if (menu != null && slot > 0 && mc.gameMode != null && mc.player != null)
            mc.gameMode.handleContainerInput(menu.containerId, slot, button,
                    shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
    }

    private void placeVanilla(int world, int source, int count) {
        Minecraft mc = Minecraft.getInstance();
        CraftingMenu menu = vanillaMenu();
        if (menu == null || mc.gameMode == null || mc.player == null || menuCell(world) < 0) return;
        if (source < 0) {
            for (int i = 0; i < count && !menu.getCarried().isEmpty(); i++) clickVanilla(world, 1, false);
            return;
        }
        // A vanilla pickup, a right-click for each item, and return. The server validates every click and recipe.
        if (!menu.getCarried().isEmpty() || source >= 36) return;
        int inventorySlot = source < 9 ? 37 + source : 10 + source - 9;
        if (menu.getSlot(inventorySlot).getItem().isEmpty()) return;
        mc.gameMode.handleContainerInput(menu.containerId, inventorySlot, 0, ContainerInput.PICKUP, mc.player);
        if (menu.getCarried().getCount() <= count) clickVanilla(world, 0, false);
        else for (int i = 0; i < count && !menu.getCarried().isEmpty(); i++) clickVanilla(world, 1, false);
        if (!menu.getCarried().isEmpty())
            mc.gameMode.handleContainerInput(menu.containerId, inventorySlot, 0, ContainerInput.PICKUP, mc.player);
    }

    public void craft(boolean all) {
        if (result.isEmpty()) {
            return;
        }
        ClientTables.Entry entry = ClientTables.get(pos);
        if (entry != null) {
            craftItems = new ItemStack[TableGrid.CELLS];
            for (int i = 0; i < TableGrid.CELLS; i++) {
                craftItems[i] = entry.items[i].copy();
            }
            craftFacing = entry.facing.clone();
        }
        craftAt = Scene.clock();
        if (Scene.clientOnly()) {
            Minecraft mc = Minecraft.getInstance();
            CraftingMenu menu = vanillaMenu();
            if (menu != null && mc.gameMode != null && mc.player != null)
                mc.gameMode.handleContainerInput(menu.containerId, 0, 0,
                        all ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        } else {
            PacketDistributor.sendToServer(new StationActionPayload(pos, StationKind.TABLE, StationActionPayload.Action.CRAFT, 0, 0, all, 0));
        }
    }

    /** 0..1 progress of the craft flourish, or -1 when idle. */
    public double craftProgress() {
        double t = (Scene.clock() - craftAt) / CRAFT_LENGTH;
        return t >= 0 && t <= 1 ? t : -1;
    }

    @Override
    public void tick() {
        recipeBook.tick();
        Minecraft mc = Minecraft.getInstance();
        ClientTables.Entry entry = ClientTables.get(pos);
        int version = entry == null ? -1 : entry.version;
        if (version == resultVersion && !Scene.clientOnly()) {
            return;
        }
        resultVersion = version;
        ItemStack next = Scene.clientOnly() ? vanillaMenu() == null ? ItemStack.EMPTY : vanillaMenu().getSlot(0).getItem()
                : choices().active() ? choices().selectedResult()
                : entry == null || mc.level == null ? ItemStack.EMPTY
                : TableCrafting.match(mc.level, entry.list(), side).map(TableCrafting.Match::result).orElse(ItemStack.EMPTY);
        if (!ItemStack.isSameItemSameComponents(next, result) || next.getCount() != result.getCount()) {
            resultChangedAt = Scene.clock();
        }
        result = next;
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera);
        if (gridEditor.visible()) return;
        drawResult(pose, buffers, camera, light);
        drawLayItem(pose, buffers, camera, light);
        drawSpreadPreview(pose, buffers, camera, light);
        recipeBook.renderWorld(pose, buffers, camera, light);
    }

    private void drawSpreadPreview(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        int spread = spreadCells();
        ItemStack held = Hands.carried();
        if (spread == 0 || held.isEmpty() || spreadShare() <= 0) return;
        ClientTables.Entry entry = ClientTables.get(pos);
        float appear = (float) Anim.smoother((Scene.clock() - spreadSince) / 0.2);
        if (buffers instanceof SceneBuffers.BufferSource source) source.endBatch();
        for (int cell = 0; cell < TableGrid.CELLS; cell++) {
            if ((spread & 1 << cell) == 0 || entry != null && !entry.items[cell].isEmpty()) continue;
            TableLayout layout = TableLayouts.current();
            layout.renderItem(pose, buffers, camera, held.copyWithCount(spreadShare()),
                    layout.cell(pos, cell).add(0, 0.002, 0), side, cell, 1, 0, light, appear * 0.4f, false);
        }
        if (buffers instanceof SceneBuffers.BufferSource source) source.endBatch();
    }

    @Override
    public void renderScreen(GuiGraphicsExtractor graphics, Font font, float alpha) {
        if (!gridEditor.visible()) recipeBook.render(graphics, font, alpha);
        gridEditor.render(graphics, font, alpha);
    }

    void drawHighlight(PoseStack pose, SceneBuffers buffers, Vec3 cam) {
        if (craftProgress() >= 0) {
            return;
        }
        int spread = spreadCells();
        for (int cell = 0; cell < TableGrid.CELLS; cell++) {
            if (cell == hoverCell || (spread & 1 << cell) != 0) drawHighlightCell(pose, buffers, cam, cell);
        }
    }

    private void drawHighlightCell(PoseStack pose, SceneBuffers buffers, Vec3 cam, int cell) {
        double half = TableGrid.CELL / 2 - 0.006;
        pose.pushPose();
        TableLayouts.current().cellPose(pose, pos, cell, cam);
        double cx = 0, cz = 0, y = 0;
        // one even, steady glow: the rim blends over the fill instead of fighting it for depth
        int alpha = 47;
        Matrix4f m = pose.last().pose();
        VertexConsumer quads = buffers.getBuffer(Fx.HIGHLIGHT);
        quads.addVertex(m, (float) (cx - half), (float) y, (float) (cz - half)).setColor(255, 244, 214, alpha);
        quads.addVertex(m, (float) (cx - half), (float) y, (float) (cz + half)).setColor(255, 244, 214, alpha);
        quads.addVertex(m, (float) (cx + half), (float) y, (float) (cz + half)).setColor(255, 244, 214, alpha);
        quads.addVertex(m, (float) (cx + half), (float) y, (float) (cz - half)).setColor(255, 244, 214, alpha);
        double width = 0.004;
        // The rim makes a partly hidden destination readable through the faded ingredient.
        highlightStrip(quads, m, cx - half, cz - half, cx + half, cz - half + width, y, 150);
        highlightStrip(quads, m, cx - half, cz + half - width, cx + half, cz + half, y, 150);
        highlightStrip(quads, m, cx - half, cz - half, cx - half + width, cz + half, y, 150);
        highlightStrip(quads, m, cx + half - width, cz - half, cx + half, cz + half, y, 150);
        pose.popPose();
    }

    private static void highlightStrip(VertexConsumer quads, Matrix4f m, double x0, double z0, double x1, double z1, double y, int alpha) {
        quads.addVertex(m, (float) x0, (float) y, (float) z0).setColor(255, 235, 185, alpha);
        quads.addVertex(m, (float) x0, (float) y, (float) z1).setColor(255, 235, 185, alpha);
        quads.addVertex(m, (float) x1, (float) y, (float) z1).setColor(255, 235, 185, alpha);
        quads.addVertex(m, (float) x1, (float) y, (float) z0).setColor(255, 235, 185, alpha);
    }

    /** The result floats over the far edge of the table, turning slowly. */
    public Vec3 resultPosition() {
        double bob = Math.sin(Scene.clock() * 2.1) * 0.012;
        return top.add(forward().scale(0.42)).add(0, 0.17 + bob, 0);
    }

    /** Click radius of the result, in GUI pixels. */
    private double resultRadiusGui() {
        Vec3 at = resultPosition();
        double[] a = Picking.toGui(at);
        double[] b = Picking.toGui(at.add(0, RESULT_SIZE * 0.5, 0));
        if (a == null || b == null) {
            return 12;
        }
        return Math.max(10, Math.hypot(a[0] - b[0], a[1] - b[1]) * 1.25);
    }

    private void drawResult(PoseStack pose, SceneBuffers buffers, Vec3 cam, int light) {
        if (result.isEmpty()) {
            return;
        }
        double craft = craftProgress();
        double appear = Anim.outBack(Anim.window(Scene.clock() - resultChangedAt, 0, 0.35), 1.8);
        double hover = hoverResult ? 1.14 : 1.0;
        double pop = craft >= 0 ? 1 - 0.6 * Anim.hump(Anim.window(craft, 0.15, 0.55)) : 1;
        float size = (float) (RESULT_SIZE * appear * hover * pop);
        if (size <= 0.002f) {
            return;
        }
        Vec3 at = resultPosition();
        float spin = (float) (Scene.clock() * (hoverResult ? 70 : 32) % 360);
        Minecraft mc = Minecraft.getInstance();
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees(spin)));
        boolean block = ItemPlacer.isBlock(result);
        float scale = block ? size * 1.6f : size;
        pose.scale(scale, scale, scale);
        int glow = LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(light), 12), LightCoordsUtil.sky(light));
        ItemModels.drawStatic(result, glow, OverlayTexture.NO_OVERLAY, pose, buffers);
        pose.popPose();
    }

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] mouse = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(mouse[0], mouse[1]);
        if (editorTip != null || gridEditor.editing()) return editorTip;
        List<Component> guide = recipeBook.tooltip(mouse[0], mouse[1]);
        if (guide != null || recipeBook.over(mouse[0], mouse[1])) return guide;
        int spread = Integer.bitCount(spreadCells());
        if (spread > 0) {
            int share = spreadShare();
            int rest = carried.getCount() - share * spread;
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("cinematic.hint.spread", share, spread));
            if (rest > 0) lines.add(Component.translatable("cinematic.hint.spread_rest", rest).withStyle(ChatFormatting.GRAY));
            return lines;
        }
        if (!carried.isEmpty()) {
            return null;
        }
        if (hoverResult && !result.isEmpty()) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), result));
            lines.add(Component.translatable("cinematic.hint.result").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        ClientTables.Entry entry = ClientTables.get(pos);
        if (hoverCell >= 0 && entry != null && !entry.items[hoverCell].isEmpty()) {
            ItemStack stack = entry.items[hoverCell];
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), stack));
            lines.add(Component.translatable("cinematic.hint.table_count", stack.getCount()).withStyle(ChatFormatting.GRAY));
            return lines;
        }
        return null;
    }

    @Override
    public Component hint() {
        if (gridEditor.editing()) return TableGridEditor.text("hint");
        return Component.translatable("cinematic.hint.table");
    }
}
