package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dyslexic.cinematic.furnace.FurnaceLayout;
import dev.dyslexic.cinematic.furnace.FurnaceZones;
import dev.dyslexic.cinematic.furnace.FurnaceZones.Face;
import dev.dyslexic.cinematic.net.FurnaceStatePayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.phys.Vec3;
import dev.dyslexic.cinematic.net.PacketDistributor;
import org.joml.Matrix4f;

/**
 * The furnace scenes: walk round to the front and feed the furnace by hand, following the openings painted on
 * its textures ({@link FurnaceLayout}). Furnace and smoker are worked crouching with the head a little tilted;
 * the blast furnace standing, leaning over it, since it is charged from the top.
 */
public final class FurnaceStation extends ClientStation {
    /** How the player and camera settle in front of one kind of furnace. */
    private record Staging(boolean crouch, double out, double up, double side, double lookV, float roll, float bodyPitch,
            Face restFace, double restU, double restV, double restOut) {}

    private static final Staging LOW = new Staging(true, 0.9, 0.5, 0.1, 0.44, -3.5f, 32f, Face.FRONT, 0.2, 0.09, 0.36);
    private static final Staging TALL = new Staging(false, 0.7, 1.15, 0.08, 0.97, -2f, 45f, Face.TOP, 0.15, 0.13, 0.06);

    static final double ITEM_REACH = 0.07;
    static final double HAND_Y = 0.062;

    private final StationKind kind;
    public final FurnaceLayout layout;
    final FurnaceGridEditor gridEditor;
    private final Staging staging;

    public int hoverZone = -1;
    private Face aimFace = Face.FRONT;

    // what the vanilla menu would show; refreshed by the server every few ticks and run on locally in between
    private int litTime, litDuration, cookingProgress, cookingTotalTime;
    private ItemStack guideHint = ItemStack.EMPTY;
    private double guideHintAt;

    public FurnaceStation(BlockPos pos, Direction facing, StationKind kind) {
        super(pos, facing);
        this.kind = kind;
        this.layout = FurnaceLayout.of(kind);
        this.staging = kind == StationKind.BLAST_FURNACE ? TALL : LOW;
        this.gridEditor = new FurnaceGridEditor(this);
    }

    @Nullable
    ItemStack cookingInput(java.util.List<ItemStack> inputs) {
        Minecraft mc = Minecraft.getInstance();
        if (inputs.isEmpty()) return null;
        if (mc.player != null) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                for (ItemStack input : inputs) {
                    if (ItemStack.isSameItem(stack, input)) return stack.copyWithCount(1);
                }
            }
        }
        return inputs.get(0).copyWithCount(1);
    }

    void showHint(@Nullable ItemStack stack) {
        guideHint = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        guideHintAt = Scene.clock();
    }

    private void drawHint(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        if (guideHint.isEmpty() || Minecraft.getInstance().level == null) return;
        ItemStack inside = ClientFurnaces.item(pos, FurnaceZones.INPUT);
        if (!inside.isEmpty()) {
            if (ItemStack.isSameItem(inside, guideHint)) guideHint = ItemStack.EMPTY;
            return;
        }
        FurnaceLayout.Bed bed = null;
        for (FurnaceLayout.Bed b : layout.beds) if (b.slot() == FurnaceZones.INPUT) bed = b;
        if (bed == null) return;
        Direction facing = Minecraft.getInstance().level.getBlockState(pos).getValue(AbstractFurnaceBlock.FACING);
        FurnaceAppearance appearance = geometry();
        double u = (bed.u0() + bed.u1()) / 2;
        Vec3 base = appearance.point(layout, pos, facing, bed.slot(), u,
                bed.face() == Face.FRONT ? bed.floor() + 0.002 : bed.floor(), -bed.depth(), true);
        float appear = (float) Anim.smoother((Scene.clock() - guideHintAt) / 0.3);
        float opacity = appear * (0.3f + 0.07f * (float) Math.sin(Scene.clock() * 3));
        if (buffers instanceof SceneBuffers.BufferSource source) source.endBatch();
        ItemPlacer.render(pose, buffers, camera, guideHint, base, facing, 7L,
                0.95f * (float) appearance.region(bed.slot()).itemScale(), bed.tilt(), light, opacity);
        if (buffers instanceof SceneBuffers.BufferSource source) source.endBatch();
    }

    @Override
    public StationKind kind() {
        return kind;
    }

    FurnaceAppearance geometry() { return FurnaceLayouts.current(kind); }
    @Override public boolean busy() { return gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); }

    @Override public List<Vec3> workSamples() {
        if (geometry().equals(FurnaceAppearance.VANILLA)) return super.workSamples();
        List<Vec3> points = new ArrayList<>(super.workSamples());
        for (int i = 0; i < 3; i++) {
            Vec3 normal = geometry().face(layout, i) == Face.TOP ? new Vec3(0, 0.04, 0) : side().scale(0.04);
            points.add(geometry().center(layout, pos, side, i).add(normal));
        }
        return points;
    }

    private String key() {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** Middle of the front face. */
    @Override
    public Vec3 center() {
        return FurnaceZones.point(pos, side, 0.5, 0.5, 0);
    }

    @Override
    public BlockPos lightPos() {
        return pos.relative(side);
    }

    @Override
    public SoundEvent touchSound() {
        return kind == StationKind.BLAST_FURNACE ? SoundEvents.METAL_STEP : SoundEvents.STONE_STEP;
    }

    @Override
    public boolean stillThere(Level level) {
        return super.stillThere(level) && level.getBlockState(pos).getValue(AbstractFurnaceBlock.FACING) == side;
    }

    // ---- staging -------------------------------------------------------------------------------------

    /** Round the furnace if needed, then to the middle of its front. */
    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 middle = new Vec3(pos.getX() + 0.5, player.getY(), pos.getZ() + 0.5);
        Vec3 out = side();
        Vec3 across = right();
        Vec3 rel = player.position().subtract(middle);
        double ahead = rel.dot(out);
        double aside = rel.dot(across);
        List<Vec3> path = new ArrayList<>();
        if (ahead < 0.45) {
            double sign = aside >= 0 ? 1 : -1;
            if (ahead < -0.45) {
                path.add(middle.add(across.scale(sign * 1.15)));
            }
            path.add(middle.add(across.scale(sign * 1.1)).add(out.scale(1.0)));
        }
        path.add(middle.add(out.scale(0.92)));
        return path;
    }

    @Override
    public boolean crouches() {
        return staging.crouch();
    }

    @Override
    public boolean workOnTop() {
        return false;
    }

    @Override
    public float bodyPitch() {
        return staging.bodyPitch();
    }

    @Override
    public boolean worksAtAnAngle() {
        return true;
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 face = center();
        Vec3 eye = face.add(side().scale(staging.out())).add(0, staging.up(), 0).add(right().scale(staging.side()));
        Vec3 look = FurnaceZones.point(pos, side, 0.5, staging.lookV(), 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, staging.roll());
    }

    /**
     * Crouched, the hands wait low in front of the furnace; standing at the blast furnace, they rest on its top. A furnace
     * sunk below the player's floor is rested on its top too, at the front edge: waiting low in front of it the arms
     * would reach all the way down into the pit.
     */
    @Override
    public Vec3 rest(boolean right) {
        if (staging.restFace() == Face.FRONT && sunk()) {
            Vec3 at = FurnaceZones.point(pos, side, Face.TOP, right ? 1 - SUNK_REST_U : SUNK_REST_U, SUNK_REST_V, SUNK_REST_OUT);
            return at.add(0, HAND_Y - SUNK_REST_OUT, 0);
        }
        double u = right ? 1 - staging.restU() : staging.restU();
        Vec3 at = FurnaceZones.point(pos, side, staging.restFace(), u, staging.restV(), staging.restOut());
        return staging.restFace() == Face.TOP ? at.add(0, HAND_Y - staging.restOut(), 0) : at;
    }

    /** Where the hands rest on the top of a sunk furnace: near the front edge, a little in from the sides. */
    private static final double SUNK_REST_U = 0.18, SUNK_REST_V = 0.14, SUNK_REST_OUT = 0.06;

    /** Whether the furnace's top is at the player's floor or below it. */
    private boolean sunk() {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        return player != null && player.getY() >= pos.getY() + 0.8;
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        hoverZone = -1;
        aim = null;
        double[] mouse = Picking.mouseGui();
        if (overPanel || gridEditor.over(mouse[0], mouse[1])) {
            return;
        }
        if (!geometry().equals(FurnaceAppearance.VANILLA)) {
            ZoneHit hit = zoneHit(false);
            if (hit != null) {
                hoverZone = hit.index(); aimFace = geometry().face(layout, hoverZone);
                FurnaceAppearance.Region region = geometry().region(hoverZone);
                Vec3 vertical = aimFace == Face.FRONT ? new Vec3(0, 1, 0) : side().scale(-1);
                aim = hit.world().add(right().scale(region.itemU() / 16)).add(vertical.scale(region.itemV() / 16));
            }
            return;
        }
        Vec3 camera = Picking.camera();
        double bestDistance = Double.MAX_VALUE;
        Vec3 face = center();
        Vec3 hit = Picking.cursorOnPlane(face, side());
        if (hit != null) {
            double u = hit.subtract(face).dot(right()) + 0.5;
            double v = hit.y - pos.getY();
            if (u > -0.08 && u < 1.08 && v > -0.08 && v < 1.08) {
                bestDistance = hit.distanceToSqr(camera);
                pick(Face.FRONT, u, v);
            }
        }
        if (layout.usesTop()) {
            Vec3 topCenter = FurnaceZones.top(pos, side, 0.5, 0.5, 0);
            Vec3 above = Picking.cursorOnPlane(topCenter, new Vec3(0, 1, 0));
            if (above != null && above.distanceToSqr(camera) < bestDistance) {
                Vec3 rel = above.subtract(topCenter);
                double u = rel.dot(right()) + 0.5;
                double v = 0.5 - rel.dot(side());
                if (u > -0.05 && u < 1.05 && v > -0.05 && v < 1.05) {
                    pick(Face.TOP, u, v);
                }
            }
        }
    }

    private void pick(Face face, double u, double v) {
        aimFace = face;
        hoverZone = layout.zoneAt(face, u, v);
        aim = FurnaceZones.point(pos, side, face, Mth.clamp(u, 0.08, 0.92), Mth.clamp(v, 0.05, 0.95), 0);
    }

    record ZoneHit(int index, Vec3 world) {}

    /** Each edited zone has its own parallel contact plane; nearest visible face wins. */
    ZoneHit zoneHit(boolean editor) {
        FurnaceAppearance appearance = geometry();
        ZoneHit best = null;
        double distance = Double.MAX_VALUE;
        for (int i = 0; i < (editor ? 5 : 3); i++) {
            Face face = appearance.face(layout, i);
            Vec3 normal = face == Face.FRONT ? side() : new Vec3(0, 1, 0);
            double out = appearance.region(i).out() / 16;
            Vec3 middle = FurnaceZones.point(pos, side, face, 0.5, 0.5, out);
            Vec3 hit = Picking.cursorOnPlane(middle, normal);
            if (hit == null) continue;
            Vec3 rel = hit.subtract(middle);
            double u = rel.dot(right()) + 0.5;
            double v = face == Face.FRONT ? hit.y - pos.getY() : 0.5 - rel.dot(side());
            double d = hit.distanceToSqr(Picking.camera());
            if (d < distance && FurnaceAppearance.contains(appearance.rect(layout, i, !editor), u, v)) {
                distance = d; best = new ZoneHit(i, hit);
            }
        }
        return best;
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        hoverZone = -1;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        if (aimFace == Face.TOP) {
            Vec3 reach = new Vec3(aim.x - shoulder.x, 0, aim.z - shoulder.z).normalize();
            double hover = carrying ? 0.075 : 0.045;
            return aim.add(0, HAND_Y + hover - 0.07 * tap, 0).subtract(reach.scale(ITEM_REACH));
        }
        Vec3 reach = aim.subtract(shoulder).normalize();
        double hover = carrying ? 0.11 : 0.075;
        // a tap pushes the fist past the face, into the opening
        return aim.add(side().scale(hover - 0.18 * tap)).subtract(reach.scale(ITEM_REACH)).add(0, 0.035, 0);
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        if (aimFace == Face.TOP) {
            Vec3 reach = new Vec3(hand.x - shoulder.x, 0, hand.z - shoulder.z).normalize();
            return hand.add(reach.scale(ITEM_REACH)).add(0, -HAND_Y + 0.012, 0);
        }
        Vec3 reach = hand.subtract(shoulder).normalize();
        return hand.add(reach.scale(ITEM_REACH)).add(0, -0.045, 0);
    }

    @Override
    public float heldItemTilt() {
        return 10f;
    }

    float heldItemScale() { return hoverZone < 0 ? 1 : (float) geometry().region(hoverZone).itemScale(); }

    @Override
    public void click(int button, boolean shift) {
        if (hoverZone < 0) {
            return;
        }
        Hands.tap();
        if (Scene.clientOnly()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.player.containerMenu instanceof AbstractFurnaceMenu menu && mc.gameMode != null)
                mc.gameMode.handleContainerInput(menu.containerId, hoverZone, button,
                        shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        } else {
            PacketDistributor.sendToServer(new StationActionPayload(pos, kind, StationActionPayload.Action.CLICK, hoverZone, button, shift, 0));
        }
    }

    /** Would the furnace take what is in hand at this zone? */
    private boolean accepts(int zone, ItemStack carried) {
        if (carried.isEmpty()) {
            return true;
        }
        return switch (zone) {
            case FurnaceZones.FUEL -> carried.has(net.minecraft.core.component.DataComponents.COOKING_FUEL);
            case FurnaceZones.RESULT -> ItemStack.isSameItemSameComponents(carried, ClientFurnaces.item(pos, FurnaceZones.RESULT));
            default -> true;
        };
    }

    @SuppressWarnings("unchecked")
    private boolean cookable(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        RecipeType<?> type = layout.recipeType;
        return mc.level != null && mc.level.recipeAccess().propertySet(type == RecipeType.SMELTING ? net.minecraft.world.item.crafting.RecipePropertySet.FURNACE_INPUT : type == RecipeType.SMOKING ? net.minecraft.world.item.crafting.RecipePropertySet.SMOKER_INPUT : net.minecraft.world.item.crafting.RecipePropertySet.BLAST_FURNACE_INPUT).test(stack);
    }

    // ---- state ---------------------------------------------------------------------------------------

    public void applyState(FurnaceStatePayload state) {
        litTime = state.litTime();
        litDuration = state.litDuration();
        cookingProgress = state.cookingProgress();
        cookingTotalTime = state.cookingTotalTime();
    }

    @Override
    public void tick() {
        if (Scene.clientOnly()) return;
        if (litTime > 0) {
            litTime--;
            if (!ClientFurnaces.item(pos, FurnaceZones.INPUT).isEmpty() && cookingTotalTime > 0) {
                cookingProgress = Math.min(cookingTotalTime - 1, cookingProgress + 1);
            }
        }
    }

    public double burn() {
        if (Scene.clientOnly() && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu menu) return menu.getLitProgress();
        return litTime <= 0 || litDuration <= 0 ? 0 : Mth.clamp(litTime / (double) litDuration, 0, 1);
    }

    public double cook() {
        if (Scene.clientOnly() && Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.containerMenu instanceof AbstractFurnaceMenu menu) return menu.getBurnProgress();
        return cookingTotalTime <= 0 ? 0 : Mth.clamp(cookingProgress / (double) cookingTotalTime, 0, 1);
    }

    // ---- drawing -------------------------------------------------------------------------------------

    private static final int[][] BOTH_WAYS = {{0, 1, 2, 3}, {3, 2, 1, 0}};
    private static final int[][] ONE_WAY = {{0, 1, 2, 3}};

    /** Hover glow on the zone under the cursor, and two gauges burnt into the front: cooking and fuel. */
    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        FurnaceAppearance appearance = geometry();
        VertexConsumer glow = buffers.getBuffer(Fx.flatGlow());
        double flicker = 0.9 + 0.1 * Math.sin(Scene.clock() * 9.0);
        gauge(pose, glow, camera, appearance.rect(layout, 3, false), Mth.lerp(gridEditor.amount(), cook(), 0.7), new int[]{255, 196, 90}, flicker, appearance.region(3).out() / 16);
        gauge(pose, glow, camera, appearance.rect(layout, 4, false), Mth.lerp(gridEditor.amount(), burn(), 0.5), new int[]{255, 92, 30}, flicker, appearance.region(4).out() / 16);
        gridEditor.renderWorld(pose, buffers, camera, light);
        if (gridEditor.visible()) return;
        drawHint(pose, buffers, camera, light);
        FurnaceLayout.Zone zone = hoverZone < 0 ? null : layout.zone(hoverZone);
        if (zone == null) {
            return;
        }
        boolean ok = accepts(hoverZone, Hands.carried());
        // one even, steady glow (it used to pulse, and its two windings fought over depth and shimmered in stripes)
        int[] tint = ok ? new int[]{255, 236, 200} : new int[]{255, 70, 60};
        quad(pose, buffers.getBuffer(Fx.HIGHLIGHT), camera, pos, side, zone.face(), appearance.rect(layout, hoverZone, false),
                0.004 + appearance.region(hoverZone).out() / 16, tint, tint, 44, false);
    }

    /** A thin glowing line on the front, lit up to {@code fill}; the rest is a faint track. */
    private void gauge(PoseStack pose, VertexConsumer glow, Vec3 camera, double[] strip, double fill, int[] rgb, double flicker, double out) {
        int[] track = {(int) (rgb[0] * 0.12), (int) (rgb[1] * 0.12), (int) (rgb[2] * 0.12)};
        glowQuad(pose, glow, camera, pos, side, Face.FRONT, strip, out + 0.003, track, track);
        if (fill > 0.001) {
            int[] lit = {(int) (rgb[0] * 0.75 * flicker), (int) (rgb[1] * 0.75 * flicker), (int) (rgb[2] * 0.75 * flicker)};
            glowQuad(pose, glow, camera, pos, side, Face.FRONT, new double[]{strip[0], strip[0] + (strip[1] - strip[0]) * fill, strip[2], strip[3]},
                    out + 0.0035, lit, lit);
        }
    }

    /** Textured emissive quad for the furnace gauges and the light inside its openings. */
    static void glowQuad(PoseStack pose, VertexConsumer buffer, Vec3 camera, BlockPos pos, Direction facing, Face face, double[] rect,
            double out, int[] near, int[] far) {
        Matrix4f m = pose.last().pose();
        Vec3[] corners = {
                FurnaceZones.point(pos, facing, face, rect[0], rect[2], out), FurnaceZones.point(pos, facing, face, rect[1], rect[2], out),
                FurnaceZones.point(pos, facing, face, rect[1], rect[3], out), FurnaceZones.point(pos, facing, face, rect[0], rect[3], out)};
        Vec3 normal = face == Face.TOP ? new Vec3(0, 1, 0) : Vec3.atLowerCornerOf(facing.getUnitVec3i());
        for (int[] order : BOTH_WAYS) {
            for (int i : order) {
                Vec3 c = corners[i];
                int[] rgb = i < 2 ? near : far;
                Fx.glowVertex(buffer, m, (float) (c.x - camera.x), (float) (c.y - camera.y), (float) (c.z - camera.z),
                        rgb[0], rgb[1], rgb[2], normal);
            }
        }
    }

    /**
     * A flat rectangle (u0, u1, v0, v1) on a face, coloured from {@code near} at v0 to {@code far} at v1 and wound both
     * ways so it shows whatever the culling.
     */
    static void quad(PoseStack pose, VertexConsumer buffer, Vec3 camera, BlockPos pos, Direction facing, Face face, double[] rect,
            double out, int[] near, int[] far, int alpha) {
        quad(pose, buffer, camera, pos, facing, face, rect, out, near, far, alpha, true);
    }

    /** The same, wound one way only when the render type does not cull ({@code bothWays} false). */
    static void quad(PoseStack pose, VertexConsumer buffer, Vec3 camera, BlockPos pos, Direction facing, Face face, double[] rect,
            double out, int[] near, int[] far, int alpha, boolean bothWays) {
        Matrix4f m = pose.last().pose();
        Vec3[] corners = {
                FurnaceZones.point(pos, facing, face, rect[0], rect[2], out), FurnaceZones.point(pos, facing, face, rect[1], rect[2], out),
                FurnaceZones.point(pos, facing, face, rect[1], rect[3], out), FurnaceZones.point(pos, facing, face, rect[0], rect[3], out)};
        int[][] orders = bothWays ? BOTH_WAYS : ONE_WAY;
        for (int[] order : orders) {
            for (int i : order) {
                Vec3 c = corners[i];
                int[] rgb = i < 2 ? near : far;
                buffer.addVertex(m, (float) (c.x - camera.x), (float) (c.y - camera.y), (float) (c.z - camera.z))
                        .setColor(rgb[0], rgb[1], rgb[2], alpha);
            }
        }
    }

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] mouse = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(mouse[0], mouse[1]);
        if (editorTip != null || gridEditor.editing()) return editorTip;
        if (hoverZone < 0) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("cinematic." + key() + ".zone." + hoverZone).withStyle(ChatFormatting.GOLD));
        if (!carried.isEmpty()) {
            if (!accepts(hoverZone, carried)) {
                lines.add(Component.translatable(hoverZone == FurnaceZones.FUEL ? "cinematic.furnace.not_fuel" : "cinematic.furnace.no_room")
                        .withStyle(ChatFormatting.RED));
            } else if (hoverZone == FurnaceZones.INPUT && !cookable(carried)) {
                lines.add(Component.translatable("cinematic." + key() + ".not_cookable").withStyle(ChatFormatting.YELLOW));
            } else {
                return null;
            }
            return lines;
        }
        ItemStack inside = ClientFurnaces.item(pos, hoverZone);
        if (inside.isEmpty()) {
            lines.add(Component.translatable("cinematic." + key() + ".empty." + hoverZone).withStyle(ChatFormatting.GRAY));
        } else {
            lines.add(inside.getHoverName().copy().append(" ×" + inside.getCount()));
        }
        if (hoverZone == FurnaceZones.INPUT && cook() > 0) {
            lines.add(Component.translatable("cinematic." + key() + ".progress", Math.round(cook() * 100)).withStyle(ChatFormatting.GRAY));
        } else if (hoverZone == FurnaceZones.FUEL && litTime > 0) {
            lines.add(Component.translatable("cinematic.furnace.burning", (litTime + 19) / 20).withStyle(ChatFormatting.GRAY));
        } else if (hoverZone == FurnaceZones.RESULT && !inside.isEmpty()) {
            lines.add(Component.translatable("cinematic.furnace.take").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    @Override
    public Component hint() {
        if (gridEditor.editing()) return TableGridEditor.text("hint");
        return Component.translatable("cinematic.hint." + key());
    }

    @Override public void renderScreen(GuiGraphicsExtractor graphics, Font font, float alpha) { gridEditor.render(graphics, font, alpha); }
}
