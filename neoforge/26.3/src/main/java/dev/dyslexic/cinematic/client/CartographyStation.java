package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The cartography table scene. The map lies open on the table, drawn as the map itself; what changes it lies to
 * the right. Touching the map does the work by hand: paper slides under it and the map shrinks into the larger
 * sheet (zooming out); a pane of glass is turned over and laid on it, a gleam runs across (locking); an empty map
 * is laid beside it and the land is traced across onto it in drifting ink (copying). Then the map goes to the
 * hand. Underneath is the vanilla cartography table menu.
 */
public final class CartographyStation extends SurfaceStation<CartographyTableMenu> {
    static final double HAND_Y = 0.062;
    static final double ITEM_REACH = 0.075;
    static final double LEAN_BACK = 0.7;
    static final double LEAN_UP = 0.64;
    /** The map in the middle (r, d: right, towards the player), what goes into it on the right. */
    static final double[][] LAYOUT = {{-0.04, 0.04}, {0.34, -0.1}};
    static final float MAP_SIZE = 0.5f;
    static final float ADDITION_SIZE = 0.24f;

    public static final double WORK_LENGTH = 2.1;
    /** The work is done: the server hands over the map now. */
    static final double BEAT = 1.55;

    public enum Target { NONE, PLACE, ITEM }

    private static final Identifier MAP_BACKGROUND = Identifier.withDefaultNamespace("textures/map/map_background.png");

    private enum Work { ZOOM, LOCK, COPY }

    public final Vec3 top;
    public Target target = Target.NONE;
    public int hoverSlot = -1;
    private int placeSlot = -1;

    // the work, as it was when it began
    private Work work = Work.ZOOM;
    private ItemStack mapBefore = ItemStack.EMPTY;
    private ItemStack addition = ItemStack.EMPTY;
    private ItemStack result = ItemStack.EMPTY;
    private boolean toInventory;
    private boolean gleamed;

    public CartographyStation(BlockPos pos, Direction side) {
        super(pos, side, CartographyTableMenu.class, 2);
        top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    @Override
    public StationKind kind() {
        return StationKind.CARTOGRAPHY;
    }

    @Override
    public Vec3 center() {
        return top;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.WOOD_STEP;
    }

    static Vec3 slotPoint(Vec3 top, Direction side, int slot) {
        return SurfaceLayouts.current(StationKind.CARTOGRAPHY).point(StationKind.CARTOGRAPHY, top, side, slot);
    }

    @Override float heldItemScale() { return heldItemScale(placeSlot >= 0 ? placeSlot : hoverSlot); }

    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(top.x + side.getStepX() * 0.86, player.getY(), top.z + side.getStepZ() * 0.86);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public boolean worksAtAnAngle() {
        return true;
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 at = top.add(side().scale(LEAN_BACK)).add(0, LEAN_UP, 0);
        float pitch = (float) Math.toDegrees(Math.atan2(LEAN_UP, LEAN_BACK + 0.02));
        return new CameraRig.Pose(at, side.getOpposite().toYRot(), pitch, 0);
    }

    @Override
    public Vec3 rest(boolean right) {
        return top.add(side().scale(0.4)).add(right().scale(right ? 0.26 : -0.26)).add(0, HAND_Y, 0);
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || gridEditor.editing() || menu() == null || moving()) {
            return;
        }
        if (!geometry().equals(SurfaceAppearance.VANILLA)) {
            int selected = surfaceSlot(Hands.carried().isEmpty());
            if (selected >= 0) {
                if (Hands.carried().isEmpty()) { target = Target.ITEM; hoverSlot = selected; }
                else { target = Target.PLACE; placeSlot = slotFor(Hands.carried(), selected); }
                aim = restPoint(placeSlot < 0 ? selected : placeSlot);
            }
            return;
        }
        ItemStack carried = Hands.carried();
        double[] mouse = Picking.mouseGui();
        if (carried.isEmpty()) {
            for (int i = 1; i >= 0; i--) {
                if (!slot(i).isEmpty() && GrindstoneStation.under(mouse, restPoint(i), i == 0 ? 0.2 : 0.1)) {
                    target = Target.ITEM;
                    hoverSlot = i;
                    aim = restPoint(i);
                    return;
                }
            }
            return;
        }
        Vec3 hit = Picking.cursorOnPlane(top.y);
        if (hit == null || Math.abs(hit.x - top.x) > 0.52 || Math.abs(hit.z - top.z) > 0.52) {
            return;
        }
        int nearest = hit.distanceToSqr(restPoint(0)) < hit.distanceToSqr(restPoint(1)) ? 0 : 1;
        target = Target.PLACE;
        placeSlot = slotFor(carried, nearest);
        aim = restPoint(placeSlot < 0 ? nearest : placeSlot);
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
                Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.5f, 1.2f);
            }
            case ITEM -> {
                if (button == 0 && hoverSlot == 0 && !slot(2).isEmpty()) {
                    work(shift);
                } else {
                    slotClick(hoverSlot, 0, shift);
                }
            }
            default -> {}
        }
    }

    private void work(boolean shift) {
        mapBefore = slot(0).copyWithCount(1);
        addition = slot(1).copyWithCount(1);
        result = slot(2).copy();
        work = addition.is(Items.PAPER) ? Work.ZOOM : addition.is(Items.GLASS_PANE) ? Work.LOCK : Work.COPY;
        toInventory = shift;
        gleamed = false;
        Minecraft mc = Minecraft.getInstance();
        startMove(WORK_LENGTH, BEAT, () -> {
            CartographyTableMenu menu = menu();
            if (slot(2).isEmpty()) {
                cancelMove();
                return;
            }
            mc.gameMode.handleContainerInput(menu.containerId, 2, 0, toInventory ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        });
        Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.7f, 0.9f);
    }

    // ---- the work ------------------------------------------------------------------------------------

    private float mapSize() { return MAP_SIZE * surfaceScale(0); }
    private float additionSize() { return ADDITION_SIZE * surfaceScale(1); }
    private float additionYaw(double t) {
        double blend = Anim.inOutCubic(Anim.window(t, 0.1, 0.55));
        return ItemPlacer.awayYaw(side) + (float) (surfaceRotation(1) + (surfaceRotation(0) - surfaceRotation(1)) * blend);
    }

    /** Where the addition is during the work: brought to the map, laid on it or beside it. */
    private Vec3 additionPos(double t) {
        Vec3 from = restPoint(1);
        Vec3 to = switch (work) {
            case ZOOM -> restPoint(0).add(0, -0.002, 0);
            case LOCK -> restPoint(0).add(0, 0.012, 0);
            case COPY -> restPoint(0).add(surfaceRight(0).scale(-0.02)).add(surfaceOut(0).scale(0.02));
        };
        double k = Anim.inOutCubic(Anim.window(t, 0.1, 0.55));
        return Anim.lerp(from, to, k).add(0, (work == Work.LOCK ? 0.16 : 0.07) * Anim.hump(k), 0);
    }

    @Nullable
    @Override
    public Vec3[] handsOverride() {
        double t = moveTime();
        if (t < 0) {
            return null;
        }
        // the right hand brings the addition over, the left holds the map down by its corner
        double carry = Anim.inOutCubic(Anim.window(t, 0, 0.12)) * (1 - Anim.inOutCubic(Anim.window(t, 0.6, 0.85)));
        Vec3 right = Anim.lerp(rest(true), additionPos(t).add(surfaceOut(0).scale(ITEM_REACH)).add(0, HAND_Y + 0.02, 0), carry);
        Vec3 corner = restPoint(0).add(surfaceRight(0).scale(-mapSize() * 0.4)).add(surfaceOut(0).scale(mapSize() * 0.35)).add(0, HAND_Y + 0.01, 0);
        double hold = Anim.inOutCubic(Anim.window(t, 0.05, 0.3)) * (1 - Anim.inOutCubic(Anim.window(t, BEAT - 0.1, BEAT + 0.1)));
        Vec3 left = Anim.lerp(rest(false), corner, hold);
        if (t > 0.85 && t < BEAT) {
            // smoothing the sheet flat across, or tracing the land with a fingertip
            double s = Anim.window(t, 0.85, BEAT - 0.05);
            Vec3 sweep = restPoint(0).add(surfaceRight(0).scale(-0.15 + 0.3 * s)).add(surfaceOut(0).scale(0.08 * Math.sin(s * Math.PI * 3))).add(0, HAND_Y + 0.02, 0);
            right = Anim.lerp(rest(true), sweep, Anim.hump(s) * 0.9 + 0.1);
        }
        if (t >= BEAT - 0.1) {
            Vec3 grab = restPoint(0).add(surfaceOut(0).scale(0.18)).add(0, HAND_Y + 0.03, 0);
            right = Anim.lerp(Anim.lerp(right, grab, Anim.inOutCubic(Anim.window(t, BEAT - 0.1, BEAT + 0.05))),
                    rest(true).add(0, toInventory ? 0 : 0.05, 0), Anim.inOutCubic(Anim.window(t, BEAT + 0.05, WORK_LENGTH)));
        }
        return new Vec3[]{right, left};
    }

    @Override
    void frame() {
        super.frame();
        double t = moveTime();
        if (t < 0) {
            return;
        }
        Vec3 map = restPoint(0).add(0, 0.02, 0);
        if (work == Work.COPY && t > 0.6 && t < BEAT && Fx.RANDOM.nextDouble() < Scene.frameDt * 30) {
            // ink drifting across from the map onto the copy
            Fx.motes(map, mapSize() * 0.3, 1, 0.35f, 0.3f, 0.25f, 0.08);
        }
        if (!gleamed && t > 0.9) {
            gleamed = true;
            Scene.sound(work == Work.LOCK ? SoundEvents.GLASS_PLACE : SoundEvents.BOOK_PAGE_TURN, 0.6f, work == Work.LOCK ? 1.4f : 1.0f);
            if (work == Work.LOCK) {
                Fx.motes(map, mapSize() * 0.4, 12, 0.8f, 0.9f, 1f, 0.15);
            }
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------

    @Override
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        Vec3 cam = camera.position();
        if (outro()) {
            drawLeaving(pose, buffers, camera, light);
            return;
        }
        double t = moveTime();
        float yaw = ItemPlacer.awayYaw(side) + surfaceRotation(0);
        if (t < 0) {
            drawMap(pose, buffers, cam, slot(0), restPoint(0), yaw, mapSize() * (hoverSlot == 0 ? 1.03f : 1f), light);
            SmithingStation.drawLaid(pose, buffers, cam, slot(1), restPoint(1), side, 2, (hoverSlot == 1 ? 1.08f : 1f) * additionSize() / 0.2f, light, surfaceRotation(1));
            return;
        }
        // what is left of the stacks while one of each is used
        ItemStack restAddition = t < BEAT ? slot(1).copyWithCount(Math.max(0, slot(1).getCount() - 1)) : slot(1);
        SmithingStation.drawLaid(pose, buffers, cam, restAddition, restPoint(1), side, 2, additionSize() / 0.2f, light, surfaceRotation(1));
        if (t < BEAT) {
            drawWorking(pose, buffers, camera, t, yaw, light);
            return;
        }
        // to the hand (or the bag)
        double k = Anim.inOutCubic(Anim.window(t, BEAT, WORK_LENGTH));
        Vec3 destination = toInventory ? cam.add(0, 0.5, 0).add(Scene.yawForward(camera.yRot()).scale(0.6))
                : heldItemPoint(Hands.position(true), Hands.shoulder(true, cam, camera.yRot()));
        Vec3 at = Anim.lerp(restPoint(0).add(0, 0.1 * Anim.hump(k), 0), destination, Anim.inCubic(k));
        float size = (float) (mapSize() * (1 - 0.7 * k));
        drawMap(pose, buffers, cam, mapBefore, at, yaw, size, light);
        if (work == Work.COPY && result.getCount() > 1) {
            drawMap(pose, buffers, cam, mapBefore, at.add(surfaceRight(0).scale(0.03 * (1 - k))).add(0, -0.004, 0), yaw + 8, size, light);
        }
    }

    private void drawWorking(PoseStack pose, SceneBuffers buffers, Camera camera, double t, float yaw, int light) {
        Vec3 cam = camera.position();
        Vec3 map = restPoint(0);
        Vec3 add = additionPos(t);
        double settle = Anim.inOutCubic(Anim.window(t, 0.55, 1.25));
        switch (work) {
            case ZOOM -> {
                // the paper grows into the larger sheet, the old map shrinks to its middle
                float sheet = (float) (additionSize() + (mapSize() * 1.04 - additionSize()) * settle);
                ItemPlacer.drawOriented(pose, buffers, cam, addition, add.add(0, 0.002, 0), ItemPlacer.lying(additionYaw(t), 0), sheet, light);
                drawMap(pose, buffers, cam, mapBefore, map.add(0, 0.006, 0), yaw, (float) (mapSize() * (1 - 0.5 * settle)), light);
            }
            case LOCK -> {
                drawMap(pose, buffers, cam, mapBefore, map, yaw, mapSize(), light);
                double flip = 180 * Anim.inOutCubic(Anim.window(t, 0.15, 0.5));
                Quaternionf turn = new Quaternionf(ItemPlacer.lying(additionYaw(t), 0))
                        .premul(new Quaternionf().rotateAxis((float) Math.toRadians(flip), (float) surfaceRight(0).x, 0, (float) surfaceRight(0).z));
                ItemPlacer.drawOriented(pose, buffers, cam, addition, add.add(0, 0.006, 0), turn,
                        (float) (additionSize() + (mapSize() * 1.02 - additionSize()) * settle), light);
                if (t > 0.9) {
                    // a gleam runs across the glass
                    double g = Anim.window(t, 0.9, 1.4);
                    float b = (float) (0.4 * Anim.hump(g));
                    Vec3 at = map.add(surfaceRight(0).scale(-mapSize() / 2 + mapSize() * g)).add(surfaceOut(0).scale(mapSize() * (0.5 - g) * 0.6)).add(0, 0.02, 0);
                    EnchantFx.billboard(pose, buffers, camera, at, 0.08, 0, EnchantFx.GLOW, b, b, b);
                }
            }
            case COPY -> {
                drawMap(pose, buffers, cam, mapBefore, map.add(surfaceRight(0).scale(-0.06 * settle)), yaw - 6 * (float) settle, mapSize(), light);
                Vec3 copy = add.add(surfaceRight(0).scale(0.08 * settle)).add(surfaceOut(0).scale(-0.02 * settle)).add(0, 0.004, 0);
                // the empty map turns into the copy as the land is traced onto it
                double traced = Anim.smoother(Anim.window(t, 0.85, BEAT - 0.05));
                if (traced < 0.5) {
                    ItemPlacer.drawOriented(pose, buffers, cam, addition, copy, ItemPlacer.lying(additionYaw(t) + 6, 0), (float) (additionSize() + (mapSize() - additionSize()) * settle),
                            light);
                } else {
                    drawMap(pose, buffers, cam, mapBefore, copy, yaw + 6, mapSize(), light);
                }
                if (t > 0.85) {
                    float b = (float) (0.3 * Anim.hump(traced));
                    EnchantFx.billboard(pose, buffers, camera, Anim.lerp(map, copy, traced).add(0, 0.03, 0), 0.07, 0, EnchantFx.GLOW, b, b * 0.9f, b * 0.7f);
                }
            }
        }
    }

    /** A filled map lying on the table, drawn as the map itself (or as its item when its data is not here). */
    static void drawMap(PoseStack pose, SceneBuffers buffers, Vec3 cam, ItemStack stack, Vec3 at, float yaw, float size, int light) {
        if (stack.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MapId id = stack.get(DataComponents.MAP_ID);
        MapItemSavedData data = id == null || mc.level == null ? null : mc.level.getMapData(id);
        if (data == null) {
            ItemPlacer.drawOriented(pose, buffers, cam, stack, at.add(0, 0.004, 0), ItemPlacer.lying(yaw, 0), size, light);
            return;
        }
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y + 0.004 - cam.y, at.z - cam.z);
        // the map's quad faces -Z: tipped over this way it faces up, its top away from the player
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees(yaw)));
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.XP.rotationDegrees(90f)));
        float s = size / 128f;
        pose.scale(s, s, s);
        pose.translate(-64f, -64f, 0f);
        // the parchment under the map, as the map in hand has it (the map itself only paints what is charted)
        VertexConsumer paper = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.text(MAP_BACKGROUND));
        Matrix4f m = pose.last().pose();
        paper.addVertex(m, -7, 135, 0.005f).setColor(255, 255, 255, 255).setUv(0, 1).setLight(light);
        paper.addVertex(m, 135, 135, 0.005f).setColor(255, 255, 255, 255).setUv(1, 1).setLight(light);
        paper.addVertex(m, 135, -7, 0.005f).setColor(255, 255, 255, 255).setUv(1, 0).setLight(light);
        paper.addVertex(m, -7, -7, 0.005f).setColor(255, 255, 255, 255).setUv(0, 0).setLight(light);
        var mapState = new net.minecraft.client.renderer.state.MapRenderState();
        mc.getMapRenderer().extractRenderState(id, data, mapState);
        mc.getMapRenderer().render(mapState, pose, dev.dyslexic.cinematic.client.SceneRenderer.collector(), false, light);
        pose.popPose();
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        if (gridEditor.visible()) return;
        if (target == Target.PLACE && aim != null) {
            float p = (float) (0.18 + 0.06 * Math.sin(Scene.clock() * 5));
            boolean ok = placeSlot >= 0;
            EnchantFx.disc(pose, buffers, camera, aim.add(0, 0.004, 0), placeSlot == 0 ? 0.26 * surfaceScale(0) : 0.12 * surfaceScale(1), 0, EnchantFx.GLOW, p, ok ? p * 0.8f : p * 0.2f,
                    ok ? p * 0.6f : p * 0.2f);
        }
    }

    // ---- what others see -----------------------------------------------------------------------------

    static void drawContents(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction side, List<ItemStack> items, int light) {
        Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        if (!items.isEmpty()) {
            drawMap(pose, buffers, cam, items.get(0), slotPoint(top, side, 0), ItemPlacer.awayYaw(side) + (float) SurfaceLayouts.current(StationKind.CARTOGRAPHY).region(0).rotation(), MAP_SIZE * (float) SurfaceLayouts.current(StationKind.CARTOGRAPHY).region(0).itemScale(), light);
        }
        if (items.size() > 1) {
            SmithingStation.drawLaid(pose, buffers, cam, items.get(1), slotPoint(top, side, 1), side, 2, ADDITION_SIZE / 0.2f * (float) SurfaceLayouts.current(StationKind.CARTOGRAPHY).region(1).itemScale(), light, (float) SurfaceLayouts.current(StationKind.CARTOGRAPHY).region(1).rotation());
        }
    }

    // ---- words ---------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] mouse = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(mouse[0], mouse[1]);
        if (editorTip != null || gridEditor.editing()) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        if (target == Target.PLACE) {
            return placeSlot < 0 ? List.of(Component.translatable("cinematic.cartography.refuse").withStyle(ChatFormatting.RED))
                    : List.of(Component.translatable(placeSlot == 0 ? "cinematic.cartography.slot.map" : "cinematic.cartography.slot.addition")
                            .withStyle(ChatFormatting.GRAY));
        }
        if (target != Target.ITEM) {
            return null;
        }
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, slot(hoverSlot)));
        lines.add(Component.empty());
        ItemStack addition = slot(1);
        if (!slot(2).isEmpty() && hoverSlot == 0) {
            String key = addition.is(Items.PAPER) ? "zoom" : addition.is(Items.GLASS_PANE) ? "lock" : "copy";
            lines.add(Component.translatable("cinematic.cartography." + key).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("cinematic.cartography.work").withStyle(ChatFormatting.GOLD));
        } else {
            if (hoverSlot == 0 && addition.isEmpty()) {
                lines.add(Component.translatable("cinematic.cartography.add").withStyle(ChatFormatting.DARK_GRAY));
            } else if (!addition.isEmpty() && slot(2).isEmpty() && !slot(0).isEmpty()) {
                lines.add(Component.translatable("cinematic.cartography.cannot").withStyle(ChatFormatting.RED));
            }
            lines.add(Component.translatable("cinematic.cartography.take").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    @Override
    public Component hint() {
        if (gridEditor.editing()) return SurfaceEditor.text("hint");
        return Component.translatable("cinematic.hint.cartography");
    }
}
