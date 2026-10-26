package dev.dyslexic.cinematic.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Whether a scene can be played where the player is, decided before it starts, the same way for every station.
 * The camera stays tied to the player's real head, so a scene only works when the body can actually get to the work:
 * <ul>
 *     <li>the way to the place to stand has ground under every step, room for the body and nothing that burns or
 *     hurts (otherwise the player stays where they are);</li>
 *     <li>from where the player will stand, the station's work zone (a table top, a furnace front) is within reach:
 *     a work surface below the eyes, a work face up to a little over head height, or down by the feet, bending to it
 *     (a block below the player's level);</li>
 *     <li>the whole work zone is open and in view from where the camera will be: a furnace whose front is hidden
 *     behind the player's own floor, or a table with something lying on its top, does not get a scene;</li>
 *     <li>the player is not standing on the block itself.</li>
 * </ul>
 * When it does not work, the block's own window opens instead, as vanilla would open it.
 */
public final class Reach {
    private Reach() {}

    /** How high over the feet a work surface can be: still seen from above by a head raised a little. */
    static final double SURFACE_HIGH = 1.42;
    /** How high a work face (a furnace front) can be: looked up at, reached with the arm up. */
    static final double FACE_HIGH = 1.85;
    /** How far below the feet the work can be: kneeling down to it. */
    static final double LOW = -0.6;
    /** How far the work can be from where the player stands, horizontally. */
    static final double FAR = 1.5;
    /** How much further out than its usual place the player may stand at a block (past a pit before it, say). */
    static final double FURTHER_OUT = 0.9;
    /** How far round obstacles (in blocks each way) a way to the block is looked for. */
    static final int ROUTE_RADIUS = 10;
    /**
     * How far round from straight in front the player may stand to work beside a block, in degrees, in the order tried:
     * well to the side first, so the arms come in from the side rather than over what is in front (lava, a pit).
     */
    static final double[] BESIDE_ANGLES = {55, 40, 70};
    /** How far from the middle of the work the player may stand beside it, nearest first. */
    static final double[] BESIDE_REACH = {0.95, 1.1, 1.25, 1.4};

    /** How far from the body anything that burns or hurts has to stay. */
    static final double HARM_MARGIN = 0.12;

    /** How far the camera may leave the head: leaning over the work, bending down to it, a little onto the toes. */
    static final double CAMERA_AHEAD = 1.05, CAMERA_DOWN = 0.95, CAMERA_UP = 0.16;
    /**
     * Leaning over low work: how far forward the head goes for every block it cannot bend lower, how close it may get to
     * the point it looks at, and how much it comes up again.
     */
    static final double LEAN_OVER = 1.2, LEAN_OVER_GAP = 0.45, LEAN_OVER_RISE = 0.35;

    /** Why a scene cannot be played where the player is, in words the player is shown ({@link ReachNotice}). */
    public enum Reason {
        /** The work is above what the player can reach or see from above. */
        TOO_HIGH,
        /** The work is down below the feet, past bending down to it. */
        TOO_LOW,
        /** The work is too far off and the player can walk up to it. */
        TOO_FAR,
        /** The usual place to stand cannot be walked to (a drop, a wall, fire), and the work is out of reach from here. */
        NO_WAY,
        /** The player stands on the block itself. */
        ON_TOP,
        /** The player stands beside or behind the side the block is worked from. */
        WRONG_SIDE,
        /** Something lies on or in front of the work: {@link Plan#blocker} names it. */
        COVERED,
        /** The way in was clear, but the player walked into something on it. */
        BLOCKED,
        /** No level ground by the fire to sit on. */
        NO_SEAT
    }

    /**
     * The way in and where the player ends up, or that the scene cannot be played here: {@code why} for the log,
     * {@code reason} (and the {@code blocker} in the way, when that is the reason) for the player.
     */
    public record Plan(boolean scene, List<Vec3> path, Vec3 feet, String why, @Nullable Reason reason, @Nullable Component blocker) {}

    public static Plan plan(ClientStation station, LocalPlayer player) {
        Level level = player.level();
        List<Vec3> usual = station.approach(player);
        Plan there = null;
        if (usual.isEmpty() || walkable(level, player, usual)) {
            there = judge(station, player, usual, false);
            if (there.scene()) {
                return there;
            }
        }
        // the usual place cannot be stood at or walked to, or the work cannot be done from it: another place in front
        // of the work, a little further out or to the side (the far edge of a pit before a sunk furnace), walked to round
        // whatever is in the way
        if (!(station instanceof CampfireStation)) {
            Vec3 stand = usual.isEmpty() ? player.position() : usual.get(usual.size() - 1);
            Routes routes = null;
            for (Vec3 feet : candidates(station, stand)) {
                if (!firm(level, player, feet) || !judge(station, player, List.of(feet), false).scene()) {
                    continue;
                }
                if (routes == null) {
                    routes = new Routes(level, player);
                }
                List<Vec3> path = routes.to(feet);
                if (path != null) {
                    return new Plan(true, path, feet, "", null, null);
                }
            }
        }
        // nowhere better: stay put, and see whether the work can be reached from here
        return there != null ? there : judge(station, player, List.of(), true);
    }

    /**
     * Standing beside the work rather than straight in front of it, turned towards it (lava or a pit right in front, a
     * way round at the side): places round the work at {@link #BESIDE_ANGLES} from straight out and within reach, the
     * nearer place first at each angle, each with the work in view and a safe way to it. The station is left
     * turned ({@link ClientStation#swing}) when one is found.
     */
    /** A client-only scene must work from the actual feet and through a clear line of sight. */
    public static Plan planHere(ClientStation station, LocalPlayer player) {
        Plan plan = judge(station, player, List.of(), false);
        if (!plan.scene()) return plan;
        Vec3 eye = player.getEyePosition();
        AABB own = new AABB(station.pos);
        for (Vec3 point : station.workSamples()) {
            BlockHitResult hit = player.level().clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() != HitResult.Type.MISS && !(own.contains(point) && hit.getBlockPos().equals(station.pos)))
                return new Plan(false, List.of(), player.position(), "work hidden from the real eye position", Reason.COVERED,
                        player.level().getBlockState(hit.getBlockPos()).getBlock().getName());
        }
        return plan;
    }

    public static Plan planBeside(ClientStation station, LocalPlayer player) {
        Level level = player.level();
        Vec3 work = station.workPoint();
        Vec3 base = new Vec3(work.x, player.getY(), work.z);
        Routes routes = null;
        for (double angle : BESIDE_ANGLES) {
            for (double reach : BESIDE_REACH) {
                for (double sign : new double[]{1, -1}) {
                    double swing = angle * sign;
                    Vec3 feet = base.add(ClientStation.turned(station.side(), swing).scale(reach));
                    station.swing = swing;
                    if (!firm(level, player, feet) || !judge(station, player, List.of(feet), false).scene()) {
                        continue;
                    }
                    if (routes == null) {
                        routes = new Routes(level, player);
                    }
                    List<Vec3> path = routes.to(feet);
                    if (path != null) {
                        return new Plan(true, path, feet, "", null, null);
                    }
                }
            }
        }
        station.swing = 0;
        return judge(station, player, List.of(), true);
    }

    /** Whether the work can be done standing where {@code path} ends (or here), and if not, why. */
    private static Plan judge(ClientStation station, LocalPlayer player, List<Vec3> path, boolean stayed) {
        Vec3 feet = path.isEmpty() ? player.position() : path.get(path.size() - 1);
        Vec3 work = station.workPoint();
        double high = work.y - feet.y;
        String why;
        Reason reason = null;
        Component blocker = null;
        if (!station.workable(feet)) {
            why = String.format("the work is out of reach: %.2f over the feet, %.2f away", high, Math.hypot(work.x - feet.x, work.z - feet.z));
            if (station instanceof CampfireStation) {
                reason = stayed ? Reason.NO_WAY : Reason.NO_SEAT;
            } else if (high > (station.workOnTop() ? SURFACE_HIGH : FACE_HIGH)) {
                reason = Reason.TOO_HIGH;
            } else if (high < LOW) {
                reason = Reason.TOO_LOW;
            } else {
                reason = stayed ? Reason.NO_WAY : Reason.TOO_FAR;
            }
        } else if (standingOn(station.pos, feet)) {
            why = "standing on the block itself";
            reason = Reason.ON_TOP;
        } else if (!station.inFront(feet)) {
            why = "standing beside or behind the side it is worked from (" + station.sideDirection().getName() + ")";
            reason = Reason.WRONG_SIDE;
        } else {
            BlockHitResult hit = hidden(station, player, feet);
            if (hit == null) {
                why = "";
            } else {
                BlockPos at = hit.getBlockPos();
                why = String.format("the work zone is not in view: %s at %s is in the way", player.level().getBlockState(at).getBlock().getName().getString(),
                        at.toShortString());
                // the block itself in the way: its working side is turned away or walled in
                reason = at.equals(station.pos) ? Reason.WRONG_SIDE : Reason.COVERED;
                blocker = player.level().getBlockState(at).getBlock().getName();
            }
        }
        if (stayed && !why.isEmpty()) {
            why = "no safe way to the usual place, staying put; " + why;
            if (reason == Reason.WRONG_SIDE) {
                // its working side could not be walked up to from anywhere
                reason = Reason.NO_WAY;
            }
        }
        return new Plan(why.isEmpty(), path, feet, why, reason, blocker);
    }

    /**
     * Places to stand, the usual one first and then the nearest to it: further out from the work (up to {@link #FURTHER_OUT}) and a
     * little to either side, at the player's own level.
     */
    private static List<Vec3> candidates(ClientStation station, Vec3 stand) {
        Vec3 out = station.side(), across = station.right();
        List<Vec3> list = new ArrayList<>();
        for (double o = 0; o <= FURTHER_OUT + 1e-6; o += 0.15) {
            for (double a : new double[]{0, -0.25, 0.25}) {
                // (the usual place itself too: walked to round what is in the way)
                list.add(stand.add(out.scale(o)).add(across.scale(a)));
            }
        }
        list.sort(Comparator.comparingDouble(p -> p.distanceToSqr(stand)));
        return list;
    }

    /** A place to stand with ground right under the middle of the feet, not just under an edge of the body. */
    static boolean firm(Level level, Entity player, Vec3 feet) {
        AABB under = new AABB(feet.x - 0.1, feet.y - 0.55, feet.z - 0.1, feet.x + 0.1, feet.y + 0.01, feet.z + 0.1);
        return standable(level, player, feet) && !level.noCollision(player, under);
    }

    /**
     * Ways from the player to places at their own level, round walls, pits and fire: the blocks within {@link #ROUTE_RADIUS}
     * that can be stood on, searched outwards from where the player is (once, for every place asked about), and a way
     * found pulled straight wherever it can go straight.
     */
    private static final class Routes {
        private final Level level;
        private final Entity player;
        private final Vec3 start;
        private final BlockPos origin;
        /** Each block reached, and the one it was reached from (the start's is itself). */
        private final Map<Long, Long> from = new HashMap<>();
        private final ArrayDeque<BlockPos> open = new ArrayDeque<>();

        Routes(Level level, Entity player) {
            this.level = level;
            this.player = player;
            this.start = player.position();
            this.origin = BlockPos.containing(start.x, start.y + 0.1, start.z);
            from.put(origin.asLong(), origin.asLong());
            open.add(origin);
        }

        /** A way to {@code goal}, or null when there is none nearby. */
        @Nullable
        List<Vec3> to(Vec3 goal) {
            if (clear(level, player, start, goal)) {
                return List.of(goal);
            }
            BlockPos goalBlock = BlockPos.containing(goal.x, origin.getY() + 0.1, goal.z);
            // search on until the goal's block is reached (what was searched is kept for the next goal)
            while (!from.containsKey(goalBlock.asLong()) && !open.isEmpty()) {
                BlockPos at = open.poll();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx != 0 || dz != 0) && step(at, dx, dz)) {
                            BlockPos next = at.offset(dx, 0, dz);
                            from.put(next.asLong(), at.asLong());
                            open.add(next);
                        }
                    }
                }
            }
            if (!from.containsKey(goalBlock.asLong())) {
                return null;
            }
            List<Vec3> points = new ArrayList<>();
            points.add(goal);
            for (long at = from.get(goalBlock.asLong()); at != origin.asLong(); at = from.get(at)) {
                points.add(middle(BlockPos.of(at)));
            }
            points.add(start);
            Collections.reverse(points);
            // pull the way straight: from each point on to the furthest one that can be walked to directly
            List<Vec3> path = new ArrayList<>();
            int i = 0;
            while (i < points.size() - 1) {
                int j = points.size() - 1;
                while (j > i + 1 && !clear(level, player, points.get(i), points.get(j))) {
                    j--;
                }
                if (!clear(level, player, points.get(i), points.get(j))) {
                    return null;
                }
                path.add(points.get(j));
                i = j;
            }
            return path;
        }

        /** Whether one may step from a block to its neighbour (diagonally only when both blocks beside are open too). */
        private boolean step(BlockPos at, int dx, int dz) {
            BlockPos next = at.offset(dx, 0, dz);
            if (from.containsKey(next.asLong()) || Math.abs(next.getX() - origin.getX()) > ROUTE_RADIUS
                    || Math.abs(next.getZ() - origin.getZ()) > ROUTE_RADIUS || !standable(level, player, middle(next))) {
                return false;
            }
            return dx == 0 || dz == 0
                    || standable(level, player, middle(at.offset(dx, 0, 0))) && standable(level, player, middle(at.offset(0, 0, dz)));
        }

        private Vec3 middle(BlockPos block) {
            return new Vec3(block.getX() + 0.5, start.y, block.getZ() + 0.5);
        }
    }

    /** The feet over the block's own top: the work would be under the player's shoes. */
    static boolean standingOn(BlockPos pos, Vec3 feet) {
        return Math.abs(feet.x - (pos.getX() + 0.5)) < 0.75 && Math.abs(feet.z - (pos.getZ() + 0.5)) < 0.75 && feet.y > pos.getY() + 0.4;
    }

    /**
     * What hides a point of the work zone ({@link ClientStation#workSamples}) from where the camera will be, or null
     * when all of them can be seen. Anything in the way counts, even the block itself (a point inside the block's own
     * space, as among the arms of a brewing stand, may be reached through it).
     */
    @Nullable
    static BlockHitResult hidden(ClientStation station, LocalPlayer player, Vec3 feet) {
        CameraRig.Pose lean = station.view();
        double height = station.crouchesFor(lean.pos().y - feet.y) ? 1.27 : 1.62;
        Vec3 from = cameraPose(station, lean, feet.add(0, height, 0)).pos();
        AABB own = new AABB(station.pos);
        for (Vec3 to : station.workSamples()) {
            BlockHitResult hit = player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || own.contains(to) && hit.getBlockPos().equals(station.pos)) {
                continue;
            }
            return hit;
        }
        return null;
    }

    /** The usual test of {@link ClientStation#workable}: the work zone within reach and in view from {@code feet}. */
    static boolean inReach(Vec3 work, boolean surface, Vec3 feet) {
        double h = work.y - feet.y;
        return h >= LOW && h <= (surface ? SURFACE_HIGH : FACE_HIGH) && Math.hypot(work.x - feet.x, work.z - feet.z) <= FAR;
    }

    /** Every step of the way, sampled every fifth of a block, is a place to stand. */
    static boolean walkable(Level level, Entity player, List<Vec3> path) {
        Vec3 from = player.position();
        for (Vec3 to : path) {
            if (!clear(level, player, from, to)) {
                return false;
            }
            from = to;
        }
        return true;
    }

    /** Every step from {@code from} to {@code to}, sampled every fifth of a block, is a place to stand. */
    static boolean clear(Level level, Entity player, Vec3 from, Vec3 to) {
        double length = Math.hypot(to.x - from.x, to.z - from.z);
        int steps = Math.max(1, (int) Math.ceil(length / 0.2));
        for (int i = 1; i <= steps; i++) {
            if (!standable(level, player, Anim.lerp(from, to, i / (double) steps))) {
                return false;
            }
        }
        return true;
    }

    /** Room for the body (a low step underfoot is climbed by itself), ground no more than a slab below, nothing harmful. */
    static boolean standable(Level level, Entity player, Vec3 feet) {
        double w = player.getBbWidth() / 2 - 0.02;
        AABB body = new AABB(feet.x - w, feet.y + 0.6, feet.z - w, feet.x + w, feet.y + player.getBbHeight(), feet.z + w);
        AABB under = new AABB(feet.x - w, feet.y - 0.55, feet.z - w, feet.x + w, feet.y + 0.01, feet.z + w);
        if (!level.noCollision(player, body) || level.noCollision(player, under)) {
            return false;
        }
        // nothing that burns or hurts anywhere the body or the feet touch, with a little room to spare
        double m = w + HARM_MARGIN;
        for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(feet.x - m, feet.y - 0.5, feet.z - m),
                BlockPos.containing(feet.x + m, feet.y + 1.1, feet.z + m))) {
            if (!harmless(level.getBlockState(at))) {
                return false;
            }
        }
        return true;
    }

    private static boolean harmless(BlockState state) {
        return !(state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE)
                || state.getFluidState().is(FluidTags.LAVA));
    }

    /**
     * The station's camera pose kept within what the head can do from where the player really is ({@code eye}):
     * when it would have to float up or lean further, it stops at the limit and turns to keep looking at the same
     * point. Work far below (at the foot of the floor the player stands on) is bent over, as in life: the lower the
     * camera would have to go past the limit, the further forward over the work the head leans instead, never closer
     * than {@link #LEAN_OVER_GAP} to the point it looks at.
     */
    static CameraRig.Pose tethered(CameraRig.Pose pose, Vec3 eye, Vec3 aimedAt) {
        Vec3 off = pose.pos().subtract(eye);
        double ox = off.x, oz = off.z;
        double dy = Mth.clamp(off.y, -CAMERA_DOWN, CAMERA_UP);
        double tooLow = -CAMERA_DOWN - off.y;
        if (tooLow > 0) {
            double tx = aimedAt.x - pose.pos().x, tz = aimedAt.z - pose.pos().z;
            double toward = Math.hypot(tx, tz);
            double move = Math.min(tooLow * LEAN_OVER, toward - LEAN_OVER_GAP);
            if (move > 0) {
                ox += tx / toward * move;
                oz += tz / toward * move;
            }
            // stretching forward over the work, the head comes up off the floor a little
            dy += Math.min(tooLow, LEAN_OVER_RISE);
        }
        double ahead = Math.hypot(ox, oz);
        double scale = ahead > CAMERA_AHEAD ? CAMERA_AHEAD / ahead : 1;
        if (scale == 1 && dy == off.y) {
            return pose;
        }
        Vec3 at = eye.add(ox * scale, dy, oz * scale);
        Vec3 look = aimedAt.subtract(at);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(at, yaw, pitch, pose.roll());
    }

    /**
     * Where the scene's camera is with the head at {@code eye}: the station's pose held within what the head can do
     * ({@link #tethered}; a dramatic move may let it go, {@link ClientStation#freeCamera}), and taken only as far from the
     * eyes as the player set ({@link Settings#leanStrength}), still looking at the same point.
     */
    static CameraRig.Pose cameraPose(ClientStation station, CameraRig.Pose pose, Vec3 eye) {
        Vec3 aim = lookPoint(pose, station.center());
        CameraRig.Pose held = station.freeCamera() ? pose : tethered(pose, eye, aim);
        double strength = Settings.leanStrength();
        if (strength >= 0.999) {
            return held;
        }
        Vec3 at = Anim.lerp(eye, held.pos(), strength);
        Vec3 look = aim.subtract(at);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(at, yaw, pitch, held.roll() * (float) strength);
    }

    /** The point a pose looks at, as far along its view as the station is. */
    static Vec3 lookPoint(CameraRig.Pose pose, Vec3 station) {
        double distance = Math.max(0.3, pose.pos().distanceTo(station));
        return pose.pos().add(Vec3.directionFromRotation(pose.pitch(), pose.yaw()).scale(distance));
    }
}
