package dev.dyslexic.cinematic.mixin;

import dev.dyslexic.cinematic.client.AnvilStation;
import dev.dyslexic.cinematic.client.ClientMenus;
import dev.dyslexic.cinematic.client.ClientPayloads;
import dev.dyslexic.cinematic.client.GrindstoneStation;
import dev.dyslexic.cinematic.client.StonecutterStation;
import dev.dyslexic.cinematic.client.ShelfBooks;
import dev.dyslexic.cinematic.station.StationKind;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Other players see someone at a station in the matching pose: hands resting on a table, a blast furnace or a smithing
 * table, crouched at a furnace reaching in, both hands raised over an enchanting table. A move plays on the same
 * timeline as in the scene: the hammer (in the hand, {@code PropLayer}) swung down onto the anvil blow by blow, the blade
 * drawn to and fro over the grindstone, the stone pushed through the saw and the piece caught.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends LivingEntity> {
    @Shadow
    @Final
    public ModelPart rightArm;

    @Shadow
    @Final
    public ModelPart leftArm;

    @Shadow
    @Final
    public ModelPart rightLeg;

    @Shadow
    @Final
    public ModelPart leftLeg;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void cinematic$stationPose(net.minecraft.client.renderer.entity.state.HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState entity)) {
            return;
        }
        StationKind kind = ClientPayloads.atStation(entity.id);
        if (kind == null) {
            return;
        }
        float t = state.ageInTicks * 0.09f + entity.id;
        double move = ClientMenus.sinceMove(entity.id);
        switch (kind) {
            case ANVIL -> {
                // the left hand holds the work; the right swings the hammer, turned in towards the work
                leftArm.xRot = -1.05f + Mth.sin(t + 2.1f) * 0.04f;
                leftArm.yRot = 0.28f;
                leftArm.zRot = -0.06f;
                boolean forging = move < AnvilStation.FORGE_LENGTH;
                rightArm.xRot = forging ? AnvilStation.armPitch(move) : -0.98f + Mth.sin(t) * 0.05f;
                rightArm.yRot = forging ? -0.35f : -0.2f;
                rightArm.zRot = 0.08f;
            }
            case CAMPFIRE -> {
                // sitting on the ground by the fire, right knee drawn up with the hand on it, leaning on the left hand
                rightLeg.xRot = -2.0f;
                rightLeg.yRot = 0.12f;
                rightLeg.zRot = 0.05f;
                leftLeg.xRot = -1.45f;
                leftLeg.yRot = -0.22f;
                leftLeg.zRot = 0f;
                rightArm.xRot = -1.05f + Mth.sin(t * 0.6f) * 0.03f;
                rightArm.yRot = -0.1f;
                rightArm.zRot = 0.05f;
                leftArm.xRot = 0.35f;
                leftArm.yRot = 0f;
                leftArm.zRot = -0.32f;
            }
            case LOOM -> {
                // throwing the shuttle across the warp from hand to hand
                float throwing = Mth.sin(t * 2.2f) * 0.2f;
                rightArm.xRot = -1.0f + Mth.sin(t * 1.1f) * 0.04f;
                rightArm.yRot = -0.35f + throwing;
                rightArm.zRot = 0.05f;
                leftArm.xRot = -1.0f + Mth.sin(t * 1.1f + 1.3f) * 0.04f;
                leftArm.yRot = 0.35f + throwing;
                leftArm.zRot = -0.05f;
            }
            case GRINDSTONE -> {
                // both hands forward and low, holding a blade to the wheel and drawing it across while it grinds
                float stroke = move < GrindstoneStation.GRIND_LENGTH ? GrindstoneStation.armSway(move) : 0f;
                rightArm.xRot = -1.15f + Mth.sin(t * 1.1f) * 0.03f;
                rightArm.yRot = -0.32f + stroke;
                rightArm.zRot = 0.05f;
                leftArm.xRot = -1.2f + Mth.sin(t * 1.1f + 1.3f) * 0.03f;
                leftArm.yRot = 0.3f + stroke;
                leftArm.zRot = -0.05f;
            }
            case STONECUTTER -> {
                // leaning over the saw: the left hand pushes the stone through, the right waits for the piece past the blade
                float[] turns = move < StonecutterStation.CUT_LENGTH ? StonecutterStation.armTurns(move) : new float[]{-0.2f, -0.12f};
                leftArm.xRot = -1.0f + Mth.sin(t + 2.1f) * 0.04f;
                leftArm.yRot = turns[0];
                leftArm.zRot = -0.06f;
                rightArm.xRot = -0.98f + Mth.sin(t) * 0.05f;
                rightArm.yRot = turns[1];
                rightArm.zRot = 0.06f;
            }
            case FURNACE, SMOKER, BREWING -> {
                // crouched at the front: one hand reaching into the openings, the other steadying
                rightArm.xRot = -1.32f + Mth.sin(t * 1.3f) * 0.12f;
                rightArm.yRot = -0.12f + Mth.sin(t * 0.8f) * 0.08f;
                rightArm.zRot = 0.04f;
                leftArm.xRot = -1.6f + Mth.sin(t + 2.1f) * 0.03f;
                leftArm.yRot = 0.32f;
                leftArm.zRot = -0.05f;
            }
            case CHISELED_BOOKSHELF -> {
                if (ShelfBooks.reading(entity.id)) {
                    // a book held open in both hands in front of the chest
                    rightArm.xRot = -1.05f + Mth.sin(t * 0.6f) * 0.02f;
                    rightArm.yRot = -0.42f;
                    rightArm.zRot = 0.05f;
                    leftArm.xRot = -1.05f + Mth.sin(t * 0.6f + 1.4f) * 0.02f;
                    leftArm.yRot = 0.42f;
                    leftArm.zRot = -0.05f;
                } else {
                    // crouched at the shelf: one hand along the books, the other on the knee
                    rightArm.xRot = -1.4f + Mth.sin(t * 0.9f) * 0.1f;
                    rightArm.yRot = -0.1f + Mth.sin(t * 0.55f) * 0.18f;
                    rightArm.zRot = 0.04f;
                    leftArm.xRot = -0.55f;
                    leftArm.yRot = 0.1f;
                    leftArm.zRot = -0.1f;
                }
            }
            case ENCHANTING, BEACON -> {
                // both hands raised over the table (or towards the beacon), palms to the floating item
                rightArm.xRot = -1.75f + Mth.sin(t * 0.7f) * 0.06f;
                rightArm.yRot = -0.32f + Mth.sin(t * 0.5f) * 0.05f;
                rightArm.zRot = 0.18f;
                leftArm.xRot = -1.75f + Mth.sin(t * 0.7f + 1.4f) * 0.06f;
                leftArm.yRot = 0.32f;
                leftArm.zRot = -0.18f;
            }
            default -> {
                // leaning over a table or a blast furnace, hands resting on top
                rightArm.xRot = -0.98f + Mth.sin(t) * 0.07f;
                rightArm.yRot = -0.16f + Mth.sin(t * 0.7f) * 0.05f;
                rightArm.zRot = 0.06f;
                leftArm.xRot = -0.98f + Mth.sin(t + 2.1f) * 0.05f;
                leftArm.yRot = 0.16f;
                leftArm.zRot = -0.06f;
            }
        }
    }
}
