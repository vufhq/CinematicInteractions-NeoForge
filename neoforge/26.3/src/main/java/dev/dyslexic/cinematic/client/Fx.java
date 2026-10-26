package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Effects shared by the scenes: our own sparks (soft motes or hot streaks, added light, so they stay small and
 * crisp right in front of the camera where vanilla particles turn into blots), and plain textured boxes cut from
 * the block atlas for station props.
 */
public final class Fx {
    private Fx() {}

    static final RandomSource RANDOM = RandomSource.create();
    private static final Identifier WHITE = Identifier.withDefaultNamespace("textures/misc/white.png");

    /** Furnace light must use the emissive pipeline: lightning becomes a white reflective surface in shader packs. */
    static RenderType flatGlow() {
        return glow(WHITE);
    }

    static void glowVertex(VertexConsumer vc, org.joml.Matrix4f pose, float x, float y, float z,
            int r, int g, int b, Vec3 normal) {
        vc.addVertex(pose, x, y, z).setColor(r, g, b, 255).setUv(0.5f, 0.5f)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    /**
     * A flat see-through highlight lying on a face (the cell of a table, the opening of a furnace): drawn without writing
     * depth, so overlapping pieces of it blend instead of fighting over which is in front, and pulled a little towards
     * the camera, so it never flickers against the face it lies on.
     */
    public static final RenderType HIGHLIGHT = RenderType.create("cinematic_highlight",
            net.minecraft.client.renderer.rendertype.RenderSetup.builder(CinematicPipelines.HIGHLIGHT).sortOnUpload().createRenderSetup());

    /**
     * Added light (sparks, runes, the beacon's rays): what is drawn brightens what lies behind it, and the glow textures
     * are made for that (their colour already fades with their alpha). Since 1.21.2 vanilla blends the eyes render type
     * as ordinary see-through colour, which turns those textures into dark smudges, so this keeps the old eyes blending.
     */
    public static RenderType glow(Identifier texture) {
        return GLOWS.apply(texture);
    }

    private static final java.util.function.Function<Identifier, RenderType> GLOWS = net.minecraft.util.Util.memoize(texture ->
            RenderType.create("cinematic_glow", net.minecraft.client.renderer.rendertype.RenderSetup.builder(CinematicPipelines.GLOW)
                    .withTexture("Sampler0", texture).sortOnUpload().createRenderSetup()));

    // ---- sparks ------------------------------------------------------------------------------------

    private static final class Spark {
        double x, y, z, vx, vy, vz, age, life, gravity, streak;
        float size, r, g, b;
        /** Offset of the mote's twinkle, so neighbours do not pulse together. */
        int phase;
    }

    private static final List<Spark> SPARKS = new ArrayList<>();
    private static final int MAX_SPARKS = 900;

    /**
     * @param gravity blocks/s² pulling down (negative: drifting up)
     * @param streak  seconds of travel drawn as a tail behind the spark (0: a round mote)
     */
    public static void spark(Vec3 at, Vec3 velocity, double life, float size, float r, float g, float b, double gravity, double streak) {
        if (SPARKS.size() >= MAX_SPARKS) {
            return;
        }
        Spark s = new Spark();
        s.phase = SPARKS.size();
        s.x = at.x;
        s.y = at.y;
        s.z = at.z;
        s.vx = velocity.x;
        s.vy = velocity.y;
        s.vz = velocity.z;
        s.life = life;
        s.size = size;
        s.r = r;
        s.g = g;
        s.b = b;
        s.gravity = gravity;
        s.streak = streak;
        SPARKS.add(s);
    }

    /** Moves and draws every spark; called once per frame. */
    public static void renderSparks(PoseStack pose, SceneBuffers buffers, Camera camera, double dt) {
        for (int i = SPARKS.size() - 1; i >= 0; i--) {
            Spark s = SPARKS.get(i);
            s.age += dt;
            if (s.age >= s.life) {
                // the last spark (already moved this frame) takes its place: no shifting of the whole list, and sparks
                // add light, so the order they are drawn in does not show
                int last = SPARKS.size() - 1;
                SPARKS.set(i, SPARKS.get(last));
                SPARKS.remove(last);
                continue;
            }
            double drag = Math.exp((s.streak > 0 ? -0.9 : -2.2) * dt);
            s.vx *= drag;
            s.vy = s.vy * drag - s.gravity * dt;
            s.vz *= drag;
            s.x += s.vx * dt;
            s.y += s.vy * dt;
            s.z += s.vz * dt;
            if (s.streak > 0) {
                // a hot spark is brightest when struck and cools as it flies
                float k = (float) Math.pow(1 - s.age / s.life, 1.4);
                Vec3 head = new Vec3(s.x, s.y, s.z);
                Vec3 tail = head.subtract(s.vx * s.streak, s.vy * s.streak, s.vz * s.streak);
                streak(pose, buffers, camera, head, tail, s.size, s.r * k, s.g * k * k, s.b * k * k * k);
            } else {
                float fade = (float) Math.sin(Math.PI * s.age / s.life);
                float twinkle = 0.75f + 0.25f * (float) Math.sin(s.age * 23 + s.phase);
                float k = fade * twinkle;
                EnchantFx.billboard(pose, buffers, camera, new Vec3(s.x, s.y, s.z), s.size * (0.6 + 0.4 * fade), 0, EnchantFx.GLOW,
                        s.r * k, s.g * k, s.b * k);
            }
        }
    }

    public static void clearSparks() {
        SPARKS.clear();
    }

    /** A soft dark spot facing the camera, so something bright in front of it stands out against a light background. */
    public static void shade(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 center, double radius, float alpha) {
        org.joml.Vector3fc left = camera.leftVector();
        org.joml.Vector3fc up = camera.upVector();
        Vec3 cam = camera.position();
        double r = radius * Math.sqrt(2);
        Vec3[] c = new Vec3[4];
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            double x = Math.cos(a) * r, y = Math.sin(a) * r;
            c[i] = new Vec3(center.x + left.x() * x + up.x() * y - cam.x, center.y + left.y() * x + up.y() * y - cam.y,
                    center.z + left.z() * x + up.z() * y - cam.z);
        }
        VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(EnchantFx.GLOW));
        org.joml.Vector3fc look = camera.forwardVector();
        int a = channel(alpha);
        for (int[] order : BOTH_WAYS) {
            for (int i : order) {
                vc.addVertex(pose.last(), (float) c[i].x, (float) c[i].y, (float) c[i].z).setColor(0, 0, 0, a).setUv(QUAD_UV[i][0], QUAD_UV[i][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose.last(), -look.x(), -look.y(), -look.z());
            }
        }
    }

    /** A soft patch of added light lying on any face, spanned by the unit vectors {@code u} and {@code v}. */
    public static void facePatch(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 center, Vec3 u, Vec3 v, double half, float r, float g,
            float b) {
        Vec3 du = u.scale(half), dv = v.scale(half);
        Vec3[] c = {center.subtract(du).subtract(dv).subtract(cam), center.add(du).subtract(dv).subtract(cam), center.add(du).add(dv).subtract(cam),
                center.subtract(du).add(dv).subtract(cam)};
        quad(buffers.getBuffer(glow(EnchantFx.GLOW)), pose.last(), c, r, g, b, u.cross(v));
    }

    /** A glowing line from head to tail, turned to face the camera. */
    private static void streak(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 head, Vec3 tail, double width,
            float r, float g, float b) {
        Vec3 cam = camera.position();
        Vec3 along = head.subtract(tail);
        if (along.lengthSqr() < 1e-8) {
            along = new Vec3(0, 1e-4, 0);
        }
        Vec3 across = along.cross(head.subtract(cam)).normalize().scale(width);
        Vec3 ext = along.normalize().scale(width * 0.8);
        Vec3 h = head.add(ext), t = tail.subtract(ext);
        Vec3[] c = {t.add(across).subtract(cam), h.add(across).subtract(cam), h.subtract(across).subtract(cam), t.subtract(across).subtract(cam)};
        org.joml.Vector3fc look = camera.forwardVector();
        quad(buffers.getBuffer(glow(EnchantFx.GLOW)), pose.last(), c, r, g, b, new Vec3(-look.x(), -look.y(), -look.z()));
    }

    /** Texture corners of a quad, and its corners wound both ways (seen from either side). */
    private static final float[][] QUAD_UV = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    private static final int[][] BOTH_WAYS = {{0, 1, 2, 3}, {3, 2, 1, 0}};

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, Vec3[] c, float r, float g, float b, Vec3 normal) {
        int ri = channel(r), gi = channel(g), bi = channel(b);
        for (int[] order : BOTH_WAYS) {
            for (int i : order) {
                vc.addVertex(pose, (float) c[i].x, (float) c[i].y, (float) c[i].z).setColor(ri, gi, bi, 255).setUv(QUAD_UV[i][0], QUAD_UV[i][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
                        .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
            }
        }
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    private static double spread(double amount) {
        return (RANDOM.nextDouble() - 0.5) * 2 * amount;
    }

    /**
     * Hot sparks thrown from a point of contact (a grinding wheel, a hammer blow): streaks flying mostly along
     * {@code direction}, falling, white-yellow when struck and cooling to orange.
     */
    public static void hotSparks(Vec3 at, Vec3 direction, double spreadAmount, int count, double speed) {
        for (int i = 0; i < count; i++) {
            Vec3 dir = direction.add(spread(spreadAmount), spread(spreadAmount), spread(spreadAmount)).normalize();
            double v = speed * (0.55 + RANDOM.nextDouble() * 0.7);
            float warm = RANDOM.nextFloat();
            spark(at.add(spread(0.012), spread(0.012), spread(0.012)), dir.scale(v), 0.2 + RANDOM.nextDouble() * 0.35,
                    0.0075f + RANDOM.nextFloat() * 0.006f, 1.0f, 0.78f + 0.2f * warm, 0.45f + 0.4f * warm, 4.5, 0.045);
        }
    }

    /** A spray of hot sparks in every direction over a surface, as from a hammer blow. */
    public static void blowSparks(Vec3 at, int count, double speed) {
        for (int i = 0; i < count; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2;
            Vec3 dir = new Vec3(Math.cos(a), 0.35 + RANDOM.nextDouble() * 0.9, Math.sin(a));
            hotSparks(at, dir, 0.15, 1, speed);
        }
    }

    /** Violet motes coming off an item and falling away: the enchantment leaving it. */
    public static void flakes(Vec3 at, double radius, int count) {
        for (int i = 0; i < count; i++) {
            Vec3 from = at.add(spread(radius), spread(radius * 0.4), spread(radius));
            Vec3 v = new Vec3(spread(0.25), 0.1 + RANDOM.nextDouble() * 0.2, spread(0.25));
            float pick = RANDOM.nextFloat();
            spark(from, v, 0.9 + RANDOM.nextDouble() * 0.7, 0.014f + RANDOM.nextFloat() * 0.014f,
                    0.75f + 0.25f * pick, 0.4f + 0.3f * pick, 1.0f, 0.5, 0);
        }
    }

    /**
     * A fan of hot sparks thrown up off a point of contact (a grinding wheel, a saw): mostly upwards, leaning
     * {@code lean}, spread sideways along {@code across}. Upwards, not straight away from the camera, or the
     * streaks shrink into dots on screen.
     */
    public static void fountain(Vec3 at, Vec3 lean, Vec3 across, int count, double speed) {
        for (int i = 0; i < count; i++) {
            Vec3 dir = new Vec3(0, 1, 0).add(lean).add(across.scale(spread(0.75)));
            hotSparks(at, dir, 0.18, 1, speed);
        }
    }

    /** Soft motes of a colour drifting up from around a point (steam glow, a material's sheen). */
    public static void motes(Vec3 at, double radius, int count, float r, float g, float b, double rise) {
        for (int i = 0; i < count; i++) {
            spark(at.add(spread(radius), spread(radius * 0.3), spread(radius)), new Vec3(spread(0.04), rise * (0.6 + RANDOM.nextDouble() * 0.6), spread(0.04)),
                    0.6 + RANDOM.nextDouble() * 0.6, 0.008f + RANDOM.nextFloat() * 0.01f, r, g, b, 0, 0);
        }
    }

    /** Steam off hot metal: faint grey wisps of light rising and spreading. */
    public static void steam(Vec3 at, double radius, int count) {
        for (int i = 0; i < count; i++) {
            float k = 0.16f + RANDOM.nextFloat() * 0.1f;
            spark(at.add(spread(radius), 0, spread(radius)), new Vec3(spread(0.06), 0.18 + RANDOM.nextDouble() * 0.16, spread(0.06)),
                    0.9 + RANDOM.nextDouble() * 0.6, 0.028f + RANDOM.nextFloat() * 0.025f, k, k, k * 1.05f, -0.05, 0);
        }
    }

    // ---- props cut from the block atlas ------------------------------------------------------------

    public static TextureAtlasSprite sprite(String blockTexture) {
        return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(Identifier.withDefaultNamespace(blockTexture));
    }

    public static VertexConsumer atlas(SceneBuffers buffers) {
        return buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS));
    }

    /**
     * A box with one texture on all faces: {@code center} plus the half-extent vectors {@code ax, ay, az}
     * (mutually perpendicular). Each face shows a {@code texels}-wide square of the sprite.
     */
    public static void box(PoseStack pose, VertexConsumer vc, Vec3 cam, Vec3 center, Vec3 ax, Vec3 ay, Vec3 az, TextureAtlasSprite sprite,
            float texels, int light) {
        Vec3 c = center.subtract(cam);
        face(pose, vc, c, ax, ay, az, sprite, texels, light);
        face(pose, vc, c, ax.scale(-1), az, ay, sprite, texels, light);
        face(pose, vc, c, ay, az, ax, sprite, texels, light);
        face(pose, vc, c, ay.scale(-1), ax, az, sprite, texels, light);
        face(pose, vc, c, az, ax, ay, sprite, texels, light);
        face(pose, vc, c, az.scale(-1), ay, ax, sprite, texels, light);
    }

    /** The face of a box on the side of {@code out}, spanned by {@code u} and {@code v} (half extents). */
    private static void face(PoseStack pose, VertexConsumer vc, Vec3 c, Vec3 out, Vec3 u, Vec3 v, TextureAtlasSprite sprite, float texels,
            int light) {
        Vec3 n = out.normalize();
        // wind the face so it shows from outside
        if (u.cross(v).dot(n) < 0) {
            Vec3 t = u;
            u = v;
            v = t;
        }
        Vec3 o = c.add(out);
        Vec3[] p = {o.subtract(u).subtract(v), o.add(u).subtract(v), o.add(u).add(v), o.subtract(u).add(v)};
        float t = texels / 16f;
        float[][] uv = {{0, t}, {t, t}, {t, 0}, {0, 0}};
        for (int i = 0; i < 4; i++) {
            vc.addVertex(pose.last(), (float) p[i].x, (float) p[i].y, (float) p[i].z).setColor(255, 255, 255, 255)
                    .setUv(sprite.getU(uv[i][0]), sprite.getV(uv[i][1])).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                    .setNormal(pose.last(), (float) n.x, (float) n.y, (float) n.z);
        }
    }
}
