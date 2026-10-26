package dev.dyslexic.cinematic.client;

import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The camera of the scene. During the approach it glides from the player's eyes to the station's leaning pose;
 * while working it breathes and drifts a little towards the cursor; when leaving it glides back to wherever the
 * player's eyes are by then, so control resumes without a cut.
 */
public final class CameraRig {
    private CameraRig() {}

    public record Pose(Vec3 pos, float yaw, float pitch, float roll) {}

    @Nullable
    private static Pose last;
    /** 0 = player's own view, 1 = fully leaning; drives the field of view too. */
    private static double weight;
    private static double parallaxX, parallaxY;

    public static double weight() {
        return weight;
    }

    @Nullable
    public static Pose last() {
        return last;
    }

    /** Called at the end of {@code Camera.setup}; returns the pose to use instead, or null. */
    @Nullable
    public static Pose override(Vec3 livePos, float liveYaw, float livePitch) {
        ClientStation station = Scene.station;
        if (!Scene.active() || station == null) {
            last = null;
            weight = 0;
            return null;
        }
        Pose live = new Pose(livePos, liveYaw, livePitch, 0);
        Pose pose;
        switch (Scene.phase) {
            case INTRO -> {
                double u = Scene.introProgress();
                // the camera stays with the eyes while the player walks, and leans in only once they have arrived
                double ep = Scene.arrived() ? Anim.inOutCubic(Anim.window(u, Scene.walkShare, 1.0)) : 0;
                double er = Scene.arrived() ? Anim.inOutCubic(Anim.window(u, Scene.walkShare, 0.82)) : 0;
                Pose lean = tether(breathe(station.view(), 0), livePos, station);
                // moving over from another station, the camera sets off from where it was (back to the head first if
                // the player has to walk over)
                Pose start = Scene.transitionFrom == null ? live : blend(Scene.transitionFrom, live, Scene.released());
                Vec3 pos = Anim.lerp(start.pos(), lean.pos(), ep).add(0, (Scene.transition ? 0.02 : 0.045) * Anim.hump(u), 0);
                float turn = Mth.wrapDegrees(lean.yaw() - start.yaw());
                float yaw = start.yaw() + turn * (float) er;
                float pitch = Mth.lerp((float) er, start.pitch(), lean.pitch());
                float swing = (float) (Anim.hump(u) * Mth.clamp(turn * 0.04, -1.6, 1.6));
                pose = new Pose(pos, yaw, pitch, swing + Mth.lerp((float) er, start.roll(), lean.roll()));
                weight = Scene.transition ? Math.max(ep, 1 - Scene.released()) : ep;
            }
            case ACTIVE -> {
                pose = FreeLook.apply(kick(tether(breathe(station.view(), Anim.smoother(Scene.time / 0.6)), livePos, station), station.cameraKick()));
                weight = 1;
            }
            default -> {
                Pose from = Scene.outroFrom != null ? Scene.outroFrom : station.view();
                double u = Scene.time / Scene.OUTRO_LENGTH;
                double ep = Anim.inOutCubic(Anim.window(u, 0.14, 1.0));
                double er = Anim.inOutCubic(Anim.window(u, 0.2, 1.0));
                Vec3 pos = Anim.lerp(from.pos(), live.pos(), ep).add(0, 0.03 * Anim.hump(ep), 0);
                float yaw = from.yaw() + Mth.wrapDegrees(live.yaw() - from.yaw()) * (float) er;
                float pitch = Mth.lerp((float) er, from.pitch(), live.pitch());
                float roll = Mth.lerp((float) er, from.roll(), 0);
                pose = new Pose(pos, yaw, pitch, roll);
                weight = (1 - ep) * Scene.outroWeight;
            }
        }
        if (Scene.clientOnly()) pose = constrain(pose, livePos);
        last = pose;
        return pose;
    }

    /** Between two poses, turning the short way round. */
    private static Pose blend(Pose a, Pose b, double k) {
        float f = (float) k;
        return new Pose(Anim.lerp(a.pos(), b.pos(), k), a.yaw() + Mth.wrapDegrees(b.yaw() - a.yaw()) * f, Mth.lerp(f, a.pitch(), b.pitch()),
                Mth.lerp(f, a.roll(), b.roll()));
    }

    /** The station's pose kept within what the player's head can do from where the player really is. */
    private static Pose tether(Pose pose, Vec3 eye, ClientStation station) {
        Pose held = Reach.cameraPose(station, pose, eye);
        return Scene.clientOnly() ? constrain(held, eye) : held;
    }

    /** The finished shot, including its intro and exit bob, stays within the player's real viewpoint. */
    private static Pose constrain(Pose pose, Vec3 eye) {
        Vec3 offset = pose.pos().subtract(eye);
        Vec3 end = eye.add(offset.length() > 0.35 ? offset.normalize().scale(0.35) : offset);
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            var hit = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3 travel = hit.getLocation().subtract(eye);
                end = eye.add(travel.length() > 0.08 ? travel.normalize().scale(travel.length() - 0.08) : Vec3.ZERO);
            }
        }
        return new Pose(end, pose.yaw(), pose.pitch(), pose.roll());
    }

    /** A dramatic moment: a tremor that dies down with it. */
    private static Pose kick(Pose pose, double kick) {
        if (kick <= 0.001 || !Settings.cameraShake()) {
            return pose;
        }
        double t = Scene.clock();
        float shakeYaw = (float) ((Math.sin(t * 47) + Math.sin(t * 31 + 1)) * 0.18 * kick);
        float shakePitch = (float) ((Math.sin(t * 53 + 2) + Math.sin(t * 29)) * 0.14 * kick);
        return new Pose(pose.pos(), pose.yaw() + shakeYaw, pose.pitch() + shakePitch, pose.roll() + shakeYaw * 0.4f);
    }

    /** Slow breathing, plus a slight drift towards the cursor once the hands are at work. */
    private static Pose breathe(Pose pose, double parallax) {
        if (!Settings.livingCamera()) {
            parallaxX = parallaxY = 0;
            return pose;
        }
        double t = Scene.clock();
        double dt = Scene.frameDt;
        double[] ndc = Scene.phase == Scene.Phase.ACTIVE ? Picking.mouseNdc() : new double[]{0, 0};
        parallaxX = Anim.approach(parallaxX, Mth.clamp(ndc[0], -1, 1), 4, dt);
        parallaxY = Anim.approach(parallaxY, Mth.clamp(ndc[1], -1, 1), 4, dt);
        Vec3 pos = pose.pos().add(0, Math.sin(t * 1.3) * 0.0035, 0);
        float yaw = pose.yaw() + (float) (Math.sin(t * 0.61) * 0.16 + parallaxX * 1.1 * parallax);
        float pitch = pose.pitch() + (float) (Math.sin(t * 0.83 + 1.1) * 0.13 - parallaxY * 0.8 * parallax);
        return new Pose(pos, yaw, pitch, pose.roll());
    }

    public static double fov(double vanilla) {
        ClientStation station = Scene.station;
        if (station == null) {
            return vanilla;
        }
        double punch = Settings.cameraShake() ? 7 * station.cameraKick() : 0;
        // looking round from behind, the view widens back to the player's own
        return Mth.lerp(weight * Settings.sceneZoom() * (1 - FreeLook.amount()), vanilla, station.leanFov()) - punch * (1 - FreeLook.amount());
    }

    public static void reset() {
        last = null;
        weight = 0;
        parallaxX = parallaxY = 0;
    }
}
