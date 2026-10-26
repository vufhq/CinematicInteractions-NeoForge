package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.campfire.CampfireActions;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.net.CampfireProgressPayload;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import dev.dyslexic.cinematic.net.PacketDistributor;
import org.joml.Vector3f;

/**
 * Resting by a campfire, started by its own key (using the campfire stays vanilla). The player walks up, sits
 * down on the ground facing the fire with one knee drawn up (their own legs are in the frame), the right hand on
 * the knee. Food from the inventory is put on a chosen spot of the fire and taken off it by hand; the campfire
 * cooks it as usual.
 */
public final class CampfireStation extends ClientStation implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    /** From the middle of the fire to where the player sits. */
    static final double SEAT = 1.6;
    /** Eye height while sitting on the ground. */
    static final double EYE = 0.8;
    static final double HAND_Y = 0.055;
    static final double ITEM_REACH = 0.07;

    public enum Target { NONE, PLACE, FOOD }

    public final Vec3 fire;
    /** Horizontal unit vectors: towards the fire, and the player's right while facing it. */
    private final Vec3 toward, across;
    private final Vec3 seat;
    private final Direction facing;

    public Target target = Target.NONE;
    public int hoverSpot = -1;
    private int placeSpot = -1;
    /** 0..1: how far the player leans in from the seat towards the fire, to reach the spot under the cursor. */
    private double leanIn;
    /** The cursor in the last frame, to tell the mouse moving from the camera moving. */
    @Nullable
    private double[] lastMouse;
    private final int[] cookingProgress = new int[CampfireActions.SPOTS];
    private final int[] cookingTime = new int[CampfireActions.SPOTS];
    private final double[] shownProgress = new double[CampfireActions.SPOTS];
    private final float[] railSeen = {1, 1, 1, 1};
    private static final double[] RAIL_SAMPLES = {-0.06, -0.03, 0, 0.03, 0.06};
    private boolean cookingLit;
    private double progressReceivedAt;

    private static float unhidden(Vec3 at) {
        net.minecraft.client.Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        org.joml.Vector3fc left = camera.leftVector();
        int clear = 0;
        for (double d : RAIL_SAMPLES)
            if (!Hands.hides(camera.position(), camera.yRot(), at.add(left.x() * d, left.y() * d, left.z() * d))) clear++;
        return clear / (float) RAIL_SAMPLES.length;
    }

    public CampfireStation(BlockPos pos, LocalPlayer player) {
        super(pos, sideOf(pos, player));
        fire = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        Vec3 out = outward(pos, player);
        toward = out.scale(-1);
        across = new Vec3(-toward.z, 0, toward.x);
        double ground = Math.abs(player.getY() - pos.getY()) < 0.6 ? player.getY() : pos.getY();
        Vec3 at = fire.add(out.scale(SEAT));
        seat = new Vec3(at.x, ground, at.z);
        BlockState state = player.level().getBlockState(pos);
        facing = state.hasProperty(CampfireBlock.FACING) ? state.getValue(CampfireBlock.FACING) : Direction.NORTH;
    }

    private static Vec3 outward(BlockPos pos, LocalPlayer player) {
        Vec3 out = new Vec3(player.getX() - (pos.getX() + 0.5), 0, player.getZ() - (pos.getZ() + 0.5));
        if (out.lengthSqr() < 1e-4) {
            out = Scene.yawForward(player.getYRot()).scale(-1);
        }
        return out.normalize();
    }

    private static Direction sideOf(BlockPos pos, LocalPlayer player) {
        Vec3 out = outward(pos, player);
        return Direction.getApproximateNearest(out.x, 0, out.z);
    }

    @Override
    public StationKind kind() {
        return StationKind.CAMPFIRE;
    }

    @Override
    public Vec3 center() {
        return fire.add(0, 0.45, 0);
    }

    @Override
    public Vec3 forward() {
        return toward;
    }

    @Override
    public Vec3 right() {
        return across;
    }

    @Override
    public Vec3 side() {
        return toward.scale(-1);
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.GRASS_STEP;
    }

    @Override
    public BlockPos lightPos() {
        return BlockPos.containing(seat.add(0, 0.5, 0));
    }

    /** Where food lies on spot {@code j} of the fire: the same transform the campfire renderer uses. */
    static Vec3 vanillaSpot(BlockPos pos, Direction facing, int j) {
        Vector3f v = new Vector3f(-0.3125f, -0.3125f, 0f);
        Axis.XP.rotationDegrees(90f).transform(v);
        Direction d = Direction.from2DDataValue((j + facing.get2DDataValue()) % 4);
        Axis.YP.rotationDegrees(-d.toYRot()).transform(v);
        return new Vec3(pos.getX() + 0.5 + v.x, pos.getY() + 0.44921875 + v.y, pos.getZ() + 0.5 + v.z);
    }

    static Vec3 spot(BlockPos pos, Direction facing, int j) {
        return SpatialLayouts.current(StationKind.CAMPFIRE).point(RemainingLayouts.frame(StationKind.CAMPFIRE, pos, facing), j);
    }

    Vec3 spot(int j) {
        return spot(pos, facing, j);
    }

    private List<ItemStack> food() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockEntity(pos) instanceof CampfireBlockEntity campfire ? campfire.getItems() : List.of();
    }

    void applyProgress(CampfireProgressPayload payload) {
        cookingLit = payload.lit();
        progressReceivedAt = Scene.clock();
        for (int i = 0; i < CampfireActions.SPOTS; i++) {
            int nextTotal = Math.max(0, payload.total()[i]);
            int nextProgress = Math.max(0, payload.progress()[i]);
            if (nextTotal == 0 || nextTotal != cookingTime[i]
                    || cookingLit && nextProgress + 2 < cookingProgress[i]) shownProgress[i] = 0;
            cookingTime[i] = nextTotal;
            cookingProgress[i] = nextProgress;
        }
    }

    private double cookingFraction(int slot) {
        int total = cookingTime[slot];
        if (total <= 0) return 0;
        double elapsed = Math.max(0, Math.min(0.25, Scene.clock() - progressReceivedAt));
        double progress = cookingProgress[slot] + elapsed * (cookingLit ? 20 : -40);
        return Math.max(0, Math.min(total - 1, progress)) / total;
    }

    int syncedProgress(int slot) { return cookingProgress[slot]; }
    int syncedCookingTime(int slot) { return cookingTime[slot]; }
    boolean syncedLit() { return cookingLit; }


    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        boolean walk = Math.hypot(seat.x - player.getX(), seat.z - player.getZ()) > 0.06;
        return walk ? List.of(seat) : List.of();
    }

    /** How far forward the head goes when leaning all the way in: the arm then reaches the far side of the fire. */
    static final double LEAN_IN = 0.85;

    @Override
    public CameraRig.Pose lean() {
        // reaching for the far side of the fire, the player leans in over the outstretched leg instead of growing arms
        Vec3 eye = seat.add(0, EYE - 0.1 * leanIn, 0).add(toward.scale(-0.05 + LEAN_IN * leanIn));
        // looking down at the fire: the raised knee and the outstretched leg are in the lower part of the frame
        Vec3 look = fire.add(0, 0.18, 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, 0);
    }

    @Override
    public double leanFov() {
        return 68;
    }

    /** Sitting down needs the seat itself: on the ground by the fire, at the fire's own level. */
    @Override
    public boolean workable(Vec3 feet) {
        return Math.hypot(feet.x - seat.x, feet.z - seat.z) < 0.3 && Math.abs(feet.y - fire.y) < 0.6;
    }

    /** The seat is already on the player's side of the fire ({@link #workable}). */
    @Override
    public boolean inFront(Vec3 feet) {
        return true;
    }

    /** The player sits, never crouches. */
    @Override
    public boolean crouchesFor(double cameraHeight) {
        return false;
    }

    /** Whether the player could sit down by this campfire now (the invitation shows only then). */
    static boolean sittable(LocalPlayer player, BlockPos pos) {
        return Reach.plan(new CampfireStation(pos, player), player).scene();
    }

    /** Even leaning in, an arm stays the length of an arm. */
    @Override
    public double maxReach() {
        return 1.0;
    }

    @Override
    public float bodyPitch() {
        return 22f;
    }

    // ---- the body: sitting with the right knee drawn up ------------------------------------------------

    /** 0 standing .. 1 sitting, following the camera down on the way in and up on the way out. */
    private double seated() {
        return switch (Scene.phase) {
            case INTRO -> Anim.smoother(Anim.window(Scene.introProgress(), 0.4, 0.95));
            case ACTIVE -> 1;
            case OUTRO -> 1 - Anim.smoother(Anim.window(Scene.time / Scene.OUTRO_LENGTH, 0.0, 0.45));
            default -> 0;
        };
    }

    private Vec3 at(Vec3 base, double fwd, double right, double up) {
        return base.add(toward.scale(fwd)).add(across.scale(right)).add(0, up, 0);
    }

    private Vec3 kneeRight() {
        return at(seat, 0.36, 0.17, 0.46);
    }

    private Vec3 kneeLeft() {
        return at(seat, 0.45, -0.13, 0.13);
    }

    /** The right hand on the raised knee, the left one on the outstretched thigh. */
    @Override
    public Vec3 rest(boolean right) {
        return right ? kneeRight().add(toward.scale(0.02)).add(0, 0.075, 0) : kneeLeft().add(toward.scale(-0.08)).add(0, 0.085, 0);
    }

    @Override
    public void renderBody(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // lean in as far as the spot under the cursor needs, and settle back when the hands rest
        double want = Scene.phase == Scene.Phase.ACTIVE && aim != null
                ? Anim.clamp01((Math.hypot(aim.x - seat.x, aim.z - seat.z) - 1.15) / 0.7) : 0;
        leanIn = Anim.approach(leanIn, want, want > leanIn ? 5 : 3, Scene.frameDt);
        double s = seated();
        if (s <= 0.001) {
            return;
        }
        Vec3 feet = player.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        Vec3 base = Anim.lerp(new Vec3(feet.x, seat.y, feet.z), seat, s);
        for (boolean right : new boolean[]{true, false}) {
            double r = right ? 1 : -1;
            Vec3 standHip = at(base, 0, 0.12 * r, 0.75), standKnee = at(base, 0, 0.12 * r, 0.38), standFoot = at(base, 0, 0.12 * r, 0.02);
            Vec3 hip = right ? at(seat, -0.02, 0.1, 0.14) : at(seat, 0, -0.1, 0.12);
            Vec3 knee = right ? kneeRight() : kneeLeft();
            Vec3 foot = right ? at(seat, 0.56, 0.15, 0.05) : at(seat, 0.7, -0.15, 0.07);
            LegMesh.draw(pose, buffers, camera, player, right, Anim.lerp(standHip, hip, s), Anim.lerp(standKnee, knee, s), Anim.lerp(standFoot, foot, s),
                    across.scale(r), light);
        }
        if (Scene.phase == Scene.Phase.ACTIVE && lit() && Fx.RANDOM.nextDouble() < Scene.frameDt * 5) {
            // embers rising off the fire (a soul fire's are cold blue)
            if (soul()) {
                Fx.motes(fire.add(0, 0.45, 0), 0.18, 1, 0.25f, 0.85f, 1f, 0.45);
            } else {
                Fx.motes(fire.add(0, 0.45, 0), 0.18, 1, 1f, 0.55f, 0.2f, 0.45);
            }
        }
    }

    private boolean soul() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockState(pos).is(Blocks.SOUL_CAMPFIRE);
    }

    private boolean lit() {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = mc.level == null ? null : mc.level.getBlockState(pos);
        return state != null && state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT);
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        int was = hoverSpot;
        int previousPlace = placeSpot;
        clearCursor();
        double[] mouse = Picking.mouseGui();
        boolean still = lastMouse != null && lastMouse[0] == mouse[0] && lastMouse[1] == mouse[1];
        lastMouse = mouse;
        if (overPanel) {
            return;
        }
        List<ItemStack> food = food();
        if (food.size() < CampfireActions.SPOTS) {
            return;
        }
        ItemStack carried = Hands.carried();
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            int picked = layoutPick();
            if (carried.isEmpty() && was >= 0 && !food.get(was).isEmpty()
                    && (still || GrindstoneStation.under(mouse, spot(was), 0.25 * geometry().scale(was)))) picked = was;
            else if (!carried.isEmpty() && previousPlace >= 0 && still) picked = previousPlace;
            if (picked >= 0) {
                if (carried.isEmpty() && !food.get(picked).isEmpty()) { target = Target.FOOD; hoverSpot = picked; aim = spot(picked); }
                else if (!carried.isEmpty()) {
                    target = Target.PLACE;
                    Minecraft mc = Minecraft.getInstance();
                    boolean cookable = mc.level.getBlockEntity(pos) instanceof CampfireBlockEntity campfire && mc.level.recipeAccess().propertySet(net.minecraft.world.item.crafting.RecipePropertySet.CAMPFIRE_INPUT).test(carried);
                    placeSpot = cookable && food.get(picked).isEmpty() ? picked : -1;
                    aim = spot(picked);
                }
            }
            return;
        }
        if (carried.isEmpty()) {
            for (int k = -1; k < CampfireActions.SPOTS; k++) {
                // first the food already under the cursor: leaning in or back moves it across the frame, so it stays
                // pointed at while the mouse rests and a little further out when it moves (it would otherwise slip away,
                // the lean would turn back and find it again, a jitter frame after frame)
                int j = k < 0 ? was : k;
                if (j >= 0 && !food.get(j).isEmpty() && (k < 0 && still || GrindstoneStation.under(mouse, spot(j), k < 0 ? 0.25 : 0.1))) {
                    target = Target.FOOD;
                    hoverSpot = j;
                    aim = spot(j);
                    return;
                }
            }
            return;
        }
        Vec3 hit = Picking.cursorOnPlane(spot(0).y);
        if (hit == null || Math.hypot(hit.x - fire.x, hit.z - fire.z) > 0.6) {
            return;
        }
        target = Target.PLACE;
        Minecraft mc = Minecraft.getInstance();
        boolean cookable = mc.level.recipeAccess().propertySet(net.minecraft.world.item.crafting.RecipePropertySet.CAMPFIRE_INPUT).test(carried);
        double best = Double.MAX_VALUE;
        for (int j = 0; j < CampfireActions.SPOTS && cookable; j++) {
            double d = hit.distanceToSqr(spot(j));
            if (food.get(j).isEmpty() && d < best) {
                best = d;
                placeSpot = j;
            }
        }
        aim = placeSpot >= 0 ? spot(placeSpot) : hit;
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        hoverSpot = -1;
        placeSpot = -1;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        Vec3 reach = new Vec3(aim.x - shoulder.x, 0, aim.z - shoulder.z).normalize();
        double hover = carrying ? 0.08 : 0.05;
        return aim.add(0, HAND_Y + hover - 0.06 * tap, 0).subtract(reach.scale(ITEM_REACH));
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        Vec3 reach = new Vec3(hand.x - shoulder.x, 0, hand.z - shoulder.z).normalize();
        return hand.add(reach.scale(ITEM_REACH)).add(0, -HAND_Y + 0.012, 0);
    }

    // ---- actions -------------------------------------------------------------------------------------

    @Override
    public void click(int button, boolean shift) {
        int index = target == Target.FOOD ? hoverSpot : target == Target.PLACE ? placeSpot : -1;
        if (index < 0) {
            return;
        }
        Hands.tap();
        PacketDistributor.sendToServer(new StationActionPayload(pos, StationKind.CAMPFIRE, StationActionPayload.Action.CLICK, index, button, shift,
                sideDirection().get2DDataValue()));
        Scene.sound(target == Target.FOOD ? SoundEvents.ITEM_PICKUP : SoundEvents.WOOD_PLACE, 0.4f, target == Target.FOOD ? 1.4f : 1.2f);
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        if (aim == null || target == Target.NONE) {
            return;
        }
        float p = (float) (0.2 + 0.07 * Math.sin(Scene.clock() * 5));
        boolean ok = target == Target.FOOD || placeSpot >= 0;
        EnchantFx.disc(pose, buffers, camera, aim.add(0, 0.02, 0), 0.14, 0, EnchantFx.GLOW, p, ok ? p * 0.75f : p * 0.2f, ok ? p * 0.4f : p * 0.2f);
    }

    // ---- words ---------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] pointer = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(pointer[0], pointer[1]);
        if (editorTip != null) return editorTip;
        if (target == Target.FOOD) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), food().get(hoverSpot)));
            if (cookingTime[hoverSpot] > 0) {
                int percent = (int) Math.round(cookingFraction(hoverSpot) * 100);
                int seconds = (int) Math.ceil((cookingTime[hoverSpot] - cookingFraction(hoverSpot) * cookingTime[hoverSpot]) / 20);
                Component status = cookingLit ? Component.translatable("cinematic.campfire.cooking", percent, seconds)
                        : Component.translatable("cinematic.campfire.cooling", percent);
                lines.add(status.copy().withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.translatable("cinematic.campfire.take").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        if (target == Target.PLACE && placeSpot < 0) {
            Minecraft mc = Minecraft.getInstance();
            boolean cookable = mc.level.recipeAccess().propertySet(net.minecraft.world.item.crafting.RecipePropertySet.CAMPFIRE_INPUT).test(carried);
            return List.of(Component.translatable(cookable ? "cinematic.campfire.full" : "cinematic.campfire.not_food").withStyle(ChatFormatting.RED));
        }
        return null;
    }

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.campfire");
    }

    @Override
    public boolean stillThere(Level level) {
        return kind().matches(level.getBlockState(pos));
    }

    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) {
        renderCookingProgress(g, alpha);
        gridEditor.render(g, font, alpha);
    }

    private void renderCookingProgress(GuiGraphicsExtractor g, float alpha) {
        if (Scene.phase != Scene.Phase.ACTIVE || gridEditor.visible()) return;
        List<ItemStack> items = food();
        if (items.size() < CampfireActions.SPOTS) return;
        boolean soul = soul();
        for (int i = 0; i < CampfireActions.SPOTS; i++) {
            if (items.get(i).isEmpty() || cookingTime[i] <= 0) continue;
            shownProgress[i] = Anim.approach(shownProgress[i], cookingFraction(i), 14, Scene.frameDt);
            double[] at = Picking.toGui(spot(i).add(0, 0.02, 0));
            if (at == null) continue;
            double x = at[0] - 15, y = at[1] - 8;
            if (x < 2 || x + 30 >= Minecraft.getInstance().getWindow().getGuiScaledWidth() - 2 || y < 18 || y > Minecraft.getInstance().getWindow().getGuiScaledHeight() - 26) continue;
            railSeen[i] = (float) Anim.approach(railSeen[i], unhidden(spot(i).add(0, 0.02, 0)), 18, Scene.frameDt);
            float a = alpha * (hoverSpot == i ? 1 : 0.82f) * railSeen[i];
            if (a < 0.02f) continue;
            g.pose().pushMatrix();
            g.pose().translate((float) x, (float) y);
            g.fill(-2, -2, 32, 7, color(a * 0.55f, 12, 16, 19));
            g.fill(0, 0, 30, 4, color(a * 0.5f, 75, 80, 82));
            int filled = (int) Math.round(30 * shownProgress[i]);
            if (filled > 0) {
                int fill = cookingLit ? soul ? color(a, 103, 211, 231) : color(a, 255, 188, 99)
                        : color(a, 155, 176, 183);
                g.fill(0, 0, filled, 4, fill);
            }
            g.pose().popMatrix();
        }
    }

    private static int color(float alpha, int red, int green, int blue) {
        return Math.max(0, Math.min(255, Math.round(alpha * 255))) << 24 | red << 16 | green << 8 | blue;
    }

    @Override public java.util.List<Vec3> workSamples() { return RemainingLayouts.samples(this, super.workSamples()); }
    @Override public float layoutHeldScale() { int slot = target == Target.FOOD ? hoverSpot : target == Target.PLACE ? placeSpot : -1; return slot < 0 ? 1 : geometry().scale(slot); }
}
