package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;

/**
 * A player arm cut in two along its length, textured from the skin exactly like the vanilla arm: the hand
 * (bottom 4 px) keeps its size while the rest can stretch, so an arm can reach across the table from outside
 * the frame without a long, smeared fist. Coordinates are vanilla's: pixels, +Y towards the hand, which ends at y = 10.
 */
public final class ArmMesh {
    private final ModelPart.Cube upper;
    private final ModelPart.Cube hand;
    private final ModelPart.Cube cap;

    /** Pixels of arm above the hand piece; the hand piece is 4. */
    public static final float UPPER = 8f;
    public static final float HAND = 4f;

    private ArmMesh(int u, int v, float x0, float width, float grow) {
        Set<Direction> sides = EnumSet.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
        Set<Direction> upperFaces = EnumSet.copyOf(sides);
        upperFaces.add(Direction.DOWN);
        upper = new ModelPart.Cube(u, v, x0, -2f, -2f, width, UPPER, 4f, grow, grow, grow, false, 64f, 64f, upperFaces);
        hand = new ModelPart.Cube(u, v + (int) UPPER, x0, -2f + UPPER, -2f, width, HAND, 4f, grow, grow, grow, false, 64f, 64f, sides);
        cap = new ModelPart.Cube(u, v, x0, -2f, -2f, width, UPPER + HAND, 4f, grow, grow, grow, false, 64f, 64f, EnumSet.of(Direction.UP));
    }

    private static final ArmMesh RIGHT_WIDE = new ArmMesh(40, 16, -3f, 4f, 0f);
    private static final ArmMesh RIGHT_WIDE_SLEEVE = new ArmMesh(40, 32, -3f, 4f, 0.25f);
    private static final ArmMesh LEFT_WIDE = new ArmMesh(32, 48, -1f, 4f, 0f);
    private static final ArmMesh LEFT_WIDE_SLEEVE = new ArmMesh(48, 48, -1f, 4f, 0.25f);
    private static final ArmMesh RIGHT_SLIM = new ArmMesh(40, 16, -2f, 3f, 0f);
    private static final ArmMesh RIGHT_SLIM_SLEEVE = new ArmMesh(40, 32, -2f, 3f, 0.25f);
    private static final ArmMesh LEFT_SLIM = new ArmMesh(32, 48, -1f, 3f, 0f);
    private static final ArmMesh LEFT_SLIM_SLEEVE = new ArmMesh(48, 48, -1f, 3f, 0.25f);

    public static ArmMesh arm(boolean right, boolean slim) {
        return right ? (slim ? RIGHT_SLIM : RIGHT_WIDE) : (slim ? LEFT_SLIM : LEFT_WIDE);
    }

    public static ArmMesh sleeve(boolean right, boolean slim) {
        return right ? (slim ? RIGHT_SLIM_SLEEVE : RIGHT_WIDE_SLEEVE) : (slim ? LEFT_SLIM_SLEEVE : LEFT_WIDE_SLEEVE);
    }

    /** Draws in model units (cubes divide by 16 themselves); {@code stretch} lengthens the upper part away from the hand. */
    public void render(PoseStack pose, VertexConsumer buffer, int light, int overlay, float stretch) {
        hand.compile(pose.last(), buffer, light, overlay, -1);
        cap.compile(pose.last(), buffer, light, overlay, -1);
        pose.pushPose();
        float joint = (UPPER - 2f) / 16f;
        pose.translate(0, joint, 0);
        pose.scale(1, stretch, 1);
        pose.translate(0, -joint, 0);
        upper.compile(pose.last(), buffer, light, overlay, -1);
        pose.popPose();
    }

    /** Upper arm, with a closed shoulder end; the lower segment is placed separately at the elbow. */
    public void renderUpper(PoseStack pose, VertexConsumer buffer, int light, int overlay) {
        upper.compile(pose.last(), buffer, light, overlay, -1);
        cap.compile(pose.last(), buffer, light, overlay, -1);
    }

    /** Forearm and hand; the same skin and sleeve layers as the original straight arm. */
    public void renderForearm(PoseStack pose, VertexConsumer buffer, int light, int overlay) {
        upper.compile(pose.last(), buffer, light, overlay, -1);
        hand.compile(pose.last(), buffer, light, overlay, -1);
    }

    /** A short, closed forearm floating near the work; its hand keeps its normal proportions. */
    public void renderFloating(PoseStack pose, VertexConsumer buffer, int light, int overlay) {
        hand.compile(pose.last(), buffer, light, overlay, -1);
        pose.pushPose();
        pose.translate(0, 6f / 16, 0);
        pose.scale(1, 0.4f, 1);
        pose.translate(0, -6f / 16, 0);
        upper.compile(pose.last(), buffer, light, overlay, -1);
        pose.popPose();
        pose.pushPose();
        pose.translate(0, 8f * 0.6f / 16, 0);
        cap.compile(pose.last(), buffer, light, overlay, -1);
        pose.popPose();
    }
}
