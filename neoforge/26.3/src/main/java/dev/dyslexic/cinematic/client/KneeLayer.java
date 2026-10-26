package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/** The raised campfire leg, split at its knee. Armor uses Minecraft's equipment layer renderer. */
public final class KneeLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public static final float THIGH = -2.2f;
    public static final float KNEE = 1.95f;
    public static boolean bends = true;

    private final EquipmentLayerRenderer equipment;
    private final ModelPart skinThigh = part(0, 16, 0f, 64f);
    private final ModelPart skinShin = part(0, 22, 0f, 64f);
    private final ModelPart pantsThigh = part(0, 32, 0.25f, 64f);
    private final ModelPart pantsShin = part(0, 38, 0.25f, 64f);
    private final Model<AvatarRenderState> leggings = armorModel(0.5f);
    private final Model<AvatarRenderState> boots = armorModel(1f);

    public KneeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent, EquipmentLayerRenderer equipment) {
        super(parent);
        this.equipment = equipment;
    }

    public static boolean sitting(int id) {
        return bends && ClientPayloads.atStation(id) == StationKind.CAMPFIRE;
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector nodes, int light, AvatarRenderState player,
            float netHeadYaw, float headPitch) {
        if (!sitting(player.id)) return;
        pose.pushPose();
        getParentModel().rightLeg.translateAndRotate(pose);
        if (!player.isInvisible) {
            submitParts(nodes, pose, light, skinThigh, skinShin, RenderTypes.entitySolid(player.skin.body().texturePath()));
            if (player.showRightPants) {
                submitParts(nodes, pose, light, pantsThigh, pantsShin,
                        RenderTypes.entityTranslucent(player.skin.body().texturePath()));
            }
        }
        submitArmor(nodes, pose, light, player, player.legsEquipment, EquipmentSlot.LEGS, leggings,
                EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS);
        submitArmor(nodes, pose, light, player, player.feetEquipment, EquipmentSlot.FEET, boots,
                EquipmentClientInfo.LayerType.HUMANOID);
        pose.popPose();
    }

    private static void submitParts(SubmitNodeCollector nodes, PoseStack pose, int light, ModelPart thigh,
            ModelPart shin, net.minecraft.client.renderer.rendertype.RenderType type) {
        pose.pushPose();
        pose.scale(1f, 8f / 6f, 1f);
        nodes.submitModelPart(thigh, pose, type, light, OverlayTexture.NO_OVERLAY, null);
        pose.popPose();
        pose.pushPose();
        pose.translate(0, 6f / 16f, 0);
        pose.mulPose(new org.joml.Matrix4f().rotation(com.mojang.math.Axis.XP.rotation(KNEE)));
        nodes.submitModelPart(shin, pose, type, light, OverlayTexture.NO_OVERLAY, null);
        pose.popPose();
    }

    private void submitArmor(SubmitNodeCollector nodes, PoseStack pose, int light, AvatarRenderState player,
            ItemStack stack, EquipmentSlot slot, Model<AvatarRenderState> model,
            EquipmentClientInfo.LayerType layer) {
        Equippable gear = stack.get(DataComponents.EQUIPPABLE);
        if (gear == null || gear.slot() != slot || gear.assetId().isEmpty()) return;
        equipment.renderLayers(layer, gear.assetId().get(), model, player, stack, pose, nodes,
                light, player.outlineColor);
    }

    private static Model<AvatarRenderState> armorModel(float grow) {
        ModelPart upper = part(0, 16, grow, 32f);
        ModelPart lower = part(0, 22, grow - 0.02f, 32f);
        upper.yScale = 8f / 6f;
        lower.y = 6f;
        lower.xRot = KNEE;
        ModelPart root = new ModelPart(List.of(), Map.of("thigh", upper, "shin", lower));
        return new Model<>(root, RenderTypes::entitySolid) {};
    }

    private static ModelPart part(int u, int v, float grow, float textureHeight) {
        ModelPart.Cube cube = new ModelPart.Cube(u, v, -2f, 0f, -2f, 4f, 6f, 4f,
                grow, grow, grow, false, 64f, textureHeight, EnumSet.allOf(Direction.class));
        return new ModelPart(List.of(cube), Map.of());
    }
}
