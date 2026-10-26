package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Stations worked through a vanilla menu (anvil, grindstone, smithing table, stonecutter, loom, cartography table,
 * brewing stand, crafter, beacon) in use: the local scene draws itself in every phase; for blocks other players work at,
 * onlookers see what lies on them, the sparks of each big moment and, at an anvil, a grindstone or a stonecutter, the
 * whole move as the player at it sees it (the hammer itself is in the player's hand, {@link PropLayer}).
 */
public final class MenuRenderer {
    private MenuRenderer() {}

    private static final double MAX_DISTANCE_SQR = 48 * 48;

    public static void renderAll(PoseStack pose, SceneBuffers buffers, Camera camera, Frustum frustum, ClientLevel level) {
        Vec3 cam = camera.position();
        MenuStation<?> mine = Scene.active() && Scene.station instanceof MenuStation<?> station ? station : null;
        if (mine != null) {
            mine.frame();
            mine.renderScene(pose, buffers, camera, net.minecraft.util.LightCoordsUtil.getLightCoords(level, mine.lightPos()));
        }
        double clock = Scene.clock();
        double dt = Scene.frameDt;
        Iterator<Map.Entry<BlockPos, ClientMenus.Entry>> it = ClientMenus.BLOCKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, ClientMenus.Entry> e = it.next();
            BlockPos pos = e.getKey();
            ClientMenus.Entry entry = e.getValue();
            BlockState state = level.getBlockState(pos);
            if (entry.kind == null || !entry.kind.matches(state)) {
                it.remove();
                continue;
            }
            if ((mine != null && mine.pos.equals(pos)) || pos.distToCenterSqr(cam) > MAX_DISTANCE_SQR) {
                continue;
            }
            double age = clock - entry.fxAt;
            double move = clock - entry.moveAt;
            // the effects run in or out of view (sparks may fly into it)
            onlookerFx(pose, buffers, camera, pos, state, entry, age, move, dt);
            if (!frustum.isVisible(new AABB(pos).inflate(1.5))) {
                continue;
            }
            int light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, entry.kind == StationKind.GRINDSTONE ? pos : pos.above());
            switch (entry.kind) {
                case ANVIL -> {
                    if (move < AnvilStation.FORGE_LENGTH) {
                        AnvilStation.drawOnlooker(pose, buffers, camera, pos, entry, move, light);
                    } else {
                        AnvilStation.drawContents(pose, buffers, cam, pos, entry.side, entry.items, light);
                    }
                }
                case GRINDSTONE -> {
                    if (entry.spin > 0.01) {
                        GrindstoneStation.drawRim(pose, buffers, cam, GrindstoneStation.wheelCenter(pos, state), entry.side, entry.run, light);
                    }
                    if (move < GrindstoneStation.GRIND_LENGTH) {
                        GrindstoneStation.drawOnlooker(pose, buffers, camera, pos, state, entry, move, light);
                    } else {
                        GrindstoneStation.drawContents(pose, buffers, cam, pos, state, entry.side, entry.items, light);
                    }
                }
                case SMITHING -> SmithingStation.drawContents(pose, buffers, cam, pos, entry.side, entry.items, light);
                case STONECUTTER -> {
                    if (move < (entry.moveToBag ? StonecutterStation.CUT_ALL_LENGTH : StonecutterStation.CUT_LENGTH)) {
                        StonecutterStation.drawOnlooker(pose, buffers, camera, pos, entry, move, light);
                    } else {
                        StonecutterStation.drawContents(pose, buffers, cam, pos, entry.side, entry.items, light);
                    }
                }
                case LOOM -> LoomStation.drawContents(pose, buffers, cam, pos, entry.side, entry.items, light);
                case CARTOGRAPHY -> CartographyStation.drawContents(pose, buffers, cam, pos, entry.side, entry.items, light);
                case CRAFTER -> CrafterStation.drawContents(pose, buffers, cam, pos, state, entry.side, entry.items, light);
                case BEACON -> BeaconStation.drawContents(pose, buffers, camera, pos, entry.side, entry.items);
                default -> {}
            }
        }
    }

    /** The effects of someone else's big moment or move at a block: {@code age} since the moment, {@code move} since the move began. */
    private static void onlookerFx(PoseStack pose, SceneBuffers buffers, Camera camera, BlockPos pos, BlockState state, ClientMenus.Entry entry,
            double age, double move, double dt) {
        switch (entry.kind) {
            case ANVIL -> AnvilStation.onlookerFx(pos, entry, move, dt);
            case GRINDSTONE -> GrindstoneStation.onlookerFx(pos, state, entry, move, dt);
            case SMITHING -> SmithingStation.onlookerFx(pos, entry.side, age, dt);
            case STONECUTTER -> StonecutterStation.onlookerFx(pos, entry, move, dt);
            case BEACON -> BeaconStation.onlookerFx(pose, buffers, camera, pos, age, dt);
            default -> {}
        }
    }
}
