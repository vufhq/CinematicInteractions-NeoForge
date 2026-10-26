package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.math.Axis;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;

/**
 * Draws an item stack resting on a surface: flat items lie down like a dropped sheet, block items stand as
 * little cubes. Bigger stacks show a few slightly scattered copies, the way item entities do.
 */
public final class ItemPlacer {
    private ItemPlacer() {}

    /** Edge length of a lying item, in blocks; a cell is 3/16. */
    public static final float FLAT_SIZE = 0.205f;
    /** Edge length of a standing block item. */
    public static final float BLOCK_SIZE = 0.15f;

    private static final Map<RenderType, RenderType> FADING_TYPES = new IdentityHashMap<>();
    /** Reseeded for every draw (the render thread only), so each item keeps its scatter without a new generator. */
    private static final RandomSource RANDOM = RandomSource.create();

    /**
     * @param base   point on the surface under the item's centre
     * @param facing the side the item was put down from: the item's top points away from it
     * @param scale  extra scale (animations)
     * @param tilt   degrees the item is tipped towards its owner (0 = lying flat)
     */
    public static void render(PoseStack pose, SceneBuffers buffers, Vec3 camera, ItemStack stack, Vec3 base, Direction facing,
            long seed, float scale, float tilt, int light) {
        render(pose, buffers, camera, stack, base, facing, seed, scale, tilt, light, 1f);
    }

    /** Opacity affects only this draw; the actual stack and its usual rendering stay untouched. */
    public static void render(PoseStack pose, SceneBuffers buffers, Vec3 camera, ItemStack stack, Vec3 base, Direction facing,
            long seed, float scale, float tilt, int light, float opacity) {
        render(pose, buffers, camera, stack, base, facing, seed, scale, tilt, light, opacity, false);
    }

    /** One item of the stack, however many it holds (as {@link #render} draws a stack of one, without copying the stack). */
    public static void renderOne(PoseStack pose, SceneBuffers buffers, Vec3 camera, ItemStack stack, Vec3 base, Direction facing,
            long seed, float scale, float tilt, int light) {
        render(pose, buffers, camera, stack, base, facing, seed, scale, tilt, light, 1f, true);
    }

    private static void render(PoseStack pose, SceneBuffers buffers, Vec3 camera, ItemStack stack, Vec3 base, Direction facing,
            long seed, float scale, float tilt, int light, float opacity, boolean one) {
        if (stack.isEmpty() || scale <= 0.001f) {
            return;
        }
        if (opacity < 0.999f) {
            SceneBuffers source = buffers;
            float alpha = (float) Anim.clamp01(opacity);
            // Glint must keep its fixed buffer: the item renderer writes to it and the model buffer together.
            // Iris extends entity formats, so compare their required attributes instead of format identity.
            buffers = type -> new VertexConsumerWrapper(source.getBuffer(type.format().contains("Color")
                    && type.format().contains("Normal") && type.format().contains("UV2")
                    ? FADING_TYPES.computeIfAbsent(type, ItemPlacer::fadingType) : type)) {
                @Override
                public VertexConsumer setColor(int r, int g, int b, int a) {
                    parent.setColor(r, g, b, Math.round(a * alpha));
                    return this;
                }
            };
        }
        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState model = model(stack, (int) seed);
        boolean block = model.usesBlockLight();
        int copies = one ? 1 : copies(stack.getCount(), block);
        RandomSource random = RANDOM;
        random.setSeed(seed);
        float baseYaw = 180f - facing.getOpposite().toYRot() + (random.nextFloat() - 0.5f) * 16f;

        for (int c = 0; c < copies; c++) {
            float jx = c == 0 ? 0 : (random.nextFloat() - 0.5f) * 0.05f;
            float jz = c == 0 ? 0 : (random.nextFloat() - 0.5f) * 0.05f;
            float yaw = baseYaw + (c == 0 ? 0 : (random.nextFloat() - 0.5f) * 40f);
            pose.pushPose();
            pose.translate(base.x - camera.x + jx, base.y - camera.y, base.z - camera.z + jz);
            pose.rotate(Axis.YP.rotationDegrees(yaw));
            if (block) {
                float size = BLOCK_SIZE * scale;
                // FIXED shows a block as a cube of 0.5 centred on the origin
                pose.translate(0, size * 0.5f, 0);
                pose.rotate(Axis.YP.rotationDegrees(180f));
                pose.rotate(Axis.XP.rotationDegrees(-tilt * 0.4f));
                pose.scale(size * 2f, size * 2f, size * 2f);
            } else {
                float size = FLAT_SIZE * scale;
                pose.translate(0, 0.0035f + c * flatStep(scale), 0);
                pose.rotate(Axis.XP.rotationDegrees(-90f + tilt));
                pose.scale(size, size, size);
            }
            ItemModels.draw(model, pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    /** Keep each model's texture (including special block items), but allow its vertex alpha to blend. */
    private static RenderType fadingType(RenderType original) {
        // the same textures, light and target as the item's own render type (since 1.21.11 a render type is a setup)
        net.minecraft.client.renderer.rendertype.RenderSetup setup = original.state;
        var builder = net.minecraft.client.renderer.rendertype.RenderSetup.builder(CinematicPipelines.fading(original.pipeline()));
        setup.textures.forEach((name, binding) -> builder.withTexture(name, binding.location(), binding.sampler()));
        if (setup.useLightmap) builder.useLightmap();
        if (setup.useOverlay) builder.useOverlay();
        return RenderType.create("cinematic_fading_item", builder.setLayeringTransform(setup.layeringTransform)
                .setTextureTransform(setup.textureTransform).sortOnUpload().createRenderSetup());
    }

    static SceneBuffers opacity(SceneBuffers source, float alpha) {
        return type -> new VertexConsumerWrapper(source.getBuffer(type.format().contains("Normal")
                && type.format().contains("UV2") ? FADING_TYPES.computeIfAbsent(type, ItemPlacer::fadingType) : type)) {
            @Override public VertexConsumer setColor(int r, int g, int b, int a) {
                parent.setColor(r, g, b, Math.round(a * (float) Anim.clamp01(alpha)));
                return this;
            }
        };
    }

    /** Conservative footprint of the normal resting model, including its small yaw and stack scatter. */
    public static AABB restingBounds(ItemStack stack, Vec3 base) {
        boolean block = isBlock(stack);
        double half = block ? BLOCK_SIZE * 0.66 : FLAT_SIZE * 0.71 + (stack.getCount() > 1 ? 0.025 : 0);
        double height = block ? BLOCK_SIZE : 0.006 + (copies(stack.getCount(), false) - 1) * flatStep(1f);
        return new AABB(base.x - half, base.y, base.z - half, base.x + half, base.y + height, base.z + half);
    }

    /** The yaw that turns an item's top away from a player standing on {@code side} (as {@link #render} lays it). */
    public static float awayYaw(Direction side) {
        return 180f - side.getOpposite().toYRot();
    }

    /** Lying on a surface, turned {@code yaw} degrees, tipped up by {@code tilt} towards the player. */
    public static Quaternionf lying(float yaw, float tilt) {
        return Axis.YP.rotationDegrees(yaw).mul(Axis.XP.rotationDegrees(-90f + tilt));
    }

    /**
     * One item centred at {@code center} with any orientation and no stack copies: a flat item is a square
     * {@code size} across ({@code rotation} turns it from upright, facing south); a block item is a cube of
     * {@code size}.
     */
    public static void drawOriented(PoseStack pose, SceneBuffers buffers, Vec3 camera, ItemStack stack, Vec3 center, Quaternionf rotation,
            float size, int light) {
        if (stack.isEmpty() || size <= 0.001f) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState model = model(stack, 0);
        pose.pushPose();
        pose.translate(center.x - camera.x, center.y - camera.y, center.z - camera.z);
        pose.rotate(rotation);
        float scale = model.usesBlockLight() ? size * 2f : size;
        pose.scale(scale, scale, scale);
        ItemModels.draw(model, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    static ItemStackRenderState model(ItemStack stack, int seed) {
        var mc = Minecraft.getInstance();
        var state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.FIXED, mc.level, null, seed);
        return state;
    }

    public static boolean isBlock(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        return model(stack, 0).usesBlockLight();
    }

    private static float flatStep(float scale) {
        return FLAT_SIZE * scale / 16f + 0.0015f;
    }

    private static int copies(int count, boolean block) {
        if (block) {
            return 1;
        }
        return count >= 33 ? 4 : count >= 17 ? 3 : count >= 2 ? 2 : 1;
    }
}
