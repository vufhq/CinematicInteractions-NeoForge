package dev.dyslexic.cinematic.client;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.math.MatrixUtil;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Records vanilla item geometry through the scene's alpha consumer, preserving tint and foil. */
final class ItemModels {
    private ItemModels() {}
    static void drawStatic(net.minecraft.world.item.ItemStack stack, int light, int overlay, PoseStack pose, SceneBuffers buffers) {
        draw(ItemPlacer.model(stack, 0), pose, buffers, light, overlay);
    }
    static void draw(ItemStackRenderState state, PoseStack pose, SceneBuffers buffers, int light, int overlay) {
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(light);
        instance.setOverlayCoords(overlay);
        for (int i = 0; i < state.activeLayerCount; i++) {
            var layer = state.layers[i];
            pose.pushPose();
            layer.itemTransform.apply(state.displayContext.leftHand(), pose.last());
            pose.last().mulPose(layer.localTransform);
            if (layer.specialRenderer != null) {
                drawSpecial(layer, pose, buffers, light, overlay);
            } else {
                PoseStack.Pose foilPose = pose.last().copy();
                if (state.displayContext == net.minecraft.world.item.ItemDisplayContext.GUI) {
                    MatrixUtil.mulComponentWise(foilPose.pose(), 0.5f);
                } else if (state.displayContext.firstPerson()) {
                    MatrixUtil.mulComponentWise(foilPose.pose(), 0.75f);
                }
                var tints = layer.tintLayers();
                for (BakedQuad quad : layer.quads.all()) {
                    var material = quad.materialInfo();
                    var type = switch (layer.foilType) {
                        case NONE -> material.itemRenderType();
                        case STANDARD -> material.itemGlintRenderType();
                        case SPECIAL -> material.itemGlintSpecialRenderType();
                    };
                    int tint = material.isTinted() && material.tintIndex() >= 0 && material.tintIndex() < tints.size()
                            ? tints.getInt(material.tintIndex()) : -1;
                    instance.setColor(tint);
                    if (layer.foilType == ItemStackRenderState.FoilType.SPECIAL) {
                        buffers.getBuffer(type).putBakedQuadWithGlint(pose.last(), quad, instance, foilPose);
                    } else {
                        buffers.getBuffer(type).putBakedQuad(pose.last(), quad, instance);
                    }
                }
            }
            pose.popPose();
        }
    }

    private static void drawSpecial(ItemStackRenderState.LayerRenderState layer, PoseStack pose, SceneBuffers buffers, int light, int overlay) {
        var storage = new net.minecraft.client.renderer.SubmitNodeStorage();
        layer.specialRenderer.submit(layer.argumentForSpecialRendering, pose, storage, light, overlay,
                layer.foilType != ItemStackRenderState.FoilType.NONE, 0);
        var nodes = new java.util.ArrayList<net.minecraft.client.renderer.feature.submit.SubmitNode>();
        storage.drainPhases(phase -> phase.sortInto((node, ordered) -> nodes.add(node)));
        if (nodes.stream().anyMatch(node -> !(node instanceof net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?>)
                && !(node instanceof net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit))) {
            // Keep extensions that submit another feature type in the game's own renderer.
            layer.specialRenderer.submit(layer.argumentForSpecialRendering, pose, SceneRenderer.collector(), light, overlay,
                    layer.foilType != ItemStackRenderState.FoilType.NONE, 0);
            return;
        }
        for (var node : nodes) {
            if (node instanceof net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> model) {
                drawModel(model, buffers);
            } else if (node instanceof net.minecraft.client.renderer.feature.CustomFeatureRenderer.Submit custom) {
                custom.customGeometryRenderer().render(custom.pose(), buffers.getBuffer(custom.renderType()));
            }
        }
    }

    private static <S> void drawModel(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<S> submit, SceneBuffers buffers) {
        PoseStack pose = new PoseStack();
        pose.last().set(submit.pose());
        var consumer = buffers.getBuffer(submit.renderType());
        if (submit.sheetedDecalPose() != null) {
            consumer = new com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator(consumer, submit.sheetedDecalPose(), 1);
        } else if (submit.uvMapping() != null) {
            consumer = submit.uvMapping().wrap(consumer);
        }
        submit.model().setupAnim(submit.state());
        submit.model().renderToBuffer(pose, consumer, submit.lightCoords(), submit.overlayCoords(), submit.tintedColor());
    }
}
