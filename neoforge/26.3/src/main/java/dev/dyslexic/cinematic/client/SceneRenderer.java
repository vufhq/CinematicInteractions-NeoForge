package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

/** World drawing: what lies on and in the stations for everyone, the sparks, plus the scene's extras and the arms. */
public final class SceneRenderer {
    private SceneRenderer() {}

    /** A thin warm outline round the neighbouring station under the cursor (a right click moves over to it). */
    private static void outlineNeighbor(PoseStack pose, SceneBuffers buffers, Vec3 cam, ClientLevel level) {
        if (Scene.neighbor == null) {
            return;
        }
        net.minecraft.world.phys.shapes.VoxelShape shape = level.getBlockState(Scene.neighbor).getShape(level, Scene.neighbor);
        if (shape.isEmpty()) {
            return;
        }
        net.minecraft.world.phys.AABB box = shape.bounds().move(Scene.neighbor).inflate(0.004).move(-cam.x, -cam.y, -cam.z);
        collector().submitShapeOutline(pose, net.minecraft.world.phys.shapes.Shapes.create(box),
                net.minecraft.client.renderer.rendertype.RenderTypes.lines(), 0xBFFFE099, 2.5f, false);
    }

    private static Camera levelCamera;
    private static SubmitNodeStorage collecting;
    private static Frustum frustum = new Frustum(new Matrix4f(), new Matrix4f());

    /** The world's frame begins: this frame's camera and matrices. */
    private static void beginLevel(Camera camera, Matrix4f view, Matrix4f projection) {
        Picking.capture(projection, view, camera.position());
        levelCamera = camera;
        frustum = new Frustum(view, projection);
        Vec3 at = camera.position();
        frustum.prepare(at.x, at.y, at.z);
    }

    public static SubmitNodeCollector collector() {
        if (collecting == null) throw new IllegalStateException("Scene geometry outside extraction");
        return collecting;
    }

    public static void beginExtraction() {
        BrewingBottles.beginFrame();
    }

    public static void extract() {
        Minecraft mc = Minecraft.getInstance();
        LevelRenderState state = mc.gameRenderer.gameRenderState().levelRenderState;
        collecting = new SubmitNodeStorage();
        try {
            beginLevel(mc.gameRenderer.mainCamera(), state.cameraRenderState.viewRotationMatrix, state.cameraRenderState.projectionMatrix);
            render();
            ((SceneFrameAccess) state).cinematic$setScene(collecting);
        } finally {
            collecting = null;
        }
    }

    @SuppressWarnings("unchecked")
    public static void append(LevelRenderState state, SubmitNodeStorage world) {
        SubmitNodeStorage scene = ((SceneFrameAccess) state).cinematic$getScene();
        if (scene == null) return;
        scene.getSubmitsPerOrder().forEach((order, source) -> {
            var target = world.order(order);
            for (int i = 0; i < source.allPhases().size(); i++) {
                FeatureRenderPhase<SubmitNode> phase = (FeatureRenderPhase<SubmitNode>) target.allPhases().get(i);
                source.allPhases().get(i).sortInto((node, ordered) -> phase.submit(node));
            }
        });
        ((SceneFrameAccess) state).cinematic$setScene(null);
    }

    private static void render() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || levelCamera == null) {
            return;
        }
        Camera camera = levelCamera;
        Vec3 cam = camera.position();
        PoseStack pose = new PoseStack();
        SceneBuffers.BufferSource buffers = new SceneBuffers.BufferSource(collector());

        Scene.updateCursor();
        Hands.update(cam, camera.yRot());

        BrewingBottles.renderAll(pose, buffers, cam, level);
        TableRenderer.renderAll(pose, buffers, cam, frustum, level);
        FurnaceRenderer.renderAll(pose, buffers, cam, frustum, level);
        EnchantRenderer.renderAll(pose, buffers, camera, frustum, level);
        MenuRenderer.renderAll(pose, buffers, camera, frustum, level);
        ShelfBooks.renderAll(pose, buffers, camera, frustum, level);
        ShelfLayoutBooks.renderAll(pose, buffers, camera, frustum, level);
        Fx.renderSparks(pose, buffers, camera, Scene.frameDt);

        ClientStation station = Scene.station;
        if (Scene.active() && station != null) {
            int light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, station.lightPos());
            if (station.kind() == dev.dyslexic.cinematic.station.StationKind.ADDON
                    && station instanceof MenuStation<?> menuStation) {
                menuStation.renderScene(pose, buffers, camera, light);
            }
            if (Scene.phase == Scene.Phase.ACTIVE) {
                station.renderWorld(pose, buffers, cam, light);
                outlineNeighbor(pose, buffers, cam, level);
            }
            if (!FreeLook.showsBody()) {
                // looking round from behind, the game draws the player's body as others see it instead
                station.renderBody(pose, buffers, cam, light);
                Hands.render(pose, buffers, cam, camera.yRot(), light);
            }
        }
        buffers.endBatch();
        if (Showcase.ENABLED) {
            FrameProbe.sample(camera);
        }
    }
}
