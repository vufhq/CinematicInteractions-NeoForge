package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.station.StationKind;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Display adapters retain vanilla block entities while applying a client's saved model alignment. */
public final class StationDisplayLayouts {
    private StationDisplayLayouts() {}
    public static boolean campfire(net.minecraft.client.renderer.blockentity.state.CampfireRenderState fire, PoseStack pose,
            net.minecraft.client.renderer.SubmitNodeCollector collector) {
        if (!ClientOnly.serverHasMod()) return false;
        var layout = SpatialLayouts.current(StationKind.CAMPFIRE);
        if (layout.equals(SpatialAppearance.VANILLA)) return false;
        BlockPos pos = fire.blockPos;
        Direction facing = fire.facing;
        var frame = RemainingLayouts.frame(StationKind.CAMPFIRE, pos, facing);
        for (int i = 0; i < fire.items.size(); i++) {
            var item = fire.items.get(i);
            if (item.isEmpty()) continue;
            Direction turn = Direction.from2DDataValue((i + facing.get2DDataValue()) % 4);
            Vec3 at = layout.point(frame, i).subtract(Vec3.atLowerCornerOf(pos));
            pose.pushPose();
            pose.translate(at.x, at.y, at.z);
            pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees(-turn.toYRot() - (float) layout.rotation(i)).mul(Axis.XP.rotationDegrees(90))));
            float size = 0.375f * layout.scale(i) * (item.usesBlockLight() ? 2 : 1);
            pose.scale(size, size, size);
            item.submit(pose, collector, fire.lightCoords, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
        return true;
    }
    public static boolean customLectern() { return ClientOnly.serverHasMod() && !SpatialLayouts.current(StationKind.LECTERN).equals(SpatialAppearance.VANILLA); }
    public static void lectern(net.minecraft.client.renderer.blockentity.state.LecternRenderState lectern, PoseStack pose) {
        BlockPos pos = lectern.blockPos;
        var frame = RemainingLayouts.frame(StationKind.LECTERN, pos, Direction.fromYRot(lectern.yRot).getCounterClockWise());
        var layout = SpatialLayouts.current(StationKind.LECTERN);
        Vec3 from = frame.base(0).subtract(Vec3.atLowerCornerOf(pos)), to = layout.point(frame, 0).subtract(Vec3.atLowerCornerOf(pos));
        Vec3 normal = frame.normal();
        pose.translate(to.x, to.y, to.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(new Quaternionf().rotationAxis((float) Math.toRadians(layout.rotation(0)), (float) normal.x, (float) normal.y, (float) normal.z)));
        float scale = layout.scale(0); pose.scale(scale, scale, scale);
        pose.translate(-from.x, -from.y, -from.z);
    }
}
