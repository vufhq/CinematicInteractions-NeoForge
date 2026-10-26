package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Enchanting tables in use: the local scene draws itself; for tables other players work at, onlookers see the
 * rune circle glowing faintly, the item hovering, the lapis in its socket and the flash of each enchantment.
 */
public final class EnchantRenderer {
    private EnchantRenderer() {}

    private static final double MAX_DISTANCE_SQR = 48 * 48;

    /** Tables drawn this frame (kept between frames to save making a new set every frame). */
    private static final Set<BlockPos> DRAWN = new HashSet<>();

    public static void renderAll(PoseStack pose, SceneBuffers buffers, Camera camera, Frustum frustum, ClientLevel level) {
        Vec3 cam = camera.position();
        EnchantStation mine = Scene.station(EnchantStation.class);
        if (mine != null) {
            mine.renderScene(pose, buffers, camera, net.minecraft.util.LightCoordsUtil.getLightCoords(level, mine.pos.above()));
        }
        Set<BlockPos> drawn = DRAWN;
        drawn.clear();
        double clock = Scene.clock();
        for (Map.Entry<BlockPos, ClientEnchants.Entry> e : ClientEnchants.TABLES.entrySet()) {
            BlockPos pos = e.getKey();
            if ((mine != null && mine.pos.equals(pos)) || pos.distToCenterSqr(cam) > MAX_DISTANCE_SQR || !level.getBlockState(pos).is(Blocks.ENCHANTING_TABLE)
                    || !frustum.isVisible(visibleBox(pos))) {
                continue;
            }
            drawn.add(pos);
            ClientEnchants.Entry entry = e.getValue();
            double flash = clock - entry.flashAt;
            double boost = flash >= 0 && flash < 1.2 ? 1.2 * (1 - flash / 1.2) : 0;
            drawCircle(pose, buffers, cam, pos, 0.28 + boost, clock, entry.item.isEmpty() || entry.lapis > 0);
            Vec3 at = EnchantStation.itemPosition(pos, entry.side, clock);
            if (!entry.item.isEmpty()) {
                EnchantStation.drawFloating(pose, buffers, cam, entry.item, at, (float) (clock * 40 + SpatialLayouts.current(StationKind.ENCHANTING).rotation(0)), 0.34f * SpatialLayouts.current(StationKind.ENCHANTING).scale(0));
            }
            if (entry.lapis > 0) {
                EnchantStation.drawLapis(pose, buffers, cam, EnchantStation.lapisPosition(pos, entry.side), entry.lapis,
                        net.minecraft.util.LightCoordsUtil.getLightCoords(level, pos.above()), SpatialLayouts.current(StationKind.ENCHANTING).scale(1),
                        (float) -SpatialLayouts.current(StationKind.ENCHANTING).rotation(1));
            }
            if (boost > 0) {
                float b = (float) boost;
                EnchantFx.beam(pose, buffers, cam, new Vec3(at.x, EnchantFx.top(pos).y, at.z), 1.9, 0.6, clock * 0.9, 0.7f * b, 0.5f * b, 1.0f * b);
            }
        }
        // someone stands at a table but has not put anything down yet
        for (ClientPayloads.AtStation at : ClientPayloads.AT_STATION.values()) {
            if (at.kind() == StationKind.ENCHANTING && !drawn.contains(at.pos()) && (mine == null || !mine.pos.equals(at.pos()))
                    && at.pos().distToCenterSqr(cam) < MAX_DISTANCE_SQR && frustum.isVisible(visibleBox(at.pos()))) {
                drawn.add(at.pos());
                drawCircle(pose, buffers, cam, at.pos(), 0.28, clock, true);
            }
        }
    }

    /** Everything drawn at a table: the circle on top, the item over it and the shaft of light of a spell. */
    private static AABB visibleBox(BlockPos pos) {
        return new AABB(pos).inflate(0.5).expandTowards(0, 2.0, 0);
    }

    private static void drawCircle(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, double glow, double clock, boolean ready) {
        float g = (float) glow;
        Vec3 top = EnchantFx.top(pos);
        EnchantFx.CirclePalette palette = ready ? EnchantFx.CirclePalette.VIOLET : EnchantFx.CirclePalette.RED;
        EnchantFx.circle(pose, buffers, cam, top.add(0, EnchantFx.OUTER_LIFT, 0), 0.47, clock * 0.25, palette, 0.85f * g);
        EnchantFx.circle(pose, buffers, cam, top.add(0, EnchantFx.INNER_LIFT, 0), 0.25, -clock * 0.42, palette, 0.55f * g);
    }
}
