package dev.dyslexic.cinematic.client;

import net.minecraft.world.phys.Vec3;

/** Easing curves and a critically damped spring. */
public final class Anim {
    private Anim() {}

    public static double clamp01(double t) {
        return t < 0 ? 0 : t > 1 ? 1 : t;
    }

    /** Remaps t from [a, b] onto [0, 1], clamped. */
    public static double window(double t, double a, double b) {
        return clamp01((t - a) / (b - a));
    }

    public static double smoother(double t) {
        t = clamp01(t);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    public static double inOutCubic(double t) {
        t = clamp01(t);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    public static double inOutSine(double t) {
        return -(Math.cos(Math.PI * clamp01(t)) - 1) / 2;
    }

    public static double outCubic(double t) {
        t = clamp01(t);
        return 1 - Math.pow(1 - t, 3);
    }

    public static double inCubic(double t) {
        t = clamp01(t);
        return t * t * t;
    }

    public static double outBack(double t, double overshoot) {
        t = clamp01(t);
        double c3 = overshoot + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + overshoot * Math.pow(t - 1, 2);
    }

    /** sin hump: 0 at both ends, 1 in the middle. */
    public static double hump(double t) {
        return Math.sin(Math.PI * clamp01(t));
    }

    public static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    /** Frame-rate independent exponential approach. */
    public static double approach(double current, double target, double rate, double dt) {
        return target + (current - target) * Math.exp(-rate * dt);
    }

    /** Critically damped spring in 3D, stepped exactly for any frame time. */
    public static final class Spring3 {
        public double x, y, z;
        private double vx, vy, vz;

        public void snap(Vec3 p) {
            x = p.x;
            y = p.y;
            z = p.z;
            vx = vy = vz = 0;
        }

        public Vec3 pos() {
            return new Vec3(x, y, z);
        }

        public double speed() {
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        }

        public void update(Vec3 target, double omega, double dt) {
            double e = Math.exp(-omega * dt);
            double dx = x - target.x, tx = (vx + omega * dx) * dt;
            vx = (vx - omega * tx) * e;
            x = target.x + (dx + tx) * e;
            double dy = y - target.y, ty = (vy + omega * dy) * dt;
            vy = (vy - omega * ty) * e;
            y = target.y + (dy + ty) * e;
            double dz = z - target.z, tz = (vz + omega * dz) * dt;
            vz = (vz - omega * tz) * e;
            z = target.z + (dz + tz) * e;
        }
    }
}
