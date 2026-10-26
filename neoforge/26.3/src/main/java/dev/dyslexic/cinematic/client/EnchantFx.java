package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.Cinematic;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** The light and particle work of the enchanting scene: rune circles, sigils, the shaft of light, glyph streams. */
public final class EnchantFx {
    private EnchantFx() {}

    public static final Identifier CIRCLE = Cinematic.id("textures/fx/magic_circle.png");
    public static final Identifier PIXEL_CIRCLE = Cinematic.id("textures/fx/enchant_circle_pixel.png");
    private static final RenderType PIXEL_RENDER = RenderType.create("cinematic_enchant_pixel",
            net.minecraft.client.renderer.rendertype.RenderSetup.builder(CinematicPipelines.ENCHANT_CIRCLE)
                    .withTexture("Sampler0", PIXEL_CIRCLE).sortOnUpload().createRenderSetup());
    public static final double OUTER_LIFT = 0.012, INNER_LIFT = 0.020, HINT_LIFT = 0.028, SOCKET_LIFT = 0.016;

    public enum CirclePalette {
        VIOLET(0xAB00F9, 0xC566FF), RED(0xA3303B, 0xCF5354), LAPIS(0x3B78D9, 0x9CC8FF);
        final int top, bottom;
        CirclePalette(int top, int bottom) { this.top = top; this.bottom = bottom; }
    }
    public static final Identifier GLOW = Cinematic.id("textures/fx/glow.png");
    public static final Identifier BEAM = Cinematic.id("textures/fx/beam.png");

    public static final double LAPIS_RADIUS = 0.115;
    // Raising the right-edge rune clears the open book without putting it behind the resting hand.
    private static final double LAPIS_RIGHT = 0.44;
    private static final double LAPIS_NEAR = 0.18;
    private static final double LAPIS_LIFT = 0.27;

    private static final RandomSource RANDOM = RandomSource.create();

    /** Height of the enchanting table's top. */
    public static Vec3 top(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.75, pos.getZ() + 0.5);
    }

    /** Where the item hovers: high over the book. */
    public static Vec3 itemCenter(BlockPos pos, Direction side, double clock) {
        return top(pos).add(0, 0.62 + Math.sin(clock * 1.6) * 0.025, 0);
    }

    /** The small floating socket beside the book, as seen by a player on {@code side}. */
    public static Vec3 lapisCorner(BlockPos pos, Direction side) {
        Vec3 out = Vec3.atLowerCornerOf(side.getUnitVec3i());
        Vec3 right = Vec3.atLowerCornerOf(side.getOpposite().getClockWise().getUnitVec3i());
        return top(pos).add(out.scale(LAPIS_NEAR)).add(right.scale(LAPIS_RIGHT)).add(0, LAPIS_LIFT, 0);
    }

    /** A small empty-socket crystal made of added light, rather than an opaque placeholder item. */
    public static void lapisGhost(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 at, float brightness) {
        lapisGhost(pose, buffers, camera, at, brightness, 1, 0);
    }

    public static void lapisGhost(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 at, float brightness, float scale, float rotation) {
        Vec3 pivot = at.subtract(camera.position());
        pose.pushPose(); pose.translate(pivot.x, pivot.y, pivot.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees(rotation))); pose.scale(scale, scale, scale);
        pose.translate(-pivot.x, -pivot.y, -pivot.z);
        double[][] gem = {{0, 0.052}, {0.035, 0.018}, {0.023, -0.032}, {-0.023, -0.032}, {-0.035, 0.018}};
        VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.lightning());
        for (int i = 0; i < gem.length; i++) {
            double[] a = gem[i];
            double[] b = gem[(i + 1) % gem.length];
            crystalLine(vc, pose.last(), camera, at, a[0], a[1], b[0], b[1], brightness);
        }
        crystalLine(vc, pose.last(), camera, at, -0.035, 0.018, 0.035, 0.018, brightness * 0.65f);
        crystalLine(vc, pose.last(), camera, at, 0, 0.052, -0.023, -0.032, brightness * 0.65f);
        crystalLine(vc, pose.last(), camera, at, 0, 0.052, 0.023, -0.032, brightness * 0.65f);
        pose.popPose();
    }

    private static void crystalLine(VertexConsumer vc, PoseStack.Pose pose, Camera camera, Vec3 at,
            double x0, double y0, double x1, double y1, float brightness) {
        double length = Math.hypot(x1 - x0, y1 - y0);
        double dx = -(y1 - y0) / length * 0.0025;
        double dy = (x1 - x0) / length * 0.0025;
        // Fade both edges to zero alpha so the little ribbons remain light under shader packs.
        for (int edge = -1; edge <= 1; edge += 2) {
            double[][] ribbon = {{x0, y0}, {x1, y1}, {x1 + dx * edge, y1 + dy * edge}, {x0 + dx * edge, y0 + dy * edge}};
            int[][] windings = {{0, 1, 2, 3}, {3, 2, 1, 0}};
            for (int[] winding : windings) {
                for (int vertex : winding) {
                    crystalVertex(vc, pose, camera, at, ribbon[vertex][0], ribbon[vertex][1], brightness, vertex < 2 ? 190 : 0);
                }
            }
        }
    }

    private static void crystalVertex(VertexConsumer vc, PoseStack.Pose pose, Camera camera, Vec3 at,
            double x, double y, float brightness, int alpha) {
        org.joml.Vector3fc left = camera.leftVector();
        org.joml.Vector3fc up = camera.upVector();
        Vec3 cam = camera.position();
        vc.addVertex(pose, (float) (at.x + left.x() * x + up.x() * y - cam.x),
                        (float) (at.y + left.y() * x + up.y() * y - cam.y),
                        (float) (at.z + left.z() * x + up.z() * y - cam.z))
                .setColor(clamp(0.25f * brightness), clamp(0.62f * brightness), clamp(brightness), alpha);
    }

    // ---- quads -------------------------------------------------------------------------------------

    /** A textured, additive quad given by four camera-relative corners (wound both ways). */
    /** Texture corners of a quad, and its corners wound both ways (seen from either side). */
    private static final float[][] QUAD_UV = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    private static final int[][] BOTH_WAYS = {{0, 1, 2, 3}, {3, 2, 1, 0}};

    private static void pixelQuad(VertexConsumer vc, PoseStack.Pose pose, Vec3[] corners, CirclePalette palette, float brightness, boolean surface) {
        float presence = Math.max(0, Math.min(1, brightness / 0.4f));
        int alpha = clamp(presence * presence * (3 - 2 * presence));
        if (alpha == 0) return;
        int[][] windings = surface ? new int[][] {{3, 2, 1, 0}} : BOTH_WAYS;
        for (int[] order : windings) for (int i : order) {
            int rgb = i < 2 ? palette.top : palette.bottom;
            vc.addVertex(pose, (float) corners[i].x, (float) corners[i].y, (float) corners[i].z)
                    .setColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, alpha)
                    .setUv(QUAD_UV[i][0], QUAD_UV[i][1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
                    .setNormal(pose, 0, 1, 0);
        }
    }

    public static void circle(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 center, double radius, double angle,
            CirclePalette palette, float brightness) {
        Vec3[] corners = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            double a = angle + Math.PI / 4 + i * Math.PI / 2, d = radius * Math.sqrt(2);
            corners[i] = new Vec3(center.x + Math.cos(a) * d - cam.x, center.y - cam.y, center.z + Math.sin(a) * d - cam.z);
        }
        pixelQuad(buffers.getBuffer(PIXEL_RENDER), pose.last(), corners, palette, brightness, true);
    }

    public static void circleBillboard(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 center, double radius,
            double angle, CirclePalette palette, float brightness) {
        org.joml.Vector3fc left = camera.leftVector(), up = camera.upVector();
        Vec3 cam = camera.position();
        Vec3[] corners = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            double a = angle + Math.PI / 4 + i * Math.PI / 2;
            double x = Math.cos(a) * radius * Math.sqrt(2), y = Math.sin(a) * radius * Math.sqrt(2);
            corners[i] = new Vec3(center.x + left.x() * x + up.x() * y - cam.x,
                    center.y + left.y() * x + up.y() * y - cam.y, center.z + left.z() * x + up.z() * y - cam.z);
        }
        pixelQuad(buffers.getBuffer(PIXEL_RENDER), pose.last(), corners, palette, brightness, false);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vec3[] c, float r, float g, float b, Vec3 normal) {
        int ri = clamp(r), gi = clamp(g), bi = clamp(b);
        for (int[] order : BOTH_WAYS) {
            for (int i : order) {
                vc.addVertex(pose, (float) c[i].x, (float) c[i].y, (float) c[i].z).setColor(ri, gi, bi, 255).setUv(QUAD_UV[i][0], QUAD_UV[i][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
                        .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
            }
        }
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    /** The rune circle lying on a surface, turned by {@code angle} radians, coloured (r, g, b) as light. */
    public static void circle(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 center, double radius, double angle,
            float r, float g, float b) {
        disc(pose, buffers, cam, center, radius, angle, CIRCLE, r, g, b);
    }

    /** A texture lying flat on a surface as added light. */
    public static void disc(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 center, double radius, double angle,
            Identifier texture, float r, float g, float b) {
        Vec3[] c = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            double a = angle + Math.PI / 4 + i * Math.PI / 2;
            double d = radius * Math.sqrt(2);
            c[i] = new Vec3(center.x + Math.cos(a) * d - cam.x, center.y - cam.y, center.z + Math.sin(a) * d - cam.z);
        }
        quad(buffers.getBuffer(Fx.glow(texture)), pose.last(), c, r, g, b, new Vec3(0, 1, 0));
    }

    /** A disc facing the camera: the circle texture or the soft glow. */
    public static void billboard(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 center, double radius, double angle,
            Identifier texture, float r, float g, float b) {
        org.joml.Vector3fc left = camera.leftVector();
        org.joml.Vector3fc up = camera.upVector();
        Vec3 cam = camera.position();
        Vec3[] c = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            double a = angle + Math.PI / 4 + i * Math.PI / 2;
            double x = Math.cos(a) * radius * Math.sqrt(2);
            double y = Math.sin(a) * radius * Math.sqrt(2);
            c[i] = new Vec3(center.x + left.x() * x + up.x() * y - cam.x, center.y + left.y() * x + up.y() * y - cam.y,
                    center.z + left.z() * x + up.z() * y - cam.z);
        }
        org.joml.Vector3fc look = camera.forwardVector();
        quad(buffers.getBuffer(Fx.glow(texture)), pose.last(), c, r, g, b, new Vec3(-look.x(), -look.y(), -look.z()));
    }

    /** A shaft of light rising from {@code base}: two crossed planes, bright at the foot. */
    public static void beam(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 base, double height, double width, double spin,
            float r, float g, float b) {
        VertexConsumer vc = buffers.getBuffer(Fx.glow(BEAM));
        for (int plane = 0; plane < 2; plane++) {
            double a = spin + plane * Math.PI / 2;
            double dx = Math.cos(a) * width / 2;
            double dz = Math.sin(a) * width / 2;
            Vec3[] c = {
                    new Vec3(base.x - dx - cam.x, base.y + height - cam.y, base.z - dz - cam.z),
                    new Vec3(base.x + dx - cam.x, base.y + height - cam.y, base.z + dz - cam.z),
                    new Vec3(base.x + dx - cam.x, base.y - cam.y, base.z + dz - cam.z),
                    new Vec3(base.x - dx - cam.x, base.y - cam.y, base.z - dz - cam.z)};
            quad(vc, pose.last(), c, r, g, b, new Vec3(Math.sin(a), 0, -Math.cos(a)));
        }
    }

    // ---- particles ---------------------------------------------------------------------------------

    /** Glyphs flying from {@code from} into {@code to} (the vanilla enchanting particle converges on its spawn point). */
    public static void stream(Vec3 from, Vec3 to, int count) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (int i = 0; i < count; i++) {
            double jx = (RANDOM.nextDouble() - 0.5) * 0.5;
            double jy = (RANDOM.nextDouble() - 0.5) * 0.4;
            double jz = (RANDOM.nextDouble() - 0.5) * 0.5;
            level.addParticle(ParticleTypes.ENCHANT, to.x, to.y, to.z, from.x - to.x + jx, from.y - to.y + jy, from.z - to.z + jz);
        }
    }

    // ---- sparks: soft glowing motes ({@link Fx}) in the violet and blue of the enchanting table ---------

    private static final float[][] PALETTE = {{0.75f, 0.45f, 1.0f}, {0.45f, 0.8f, 1.0f}, {1.0f, 0.92f, 1.0f}};

    /** A mote drifting slowly upwards. */
    public static void spark(Vec3 at, Vec3 velocity, double life, float size) {
        float[] c = PALETTE[RANDOM.nextInt(PALETTE.length)];
        Fx.spark(at, velocity, life, size, c[0], c[1], c[2], -0.04, 0);
    }

    /** The moment of enchantment: motes flung out, glyphs pulled in, a little purple haze. */
    public static void burst(Vec3 at, double strength) {
        for (int i = 0; i < (int) (70 * strength); i++) {
            Vec3 dir = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian() * 0.7 + 0.25, RANDOM.nextGaussian()).normalize();
            spark(at, dir.scale(0.35 + RANDOM.nextDouble() * 1.0), 0.6 + RANDOM.nextDouble() * 0.8, 0.018f + RANDOM.nextFloat() * 0.03f);
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (int i = 0; i < (int) (8 * strength); i++) {
            level.addParticle(ParticleTypes.REVERSE_PORTAL, at.x + (RANDOM.nextDouble() - 0.5) * 0.3, at.y + (RANDOM.nextDouble() - 0.5) * 0.3,
                    at.z + (RANDOM.nextDouble() - 0.5) * 0.3, (RANDOM.nextDouble() - 0.5) * 0.05, RANDOM.nextDouble() * 0.04,
                    (RANDOM.nextDouble() - 0.5) * 0.05);
        }
        for (int i = 0; i < (int) (30 * strength); i++) {
            Vec3 dir = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian(), RANDOM.nextGaussian()).normalize().scale(1.1 + RANDOM.nextDouble() * 0.6);
            level.addParticle(ParticleTypes.ENCHANT, at.x, at.y, at.z, dir.x, dir.y, dir.z);
        }
    }

    /** A mote drifting up close around a point. */
    public static void sparkle(Vec3 at, double radius) {
        double a = RANDOM.nextDouble() * Math.PI * 2;
        double r = radius * (0.6 + RANDOM.nextDouble() * 0.4);
        spark(at.add(Math.cos(a) * r, (RANDOM.nextDouble() - 0.5) * 0.2, Math.sin(a) * r), new Vec3(0, 0.05 + RANDOM.nextDouble() * 0.05, 0),
                1.0 + RANDOM.nextDouble() * 0.6, 0.012f + RANDOM.nextFloat() * 0.012f);
    }

    /** Motes drawn in from a shell around a point. */
    public static void gather(Vec3 at, double radius) {
        Vec3 dir = new Vec3(RANDOM.nextGaussian(), RANDOM.nextGaussian(), RANDOM.nextGaussian()).normalize();
        double life = 0.45 + RANDOM.nextDouble() * 0.2;
        spark(at.add(dir.scale(radius)), dir.scale(-radius / life * 1.6), life, 0.014f + RANDOM.nextFloat() * 0.012f);
    }

    /** Centres of the bookshelves that empower this table. */
    public static List<Vec3> shelves(ClientLevel level, BlockPos pos) {
        List<Vec3> list = new ArrayList<>();
        for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantingTableBlock.isValidBookShelf(level, pos, offset)) {
                list.add(Vec3.atCenterOf(pos.offset(offset)));
            }
        }
        return list;
    }
}
