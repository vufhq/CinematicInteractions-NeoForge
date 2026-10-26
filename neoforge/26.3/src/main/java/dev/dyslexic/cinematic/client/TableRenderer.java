package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.table.TableGrid;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Items lying on every nearby crafting table; the one being crafted at gathers its ingredients. */
public final class TableRenderer {
    private TableRenderer() {}

    private static final double MAX_DISTANCE_SQR = 64 * 64;

    public static void renderAll(PoseStack pose, SceneBuffers buffers, Vec3 cam, Frustum frustum, ClientLevel level) {
        TableStation mine = Scene.station(TableStation.class);
        if (mine != null) {
            mine.updateOcclusion(cam);
        }
        for (Map.Entry<BlockPos, ClientTables.Entry> e : ClientTables.TABLES.entrySet()) {
            BlockPos pos = e.getKey();
            if (pos.distToCenterSqr(cam) > MAX_DISTANCE_SQR || !frustum.isVisible(new AABB(pos).expandTowards(0, 0.4, 0))
                    || !TableGrid.isTable(level.getBlockState(pos))) {
                continue;
            }
            TableStation station = mine != null && mine.pos.equals(pos) ? mine : null;
            draw(pose, buffers, cam, pos, e.getValue(), station, net.minecraft.util.LightCoordsUtil.getLightCoords(level, pos.above()), false);
        }
        if (mine != null && Scene.clientOnly() && ClientTables.get(mine.pos) != null
                && frustum.isVisible(new AABB(mine.pos).expandTowards(0, 0.4, 0))) {
            draw(pose, buffers, cam, mine.pos, ClientTables.get(mine.pos), mine,
                    net.minecraft.util.LightCoordsUtil.getLightCoords(level, mine.pos.above()), false);
        }
        if (mine != null && mine.craftItems != null && ClientTables.get(mine.pos) == null && gathering(mine)) {
            // everything was used up: still show the ingredients gathering
            draw(pose, buffers, cam, mine.pos, null, mine, net.minecraft.util.LightCoordsUtil.getLightCoords(level, mine.pos.above()), false);
        }
        if (mine != null) {
            mine.drawHighlight(pose, buffers, cam);
            if (mine.hasFadedItems()) {
                // Finish the destination and all opaque ingredients before blending the blockers over them.
                if (buffers instanceof SceneBuffers.BufferSource source) {
                    source.endBatch();
                }
                draw(pose, buffers, cam, mine.pos, ClientTables.get(mine.pos), mine,
                        net.minecraft.util.LightCoordsUtil.getLightCoords(level, mine.pos.above()), true);
                if (buffers instanceof SceneBuffers.BufferSource source) {
                    source.endBatch();
                }
            }
        }
    }

    private static boolean gathering(TableStation station) {
        double craft = station.craftProgress();
        return craft >= 0 && craft < TableStation.CRAFT_MID / TableStation.CRAFT_LENGTH && station.craftItems != null;
    }

    private static void draw(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, ClientTables.Entry entry,
            TableStation station, int light, boolean translucent) {
        double now = Scene.clock();
        double craft = station == null ? -1 : station.craftProgress();
        double midpoint = TableStation.CRAFT_MID / TableStation.CRAFT_LENGTH;
        boolean gathering = station != null && gathering(station);
        double squeeze = craft < 0 ? 0 : craft < midpoint ? Anim.inOutSine(craft / midpoint) : 1 - Anim.outCubic((craft - midpoint) / (1 - midpoint));
        TableLayout layout = TableLayouts.current();
        Vec3 center = layout.center(pos);

        for (int i = 0; i < TableGrid.CELLS; i++) {
            ItemStack stack = gathering ? station.craftItems[i] : entry == null ? ItemStack.EMPTY : entry.items[i];
            if (stack.isEmpty()) {
                continue;
            }
            float opacity = station == null ? 1f : station.itemOpacity(i);
            if ((opacity < 0.999f) != translucent) {
                continue;
            }
            byte facing = gathering ? station.craftFacing[i] : entry.facing[i];
            Vec3 base = layout.cell(pos, i);
            float scale = 1f;
            if (squeeze > 0) {
                base = Anim.lerp(base, center, squeeze * 0.55).add(0, 0.05 * squeeze, 0);
                scale *= (float) (1 - 0.35 * squeeze);
            }
            if (!gathering && entry != null) {
                double landed = now - entry.landedAt[i];
                if (landed >= 0 && landed < 0.25) {
                    double k = landed / 0.25;
                    base = base.add(0, 0.035 * (1 - Anim.outCubic(k)), 0);
                    scale *= (float) (0.92 + 0.08 * Anim.outBack(k, 2.2));
                }
            }
            long seed = pos.asLong() * 31L + i * 7919L;
            layout.renderItem(pose, buffers, cam, stack, base, Direction.from2DDataValue(facing), seed, scale, 0f, light, opacity, false);
        }
    }
}
