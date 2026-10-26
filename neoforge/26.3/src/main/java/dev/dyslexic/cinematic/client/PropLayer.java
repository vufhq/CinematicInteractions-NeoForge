package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.station.StationKind;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;


/**
 * What a player at a station holds, as others see it: the smith's hammer in the right hand while they forge (the arm
 * swings it, {@code HumanoidModelMixin}, on the scene's own timeline). Whatever they really hold in that hand is not drawn
 * meanwhile ({@code ItemInHandLayerMixin}).
 */
public final class PropLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public PropLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    /** How much of the hammer this player has in hand: 0 none, 1 all of it (it comes off the belt and goes back). */
    public static double hammer(Entity entity) {
        return hammer(entity.getId());
    }

    public static double hammer(int id) {
        return ClientPayloads.atStation(id) == StationKind.ANVIL ? AnvilStation.hammerShown(ClientMenus.sinceMove(id)) : 0;
    }

    @Override
    public void submit(PoseStack pose, SubmitNodeCollector features, int light, AvatarRenderState player, float netHeadYaw, float headPitch) {
        double shown = hammer(player.id);
        if (shown <= 0.001) {
            return;
        }
        pose.pushPose();
        getParentModel().translateToHand(player, HumanoidArm.RIGHT, pose);
        HammerModel.renderInArm(pose, features, shown, light);
        pose.popPose();
    }

}
