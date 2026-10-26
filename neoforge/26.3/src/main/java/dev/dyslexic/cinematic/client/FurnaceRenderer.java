package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dyslexic.cinematic.furnace.FurnaceLayout;
import dev.dyslexic.cinematic.furnace.FurnaceZones;
import dev.dyslexic.cinematic.furnace.FurnaceZones.Face;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What sits inside every nearby furnace, drawn half-way into its openings: the block hides the inner half, so
 * things seem to rest inside. Raw items on the left of the cooking opening, finished ones on the right, fuel
 * behind the fire; a lit furnace glows from within and its contents are lit by the fire.
 */
public final class FurnaceRenderer {
    private FurnaceRenderer() {}

    private static final double MAX_DISTANCE_SQR = 48 * 48;
    private static final int[] FUEL_GLOW = {FurnaceZones.FUEL};
    private static final int[] INPUT_GLOW = {FurnaceZones.INPUT};
    private static final int[] COOKING_GLOW = {FurnaceZones.INPUT, FurnaceZones.RESULT};

    public static void renderAll(PoseStack pose, SceneBuffers buffers, Vec3 cam, Frustum frustum, ClientLevel level) {
        FurnaceStation mine = Scene.station(FurnaceStation.class);
        for (Map.Entry<BlockPos, ClientFurnaces.Entry> e : ClientFurnaces.FURNACES.entrySet()) {
            BlockPos pos = e.getKey();
            if (pos.distToCenterSqr(cam) > MAX_DISTANCE_SQR) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            StationKind kind = StationKind.of(state);
            FurnaceLayout layout = kind == null ? null : FurnaceLayout.of(kind);
            if (layout == null) {
                continue;
            }
            FurnaceAppearance appearance = FurnaceLayouts.current(kind);
            if (!frustum.isVisible(new AABB(pos).inflate(appearance.equals(FurnaceAppearance.VANILLA) ? 0.2 : 0.6))) continue;
            Direction facing = state.getValue(AbstractFurnaceBlock.FACING);
            Vec3 toCamera = cam.subtract(FurnaceZones.point(pos, facing, 0.5, 0.5, 0));
            boolean frontSeen = toCamera.dot(Vec3.atLowerCornerOf(facing.getUnitVec3i())) > 0;
            boolean topSeen = cam.y > pos.getY() + 1;
            boolean lit = state.getValue(AbstractFurnaceBlock.LIT);
            int light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, pos.relative(facing));
            int inside = lit ? LightCoordsUtil.pack(15, LightCoordsUtil.sky(light)) : light;
            ClientFurnaces.Entry entry = e.getValue();
            for (FurnaceLayout.Bed bed : layout.beds) {
                if (bed.face() == Face.FRONT ? frontSeen : topSeen) {
                    drawBed(pose, buffers, cam, pos, facing, entry, layout, appearance, bed, bed.slot() == FurnaceZones.RESULT ? light : inside);
                }
            }
            if (lit) {
                double heat = mine != null && mine.pos.equals(pos) ? mine.cook() : 0.5;
                drawGlow(pose, buffers, cam, pos, facing, layout, appearance, heat, frontSeen, topSeen);
            }
        }
        if (mine != null && Scene.clientOnly()) {
            ClientFurnaces.Entry entry = ClientFurnaces.get(mine.pos);
            if (entry != null) {
                BlockState state = level.getBlockState(mine.pos);
                Direction facing = state.getValue(AbstractFurnaceBlock.FACING);
                FurnaceLayout layout = mine.layout;
                FurnaceAppearance appearance = FurnaceLayouts.current(mine.kind());
                Vec3 toCamera = cam.subtract(FurnaceZones.point(mine.pos, facing, 0.5, 0.5, 0));
                boolean frontSeen = toCamera.dot(Vec3.atLowerCornerOf(facing.getUnitVec3i())) > 0;
                boolean topSeen = cam.y > mine.pos.getY() + 1;
                int light = LightCoordsUtil.getLightCoords(level, mine.pos.relative(facing));
                int inside = state.getValue(AbstractFurnaceBlock.LIT) ? LightCoordsUtil.pack(15, LightCoordsUtil.sky(light)) : light;
                for (FurnaceLayout.Bed bed : layout.beds)
                    if (bed.face() == Face.FRONT ? frontSeen : topSeen)
                        drawBed(pose, buffers, cam, mine.pos, facing, entry, layout, appearance, bed,
                                bed.slot() == FurnaceZones.RESULT ? light : inside);
                if (state.getValue(AbstractFurnaceBlock.LIT))
                    drawGlow(pose, buffers, cam, mine.pos, facing, layout, appearance, mine.cook(), frontSeen, topSeen);
            }
        }
    }

    private static void drawBed(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction facing,
            ClientFurnaces.Entry entry, FurnaceLayout layout, FurnaceAppearance appearance, FurnaceLayout.Bed bed, int light) {
        ItemStack stack = entry.items[bed.slot()];
        if (stack.isEmpty()) {
            return;
        }
        int copies = Math.min(bed.maxCopies(), stack.getCount() >= 32 ? 4 : stack.getCount() >= 9 ? 3 : stack.getCount() >= 2 ? 2 : 1);
        double landed = Scene.clock() - entry.landedAt[bed.slot()];
        double slide = landed >= 0 && landed < 0.3 ? 1 - Anim.outCubic(landed / 0.3) : 0;
        long seed = pos.asLong() * 131L + bed.slot() * 977L;
        for (int c = 0; c < copies; c++) {
            double u = copies == 1 ? (bed.u0() + bed.u1()) / 2 : bed.u0() + (bed.u1() - bed.u0()) * (c + 0.5) / copies;
            double wobble = ((seed >> (c * 5)) & 7) / 7.0 - 0.5;
            Vec3 base;
            if (bed.face() == Face.FRONT) {
                // a fresh item slides in from the front
                double out = -bed.depth() + wobble * 0.02 + 0.06 * slide;
                base = appearance.point(layout, pos, facing, bed.slot(), u, bed.floor() + 0.002 + c * 0.0015, out, true);
            } else {
                // charged from the top: it settles half-sunk into the furnace
                double v = bed.floor() + wobble * 0.08;
                base = appearance.point(layout, pos, facing, bed.slot(), u, v, -bed.depth() + 0.06 * slide, true);
            }
            ItemPlacer.renderOne(pose, buffers, cam, stack, base, facing, seed + c * 31L,
                    0.95f * (float) appearance.region(bed.slot()).itemScale(), bed.tilt(), light);
        }
    }

    /** Warm light inside the openings, strongest at their floors and flickering with the fire; the cooking one heats up. */
    private static void drawGlow(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction facing, FurnaceLayout layout, FurnaceAppearance appearance,
            double heat, boolean frontSeen, boolean topSeen) {
        double t = Scene.clock() + (pos.asLong() & 255) * 0.37;
        double flicker = 0.82 + 0.1 * Math.sin(t * 11.3) + 0.08 * Math.sin(t * 23.1 + 1.3);
        VertexConsumer glow = buffers.getBuffer(Fx.flatGlow());
        for (FurnaceLayout.Glow g : layout.glows) {
            if (g.face() == Face.FRONT ? !frontSeen : !topSeen) {
                continue;
            }
            int[] regions = g.fire() ? FUEL_GLOW
                    : g.face() == Face.TOP || appearance.equals(FurnaceAppearance.VANILLA) ? INPUT_GLOW
                    : COOKING_GLOW;
            for (int region : regions) {
                double[] rect = appearance.glowRect(layout, g, region);
                if (rect == null) continue;
                double out = 0.003 + appearance.region(region).out() / 16;
                if (g.fire()) {
                    double strength = 120 * flicker;
                    FurnaceStation.glowQuad(pose, glow, cam, pos, facing, g.face(), rect, out, rgb(strength, 0.42, 0.1),
                            rgb(strength * 0.25, 0.35, 0.08));
                } else if (g.face() == Face.TOP) {
                    // the molten heart of a blast furnace glowing up through its top
                    double strength = (130 + 90 * heat) * flicker;
                    Vec3 center = FurnaceZones.top(pos, facing, (rect[0] + rect[1]) / 2, (rect[2] + rect[3]) / 2, out);
                    radial(pose, glow, cam, center, (rect[1] - rect[0]) * 0.72, rgb(strength, 0.45, 0.12));
                } else {
                    double strength = (35 + 70 * heat) * flicker;
                    FurnaceStation.glowQuad(pose, glow, cam, pos, facing, g.face(), rect, out, rgb(strength, 0.5, 0.16),
                            rgb(strength * 0.08, 0.5, 0.16));
                }
            }
        }
    }

    /** A round emissive glow lying flat: bright in the middle, fading out at the rim. */
    private static void radial(PoseStack pose, VertexConsumer buffer, Vec3 cam, Vec3 center, double radius, int[] rgb) {
        org.joml.Matrix4f m = pose.last().pose();
        int segments = 24;
        float cx = (float) (center.x - cam.x), cy = (float) (center.y - cam.y), cz = (float) (center.z - cam.z);
        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2 / segments;
            double a1 = (i + 1) * Math.PI * 2 / segments;
            float x0 = cx + (float) (Math.cos(a0) * radius), z0 = cz + (float) (Math.sin(a0) * radius);
            float x1 = cx + (float) (Math.cos(a1) * radius), z1 = cz + (float) (Math.sin(a1) * radius);
            // Black rim vertices contribute no light; both windings remain visible from above or below.
            Fx.glowVertex(buffer, m, cx, cy, cz, rgb[0], rgb[1], rgb[2], UP);
            Fx.glowVertex(buffer, m, x0, cy, z0, 0, 0, 0, UP);
            Fx.glowVertex(buffer, m, x1, cy, z1, 0, 0, 0, UP);
            Fx.glowVertex(buffer, m, cx, cy, cz, rgb[0], rgb[1], rgb[2], UP);
            Fx.glowVertex(buffer, m, cx, cy, cz, rgb[0], rgb[1], rgb[2], UP);
            Fx.glowVertex(buffer, m, x1, cy, z1, 0, 0, 0, UP);
            Fx.glowVertex(buffer, m, x0, cy, z0, 0, 0, 0, UP);
            Fx.glowVertex(buffer, m, cx, cy, cz, rgb[0], rgb[1], rgb[2], UP);
        }
    }

    private static final Vec3 UP = new Vec3(0, 1, 0);

    private static int[] rgb(double red, double green, double blue) {
        return new int[]{(int) red, (int) (red * green), (int) (red * blue)};
    }
}
