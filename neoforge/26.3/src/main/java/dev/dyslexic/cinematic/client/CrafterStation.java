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
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * The crafter scene. Items are laid into the nine windows of the crafter's grid, on whichever face the grid is
 * turned to: lying on it when it faces up, set into it when it faces sideways. An empty window touched with an
 * empty hand is shut, red, as in the crafter's window. What it would make hangs over the grid; the crafter only
 * makes it on a redstone signal, exactly as in vanilla. Underneath is the vanilla crafter menu.
 */
public final class CrafterStation extends MenuStation<CrafterMenu> implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    static final double HAND_Y = 0.062;
    static final double ITEM_REACH = 0.075;
    private static final double UPRIGHT_REST_OUT = 0.18, UPRIGHT_REST_SPREAD = 0.38, UPRIGHT_REST_DROP = 0.28;
    /** Distance between the windows: 2 px each with a 1 px bar between (crafter_top.png). */
    static final double PITCH = 3 / 16.0;
    /** Index of the menu's result slot: after the 9 grid slots and the 36 of the inventory. */
    static final int RESULT = 45;

    public enum Target { NONE, CELL, RESULT }

    private static final Identifier DISABLED_SLOT = Identifier.withDefaultNamespace("container/crafter/disabled_slot");

    /** Which way the grid faces: up (worked from any side, leaning over) or sideways (worked standing before it). */
    private final Direction grid;
    private final Vec3 face;
    public Target target = Target.NONE;
    public int hoverCell = -1;
    private boolean wasCrafting;

    public CrafterStation(BlockPos pos, Direction side, BlockState state) {
        super(pos, side, CrafterMenu.class, 9);
        grid = state.getValue(BlockStateProperties.ORIENTATION).top();
        face = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(grid.getUnitVec3i()).scale(0.5));
    }

    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    @Override public ItemStack layoutItem(int slot) { return slot(slot); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public void renderScreen(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public SpatialAppearance.Frame layoutFrame() {
        return SpatialAppearance.Frame.of(kind(), pos, side, grid);
    }
    @Override public float layoutHeldScale() { return hoverCell < 0 ? 1f : geometry().scale(hoverCell); }
    @Override public List<Vec3> workSamples() { return layoutSamples(super.workSamples()); }

    @Override
    public StationKind kind() {
        return StationKind.CRAFTER;
    }

    private boolean flat() {
        return grid == Direction.UP;
    }

    private Vec3 normal() {
        return Vec3.atLowerCornerOf(grid.getUnitVec3i());
    }

    /** Towards the first row of the grid: away from the player when it lies flat, up when it stands. */
    private Vec3 rows() {
        return flat() ? forward() : new Vec3(0, 1, 0);
    }

    @Override
    public Vec3 center() {
        return face;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.STONE_STEP;
    }

    @Override
    public BlockPos lightPos() {
        return pos.relative(grid);
    }

    /** Middle of window {@code i} (the crafting grid's order: rows from the top as the player reads it). */
    public Vec3 cell(int i) {
        return layoutPoint(i);
    }

    @Override
    protected Vec3 restPoint(int slot) {
        return cell(slot).add(normal().scale(0.01));
    }

    public Vec3 resultPoint() {
        double bob = Math.sin(Scene.clock() * 2.1) * 0.01;
        return flat() ? face.add(forward().scale(0.42)).add(0, 0.17 + bob, 0) : face.add(0, 0.4 + bob, 0).add(normal().scale(0.14));
    }

    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 middle = Vec3.atBottomCenterOf(pos);
        Vec3 stand = new Vec3(middle.x + side.getStepX() * 0.9, player.getY(), middle.z + side.getStepZ() * 0.9);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public boolean crouches() {
        return !flat();
    }

    @Override
    public boolean workOnTop() {
        return flat();
    }

    @Override
    public boolean worksAtAnAngle() {
        return true;
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 eye = flat() ? face.add(side().scale(0.66)).add(0, 0.62, 0) : face.add(side().scale(0.8)).add(0, 0.2, 0);
        Vec3 look = (flat() ? face.add(side().scale(-0.04)) : face.add(0, 0.04, 0)).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, 0);
    }

    @Override
    public float bodyPitch() {
        return flat() ? 50f : 20f;
    }

    @Override
    public Vec3 rest(boolean right) {
        // On an upright grid the lid is above the eyes; resting on it would cover the upper windows with both arms.
        if (!flat()) return face.add(normal().scale(UPRIGHT_REST_OUT)).add(right().scale(right ? UPRIGHT_REST_SPREAD : -UPRIGHT_REST_SPREAD))
                .add(0, -UPRIGHT_REST_DROP, 0);
        Vec3 top = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        return top.add(side().scale(0.36)).add(right().scale(right ? 0.25 : -0.25)).add(0, HAND_Y, 0);
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        CrafterMenu menu = menu();
        if (overPanel || menu == null) {
            return;
        }
        double[] mouse = Picking.mouseGui();
        if (!slot(RESULT).isEmpty() && Hands.carried().isEmpty() && GrindstoneStation.under(mouse, resultPoint(), 0.12)) {
            target = Target.RESULT;
            aim = resultPoint().add(0, -0.12, 0).add(side().scale(0.12));
            return;
        }
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            hoverCell = layoutPick();
            if (hoverCell >= 0) { target = Target.CELL; aim = cell(hoverCell); }
            return;
        }
        Vec3 hit = Picking.cursorOnPlane(face, normal());
        if (hit == null) {
            return;
        }
        Vec3 rel = hit.subtract(face);
        double across = rel.dot(right()), down = -rel.dot(rows());
        if (Math.abs(across) > 1.6 * PITCH || Math.abs(down) > 1.6 * PITCH) {
            return;
        }
        int col = (int) Math.round(across / PITCH) + 1;
        int row = (int) Math.round(down / PITCH) + 1;
        hoverCell = Math.max(0, Math.min(2, row)) * 3 + Math.max(0, Math.min(2, col));
        target = Target.CELL;
        aim = cell(hoverCell);
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        hoverCell = -1;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        if (!flat() && target == Target.CELL) {
            // in front of a window in the standing grid, pushing into it on a tap
            return aim.add(normal().scale(0.1 - 0.05 * tap)).add(0, -0.04, 0);
        }
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
        CrafterMenu menu = menu();
        Minecraft mc = Minecraft.getInstance();
        if (target != Target.CELL || menu == null || mc.gameMode == null) {
            return;
        }
        boolean carrying = !Hands.carried().isEmpty();
        if (!slot(hoverCell).isEmpty() || carrying) {
            if (carrying && menu.isSlotDisabled(hoverCell)) {
                deny();
                return;
            }
            slotClick(hoverCell, button, shift);
            Scene.sound(SoundEvents.STONE_BUTTON_CLICK_ON, 0.35f, 1.4f);
            return;
        }
        // an empty window touched with an empty hand opens or shuts, as in the crafter's window
        boolean enable = menu.isSlotDisabled(hoverCell);
        menu.setSlotState(hoverCell, enable);
        mc.gameMode.handleSlotStateChanged(hoverCell, menu.containerId, enable);
        Hands.tap();
        Scene.sound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, enable ? 1.0f : 0.75f);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        BlockState state = mc.level == null ? null : mc.level.getBlockState(pos);
        boolean crafting = state != null && state.hasProperty(CrafterBlock.CRAFTING) && state.getValue(CrafterBlock.CRAFTING);
        if (crafting && !wasCrafting) {
            // it made something: a puff at its mouth
            Direction front = state.getValue(BlockStateProperties.ORIENTATION).front();
            Fx.motes(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(front.getUnitVec3i()).scale(0.55)), 0.15, 16, 1f, 0.35f, 0.25f, 0.25);
        }
        wasCrafting = crafting;
    }

    // ---- drawing -------------------------------------------------------------------------------------

    @Override
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        if (outro()) {
            // the crafter keeps them: they settle back into its windows as the player steps away
            float stay = (float) (1 - Anim.smoother(Anim.window(Scene.time / Scene.OUTRO_LENGTH, 0.1, 0.55)));
            if (stay > 0.01f) {
                drawGrid(pose, buffers, camera.position(), pos, grid, side, leftBehind(), -1, light, stay);
            }
            return;
        }
        List<ItemStack> items = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            items.add(slot(i));
        }
        drawGrid(pose, buffers, camera.position(), pos, grid, side, items, hoverCell, light);
    }

    /** The items in the grid's windows. */
    static void drawGrid(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction grid, Direction side, List<ItemStack> items,
            int hover, int light) {
        drawGrid(pose, buffers, cam, pos, grid, side, items, hover, light, 1f);
    }

    /** The same, each item at {@code scale} of its size (settling back into the windows). */
    static void drawGrid(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction grid, Direction side, List<ItemStack> items,
            int hover, int light, float scale) {
        SpatialAppearance layout = SpatialLayouts.current(StationKind.CRAFTER);
        SpatialAppearance.Frame frame = SpatialAppearance.Frame.of(StationKind.CRAFTER, pos, side, grid);
        Vec3 normal = frame.normal();
        for (int i = 0; i < Math.min(9, items.size()); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            Vec3 at = layout.point(frame, i);
            float grow = (i == hover ? 1.1f : 1f) * scale * layout.scale(i);
            if (grid == Direction.UP) {
                pose.pushPose();
                Vec3 pivot = at.subtract(cam);
                pose.translate(pivot.x, pivot.y, pivot.z);
                pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees((float) layout.rotation(i))));
                pose.translate(-pivot.x, -pivot.y, -pivot.z);
                ItemPlacer.render(pose, buffers, cam, stack, at.add(0, 0.001, 0), side, 4242L + i, 0.55f * grow, 0f, light);
                pose.popPose();
            } else {
                boolean block = ItemPlacer.isBlock(stack);
                float size = (block ? 0.085f : 0.12f) * grow;
                ItemPlacer.drawOriented(pose, buffers, cam, stack, at.add(normal.scale(block ? size / 2 : 0.006)),
                        new org.joml.Quaternionf().rotationAxis((float) Math.toRadians(layout.rotation(i)),
                                (float) normal.x, (float) normal.y, (float) normal.z).mul(ItemPlacer.lying(ItemPlacer.awayYaw(side), 90)), size, light);
            }
        }
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        CrafterMenu menu = menu();
        if (menu == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.mainCamera();
        double clock = Scene.clock();
        for (int i = 0; i < 9; i++) {
            boolean disabled = menu.isSlotDisabled(i);
            boolean hovered = i == hoverCell;
            if (!disabled && !hovered) {
                continue;
            }
            Vec3 at = cell(i).add(normal().scale(0.004));
            float p = (float) (0.22 + 0.06 * Math.sin(clock * 5));
            if (disabled) {
                // a shut window shows the crafter window's own mark for it
                drawShut(pose, buffers, camera, at, i, hovered ? 1.1 : 1.0);
            } else {
                boolean ok = Hands.carried().isEmpty() || slot(i).isEmpty() || ItemStack.isSameItemSameComponents(Hands.carried(), slot(i));
                Fx.facePatch(pose, buffers, camera, at, geometry().axis(layoutFrame(), i, true), geometry().axis(layoutFrame(), i, false), 0.075 * geometry().scale(i), p, ok ? p * 0.85f : p * 0.25f, ok ? p * 0.6f : p * 0.2f);
            }
        }
        ItemStack result = slot(RESULT);
        if (!result.isEmpty()) {
            // what it would make, spinning over the grid: made on a redstone signal
            boolean powered = menu.isPowered();
            Vec3 at = resultPoint();
            float g = (float) (powered ? 0.35 + 0.15 * Math.sin(clock * 8) : 0.16);
            EnchantFx.billboard(pose, buffers, cam, at, 0.1, clock * 0.3, EnchantFx.GLOW, g, powered ? g * 0.3f : g * 0.85f, powered ? g * 0.25f : g * 0.7f);
            pose.pushPose();
            pose.translate(at.x - camera.x, at.y - camera.y, at.z - camera.z);
            pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees((float) (clock * (target == Target.RESULT ? 70 : 32) % 360))));
            float size = target == Target.RESULT ? 0.22f : 0.19f;
            float scale = ItemPlacer.isBlock(result) ? size * 1.6f : size;
            pose.scale(scale, scale, scale);
            ItemModels.drawStatic(result, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers);
            pose.popPose();
        }
    }

    /** The crafter window's mark for a disabled slot, laid into the window. */
    private void drawShut(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 at, int slot, double scale) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.GUI).getSprite(DISABLED_SLOT);
        VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(sprite.atlasLocation()));
        double[] half = geometry().half(layoutFrame(), slot);
        Vec3 u = geometry().axis(layoutFrame(), slot, true).scale(half[0] * 0.95 * scale);
        Vec3 v = geometry().axis(layoutFrame(), slot, false).scale(half[1] * 0.95 * scale), n = normal();
        Vec3[] corners = {at.subtract(u).add(v), at.subtract(u).subtract(v), at.add(u).subtract(v), at.add(u).add(v)};
        float[][] uv = {{sprite.getU0(), sprite.getV0()}, {sprite.getU0(), sprite.getV1()}, {sprite.getU1(), sprite.getV1()}, {sprite.getU1(), sprite.getV0()}};
        for (int i = 0; i < 4; i++) {
            Vec3 p = corners[i].subtract(cam);
            vc.addVertex(pose.last(), (float) p.x, (float) p.y, (float) p.z).setColor(255, 255, 255, 235).setUv(uv[i][0], uv[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose.last(), (float) n.x, (float) n.y, (float) n.z);
        }
    }

    // ---- what others see -----------------------------------------------------------------------------

    static void drawContents(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, BlockState state, Direction side, List<ItemStack> items,
            int light) {
        Direction grid = state.getValue(BlockStateProperties.ORIENTATION).top();
        if (grid != Direction.DOWN) {
            drawGrid(pose, buffers, cam, pos, grid, side, items, -1, light);
        }
    }

    // ---- words ---------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] editorMouse = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(editorMouse[0], editorMouse[1]);
        if (editorTip != null || gridEditor.editing()) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        CrafterMenu menu = menu();
        if (menu == null) {
            return null;
        }
        if (target == Target.RESULT) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, slot(RESULT)));
            lines.add(Component.translatable(menu.isPowered() ? "cinematic.crafter.powered" : "cinematic.crafter.signal").withStyle(ChatFormatting.RED));
            return lines;
        }
        if (target != Target.CELL) {
            return null;
        }
        if (menu.isSlotDisabled(hoverCell)) {
            return List.of(Component.translatable("cinematic.crafter.shut").withStyle(ChatFormatting.RED),
                    Component.translatable("cinematic.crafter.open").withStyle(ChatFormatting.GRAY));
        }
        ItemStack there = slot(hoverCell);
        if (!there.isEmpty() && carried.isEmpty()) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, there));
            lines.add(Component.translatable("cinematic.crafter.take").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        return carried.isEmpty() ? List.of(Component.translatable("cinematic.crafter.toggle").withStyle(ChatFormatting.GRAY)) : null;
    }

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.crafter");
    }
}
