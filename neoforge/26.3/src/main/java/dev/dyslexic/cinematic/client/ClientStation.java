package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One kind of scene: where the player stands, how the camera leans in, where the hands rest and reach,
 * what the cursor can touch and what is drawn. {@link Scene} runs the shared approach, work and exit around it.
 */
public abstract class ClientStation {
    public final BlockPos pos;
    protected final Direction side;

    /** Where the working hand should go this frame; null while the cursor is off the station. */
    @Nullable
    public Vec3 aim;

    /**
     * Degrees the player stands turned round the work from straight in front of its side: not 0 when the place right in
     * front cannot be stood at (lava, a pit) and the player works from beside it ({@link Reach}). The camera's pose
     * ({@link #view}) and the body turn with it.
     */
    public double swing;

    protected ClientStation(BlockPos pos, Direction side) {
        this.pos = pos.immutable();
        this.side = side;
    }

    public abstract StationKind kind();

    /** The side of the block the player works from. */
    public Direction sideDirection() {
        return side;
    }

    /** Unit vector from the block towards the player. */
    public Vec3 side() {
        return Vec3.atLowerCornerOf(side.getUnitVec3i());
    }

    /** Unit vector the player faces while working. */
    public Vec3 forward() {
        return Vec3.atLowerCornerOf(side.getOpposite().getUnitVec3i());
    }

    /** The player's right while facing the block. */
    public Vec3 right() {
        return Vec3.atLowerCornerOf(side.getOpposite().getClockWise().getUnitVec3i());
    }

    public Vec3 center() {
        return Vec3.atCenterOf(pos);
    }

    /**
     * The side of a block nearest to the player among those the station may be worked from
     * ({@link StationKind#sides}) that leave room to stand and lean in: first where the player could stand at their own
     * level (the walk in never climbs or drops, so a table at the foot of a raised floor is worked from the floor), then
     * where there is room at the block's level.
     */
    static Direction freeSide(Level level, BlockPos pos, LocalPlayer player, StationKind kind) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        List<Direction> sides = kind.sides(level.getBlockState(pos));
        for (int pass = 0; pass < 2; pass++) {
            Direction best = null;
            double bestScore = -Double.MAX_VALUE;
            for (Direction candidate : sides) {
                double score = candidate.getStepX() * dx + candidate.getStepZ() * dz;
                boolean room = pass == 0
                        ? Reach.standable(level, player, new Vec3(pos.getX() + 0.5 + candidate.getStepX(), player.getY(), pos.getZ() + 0.5 + candidate.getStepZ()))
                        : StationKind.roomAt(level, pos.relative(candidate));
                if (room && score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            if (best != null) {
                return best;
            }
        }
        Direction facing = Direction.fromYRot(player.getYRot()).getOpposite();
        return sides.contains(facing) ? facing : sides.get(0);
    }

    /** The sides a block may be worked from ({@link StationKind#sides}), the one facing the player first. */
    static List<Direction> sidesByNearness(Level level, BlockPos pos, LocalPlayer player, StationKind kind) {
        double dx = player.getX() - (pos.getX() + 0.5);
        double dz = player.getZ() - (pos.getZ() + 0.5);
        List<Direction> sides = new java.util.ArrayList<>(kind.sides(level.getBlockState(pos)));
        sides.sort(java.util.Comparator.comparingDouble(side -> -(side.getStepX() * dx + side.getStepZ() * dz)));
        return sides;
    }

    // ---- staging -------------------------------------------------------------------------------------

    /** Points to walk through on the way in, the last one being where the player stands; empty to stay put. */
    public abstract List<Vec3> approach(LocalPlayer player);

    /** Whether the player crouches down to work here. */
    public boolean crouches() {
        return false;
    }

    /**
     * Whether the player crouches with the camera this high over the feet: a station that crouches does so while its
     * work is low, any station when the work is down by the feet.
     */
    public boolean crouchesFor(double cameraHeight) {
        return crouches() ? cameraHeight < 1.4 : cameraHeight < 1.0;
    }

    /** The middle of where the hands work (a table top, a furnace front): it must be within reach and in view. */
    public Vec3 workPoint() {
        return center();
    }

    /** Whether the work lies on a top surface, seen from above, rather than on a face looked at from the front. */
    public boolean workOnTop() {
        return true;
    }

    /**
     * Points of the work zone that must be in view, nothing in the way, for the scene: the middle of the work and round
     * it, just off a work surface (a table top) or just out of a work face (a furnace front with its openings).
     */
    public List<Vec3> workSamples() {
        Vec3 work = workPoint();
        if (workOnTop()) {
            Vec3 lift = new Vec3(0, 0.04, 0), a = right().scale(0.3), b = side().scale(0.3);
            return List.of(work.add(lift), work.add(a).add(lift), work.subtract(a).add(lift), work.add(b).add(lift), work.subtract(b).add(lift));
        }
        Vec3 out = side().scale(0.04), a = right().scale(0.3), b = new Vec3(0, 0.3, 0);
        return List.of(work.add(out), work.add(a).add(out), work.subtract(a).add(out), work.add(b).add(out), work.subtract(b).add(out));
    }

    /** Whether the work can be done with the player standing at {@code feet} ({@link Reach}). */
    public boolean workable(Vec3 feet) {
        return Reach.inReach(workPoint(), workOnTop(), feet);
    }

    /** Whether {@code feet} are on the side the station is worked from, not beside or behind the work. */
    public boolean inFront(Vec3 feet) {
        Vec3 off = feet.subtract(workPoint());
        return off.x * side().x + off.z * side().z >= 0.2;
    }

    /** The camera stays within reach of the player's head ({@link Reach#tethered}) unless a dramatic move lets it go. */
    public boolean freeCamera() {
        return false;
    }

    public abstract CameraRig.Pose lean();

    /** The camera's pose at work: {@link #lean} turned round the work by {@link #swing}. */
    public final CameraRig.Pose view() {
        CameraRig.Pose lean = lean();
        if (Math.abs(swing) < 1e-3) {
            return lean;
        }
        Vec3 pivot = center();
        Vec3 off = turned(lean.pos().subtract(pivot), swing);
        return new CameraRig.Pose(pivot.add(off), lean.yaw() + (float) swing, lean.pitch(), lean.roll());
    }

    /** A horizontal turn by {@code degrees} the way a yaw grows (a vector pointing along a yaw then points along yaw + degrees). */
    static Vec3 turned(Vec3 v, double degrees) {
        double t = Math.toRadians(degrees);
        double cos = Math.cos(t), sin = Math.sin(t);
        return new Vec3(v.x * cos - v.z * sin, v.y, v.x * sin + v.z * cos);
    }

    /**
     * Whether the scene still works with the player beside the work rather than straight in front of it (a furnace front,
     * a table top seen at an angle); scenes laid out round the camera (the enchanting runes, the lectern's book, the
     * beacon's crown, the stonecutter's dial, the loom's board) need the straight view.
     */
    public boolean worksAtAnAngle() {
        return false;
    }

    public double leanFov() {
        return 66;
    }

    /** Head pitch others see while the player works here. */
    public float bodyPitch() {
        return 50f;
    }

    /** Where a hand rests while it has nothing to do. */
    public abstract Vec3 rest(boolean right);

    /** Signed distance of a point to the left (negative) or right of the block centre, for picking the hand. */
    public double lateral(Vec3 point) {
        return point.subtract(center()).dot(right());
    }

    // ---- interaction ---------------------------------------------------------------------------------

    /** Works out what the cursor points at with this frame's matrices; sets {@link #aim}. */
    public abstract void updateCursor(boolean overPanel);

    public void clearCursor() {
        aim = null;
    }

    /** Where the fist goes so that a held item ends up at {@code aim}. */
    public abstract Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap);

    /** Where a held item hangs off the fist. */
    public abstract Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder);

    public float heldItemTilt() {
        return 18f;
    }

    /** Targets for both hands during a special move (right, left), or null. */
    @Nullable
    public Vec3[] handsOverride() {
        return null;
    }

    /** How far a working hand may reach from its shoulder; beyond that it stops short instead of stretching the arm. */
    public double maxReach() {
        return Double.MAX_VALUE;
    }

    /** How quickly the hands follow {@link #handsOverride} (spring rate; fast blows need more). */
    public double handsStiffness() {
        return 20;
    }

    /** A click on the scene (not on the inventory drawer). */
    public abstract void click(int button, boolean shift);

    public void drag(int button) {}

    public void release(int button) {}

    /** The mouse wheel turned over the scene. */
    public void scroll(double delta) {}

    /** A key pressed while working (before the keys that step away); true when the station used it. */
    public boolean keyPressed(int key) {
        return false;
    }

    /** Whether the inventory drawer shows at the top of the frame (a lectern has no use for it). */
    public boolean showsInventory() {
        return true;
    }

    /** At the start of every frame, outside the world pass (textures can be painted here). */
    public void beforeFrame() {}

    public void tick() {}

    /** In the middle of a move (a craft, a blow, a turning page): not the moment to move over to another station. */
    public boolean busy() {
        return false;
    }

    /** The scene is over (stepping away or dropped): let go of whatever the station holds. */
    public void onEnd() {}

    /** Shift-click a drawer item into a menu-backed station. */
    public void quickMove(int inventorySlot, int menuSlot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (busy() || mc.player == null || mc.gameMode == null || mc.level == null) return;
        if (Scene.clientOnly()) {
            QuickMoves.sendVanilla(mc, mc.player.containerMenu, menuSlot);
            return;
        }
        if (kind().usesMenu() || kind().isFurnace() || kind() == StationKind.CAMPFIRE) {
            dev.dyslexic.cinematic.net.PacketDistributor.sendToServer(new dev.dyslexic.cinematic.net.StationActionPayload(
                    pos, kind(), dev.dyslexic.cinematic.net.StationActionPayload.Action.QUICK,
                    inventorySlot, 0, true, 0));
        }
    }

    /** 0..1: how hard the camera is pulled in and shaken right now (a dramatic moment). */
    public double cameraKick() {
        return 0;
    }

    /** 0..1 strength of a flash over the whole screen right now (the light of a spell, a white-hot blow). */
    public double flash() {
        return 0;
    }

    /** Colour of {@link #flash}, 0xRRGGBB. */
    public int flashColor() {
        return 0xFFFFFF;
    }

    public boolean stillThere(Level level) {
        return kind().matches(level.getBlockState(pos));
    }

    // ---- drawing -------------------------------------------------------------------------------------

    /** Extras in the world while working (hover glow, floating result...). */
    public abstract void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light);

    /** The player's own body where the scene shows it (legs while sitting); every frame, every phase, before the arms. */
    public void renderBody(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {}

    /** Extras over the scene (progress indicators...). */
    public void renderScreen(GuiGraphicsExtractor graphics, Font font, float alpha) {}

    private final RecipeChoiceStrip choiceStrip = new RecipeChoiceStrip();

    RecipeChoiceStrip choices() { return choiceStrip; }

    void chose(int index) {
        RecipeChoiceStrip.send(pos, kind(), index);
    }

    /** Tooltip for what the cursor is over, or null. */
    @Nullable
    public List<Component> tooltip(ItemStack carried) {
        return null;
    }

    public abstract Component hint();

    /** Where the light for hands and items is sampled. */
    public BlockPos lightPos() {
        return pos.above();
    }

    /** Heard when the hands settle on the block. */
    public SoundEvent touchSound() {
        return SoundEvents.WOOD_STEP;
    }

    /** Where scene sounds come from. */
    public Vec3 soundPoint() {
        return center();
    }
}
