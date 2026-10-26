package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.Cinematic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Showcase only: how evenly a scene moves from frame to frame. Every stretch of one phase at one station is summed up
 * in the log: frame pacing, and for the camera, its turning and both hands how uneven their speed is from one frame to
 * the next ("uneven", in percent of their usual speed) and how often they stall for a frame and then catch up. A
 * motion stepping with game ticks instead of following the frames shows up at once, so a port (other game versions,
 * other loaders) can be held to the same smoothness as the original.
 */
final class FrameProbe {
    private FrameProbe() {}

    /** Per frame: scene clock, camera x y z, yaw, pitch, right hand x y z, left hand x y z. */
    private static final List<double[]> FRAMES = new ArrayList<>();
    private static String segment;
    /**
     * Frames still held up by a showcase screenshot (reading the frame back): left out of the frame pacing and of the
     * evenness, since such a hitch belongs to the walkthrough, not to the scene (where reading back is slow, as from
     * 1.21.6 on, one long step through an easing would otherwise count as uneven motion).
     */
    private static int afterShot;

    static void shotTaken() {
        afterShot = 2;
    }

    static void sample(Camera camera) {
        String key = Scene.active() && Scene.station != null ? Scene.station.kind() + " " + Scene.phase : null;
        if (!Objects.equals(key, segment)) {
            report();
            FRAMES.clear();
            segment = key;
        }
        if (key == null) {
            return;
        }
        Vec3 c = camera.position();
        Vec3 r = Hands.position(true);
        Vec3 l = Hands.position(false);
        FRAMES.add(new double[]{Scene.clock(), afterShot-- > 0 ? Double.NaN : System.nanoTime() / 1e9, c.x, c.y, c.z, camera.yRot(), camera.xRot(), r.x, r.y, r.z,
                l.x, l.y, l.z});
    }

    private static void report() {
        int n = FRAMES.size();
        if (segment == null || n < 12) {
            return;
        }
        double[] dt = java.util.stream.IntStream.range(1, n).mapToDouble(i -> FRAMES.get(i)[1] - FRAMES.get(i - 1)[1])
                .filter(d -> !Double.isNaN(d)).toArray();
        double mean = Arrays.stream(dt).average().orElse(0);
        double sd = Math.sqrt(Arrays.stream(dt).map(d -> (d - mean) * (d - mean)).average().orElse(0));
        double max = Arrays.stream(dt).max().orElse(0);
        Cinematic.LOGGER.info("[Probe] {} frames={} time={} dt={}±{}ms max={}ms | camera {} | turn {} | right hand {} | left hand {}",
                segment, n, String.format("%.2fs", FRAMES.get(n - 1)[0] - FRAMES.get(0)[0]), String.format("%.1f", mean * 1000),
                String.format("%.1f", sd * 1000), String.format("%.0f", max * 1000), evenness(2, false), evenness(5, true), evenness(7, false),
                evenness(10, false));
    }

    /** Unevenness of a 3-vector's (or the two view angles') speed: frame to frame against the speed around it. */
    private static String evenness(int at, boolean angles) {
        int n = FRAMES.size();
        double[] speed = new double[n - 1];
        for (int i = 1; i < n; i++) {
            double[] a = FRAMES.get(i - 1), b = FRAMES.get(i);
            double step = b[0] - a[0];
            double d = angles ? Math.hypot(Mth.wrapDegrees(b[at] - a[at]), b[at + 1] - a[at + 1])
                    : Math.sqrt(sq(b[at] - a[at]) + sq(b[at + 1] - a[at + 1]) + sq(b[at + 2] - a[at + 2]));
            speed[i - 1] = step > 1e-6 && !Double.isNaN(a[1]) && !Double.isNaN(b[1]) ? d / step : -1;
        }
        double usual = Arrays.stream(speed).filter(s -> s >= 0).average().orElse(0);
        if (usual < (angles ? 2 : 0.02)) {
            return "still";
        }
        double uneven = 0;
        int counted = 0, stalls = 0;
        for (int i = 2; i < speed.length - 2; i++) {
            double[] around = {speed[i - 2], speed[i - 1], speed[i + 1], speed[i + 2]};
            if (speed[i] < 0 || Arrays.stream(around).anyMatch(s -> s < 0)) {
                continue;
            }
            Arrays.sort(around);
            double local = (around[1] + around[2]) / 2;
            uneven += Math.abs(speed[i] - local) / usual;
            counted++;
            if (local > usual * 0.5 && speed[i] < local * 0.25) {
                stalls++;
            }
        }
        return counted == 0 ? "-" : String.format("uneven %.1f%% stalls %d", 100 * uneven / counted, stalls);
    }

    private static double sq(double v) {
        return v * v;
    }
}
