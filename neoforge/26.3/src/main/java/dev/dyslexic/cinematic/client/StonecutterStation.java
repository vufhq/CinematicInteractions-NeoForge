package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.phys.Vec3;

/**
 * The stonecutter scene. The player leans over the saw. The stone is laid on the table beside the blade, and
 * the shapes it can be cut into hang in an arc round the blade like a dial (the mouse wheel turns it when there
 * are many). Touching a shape cuts one: the left hand takes a block off the stone, sets it at the end of the blade
 * and pushes it through in a spray of stone dust; what comes out on the other side is the new piece, caught by
 * the right hand.
 * Shift cuts the whole stack. Underneath is the vanilla stonecutter menu: the shape is its recipe button, the
 * piece its result slot.
 */
public final class StonecutterStation extends MenuStation<StonecutterMenu> implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    static final double HAND_Y = 0.062;
    static final double ITEM_REACH = 0.07;
    /** Height of the table top: the block is 9 px tall, the blade stands up out of it. */
    static final double TABLE = 9 / 16.0;
    static final double BLADE = 7 / 16.0;
    /** The dial is a flattened arc round the blade: wide across, lower up. */
    static final double DIAL_WIDE = 0.72;
    static final double DIAL_HIGH = 0.5;
    static final int DIAL_SLOTS = 9;
    static final float SHAPE_SIZE = 0.15f;
    static final float PIECE_SIZE = 0.2f;

    public static final double CUT_LENGTH = 1.05;
    public static final double CUT_ALL_LENGTH = 1.5;
    /** The block meets the blade: the server cuts now. */
    static final double BITE = 0.26;

    public enum Target { NONE, PLACE, STACK, SHAPE }

    public final Vec3 top;
    public Target target = Target.NONE;
    public int hoverShape = -1;

    private double scroll;
    private int scrollTarget;
    private int lastShapes = -1;
    private double shapesAt = -10;
    private int lastHover = -1;

    // the move, as it was when it began
    private ItemStack input = ItemStack.EMPTY;
    private ItemStack piece = ItemStack.EMPTY;
    private boolean cutAll;
    private double length = CUT_LENGTH;

    public StonecutterStation(BlockPos pos, Direction side) {
        super(pos, side, StonecutterMenu.class, 1);
        top = topOf(pos);
    }

    static Vec3 topOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + TABLE, pos.getZ() + 0.5);
    }

    private static Vec3 rightOf(Direction side) {
        return Vec3.atLowerCornerOf(side.getOpposite().getClockWise().getUnitVec3i());
    }

    @Override
    public StationKind kind() {
        return StationKind.STONECUTTER;
    }

    @Override
    public Vec3 center() {
        return top;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.STONE_STEP;
    }

    @Override
    public BlockPos lightPos() {
        return pos.above();
    }

    static Vec3 stackPoint(Vec3 top, Direction side) {
        return RemainingLayouts.point(StationKind.STONECUTTER, top, side, 0);
    }

    @Override
    protected Vec3 restPoint(int slot) {
        return stackPoint(top, side).add(right().scale(denyShake()));
    }

    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(top.x + side.getStepX() * 0.95, player.getY(), top.z + side.getStepZ() * 0.95);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 eye = top.add(side().scale(1.0)).add(0, 0.95, 0);
        Vec3 look = top.add(side().scale(-0.05)).add(0, 0.25, 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, 1.5f);
    }

    @Override
    public float bodyPitch() {
        return 50f;
    }

    @Override
    public Vec3 rest(boolean right) {
        return top.add(side().scale(0.36)).add(right().scale(right ? 0.26 : -0.38)).add(0, HAND_Y, 0);
    }

    // ---- the dial ------------------------------------------------------------------------------------

    private List<net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry<StonecutterRecipe>> shapes() {
        StonecutterMenu menu = menu();
        return menu == null || !menu.hasInputItem() ? List.of() : menu.getVisibleRecipes().entries();
    }

    private ItemStack shapeResult(int k) {
        Minecraft mc = Minecraft.getInstance();
        List<net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry<StonecutterRecipe>> shapes = shapes();
        return k < 0 || k >= shapes.size() || mc.level == null ? ItemStack.EMPTY : shapes.get(k).recipe().optionDisplay().resolveForFirstStack(net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(mc.level));
    }

    private int visible() {
        return Math.min(DIAL_SLOTS, shapes().size());
    }

    private double step() {
        int n = visible();
        return n <= 1 ? 0 : Math.min(22, 144.0 / (n - 1));
    }

    /** Angle of a shape on the dial in degrees: 90 is straight over the blade, the first ones to the left. */
    private double angle(int k) {
        return 90 + ((visible() - 1) / 2.0 - (k - scroll)) * step();
    }

    /** 0..1: shapes turned past the ends of the dial fade away. */
    private double onDial(int k) {
        double a = angle(k);
        return Anim.smoother(Anim.window(a, 4, 18)) * Anim.smoother(Anim.window(176 - a, 0, 14));
    }

    public Vec3 shapePoint(int k) {
        double bob = 0.008 * Math.sin(Scene.clock() * 1.7 + k * 1.3);
        return dialPoint(angle(k), 0.04 + bob, fit());
    }

    /**
     * Ways for the dial to make room for blocks standing beside the stonecutter: {width, lift, towards the player}. The
     * full dial reaches 0.72 to either side, into the neighbouring blocks; then it closes in over the blade, rises above
     * them and comes forward, where the player stands and nothing is in the way.
     */
    private static final double[][] DIAL_FITS = {{DIAL_WIDE, 0, 0}, {0.5, 0.08, 0.04}, {0.42, 0.14, 0.26}, {0.34, 0.2, 0.42}};
    /** Room a shape needs round its middle (blocks): its own size, give or take. */
    private static final double SHAPE_ROOM = 0.07;
    /** Seconds between looks at the blocks around (one may be placed or broken while working). */
    private static final double FIT_EVERY = 0.5;
    /** For the walkthrough's before/after: the dial as it was, whatever stands beside. */
    static boolean fitsDial = true;
    private int fitIndex;
    private double fitAt = -10;

    private double[] fit() {
        if (Scene.clock() - fitAt > FIT_EVERY) {
            fitAt = Scene.clock();
            fitIndex = DIAL_FITS.length - 1;
            for (int i = 0; i < DIAL_FITS.length; i++) {
                if (!fitsDial || free(DIAL_FITS[i])) {
                    fitIndex = i;
                    break;
                }
            }
        }
        return DIAL_FITS[fitIndex];
    }

    int dialFit() {
        return fitIndex;
    }

    /** No shape of this dial, all the way round, inside a block (the stonecutter itself has nothing up there). */
    private boolean free(double[] fit) {
        var level = Minecraft.getInstance().level;
        if (level == null) return true;
        for (double a = 4; a <= 176; a += 8) {
            Vec3 at = dialPoint(a, 0.04, fit);
            net.minecraft.core.BlockPos block = net.minecraft.core.BlockPos.containing(at);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                net.minecraft.core.BlockPos near = block.offset(dx, dy, dz);
                if (near.equals(pos)) continue;
                for (net.minecraft.world.phys.AABB box : level.getBlockState(near).getShape(level, near).toAabbs()) {
                    if (box.move(near).inflate(SHAPE_ROOM).contains(at)) return false;
                }
            }
        }
        return true;
    }

    private Vec3 dialPoint(double degrees, double up, double[] fit) {
        double a = Math.toRadians(degrees);
        Vec3 original = top.add(side().scale(-0.06 + fit[2])).add(right().scale(Math.cos(a) * fit[0]))
                .add(0, up + fit[1] + Math.sin(a) * DIAL_HIGH, 0);
        return RemainingLayouts.transform(kind(), top, side, 2, original);
    }

    @Override
    public void scroll(double delta) {
        int max = Math.max(0, shapes().size() - DIAL_SLOTS);
        int next = Mth.clamp(scrollTarget - (int) Math.signum(delta), 0, max);
        if (next != scrollTarget) {
            scrollTarget = next;
            Scene.sound(SoundEvents.UI_STONECUTTER_SELECT_RECIPE, 0.3f, 1.4f);
        }
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || menu() == null || moving()) {
            return;
        }
        double[] mouse = Picking.mouseGui();
        List<net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry<StonecutterRecipe>> shapes = shapes();
        for (int k = 0; k < shapes.size(); k++) {
            if (onDial(k) > 0.5 && GrindstoneStation.under(mouse, shapePoint(k), 0.075 * geometry().scale(2))) {
                target = Target.SHAPE;
                hoverShape = k;
                aim = shapePoint(k).add(0, -0.1, 0).add(side().scale(0.12));
                return;
            }
        }
        ItemStack carried = Hands.carried();
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            if (layoutPick() == 0) {
                target = carried.isEmpty() ? slot(0).isEmpty() ? Target.NONE : Target.STACK : Target.PLACE;
                aim = restPoint(0);
            }
            return;
        }
        if (carried.isEmpty() && !slot(0).isEmpty() && GrindstoneStation.under(mouse, restPoint(0).add(0, 0.08, 0), 0.12)) {
            target = Target.STACK;
            aim = restPoint(0).add(0, 0.12, 0);
            return;
        }
        Vec3 hit = Picking.cursorOnPlane(top.y);
        if (!carried.isEmpty() && hit != null && Math.abs(hit.subtract(top).dot(right())) < 0.52 && Math.abs(hit.subtract(top).dot(side())) < 0.52) {
            target = Target.PLACE;
            aim = restPoint(0);
        }
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        hoverShape = -1;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        Vec3 reach = new Vec3(aim.x - shoulder.x, 0, aim.z - shoulder.z).normalize();
        double hover = carrying ? 0.075 : 0.045;
        return aim.add(0, HAND_Y + hover - 0.06 * tap, 0).subtract(reach.scale(ITEM_REACH));
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        Vec3 reach = new Vec3(hand.x - shoulder.x, 0, hand.z - shoulder.z).normalize();
        return hand.add(reach.scale(ITEM_REACH)).add(0, -HAND_Y + 0.012, 0);
    }

    // ---- actions -------------------------------------------------------------------------------------

    @Override
    protected void act(int button, boolean shift) {
        switch (target) {
            case PLACE -> {
                slotClick(0, button, false);
                Scene.sound(SoundEvents.STONE_PLACE, 0.6f, 1.1f);
            }
            case STACK -> slotClick(0, button, shift);
            case SHAPE -> {
                ItemStack carried = Hands.carried();
                ItemStack result = shapeResult(hoverShape);
                boolean room = carried.isEmpty() || ItemStack.isSameItemSameComponents(carried, result)
                        && carried.getCount() + result.getCount() <= carried.getMaxStackSize();
                if (button != 0 || result.isEmpty() || (!room && !shift)) {
                    deny();
                    return;
                }
                cut(hoverShape, shift);
            }
            default -> {}
        }
    }

    private void cut(int k, boolean all) {
        StonecutterMenu menu = menu();
        Minecraft mc = Minecraft.getInstance();
        if (menu == null || mc.gameMode == null || mc.player == null) {
            return;
        }
        if (menu.getSelectedRecipeIndex() != k) {
            // the window does the same: pick the recipe here and tell the server
            menu.clickMenuButton(mc.player, k);
            mc.gameMode.handleInventoryButtonClick(menu.containerId, k);
            Scene.sound(SoundEvents.UI_STONECUTTER_SELECT_RECIPE, 0.6f, 1.0f);
        }
        input = slot(0).copy();
        piece = slot(1).isEmpty() ? shapeResult(k) : slot(1).copy();
        cutAll = all;
        length = all ? CUT_ALL_LENGTH : CUT_LENGTH;
        startMove(length, BITE, () -> {
            StonecutterMenu open = menu();
            if (slot(1).isEmpty()) {
                cancelMove();
                return;
            }
            mc.gameMode.handleContainerInput(open.containerId, 1, 0, cutAll ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        });
        showMove(all);
    }

    @Override
    public void tick() {
        int count = shapes().size();
        if (count != lastShapes) {
            lastShapes = count;
            shapesAt = Scene.clock();
            scrollTarget = 0;
        }
        if (hoverShape != lastHover && hoverShape >= 0) {
            Scene.sound(SoundEvents.STONE_HIT, 0.3f, 1.6f + hoverShape % 3 * 0.1f);
        }
        lastHover = hoverShape;
    }

    // ---- the move ------------------------------------------------------------------------------------

    /** When the piece is out past the blade: one block, or the whole stack. */
    static double outAt(boolean all) {
        return all ? 1.15 : 0.72;
    }

    /** How far along the blade (in blocks, from the middle to the right) the piece is at time {@code t}. */
    private double along(double t) {
        return along(t, cutAll);
    }

    static double along(double t, boolean all) {
        return -0.46 + 0.76 * Anim.inOutSine(Anim.window(t, BITE - 0.04, outAt(all)));
    }

    /** The piece until it is out past the blade: taken off the stack, set at the end of the blade, pushed through. */
    static Vec3 piecePath(Vec3 top, Direction side, double t, boolean all) {
        if (t < BITE) {
            Vec3 onStack = stackPoint(top, side).add(0, PIECE_SIZE / 2 + 0.01, 0);
            Vec3 start = RemainingLayouts.transform(StationKind.STONECUTTER, top, side, 1, top.add(rightOf(side).scale(-0.46)).add(0, PIECE_SIZE / 2, 0));
            double k = Anim.inOutCubic(Anim.window(t, 0.06, BITE - 0.02));
            return Anim.lerp(onStack, start, k).add(0, 0.07 * Anim.hump(k), 0);
        }
        return RemainingLayouts.transform(StationKind.STONECUTTER, top, side, 1, top.add(rightOf(side).scale(along(t, all))).add(0, PIECE_SIZE / 2, 0));
    }

    /** The piece in the hand: taken off the stack, set at the end of the blade, pushed through, picked up. */
    private Vec3 piecePos(double t, Camera camera) {
        double out = outAt(cutAll);
        Vec3 sliding = piecePath(top, side, Math.min(t, out), cutAll);
        if (t < out) {
            return sliding;
        }
        if (cutAll) {
            Vec3 drawer = camera.position().add(0, 0.5, 0).add(Scene.yawForward(camera.yRot()).scale(0.6));
            return Anim.lerp(sliding, drawer, Anim.inCubic(Anim.window(t, out, length)));
        }
        Vec3 held = heldItemPoint(Hands.position(true), Hands.shoulder(true, camera.position(), camera.yRot()));
        double k = Anim.inOutCubic(Anim.window(t, out, length));
        return Anim.lerp(sliding, held, k).add(0, 0.06 * Anim.hump(k), 0);
    }

    /** Where the right hand waits for the piece, past the far end of the blade. */
    private Vec3 catchPoint() {
        return RemainingLayouts.transform(kind(), top, side, 1, top.add(right().scale(0.34)).add(side().scale(0.08)).add(0, PIECE_SIZE * 0.6 + HAND_Y, 0));
    }

    @Nullable
    @Override
    public Vec3[] handsOverride() {
        double t = moveTime();
        if (t < 0) {
            return null;
        }
        double out = cutAll ? 1.15 : 0.72;
        // the left hand takes a block off the stone and pushes it through the blade from the left
        Vec3 onStack = restPoint(0).add(0, PIECE_SIZE + HAND_Y, 0).add(side().scale(0.05));
        Vec3 left;
        if (t < 0.08) {
            left = Anim.lerp(rest(false), onStack, Anim.inOutCubic(t / 0.08));
        } else {
            Vec3 start = RemainingLayouts.transform(kind(), top, side, 1, top.add(right().scale(-0.63)).add(0, PIECE_SIZE * 0.55 + HAND_Y, 0).add(side().scale(0.05)));
            Vec3 path = t < BITE ? Anim.lerp(onStack, start, Anim.inOutCubic(Anim.window(t, 0.06, BITE - 0.02)))
                    : RemainingLayouts.transform(kind(), top, side, 1, top.add(right().scale(along(t) - 0.17)).add(0, PIECE_SIZE * 0.55 + HAND_Y, 0).add(side().scale(0.05)));
            left = Anim.lerp(path, rest(false), Anim.inOutCubic(Anim.window(t, out - 0.05, length)));
        }
        // the right hand waits past the blade for the new piece, then keeps it
        Vec3 waiting = Anim.lerp(rest(true), catchPoint(), Anim.inOutCubic(Anim.window(t, BITE, out - 0.05)));
        Vec3 end = rest(true).add(0, cutAll ? 0 : 0.05, 0);
        Vec3 right = Anim.lerp(waiting, end, Anim.inOutCubic(Anim.window(t, out, length)));
        return new Vec3[]{right, left};
    }

    @Override
    public double cameraKick() {
        double t = moveTime();
        if (t < 0) {
            return 0;
        }
        return cutting(t) ? 0.12 : 0;
    }

    /** The piece is inside the blade. */
    private boolean cutting(double t) {
        return cutting(t, cutAll);
    }

    static boolean cutting(double t, boolean all) {
        return t >= BITE && Math.abs(along(t, all)) < BLADE && t < outAt(all);
    }

    @Override
    void frame() {
        super.frame();
        double t = moveTime();
        if (t >= 0) {
            cutFx(top, side, t, cutAll, input, Scene.frameDt);
        }
    }

    /** Dust and a few sparks thrown up where the blade bites the stone, while it does. */
    static void cutFx(Vec3 top, Direction side, double t, boolean all, ItemStack stone, double dt) {
        if (!cutting(t, all)) {
            return;
        }
        Vec3 out = Vec3.atLowerCornerOf(side.getUnitVec3i());
        Vec3 bite = RemainingLayouts.transform(StationKind.STONECUTTER, top, side, 1, top.add(rightOf(side).scale(along(t, all) + PIECE_SIZE * 0.3)).add(0, PIECE_SIZE * 0.85, 0));
        if (Fx.RANDOM.nextDouble() < dt * 45) {
            Fx.fountain(bite, out.scale(-0.3), out, 1, 1.6);
        }
        dust(bite, rightOf(side).scale(0.5), (int) Math.round(dt * (all ? 110 : 75) + Fx.RANDOM.nextDouble()), stone);
    }

    /**
     * Chips and dust of the stone being cut, thrown up and off towards {@code away}, in the stone's own colour
     * (our own motes: vanilla block crumbs this close to the camera are big grey squares).
     */
    static void dust(Vec3 at, Vec3 away, int count, ItemStack stone) {
        int rgb = stone.getItem() instanceof BlockItem block ? block.getBlock().defaultMapColor().col : 0x8A8A8A;
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        for (int i = 0; i < count; i++) {
            Vec3 dir = away.add(Fx.RANDOM.nextGaussian() * 0.5, 0.8 + Fx.RANDOM.nextDouble() * 0.7, Fx.RANDOM.nextGaussian() * 0.5).normalize();
            float k = 0.55f + Fx.RANDOM.nextFloat() * 0.45f;
            Fx.spark(at.add(Fx.RANDOM.nextGaussian() * 0.015, 0, Fx.RANDOM.nextGaussian() * 0.015), dir.scale(0.6 + Fx.RANDOM.nextDouble() * 1.1),
                    0.3 + Fx.RANDOM.nextDouble() * 0.4, 0.005f + Fx.RANDOM.nextFloat() * 0.007f, r * k, g * k, b * k, 2.6, 0.012);
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------

    @Override
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        Vec3 cam = camera.position();
        scroll = Anim.approach(scroll, scrollTarget, 10, Scene.frameDt);
        if (outro()) {
            drawLeaving(pose, buffers, camera, light);
            return;
        }
        double t = moveTime();
        ItemStack stack = slot(0);
        if (t >= 0 && t < BITE && !cutAll) {
            // one block is already in the hand
            stack = input.copyWithCount(Math.max(0, input.getCount() - 1));
        } else if (t >= 0 && cutAll) {
            stack = ItemStack.EMPTY;
        }
        drawStack(pose, buffers, cam, stack, restPoint(0), side, light);
        if (t >= 0) {
            drawPiece(pose, buffers, camera, t, light);
        }
    }

    private void drawPiece(PoseStack pose, SceneBuffers buffers, Camera camera, double t, int light) {
        float size = PIECE_SIZE * RemainingLayouts.scale(StationKind.STONECUTTER, 1);
        double out = outAt(cutAll);
        if (t > out) {
            size *= cutAll ? (float) (1 - 0.85 * Anim.inCubic(Anim.window(t, out, length))) : (float) (1 - 0.3 * Anim.window(t, out, length));
        }
        drawPieceAt(pose, buffers, camera, top, side, piecePos(t, camera), t, input, piece, cutAll, size, light);
    }

    /**
     * The block being cut, at {@code at}: the stone until the blade is through it, the new piece after (a few of them for
     * a stack cut whole), and the bright bite of the blade in it.
     */
    static void drawPieceAt(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 top, Direction side, Vec3 at, double t, ItemStack input,
            ItemStack piece, boolean all, float size, int light) {
        Vec3 cam = camera.position();
        Vec3 right = rightOf(side);
        boolean cut = t >= BITE && along(t, all) > 0;
        ItemStack shown = cut ? piece : input.copyWithCount(1);
        float yaw = ItemPlacer.awayYaw(side) + RemainingLayouts.rotation(StationKind.STONECUTTER, 1);
        boolean block = ItemPlacer.isBlock(shown);
        int copies = cut ? Math.min(3, Math.max(1, shown.getCount())) : all ? Math.min(3, Math.max(1, input.getCount() / 8)) : 1;
        for (int i = 0; i < copies; i++) {
            Vec3 offset = right.scale((i - (copies - 1) / 2.0) * size * 0.75).add(0, i * 0.01, 0);
            Vec3 c = at.add(offset).add(0, block ? 0 : -size / 2 + 0.006, 0);
            ItemPlacer.drawOriented(pose, buffers, cam, shown, c, block ? Axis.YP.rotationDegrees(yaw + 180) : ItemPlacer.lying(yaw, 0),
                    block ? size : size * 1.3f, light);
        }
        if (cutting(t, all)) {
            // the bright bite of the blade in the stone
            float b = (float) (0.45 + 0.2 * Math.sin(Scene.clock() * 50));
            Vec3 contact = RemainingLayouts.transform(StationKind.STONECUTTER, top, side, 1,
                    top.add(right.scale(along(t, all) + PIECE_SIZE * 0.3)).add(0, PIECE_SIZE * 0.8, 0));
            EnchantFx.billboard(pose, buffers, camera, contact, 0.04, 0, EnchantFx.GLOW,
                    b, b * 0.8f, b * 0.55f);
        }
    }

    /** The stone on the table: a cube or a little pile of them. */
    static void drawStack(PoseStack pose, SceneBuffers buffers, Vec3 cam, ItemStack stack, Vec3 at, Direction side, int light) {
        if (stack.isEmpty()) {
            return;
        }
        float yaw = ItemPlacer.awayYaw(side) + RemainingLayouts.rotation(StationKind.STONECUTTER, 0);
        float itemScale = RemainingLayouts.scale(StationKind.STONECUTTER, 0);
        if (!ItemPlacer.isBlock(stack)) {
            ItemPlacer.render(pose, buffers, cam, stack, at, side, 31L, 1.1f * itemScale, RemainingLayouts.rotation(StationKind.STONECUTTER, 0), light);
            return;
        }
        float cubeSize = PIECE_SIZE * itemScale;
        int cubes = stack.getCount() >= 40 ? 3 : stack.getCount() >= 12 ? 2 : 1;
        Vec3 right = SpatialLayouts.current(StationKind.STONECUTTER).axis(RemainingLayouts.frameAt(StationKind.STONECUTTER, Vec3.ZERO, side), 0, true);
        Vec3 out = SpatialLayouts.current(StationKind.STONECUTTER).axis(RemainingLayouts.frameAt(StationKind.STONECUTTER, Vec3.ZERO, side), 0, false);
        Vec3[] spots = {at, at.add(0, cubeSize, 0).add(right.scale(0.015)), at.add(right.scale(-cubeSize * 1.05)).add(out.scale(0.03))};
        float[] turns = {6f, -9f, 14f};
        for (int i = 0; i < cubes; i++) {
            ItemPlacer.drawOriented(pose, buffers, cam, stack, spots[i].add(0, cubeSize / 2, 0), Axis.YP.rotationDegrees(yaw + 180 + turns[i]),
                    cubeSize, light);
        }
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.mainCamera();
        StonecutterMenu menu = menu();
        if (menu == null) {
            return;
        }
        double clock = Scene.clock();
        if (target == Target.PLACE && aim != null) {
            float p = (float) (0.16 + 0.06 * Math.sin(clock * 5));
            EnchantFx.disc(pose, buffers, camera, aim.add(0, 0.004, 0), 0.13, 0, EnchantFx.GLOW, p, p * 0.9f, p * 0.75f);
        }
        List<net.minecraft.world.item.crafting.SelectableRecipe.SingleInputEntry<StonecutterRecipe>> shapes = shapes();
        double appear = Anim.outBack(Anim.window(clock - shapesAt, 0, 0.4), 1.4);
        double dim = moving() ? 0.45 : 1;
        for (int k = 0; k < shapes.size(); k++) {
            double on = onDial(k) * appear;
            if (on < 0.02) {
                continue;
            }
            ItemStack result = shapeResult(k);
            Vec3 at = shapePoint(k);
            boolean hovered = k == hoverShape;
            boolean selected = k == menu.getSelectedRecipeIndex();
            float g = (float) ((hovered ? 0.34 : 0.15) * on * dim);
            EnchantFx.billboard(pose, buffers, cam, at, (hovered ? 0.095 : 0.075) * on, 0, EnchantFx.GLOW, g, g * 0.95f, g * 0.85f);
            if (selected) {
                float s = (float) (0.4 * on * dim);
                EnchantFx.billboard(pose, buffers, cam, at, 0.085 * on, clock * 0.8, EnchantFx.CIRCLE, s, s * 0.85f, s * 0.4f);
            }
            float size = (float) (SHAPE_SIZE * geometry().scale(2) * on * (hovered ? 1.35 : 1.0));
            float spin = (float) (clock * (hovered ? 90 : 25) + k * 40 + RemainingLayouts.rotation(kind(), 2));
            boolean block = ItemPlacer.isBlock(result);
            ItemPlacer.drawOriented(pose, buffers, camera, result, at, Axis.YP.rotationDegrees(spin), block ? size * 0.85f : size * 1.2f,
                    LightCoordsUtil.FULL_BRIGHT);
            if (result.getCount() > 1) {
                drawCount(pose, buffers, cam, "×" + result.getCount(), at.add(0, -size * 0.85 - 0.02, 0), (float) on);
            }
        }
        if (shapes.size() > DIAL_SLOTS) {
            // more shapes behind either end of the dial: the wheel turns it
            if (scrollTarget > 0) {
                drawCount(pose, buffers, cam, "‹", shapeEnd(true), 1f);
            }
            if (scrollTarget < shapes.size() - DIAL_SLOTS) {
                drawCount(pose, buffers, cam, "›", shapeEnd(false), 1f);
            }
        }
    }

    private Vec3 shapeEnd(boolean left) {
        double a = Math.toRadians(left ? 176 : 4);
        return RemainingLayouts.transform(kind(), top, side, 2, top.add(side().scale(-0.06)).add(right().scale(Math.cos(a) * DIAL_WIDE)).add(0, 0.07 + Math.sin(a) * DIAL_HIGH, 0));
    }

    private static void drawCount(PoseStack pose, SceneBuffers buffers, Camera camera, String text, Vec3 at, float alpha) {
        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(camera.rotation()));
        float s = 0.0065f;
        pose.scale(s, -s, s);
        int a = Math.max(8, (int) (alpha * 255));
        SceneText.draw(font, text, -font.width(text) / 2f, -4f, a << 24 | 0xF2EAD8, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0,
                LightCoordsUtil.FULL_BRIGHT);
        pose.popPose();
    }

    // ---- what others see -----------------------------------------------------------------------------

    static void drawContents(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction side, List<ItemStack> items, int light) {
        if (!items.isEmpty()) {
            drawStack(pose, buffers, cam, items.get(0), stackPoint(topOf(pos), side), side, light);
        }
    }

    /**
     * Someone else cuts here, {@code t} seconds in: a block taken off the stone, pushed through the saw, and the new piece
     * picked up and away to them (the whole stack, cut whole).
     */
    static void drawOnlooker(PoseStack pose, SceneBuffers buffers, Camera camera, BlockPos pos, ClientMenus.Entry entry, double t, int light) {
        Vec3 top = topOf(pos);
        boolean all = entry.moveToBag;
        ItemStack input = entry.moved(0);
        drawStack(pose, buffers, camera.position(), all ? ItemStack.EMPTY : input.copyWithCount(Math.max(0, input.getCount() - 1)),
                stackPoint(top, entry.side), entry.side, light);
        double out = outAt(all);
        Vec3 at = piecePath(top, entry.side, Math.min(t, out), all);
        float size = PIECE_SIZE * RemainingLayouts.scale(StationKind.STONECUTTER, 1);
        if (t > out) {
            double away = Anim.inCubic(Anim.window(t, out, all ? CUT_ALL_LENGTH : CUT_LENGTH));
            at = Anim.lerp(at, entry.taker(at.add(Vec3.atLowerCornerOf(entry.side.getUnitVec3i()).scale(0.6))), away);
            size *= (float) (1 - 0.8 * away);
        }
        drawPieceAt(pose, buffers, camera, top, entry.side, at, t, input, entry.moveResult, all, size, light);
    }

    /** Someone else cuts here: dust and sparks while the blade is in the stone. */
    static void onlookerFx(BlockPos pos, ClientMenus.Entry entry, double t, double dt) {
        if (t >= 0) {
            cutFx(topOf(pos), entry.side, t, entry.moveToBag, entry.moved(0), dt);
        }
    }

    /** How far the arms have turned pushing the stone through the saw and catching the piece, as others see it ({left, right}). */
    public static float[] armTurns(double t) {
        double push = Anim.window(along(t, false), -0.46, 0.3);
        double reach = Anim.smoother(Anim.window(t, BITE, outAt(false))) * (1 - Anim.smoother(Anim.window(t, outAt(false), CUT_LENGTH)));
        return new float[]{(float) (-0.25 + 0.6 * push), (float) (-0.12 + 0.36 * reach)};
    }

    // ---- words ---------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] pointer = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(pointer[0], pointer[1]);
        if (editorTip != null) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        switch (target) {
            case SHAPE -> {
                ItemStack result = shapeResult(hoverShape);
                List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, result));
                if (result.getCount() > 1) {
                    lines.set(0, lines.get(0).copy().append(" ×" + result.getCount()));
                }
                boolean room = carried.isEmpty() || ItemStack.isSameItemSameComponents(carried, result)
                        && carried.getCount() + result.getCount() <= carried.getMaxStackSize();
                lines.add(room ? Component.translatable("cinematic.stonecutter.cut").withStyle(ChatFormatting.GOLD)
                        : Component.translatable("cinematic.stonecutter.hands_full").withStyle(ChatFormatting.RED));
                return lines;
            }
            case STACK -> {
                List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, slot(0)));
                lines.set(0, lines.get(0).copy().append(" ×" + slot(0).getCount()));
                if (shapes().isEmpty()) {
                    lines.add(Component.translatable("cinematic.stonecutter.no_shapes").withStyle(ChatFormatting.RED));
                }
                lines.add(Component.translatable("cinematic.stonecutter.take").withStyle(ChatFormatting.GRAY));
                return lines;
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.stonecutter");
    }

    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public java.util.List<Vec3> workSamples() { return RemainingLayouts.samples(this, super.workSamples()); }
    @Override public float layoutHeldScale() { return geometry().scale(target == Target.SHAPE ? 1 : 0); }
}
