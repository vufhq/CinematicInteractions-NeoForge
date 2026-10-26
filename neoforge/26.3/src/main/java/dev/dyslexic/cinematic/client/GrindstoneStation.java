package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The grindstone scene. The player stands facing the running face of the wheel, hands on the frame. Items are
 * laid on top of the wheel. Touching one grinds it: the hand takes it by the handle and the other presses the
 * blade down onto the stone. The wheel spins up, running away from the player, and the blade is drawn back and
 * forth across it. Sparks fountain off over the far side while the enchantment flakes off in violet motes and
 * falls away. Then the clean item is lifted up to the eye and kept in hand. Underneath is the vanilla
 * grindstone menu (two inputs, the result taken on the first touch of stone), so experience orbs and sounds
 * are vanilla's.
 */
public final class GrindstoneStation extends MenuStation<GrindstoneMenu> implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    /** Half the height of the wheel's square cross-section (12 px) and half its width along the axle (8 px). */
    static final double HALF = 6 / 16.0;
    static final double WIDTH = 4 / 16.0;
    static final double HAND_Y = 0.062;
    static final double ITEM_REACH = 0.07;
    static final float ITEM_SIZE = 0.3f;
    /** Turns a tool so its blade (a diagonal of the sprite) points straight away from the player. */
    static final float TOOL_TURN = -45f;
    /** Where an item lies on the wheel's top, sideways from the middle. */
    static final double SLOT_SIDE = 0.1;
    /** A blade is ground on a slant, tip away and inwards, so the hand holding it stays off the work. */
    static final float SLANT = 25f;

    public static final double GRIND_LENGTH = 2.4;
    /** The blade touches the stone: the result is taken on the server now. */
    static final double CONTACT = 0.6;
    /** Half a stroke across the wheel. */
    static final double STROKE = 0.433;
    /** The enchantment is gone. */
    static final double CLEAN_AT = 1.5;
    static final double LET_GO = 1.9;

    public enum Target { NONE, PLACE, ITEM }

    public final Vec3 wheel;
    public final Vec3 top;

    public Target target = Target.NONE;
    public int hoverSlot = -1;
    private int placeSlot = -1;

    // the wheel: speed 0..1 and how far its texture has run round, in texels
    private double spin;
    private double run;

    // the move, as it was when it began
    private final ItemStack[] before = {ItemStack.EMPTY, ItemStack.EMPTY};
    private ItemStack after = ItemStack.EMPTY;
    private boolean toInventory;
    private boolean stripping;

    public GrindstoneStation(BlockPos pos, Direction side, BlockState state) {
        super(pos, side, GrindstoneMenu.class, 2);
        wheel = wheelCenter(pos, state);
        top = wheel.add(0, HALF, 0);
    }

    @Override
    public StationKind kind() {
        return StationKind.GRINDSTONE;
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
        return pos;
    }

    /** The wheel hangs between its posts on the floor, from a wall (pushed out from it) or under a ceiling. */
    static Vec3 wheelCenter(BlockPos pos, BlockState state) {
        Vec3 c = Vec3.atCenterOf(pos);
        return switch (state.getValue(GrindstoneBlock.FACE)) {
            case FLOOR -> c.add(0, 2 / 16.0, 0);
            case CEILING -> c.add(0, -2 / 16.0, 0);
            case WALL -> c.add(Vec3.atLowerCornerOf(state.getValue(GrindstoneBlock.FACING).getUnitVec3i()).scale(2 / 16.0));
        };
    }

    static Vec3 rightOf(Direction side) {
        return Vec3.atLowerCornerOf(side.getOpposite().getClockWise().getUnitVec3i());
    }

    /** Where an input lies on top of the wheel: the first on the right, the second on the left. */
    static Vec3 restPoint(Vec3 wheel, Direction side, int slot) {
        return RemainingLayouts.point(StationKind.GRINDSTONE, wheel.add(0, HALF, 0), side, slot);
    }

    @Override
    protected Vec3 restPoint(int slot) {
        return restPoint(wheel, side, slot).add(right().scale(denyShake()));
    }

    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(wheel.x + side.getStepX() * 0.9, player.getY(), wheel.z + side.getStepZ() * 0.9);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public boolean worksAtAnAngle() {
        return true;
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 eye = wheel.add(side().scale(0.95)).add(0, 1.0, 0);
        Vec3 look = wheel.add(0, 0.32, 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, -1.5f);
    }

    @Override
    public double leanFov() {
        return 64;
    }

    @Override
    public float bodyPitch() {
        return 40f;
    }

    /** Hands on the frame, on top of the axle posts either side of the wheel. */
    @Override
    public Vec3 rest(boolean right) {
        return wheel.add(right().scale(right ? 0.31 : -0.31)).add(side().scale(0.1)).add(0, 3 / 16.0 + HAND_Y, 0);
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        GrindstoneMenu menu = menu();
        if (overPanel || menu == null || moving()) {
            return;
        }
        ItemStack carried = Hands.carried();
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            int picked = layoutPick();
            if (picked >= 0 && picked < 2) {
                if (!carried.isEmpty()) { target = Target.PLACE; placeSlot = slotFor(carried, picked); aim = restPoint(picked); }
                else if (!slot(picked).isEmpty()) { target = Target.ITEM; hoverSlot = picked; aim = restPoint(picked); }
            }
            return;
        }
        double[] mouse = Picking.mouseGui();
        if (carried.isEmpty()) {
            for (int i = 0; i < 2; i++) {
                if (!slot(i).isEmpty() && under(mouse, restPoint(i), 0.13)) {
                    target = Target.ITEM;
                    hoverSlot = i;
                    aim = restPoint(i);
                    return;
                }
            }
            return;
        }
        if (overWheel()) {
            target = Target.PLACE;
            placeSlot = slotFor(carried, slot(0).isEmpty() || !slot(1).isEmpty() ? 0 : 1);
            aim = restPoint(placeSlot < 0 ? 0 : placeSlot);
        }
    }

    /** The cursor is on the wheel: its top or the running face towards the player. */
    private boolean overWheel() {
        Vec3 hit = Picking.cursorOnPlane(top.y);
        if (hit != null) {
            Vec3 rel = hit.subtract(top);
            if (Math.abs(rel.dot(right())) < WIDTH + 0.08 && Math.abs(rel.dot(side())) < HALF + 0.06) {
                return true;
            }
        }
        Vec3 front = wheel.add(side().scale(HALF));
        hit = Picking.cursorOnPlane(front, side());
        return hit != null && Math.abs(hit.subtract(front).dot(right())) < WIDTH + 0.08 && Math.abs(hit.y - wheel.y) < HALF;
    }

    static boolean under(double[] mouse, Vec3 center, double radius) {
        double[] at = Picking.toGui(center);
        double[] edge = Picking.toGui(center.add(0, radius, 0));
        if (at == null || edge == null) {
            return false;
        }
        double r = Math.max(9, Math.hypot(at[0] - edge[0], at[1] - edge[1]));
        return Math.hypot(mouse[0] - at[0], mouse[1] - at[1]) < r;
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        hoverSlot = -1;
        placeSlot = -1;
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
                if (placeSlot < 0) {
                    deny();
                    return;
                }
                slotClick(placeSlot, button, false);
                Scene.sound(SoundEvents.STONE_PLACE, 0.5f, 1.3f);
            }
            case ITEM -> {
                if (button == 0 && !slot(2).isEmpty()) {
                    grind(shift);
                } else {
                    slotClick(hoverSlot, 0, shift);
                }
            }
            default -> {}
        }
    }

    private void grind(boolean shift) {
        before[0] = slot(0).copy();
        before[1] = slot(1).copy();
        after = slot(2).copy();
        toInventory = shift;
        stripping = removesEnchantments(before[0]) || removesEnchantments(before[1]);
        startMove(GRIND_LENGTH, CONTACT, () -> {
            if (slot(2).isEmpty()) {
                cancelMove();
                return;
            }
            GrindstoneMenu menu = menu();
            Minecraft mc = Minecraft.getInstance();
            mc.gameMode.handleContainerInput(menu.containerId, 2, 0, toInventory ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        });
        showMove(shift);
        Scene.sound(SoundEvents.STONE_HIT, 0.5f, 0.8f);
    }

    /** Whether grinding takes enchantments off this stack (curses stay). */
    static boolean removesEnchantments(ItemStack stack) {
        for (Holder<Enchantment> holder : EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet()) {
            if (!holder.is(EnchantmentTags.CURSE)) {
                return true;
            }
        }
        return false;
    }

    // ---- the move ------------------------------------------------------------------------------------

    /** Sideways offset of the blade along the wheel's edge. */
    static double stroke(double t) {
        double s = Anim.window(t, CONTACT, LET_GO);
        double fade = Anim.smoother(s * 8) * Anim.smoother((1 - s) * 8);
        return 0.075 * Math.sin(Math.PI * (t - CONTACT) / STROKE) * fade;
    }

    /** Unit vector from the handle to the tip of item {@code k} (0 right hand, 1 left) while it is ground. */
    private Vec3 blade(int k) {
        return blade(side, k);
    }

    static Vec3 blade(Direction side, int k) {
        double a = Math.toRadians(SLANT);
        return Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(-Math.cos(a)).add(rightOf(side).scale(k == 0 ? -Math.sin(a) : Math.sin(a)))
                .yRot((float) Math.toRadians(RemainingLayouts.rotation(StationKind.GRINDSTONE, k)));
    }

    /** Where the blade of item {@code k} bites the running stone: under its tip half, on top of the wheel. */
    static Vec3 bite(Vec3 wheel, Direction side, int k, double t) {
        Vec3 at = heldPos(wheel, side, k, t).add(blade(side, k).scale(0.07));
        return new Vec3(at.x, RemainingLayouts.point(StationKind.GRINDSTONE, wheel.add(0, HALF, 0), side, 2).y - 0.026, at.z);
    }

    private Vec3 heldPos(int k, double t) {
        return heldPos(wheel, side, k, t);
    }

    /** Pose of item {@code k} (0 right hand, 1 left) at time {@code t} of the move. */
    static Vec3 heldPos(Vec3 wheel, Direction side, int k, double t) {
        double sideways = (k == 0 ? 0.08 : -0.08) + stroke(t) * (k == 0 ? 1 : -1);
        var layout = SpatialLayouts.current(StationKind.GRINDSTONE);
        var frame = RemainingLayouts.frameAt(StationKind.GRINDSTONE, wheel.add(0, HALF, 0), side);
        Vec3 grinding = layout.point(frame, 2).add(layout.axis(frame, 2, true).scale(sideways));
        Vec3 lying = restPoint(wheel, side, k).add(0, 0.008, 0);
        double lift = Anim.inOutCubic(Anim.window(t, 0.28, CONTACT));
        Vec3 at = Anim.lerp(lying, grinding, lift).add(0, 0.09 * Anim.hump(lift), 0);
        // little pushes onto the stone with every stroke
        return at.add(0, -0.008 * Math.abs(Math.sin(Math.PI * (t - CONTACT) / STROKE)) * Anim.window(t, CONTACT, CONTACT + 0.1), 0);
    }

    /** From lying straight on the wheel to the slant it is ground at. */
    static float heldYaw(Direction side, int k, double t) {
        double lift = Anim.inOutCubic(Anim.window(t, 0.28, CONTACT));
        float lying = k == 0 ? -8f : 8f;
        float slant = k == 0 ? SLANT : -SLANT;
        return ItemPlacer.awayYaw(side) + TOOL_TURN + RemainingLayouts.rotation(StationKind.GRINDSTONE, k) + (float) (lying + (slant - lying) * lift);
    }

    /** The blade dips its tip onto the stone. */
    static float heldTilt(double t) {
        return (float) (-9 * Anim.inOutCubic(Anim.window(t, 0.28, CONTACT)));
    }

    /** Where the hand holds item {@code k}: by the handle, the end nearer the player. */
    private Vec3 handle(int k, Vec3 item) {
        return item.subtract(blade(k).scale(0.13 * geometry().scale(k))).add(0, HAND_Y * 0.6, 0);
    }

    /** Lifted up towards the eye after grinding. */
    private Vec3 lifted() {
        return lifted(wheel, side);
    }

    static Vec3 lifted(Vec3 wheel, Direction side) {
        return wheel.add(0, HALF, 0).add(Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(0.3)).add(rightOf(side).scale(0.06)).add(0, 0.25, 0);
    }

    /** Lifted towards the eye after grinding, turned to catch the light, then settling in the hand. */
    private Vec3 inspectPos(double t, Camera camera) {
        double up = Anim.inOutCubic(Anim.window(t, LET_GO, LET_GO + 0.3));
        Vec3 at = Anim.lerp(heldPos(0, LET_GO), lifted(), up);
        if (toInventory) {
            Vec3 drawer = camera.position().add(0, 0.55, 0).add(Scene.yawForward(camera.yRot()).scale(0.6));
            return Anim.lerp(at, drawer, Anim.inCubic(Anim.window(t, LET_GO + 0.22, GRIND_LENGTH)));
        }
        Vec3 hand = Hands.position(true);
        Vec3 held = heldItemPoint(hand, Hands.shoulder(true, camera.position(), camera.yRot()));
        return Anim.lerp(at, held, Anim.inOutCubic(Anim.window(t, LET_GO + 0.3, GRIND_LENGTH)));
    }

    @Nullable
    @Override
    public Vec3[] handsOverride() {
        double t = moveTime();
        if (t < 0) {
            return null;
        }
        Vec3 grip;
        if (t < LET_GO) {
            grip = Anim.lerp(rest(true), handle(0, heldPos(0, t)), Anim.inOutCubic(Anim.window(t, 0, 0.28)));
        } else {
            Vec3 up = Anim.lerp(handle(0, heldPos(0, LET_GO)), lifted().add(side().scale(0.1)).add(0, HAND_Y * 0.6, 0),
                    Anim.inOutCubic(Anim.window(t, LET_GO, LET_GO + 0.3)));
            Vec3 end = rest(true).add(0, toInventory ? 0 : 0.05, 0);
            grip = Anim.lerp(up, end, Anim.inOutCubic(Anim.window(t, LET_GO + 0.3, GRIND_LENGTH)));
        }
        Vec3 left;
        if (!before[1].isEmpty()) {
            // the second item is ground with the other hand, mirrored
            double hold = Anim.inOutCubic(Anim.window(t, 0.05, 0.33)) * (1 - Anim.inOutCubic(Anim.window(t, CLEAN_AT, CLEAN_AT + 0.3)));
            left = Anim.lerp(rest(false), handle(1, heldPos(1, Math.min(t, CLEAN_AT))), hold);
        } else {
            // the other hand braces the frame, bearing down with every stroke
            double bear = Math.abs(Math.sin(Math.PI * (t - CONTACT) / STROKE)) * Anim.window(t, CONTACT, CONTACT + 0.1) * (1 - Anim.window(t, LET_GO - 0.1, LET_GO));
            left = rest(false).add(0, -0.008 * bear, 0).add(side().scale(-0.02 * Anim.window(t, 0.1, 0.4)));
        }
        return new Vec3[]{grip, left};
    }

    @Override
    public double cameraKick() {
        double t = moveTime();
        if (t < 0) {
            return 0;
        }
        return 0.13 * Anim.window(t, CONTACT - 0.05, CONTACT + 0.15) * (1 - Anim.window(t, LET_GO - 0.15, LET_GO + 0.1));
    }

    // ---- per tick / frame ----------------------------------------------------------------------------

    @Override
    public void tick() {
        double t = moveTime();
        if (t >= CONTACT && t < LET_GO && Minecraft.getInstance().player.tickCount % 9 == 0) {
            Scene.sound(SoundEvents.GRINDSTONE_USE, 0.25f, 1.25f + Fx.RANDOM.nextFloat() * 0.2f);
        }
    }

    @Override
    void frame() {
        super.frame();
        double t = moveTime();
        double dt = Scene.frameDt;
        boolean driven = t >= 0 && t < LET_GO + 0.1;
        spin = driven ? Anim.approach(spin, 1, 5, dt) : Anim.approach(spin, 0, 1.3, dt);
        // the top runs away from the player
        run -= spin * 70 * dt;
        if (t >= 0) {
            grindFx(wheel, side, !before[1].isEmpty(), stripping, t, dt);
        }
    }

    /**
     * Grinding, {@code t} seconds in: sparks dragged off where the blades bite and fountaining up over the far side of the
     * wheel, the enchantment flaking off and, once it is gone, a last burst of it.
     */
    static void grindFx(Vec3 wheel, Direction side, boolean two, boolean stripping, double t, double dt) {
        if (t < CONTACT || t >= LET_GO) {
            return;
        }
        int count = (int) Math.round(dt * 160 + Fx.RANDOM.nextDouble());
        Vec3 lean = Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(-0.55);
        Fx.fountain(bite(wheel, side, 0, t), lean, rightOf(side), count, 2.2);
        if (two && t < CLEAN_AT) {
            Fx.fountain(bite(wheel, side, 1, t), lean, rightOf(side), count, 2.2);
        }
        if (stripping && t < CLEAN_AT + 0.1) {
            Vec3 steel = heldPos(wheel, side, 0, t).add(blade(side, 0).scale(0.04)).add(0, 0.02, 0);
            Fx.flakes(steel, 0.06, (int) Math.round(dt * 34 + Fx.RANDOM.nextDouble() * 0.6));
        }
        if (stripping && t - dt < CLEAN_AT && t >= CLEAN_AT) {
            Vec3 at = heldPos(wheel, side, 0, t);
            Fx.flakes(at, 0.1, 30);
            Fx.motes(at, 0.06, 12, 0.85f, 0.55f, 1f, 0.2);
            sound(at, SoundEvents.BEACON_DEACTIVATE, 0.35f, 1.7f);
            sound(at, SoundEvents.AMETHYST_CLUSTER_BREAK, 0.4f, 1.4f);
        }
    }

    private static void sound(Vec3 at, SoundEvent sound, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.BLOCKS, volume, pitch, false);
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------

    @Override
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        Vec3 cam = camera.position();
        if (spin > 0.01) {
            drawRim(pose, buffers, cam, wheel, side, run, net.minecraft.util.LightCoordsUtil.getLightCoords(Minecraft.getInstance().level, pos));
        }
        if (outro()) {
            drawLeaving(pose, buffers, camera, light);
            return;
        }
        double t = moveTime();
        for (int i = 0; i < 2; i++) {
            if (t >= 0 && !before[i].isEmpty()) {
                continue;
            }
            ItemStack stack = slot(i);
            if (!stack.isEmpty()) {
                drawLying(pose, buffers, cam, stack, restPoint(i), side, i, i == hoverSlot ? 1.08f : 1f, light);
            }
        }
        if (t >= 0) {
            drawMove(pose, buffers, camera, t, light);
        }
    }

    private void drawMove(PoseStack pose, SceneBuffers buffers, Camera camera, double t, int light) {
        if (t < LET_GO) {
            drawGrinding(pose, buffers, camera, wheel, side, before[0], before[1], after, stripping, t, light);
            return;
        }
        // lifted up to the eye: the clean steel catches the light, then it is held (or slips into the bag)
        double k = Anim.window(t, LET_GO, GRIND_LENGTH);
        float size = (float) (toInventory ? ITEM_SIZE * (1 - 0.8 * Anim.inCubic(Anim.window(k, 0.4, 1))) : ITEM_SIZE + (0.205f - ITEM_SIZE) * Anim.window(k, 0.6, 1));
        drawLifted(pose, buffers, camera.position(), side, after, inspectPos(t, camera), t, size, light);
    }

    /**
     * The items held to the running stone, {@code t} seconds into grinding: lifted off the wheel, the blades laid on at a
     * slant, drawn back and forth, the enchantment's glow draining out; a second piece merges into the first.
     */
    static void drawGrinding(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 wheel, Direction side, ItemStack first, ItemStack second,
            ItemStack after, boolean stripping, double t, int light) {
        Vec3 cam = camera.position();
        boolean clean = !stripping || t >= CLEAN_AT;
        ItemStack shown = clean || !second.isEmpty() && t >= CLEAN_AT ? after : first;
        Vec3 at = heldPos(wheel, side, 0, t);
        ItemPlacer.drawOriented(pose, buffers, cam, shown, at, ItemPlacer.lying(heldYaw(side, 0, t), heldTilt(t)), ITEM_SIZE * RemainingLayouts.scale(StationKind.GRINDSTONE, 0), light);
        if (stripping && t >= CONTACT - 0.1 && t < CLEAN_AT + 0.05) {
            // the enchantment's glow draining out of the blade
            double left = 1 - Anim.window(t, CONTACT, CLEAN_AT);
            float g = (float) (0.35 * left);
            EnchantFx.billboard(pose, buffers, camera, at.add(0, 0.02, 0), 0.17, Scene.clock() * 0.6, EnchantFx.GLOW, 0.65f * g, 0.35f * g, g);
        }
        if (!second.isEmpty() && t < CLEAN_AT) {
            ItemPlacer.drawOriented(pose, buffers, cam, second, heldPos(wheel, side, 1, t), ItemPlacer.lying(heldYaw(side, 1, t), heldTilt(t)), ITEM_SIZE * RemainingLayouts.scale(StationKind.GRINDSTONE, 1),
                    light);
        } else if (!second.isEmpty() && t < CLEAN_AT + 0.25) {
            double k = Anim.inCubic(Anim.window(t, CLEAN_AT, CLEAN_AT + 0.25));
            Vec3 from = heldPos(wheel, side, 1, CLEAN_AT);
            ItemPlacer.drawOriented(pose, buffers, cam, second, Anim.lerp(from, heldPos(wheel, side, 0, t), k),
                    ItemPlacer.lying(heldYaw(side, 1, t), heldTilt(t)), (float) (ITEM_SIZE * RemainingLayouts.scale(StationKind.GRINDSTONE, 1) * (1 - k)), light);
        }
        // the bright line where steel meets stone
        if (t >= CONTACT && t < LET_GO) {
            float b = (float) (0.55 + 0.25 * Math.sin(Scene.clock() * 40));
            EnchantFx.billboard(pose, buffers, camera, bite(wheel, side, 0, t).add(0, 0.008, 0), 0.05, 0, EnchantFx.GLOW, b, b * 0.6f, b * 0.25f);
        }
    }

    /** The clean item after grinding, at {@code at}: lifted to catch the light, turned and tilted, then settling. */
    static void drawLifted(PoseStack pose, SceneBuffers buffers, Vec3 cam, Direction side, ItemStack after, Vec3 at, double t, float size,
            int light) {
        size *= RemainingLayouts.scale(StationKind.GRINDSTONE, 0);
        double k = Anim.window(t, LET_GO, GRIND_LENGTH);
        float tilt = (float) (heldTilt(LET_GO) + (60 - heldTilt(LET_GO)) * Anim.hump(Math.min(1, k * 1.25)) + 18 * Anim.window(k, 0.8, 1));
        float from = heldYaw(side, 0, LET_GO);
        float turn = (float) (from + (ItemPlacer.awayYaw(side) - from) * Anim.inOutCubic(Anim.window(k, 0.5, 1)) + 25 * Math.sin(k * Math.PI));
        ItemPlacer.drawOriented(pose, buffers, cam, after, at, ItemPlacer.lying(turn, tilt), size, light);
    }

    /** An item lying on top of the wheel, blade pointing away. */
    static void drawLying(PoseStack pose, SceneBuffers buffers, Vec3 cam, ItemStack stack, Vec3 at, Direction side, int slot, float scale,
            int light) {
        float yaw = ItemPlacer.awayYaw(side) + TOOL_TURN + (slot == 0 ? -8f : 8f) + RemainingLayouts.rotation(StationKind.GRINDSTONE, slot);
        boolean block = ItemPlacer.isBlock(stack);
        float size = (block ? 0.16f : ITEM_SIZE) * scale * RemainingLayouts.scale(StationKind.GRINDSTONE, slot);
        Vec3 center = at.add(0, block ? size / 2 : 0.004 + slot * 0.003, 0);
        ItemPlacer.drawOriented(pose, buffers, cam, stack, center, ItemPlacer.lying(yaw, 0), size, light);
    }

    /**
     * The running faces of the wheel, drawn over the block's own with their texture rolling round: the wheel
     * spins. The top runs towards the player at {@code side}.
     */
    static void drawRim(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 wheel, Direction side, double texels, int light) {
        TextureAtlasSprite sprite = Fx.sprite("block/grindstone_round");
        VertexConsumer vc = Fx.atlas(buffers);
        double h = HALF + 0.0025;
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 out = Vec3.atLowerCornerOf(side.getUnitVec3i());
        Vec3 across = rightOf(side).scale(WIDTH);
        // each face: its starting edge, the direction it runs in, its outward normal
        Vec3[][] faces = {
                {wheel.add(up.scale(h)).subtract(out.scale(HALF)), out, up},
                {wheel.add(out.scale(h)).add(up.scale(HALF)), up.scale(-1), out},
                {wheel.subtract(up.scale(h)).add(out.scale(HALF)), out.scale(-1), up.scale(-1)},
                {wheel.subtract(out.scale(h)).subtract(up.scale(HALF)), up, out.scale(-1)}};
        double offset = ((texels % 12) + 12) % 12;
        for (Vec3[] face : faces) {
            // the face shows 12 texels running round; split where the texture wraps
            double split = (12 - offset) / 12 * (2 * HALF);
            rimQuad(pose, vc, cam, across, face[0], face[1], face[2], 0, split, offset, 12, sprite, light);
            rimQuad(pose, vc, cam, across, face[0], face[1], face[2], split, 2 * HALF, 0, offset, sprite, light);
        }
    }

    private static void rimQuad(PoseStack pose, VertexConsumer vc, Vec3 cam, Vec3 across, Vec3 start, Vec3 run, Vec3 normal, double from, double to,
            double v0, double v1, TextureAtlasSprite sprite, int light) {
        if (to - from < 1e-4) {
            return;
        }
        Vec3 a = start.add(run.scale(from)), b = start.add(run.scale(to));
        Vec3[] p = {a.subtract(across), a.add(across), b.add(across), b.subtract(across)};
        float[][] uv = {{0, (float) v0}, {8, (float) v0}, {8, (float) v1}, {0, (float) v1}};
        // wind towards the outside
        boolean flip = across.cross(run).dot(normal) < 0;
        for (int j = 0; j < 4; j++) {
            int i = flip ? 3 - j : j;
            Vec3 q = p[i].subtract(cam);
            vc.addVertex(pose.last(), (float) q.x, (float) q.y, (float) q.z).setColor(255, 255, 255, 255)
                    .setUv(sprite.getU(uv[i][0] / 16f), sprite.getV(uv[i][1] / 16f)).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                    .setNormal(pose.last(), (float) normal.x, (float) normal.y, (float) normal.z);
        }
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        if (target == Target.PLACE && aim != null) {
            float p = (float) (0.18 + 0.06 * Math.sin(Scene.clock() * 5));
            boolean ok = placeSlot >= 0;
            EnchantFx.disc(pose, buffers, camera, aim.add(0, 0.004, 0), 0.12, 0, EnchantFx.GLOW, p, ok ? p * 0.8f : p * 0.2f, ok ? p * 0.55f : p * 0.2f);
        }
    }

    // ---- what others see -----------------------------------------------------------------------------

    static void drawContents(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, BlockState state, Direction side,
            List<ItemStack> items, int light) {
        Vec3 wheel = wheelCenter(pos, state);
        for (int i = 0; i < Math.min(2, items.size()); i++) {
            if (!items.get(i).isEmpty()) {
                drawLying(pose, buffers, cam, items.get(i), restPoint(wheel, side, i), side, i, 1f, light);
            }
        }
    }

    /**
     * Someone else grinds here, {@code t} seconds in: the items held to the stone and drawn over it, then the clean one
     * lifted and away to them.
     */
    static void drawOnlooker(PoseStack pose, SceneBuffers buffers, Camera camera, BlockPos pos, BlockState state, ClientMenus.Entry entry, double t,
            int light) {
        Vec3 wheel = wheelCenter(pos, state);
        ItemStack first = entry.moved(0), second = entry.moved(1);
        if (t < LET_GO) {
            drawGrinding(pose, buffers, camera, wheel, entry.side, first, second, entry.moveResult,
                    removesEnchantments(first) || removesEnchantments(second), t, light);
            return;
        }
        Vec3 up = Anim.lerp(heldPos(wheel, entry.side, 0, LET_GO), lifted(wheel, entry.side), Anim.inOutCubic(Anim.window(t, LET_GO, LET_GO + 0.3)));
        double away = Anim.inCubic(Anim.window(t, LET_GO + 0.22, GRIND_LENGTH));
        Vec3 at = Anim.lerp(up, entry.taker(lifted(wheel, entry.side).add(Vec3.atLowerCornerOf(entry.side.getUnitVec3i()).scale(0.6))), away);
        drawLifted(pose, buffers, camera.position(), entry.side, entry.moveResult, at, t, (float) (ITEM_SIZE * (1 - 0.8 * away)), light);
    }

    /** Someone else grinds here: the wheel spins up and down, sparks fly, the stone hums as the blade is drawn over it. */
    static void onlookerFx(BlockPos pos, BlockState state, ClientMenus.Entry entry, double t, double dt) {
        boolean driven = t >= 0 && t < LET_GO + 0.1;
        entry.spin = driven ? Anim.approach(entry.spin, 1, 5, dt) : Anim.approach(entry.spin, 0, 1.3, dt);
        entry.run -= entry.spin * 70 * dt;
        if (t < 0 || t > GRIND_LENGTH) {
            return;
        }
        Vec3 wheel = wheelCenter(pos, state);
        ItemStack first = entry.moved(0), second = entry.moved(1);
        grindFx(wheel, entry.side, !second.isEmpty(), removesEnchantments(first) || removesEnchantments(second), t, dt);
        // the hum of the stone, as often as the scene's
        if (t >= CONTACT && t < LET_GO && Math.floor((t - CONTACT) / 0.45) != Math.floor((t - dt - CONTACT) / 0.45)) {
            sound(wheel, SoundEvents.GRINDSTONE_USE, 0.25f, 1.25f + Fx.RANDOM.nextFloat() * 0.2f);
        }
    }

    /** How far the arms holding a blade to the wheel sway sideways with its strokes, as others see it, at time {@code t} of grinding. */
    public static float armSway(double t) {
        return (float) (stroke(t) * 2.4);
    }

    // ---- words ---------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] pointer = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(pointer[0], pointer[1]);
        if (editorTip != null) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        if (target == Target.PLACE) {
            return placeSlot < 0 ? List.of(Component.translatable("cinematic.grindstone.refuse").withStyle(ChatFormatting.RED)) : null;
        }
        if (target != Target.ITEM || !carried.isEmpty()) {
            return null;
        }
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, slot(hoverSlot)));
        ItemStack result = slot(2);
        if (!result.isEmpty()) {
            lines.add(Component.empty());
            boolean strips = removesEnchantments(slot(0)) || removesEnchantments(slot(1));
            boolean merges = !slot(0).isEmpty() && !slot(1).isEmpty();
            if (strips) {
                lines.add(Component.translatable("cinematic.grindstone.strips").withStyle(ChatFormatting.LIGHT_PURPLE));
            }
            if (merges) {
                lines.add(Component.translatable("cinematic.grindstone.merges").withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.translatable("cinematic.grindstone.grind").withStyle(ChatFormatting.GOLD));
        } else {
            lines.add(Component.translatable("cinematic.grindstone.take").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.grindstone");
    }

    @Override
    public boolean stillThere(Level level) {
        return super.stillThere(level) && wheelCenter(pos, level.getBlockState(pos)).distanceToSqr(wheel) < 1e-6;
    }

    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public java.util.List<Vec3> workSamples() { return RemainingLayouts.samples(this, super.workSamples()); }
    @Override public float layoutHeldScale() { int slot = target == Target.PLACE ? placeSlot : hoverSlot; return slot < 0 ? 1 : geometry().scale(slot); }
}
