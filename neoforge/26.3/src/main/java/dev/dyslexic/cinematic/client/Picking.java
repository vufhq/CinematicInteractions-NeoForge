package dev.dyslexic.cinematic.client;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Turns the mouse cursor into a world ray (and world points into screen points) with this frame's matrices. */
public final class Picking {
    private Picking() {}

    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Matrix4f INVERSE = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static boolean valid;

    /** At the start of the world's frame: its projection, the camera's turn and where the camera stands. */
    public static void capture(Matrix4f projection, Matrix4f view, Vec3 cameraAt) {
        VIEW_PROJ.set(projection).mul(view);
        VIEW_PROJ.invert(INVERSE);
        camera = cameraAt;
        valid = true;
    }

    public static Vec3 camera() {
        return camera;
    }

    /** Cursor position in normalised device coordinates. */
    public static double[] mouseNdc() {
        Minecraft mc = Minecraft.getInstance();
        double w = Math.max(1, mc.getWindow().getScreenWidth());
        double h = Math.max(1, mc.getWindow().getScreenHeight());
        return new double[]{mc.mouseHandler.xpos() / w * 2 - 1, 1 - mc.mouseHandler.ypos() / h * 2};
    }

    /** Cursor position in GUI pixels. */
    public static double[] mouseGui() {
        Minecraft mc = Minecraft.getInstance();
        return new double[]{mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / Math.max(1, mc.getWindow().getScreenWidth()),
                mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / Math.max(1, mc.getWindow().getScreenHeight())};
    }

    /** The cursor's ray in the world: {origin, unit direction}, or null before the first frame. */
    @Nullable
    public static Vec3[] cursorRay() {
        if (!valid) {
            return null;
        }
        double[] ndc = mouseNdc();
        Vector4f near = INVERSE.transform(new Vector4f((float) ndc[0], (float) ndc[1], -1, 1));
        Vector4f far = INVERSE.transform(new Vector4f((float) ndc[0], (float) ndc[1], 1, 1));
        near.div(near.w);
        far.div(far.w);
        Vec3 origin = camera.add(near.x, near.y, near.z);
        Vec3 dir = new Vec3(far.x - near.x, far.y - near.y, far.z - near.z).normalize();
        return new Vec3[]{origin, dir};
    }

    /** Where the cursor ray crosses the horizontal plane y = planeY, or null. */
    @Nullable
    public static Vec3 cursorOnPlane(double planeY) {
        return cursorOnPlane(new Vec3(0, planeY, 0), new Vec3(0, 1, 0));
    }

    /** Where the cursor ray crosses the plane through {@code point} with {@code normal}, or null. */
    @Nullable
    public static Vec3 cursorOnPlane(Vec3 point, Vec3 normal) {
        if (!valid) {
            return null;
        }
        double[] ndc = mouseNdc();
        Vector4f near = INVERSE.transform(new Vector4f((float) ndc[0], (float) ndc[1], -1, 1));
        Vector4f far = INVERSE.transform(new Vector4f((float) ndc[0], (float) ndc[1], 1, 1));
        near.div(near.w);
        far.div(far.w);
        Vec3 origin = camera.add(near.x, near.y, near.z);
        Vec3 dir = new Vec3(far.x - near.x, far.y - near.y, far.z - near.z);
        double denom = dir.dot(normal);
        if (Math.abs(denom) < 1e-9) {
            return null;
        }
        double t = point.subtract(origin).dot(normal) / denom;
        return t < 0 ? null : origin.add(dir.scale(t));
    }

    /** Screen position of a world point in GUI pixels {x, y}, or null when behind the camera. */
    @Nullable
    public static double[] toGui(Vec3 world) {
        if (!valid) {
            return null;
        }
        Vector4f clip = VIEW_PROJ.transform(new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y),
                (float) (world.z - camera.z), 1));
        if (clip.w <= 1e-4) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        double nx = clip.x / clip.w, ny = clip.y / clip.w;
        return new double[]{(nx + 1) / 2 * mc.getWindow().getGuiScaledWidth(), (1 - ny) / 2 * mc.getWindow().getGuiScaledHeight()};
    }
}
