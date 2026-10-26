package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.table.TableGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import dev.dyslexic.cinematic.client.SceneBuffers;

/** Client geometry only: logical world slot numbers and recipe orientation never change. Offsets use texture pixels. */
record TableLayout(double x, double z, double rotation, double width, double depth, double height, double itemScale) {
    static final TableLayout VANILLA = new TableLayout(0, 0, 0, 1, 1, 0, 1);

    TableLayout {
        rotation = finite(rotation, 0, -180, 180);
        width = finite(width, 1, 0.5, 1.7);
        depth = finite(depth, 1, 0.5, 1.7);
        height = finite(height, 0, -2, 2);
        itemScale = finite(itemScale, 1, 0.5, 1.5);
        double c = Math.abs(Math.cos(Math.toRadians(rotation))), s = Math.abs(Math.sin(Math.toRadians(rotation)));
        double half = TableGrid.CELL * 1.5;
        double extentX = half * (c * width + s * depth), extentZ = half * (s * width + c * depth);
        double fit = Math.min(1, 0.48 / Math.max(extentX, extentZ));
        width *= fit;
        depth *= fit;
        extentX *= fit;
        extentZ *= fit;
        // Even a rotated grid stays on the block, with a small margin for the rim.
        double maxX = Math.max(0, (0.49 - extentX) * 16), maxZ = Math.max(0, (0.49 - extentZ) * 16);
        x = finite(x, 0, -maxX, maxX);
        z = finite(z, 0, -maxZ, maxZ);
    }

    private static double finite(double value, double fallback, double min, double max) {
        return Mth.clamp(Double.isFinite(value) ? value : fallback, min, max);
    }

    Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5 + x / 16, surfaceY(pos), pos.getZ() + 0.5 + z / 16);
    }

    double surfaceY(BlockPos pos) { return pos.getY() + 1 + height / 16; }

    Vec3 point(BlockPos pos, double localX, double localZ) {
        double a = Math.toRadians(rotation), c = Math.cos(a), s = Math.sin(a);
        double dx = (localX - 0.5) * width, dz = (localZ - 0.5) * depth;
        return center(pos).add(dx * c + dz * s, 0, -dx * s + dz * c);
    }

    Vec3 cell(BlockPos pos, int index) { return point(pos, TableGrid.cellX(index), TableGrid.cellZ(index)); }

    int cellAt(BlockPos pos, Vec3 world) {
        Vec3 d = world.subtract(center(pos));
        double a = Math.toRadians(rotation), c = Math.cos(a), s = Math.sin(a);
        return TableGrid.cellAt(0.5 + (d.x * c - d.z * s) / width, 0.5 + (d.x * s + d.z * c) / depth);
    }

    /** Transform a cell-local rectangle; used by both real hover targets and the recipe diagram. */
    void cellPose(PoseStack pose, BlockPos pos, int index, Vec3 camera) {
        Vec3 at = cell(pos, index).subtract(camera);
        pose.translate(at.x, at.y + 0.002, at.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees((float) rotation)));
        pose.scale((float) width, 1, (float) depth);
    }

    void renderItem(PoseStack pose, SceneBuffers buffers, Vec3 camera, ItemStack stack, Vec3 base,
            Direction facing, long seed, float scale, float tilt, int light, float opacity, boolean one) {
        if (rotation == 0) {
            if (one) ItemPlacer.renderOne(pose, buffers, camera, stack, base, facing, seed, scale * (float) itemScale, tilt, light);
            else ItemPlacer.render(pose, buffers, camera, stack, base, facing, seed, scale * (float) itemScale, tilt, light, opacity);
            return;
        }
        pose.pushPose();
        Vec3 d = base.subtract(camera);
        pose.translate(d.x, d.y, d.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees((float) rotation)));
        pose.translate(-d.x, -d.y, -d.z);
        if (one) ItemPlacer.renderOne(pose, buffers, camera, stack, base, facing, seed, scale * (float) itemScale, tilt, light);
        else ItemPlacer.render(pose, buffers, camera, stack, base, facing, seed, scale * (float) itemScale, tilt, light, opacity);
        pose.popPose();
    }

    AABB bounds(ItemStack stack, Vec3 base) {
        AABB original = ItemPlacer.restingBounds(stack, base);
        double half = Math.max(base.x - original.minX, base.z - original.minZ) * Math.max(1, itemScale) * 1.42;
        return new AABB(base.x - half, base.y, base.z - half, base.x + half,
                base.y + (original.maxY - base.y) * itemScale, base.z + half);
    }

    double value(int field) {
        return switch (field) {
            case 0 -> x; case 1 -> z; case 2 -> rotation; case 3 -> width * 100;
            case 4 -> depth * 100; case 5 -> height; case 6 -> itemScale * 100;
            default -> throw new IllegalArgumentException("Layout field " + field);
        };
    }

    TableLayout with(int field, double value) {
        return new TableLayout(field == 0 ? value : x, field == 1 ? value : z, field == 2 ? value : rotation,
                field == 3 ? value / 100 : width, field == 4 ? value / 100 : depth,
                field == 5 ? value : height, field == 6 ? value / 100 : itemScale);
    }

    TableLayout blend(TableLayout target, double t) {
        return new TableLayout(Mth.lerp(t, x, target.x), Mth.lerp(t, z, target.z), Mth.lerp(t, rotation, target.rotation),
                Mth.lerp(t, width, target.width), Mth.lerp(t, depth, target.depth), Mth.lerp(t, height, target.height),
                Mth.lerp(t, itemScale, target.itemScale));
    }
}
