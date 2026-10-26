package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The bottles in a brewing stand are the real potions: the stand's own bottle pictures (one generic red potion for
 * every bottle) are taken out of its model, and each potion that stands in it is drawn on its foot, turned to whoever
 * looks at it, in its own colour and shape (splash and lingering bottles too). The contents come from the stand's
 * update packet ({@code BrewingWatch}); in the player's own scene, straight from the menu.
 * <p>
 * Since 1.21.9 a block entity renderer only hands its models to the game's features, while the bottles are items drawn
 * straight into a buffer (ItemModels): the stands the game finds in sight are noted here and drawn with the scene, right
 * after the features (SceneRenderer).
 */
public final class BrewingBottles implements BlockEntityRenderer<BrewingStandBlockEntity, BlockEntityRenderState> {
    /** Edge of the drawn item: the bottle in it is a little smaller than the stand's own bottle picture. */
    static final float SIZE = 0.42f;

    /** A stand in sight this frame and the light at it. */
    private record Shown(BlockPos pos, int light) {}

    private static final List<Shown> SHOWN = new ArrayList<>();

    public BrewingBottles(BlockEntityRendererProvider.Context context) {}

    @Override
    public BlockEntityRenderState createRenderState() {
        return new BlockEntityRenderState();
    }

    @Override
    public void submit(BlockEntityRenderState state, PoseStack pose, SubmitNodeCollector features, CameraRenderState camera) {
    }

    @Override
    public void extractRenderState(BrewingStandBlockEntity stand, BlockEntityRenderState state, float partial, Vec3 camera,
            net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay overlay) {
        BlockEntityRenderer.super.extractRenderState(stand, state, partial, camera, overlay);
        SHOWN.add(new Shown(state.blockPos, state.lightCoords));
    }

    /** A new frame of the world: the stands in sight are gathered again. */
    static void beginFrame() {
        SHOWN.clear();
    }

    /** The bottles of the stands in sight, the pose at the camera. */
    static void renderAll(PoseStack pose, SceneBuffers buffers, Vec3 cam, ClientLevel level) {
        if (!ClientOnly.serverHasMod()) return;
        for (Shown shown : SHOWN) {
            if (level.getBlockEntity(shown.pos()) instanceof BrewingStandBlockEntity stand) {
                pose.pushPose();
                pose.translate(shown.pos().getX() - cam.x, shown.pos().getY() - cam.y, shown.pos().getZ() - cam.z);
                render(stand, pose, buffers, shown.light());
                pose.popPose();
            }
        }
        SHOWN.clear();
    }

    private static void render(BrewingStandBlockEntity stand, PoseStack pose, SceneBuffers buffers, int light) {
        BlockPos pos = stand.getBlockPos();
        Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
        // the pose already stands at the block's corner
        Vec3 origin = Vec3.atLowerCornerOf(pos);
        BrewingStation scene = Scene.station(BrewingStation.class);
        boolean mine = scene != null && scene.pos.equals(pos) && scene.menu() != null;
        for (int i = 0; i < BrewingStation.BOTTLES; i++) {
            ItemStack stack = mine ? scene.slot(i) : ClientBrewing.item(stand, i);
            if (stack.isEmpty()) {
                continue;
            }
            float grow = mine && scene.hoverSlot == i ? 1.08f : 1f;
            draw(pose, buffers, origin, cam, stack, BrewingStation.slotPoint(pos, i), grow, BrewingStation.itemScale(i), BrewingStation.itemRotation(i), light);
        }
        if (!mine) {
            // the ingredient in the bowl and the blaze powder at the foot stay where they were put (the scene at this
            // stand draws its own, melting as it brews); the powder lies on the side of the foot towards the viewer
            ItemStack ingredient = ClientBrewing.item(stand, BrewingStation.INGREDIENT);
            ItemStack fuel = ClientBrewing.item(stand, BrewingStation.FUEL);
            if (!ingredient.isEmpty() || !fuel.isEmpty()) {
                pose.pushPose();
                pose.translate(-origin.x + cam.x, -origin.y + cam.y, -origin.z + cam.z);
                Direction towards = Direction.getApproximateNearest(cam.x - (pos.getX() + 0.5), 0, cam.z - (pos.getZ() + 0.5));
                BrewingStation.drawLoose(pose, buffers, cam, pos, towards, ingredient, fuel, light);
                pose.popPose();
            }
        }
    }

    /** One bottle standing on a foot ({@code at} is the middle of the bottle), turned to the camera. */
    static void draw(PoseStack pose, SceneBuffers buffers, Vec3 origin, Vec3 cam, ItemStack stack, Vec3 at, float grow, float scale, float rotation, int light) {
        Vec3 toEye = new Vec3(cam.x - at.x, 0, cam.z - at.z);
        float yaw = (float) (180 - Math.toDegrees(Math.atan2(-toEye.x, toEye.z)));
        // standing on its foot: grown from the bottom, not the middle
        Vec3 center = at.add(0, 7 / 16.0 * SIZE * scale * (grow - 1), 0);
        ItemPlacer.drawOriented(pose, buffers, origin, stack, center, ItemPlacer.lying(yaw + rotation, 90), SIZE * grow * scale, light);
    }
}
