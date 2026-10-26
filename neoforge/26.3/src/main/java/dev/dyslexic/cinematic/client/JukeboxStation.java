package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.phys.Vec3;
import dev.dyslexic.cinematic.net.PacketDistributor;
import org.joml.Quaternionf;

/**
 * The jukebox: a short scene. The player steps up and leans over its top; a record in hand is lifted over the slot,
 * turned to stand on its edge and lowered into it until it is gone, and the music starts; a jukebox already playing is
 * pressed, its record rises out of the slot and is taken. Then the player steps away by themselves. With no record in
 * hand, one is picked from the inventory panel at the top and the jukebox clicked.
 * <p>
 * The slot runs along the block from north to south (jukebox_top.png), so the jukebox is worked from its east or west
 * side, where a record in it shows its face.
 */
public final class JukeboxStation extends ClientStation implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    /** The record's square as drawn (the disc in it is about three quarters of that across). */
    private static final float RECORD_SIZE = 0.6f;
    /** The disc's radius in the world. */
    private static final double DISC_R = RECORD_SIZE * 12 / 32.0;
    /** How long putting a record in and taking one out take, and the moment after before stepping away. */
    private static final double INSERT = 1.3, EJECT = 1.15, LINGER = 0.55;
    /** When the server is told during each move (the record goes in, the jukebox lets go of it). */
    private static final double INSERT_SEND = 0.62, EJECT_SEND = 0.16;

    private enum Move { NONE, INSERT, EJECT }

    private final Vec3 top;
    private Move move = Move.NONE;
    private double moveAt;
    private boolean sent, started;
    private ItemStack record = ItemStack.EMPTY;
    /** Where the record put in comes from: -1 the item in hand, otherwise a hotbar slot. */
    private int from = -1;
    public boolean hoverTop;

    public JukeboxStation(BlockPos pos, Direction side) {
        super(pos, side);
        top = Vec3.atCenterOf(pos).add(0, 0.5, 0);
    }

    /** Whether using the jukebox has anything to do: a record in it to take out, or one in the inventory to put in. */
    static boolean worthIt(LocalPlayer player, BlockPos pos) {
        if (player.level().getBlockState(pos).getValue(JukeboxBlock.HAS_RECORD) || isRecord(player.containerMenu.getCarried())) {
            return true;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (isRecord(player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean isRecord(ItemStack stack) {
        return stack.has(DataComponents.JUKEBOX_PLAYABLE);
    }

    @Override
    public StationKind kind() {
        return StationKind.JUKEBOX;
    }

    @Override
    public Vec3 center() {
        return top;
    }

    @Override
    public boolean worksAtAnAngle() {
        return true;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.WOOD_STEP;
    }

    private boolean playing() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.getBlockState(pos).getValue(JukeboxBlock.HAS_RECORD);
    }

    /** The record in the jukebox, as the server told (the client is not told otherwise). */
    private ItemStack inside() {
        ClientMenus.Entry entry = ClientMenus.BLOCKS.get(pos);
        ItemStack stack = entry == null || entry.items.isEmpty() ? ItemStack.EMPTY : entry.items.get(0);
        return stack.isEmpty() ? new ItemStack(Items.MUSIC_DISC_13) : stack;
    }

    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(top.x + side.getStepX() * 0.88, player.getY(), top.z + side.getStepZ() * 0.88);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 eye = top.add(side().scale(0.8)).add(0, 0.58, 0);
        Vec3 look = top.add(side().scale(0.04)).add(0, 0.2, 0).subtract(eye);
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, side.getOpposite().toYRot(), pitch, 0);
    }

    @Override
    public double leanFov() {
        return 64;
    }

    @Override
    public float bodyPitch() {
        return 40f;
    }

    @Override
    public Vec3 rest(boolean right) {
        return top.add(side().scale(0.34)).add(right().scale(right ? 0.25 : -0.25)).add(0, 0.035, 0);
    }

    // ---- the cursor ----------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        aim = null;
        hoverTop = false;
        if (overPanel || gridEditor.visible() || move != Move.NONE) {
            return;
        }
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            hoverTop = layoutPick() == 0;
            if (hoverTop) aim = layoutPoint(0);
            return;
        }
        Vec3 hit = Picking.cursorOnPlane(top.y);
        if (hit != null && Math.abs(hit.x - top.x) < 0.52 && Math.abs(hit.z - top.z) < 0.52) {
            hoverTop = true;
            aim = new Vec3(Math.max(pos.getX() + 0.1, Math.min(pos.getX() + 0.9, hit.x)), top.y, Math.max(pos.getZ() + 0.1, Math.min(pos.getZ() + 0.9, hit.z)));
        }
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        Vec3 reach = new Vec3(aim.x - shoulder.x, 0, aim.z - shoulder.z).normalize();
        return aim.add(0, (carrying ? 0.09 : 0.05) - 0.05 * tap, 0).subtract(reach.scale(0.07));
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        Vec3 reach = new Vec3(hand.x - shoulder.x, 0, hand.z - shoulder.z).normalize();
        return hand.add(reach.scale(0.08)).add(0, -0.03, 0);
    }

    // ---- the moves -----------------------------------------------------------------------------------

    @Override
    public void click(int button, boolean shift) {
        if (move != Move.NONE || !hoverTop || button != 0) {
            return;
        }
        if (playing()) {
            begin(Move.EJECT, inside(), -1);
        } else if (isRecord(Hands.carried())) {
            begin(Move.INSERT, Hands.carried().copyWithCount(1), -1);
        } else {
            Hands.tap();
        }
    }

    private void begin(Move next, ItemStack stack, int source) {
        move = next;
        record = stack;
        from = source;
        moveAt = Scene.clock();
        sent = false;
    }

    /** 0..1 through the move under way. */
    private double progress() {
        return Anim.clamp01((Scene.clock() - moveAt) / (move == Move.EJECT ? EJECT : INSERT));
    }

    @Override
    public void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || gridEditor.visible() || Scene.phase != Scene.Phase.ACTIVE) {
            return;
        }
        if (!started && Scene.time > 0.1) {
            // the use that started the scene says what to do: take the record out, or put in the one in hand
            started = true;
            ItemStack hand = player.getMainHandItem();
            if (Settings.developerMode()) {
                // In the editor mode a short automatic scene must wait long enough to be edited.
                return;
            }
            if (playing()) {
                begin(Move.EJECT, inside(), -1);
            } else if (isRecord(hand)) {
                begin(Move.INSERT, hand.copyWithCount(1), player.getInventory().getSelectedSlot());
            } else if (isRecord(Hands.carried())) {
                begin(Move.INSERT, Hands.carried().copyWithCount(1), -1);
            }
        }
        if (move == Move.NONE) {
            return;
        }
        double t = progress();
        if (!sent && t >= (move == Move.EJECT ? EJECT_SEND : INSERT_SEND)) {
            sent = true;
            // index 0: the record in hand (or taking one out), 1: the record in hotbar slot "button"
            PacketDistributor.sendToServer(new StationActionPayload(pos, StationKind.JUKEBOX, StationActionPayload.Action.CLICK,
                    from < 0 ? 0 : 1, Math.max(from, 0), false, 0));
            if (move == Move.INSERT) {
                Fx.motes(layoutPoint(0).add(0, 0.06, 0), 0.12, 10, 1.0f, 0.82f, 0.45f, 0.25);
            }
        }
        double length = move == Move.EJECT ? EJECT : INSERT;
        if (Scene.clock() - moveAt > length + LINGER) {
            // a short scene: done, the player steps away by themselves
            move = Move.NONE;
            if (!Settings.developerMode()) Scene.leave();
        }
    }

    @Override
    public boolean busy() {
        return move != Move.NONE || gridEditor.editing();
    }

    /** Where the record's middle is in the move (null when it is not in sight). */
    @Nullable
    private Vec3 recordPoint() {
        if (move == Move.NONE) {
            return null;
        }
        double t = progress();
        Vec3 low = rest(true).add(side().scale(0.18)).add(0, -0.12, 0);
        Vec3 contact = layoutPoint(0);
        double radius = DISC_R * geometry().scale(0);
        Vec3 above = contact.add(0, radius + 0.08, 0);
        Vec3 halfIn = contact.add(0, radius * 0.25, 0);
        Vec3 gone = contact.add(0, -radius - 0.02, 0);
        if (move == Move.INSERT) {
            if (t < 0.32) {
                double k = Anim.inOutCubic(t / 0.32);
                return Anim.lerp(low, above, k).add(0, 0.06 * Anim.hump(k), 0);
            }
            if (t < 0.42) {
                return above;
            }
            if (t < 0.72) {
                return Anim.lerp(above, halfIn, Anim.inOutSine((t - 0.42) / 0.3));
            }
            if (t < 0.86) {
                return Anim.lerp(halfIn, gone, Anim.inCubic((t - 0.72) / 0.14));
            }
            return null;
        }
        // taking it out: it rises once the jukebox is pressed, is taken by its edge and carried off towards the player
        if (t < 0.2) {
            return null;
        }
        if (t < 0.55) {
            return Anim.lerp(gone, above, Anim.outCubic((t - 0.2) / 0.35));
        }
        if (t < 0.7) {
            return above;
        }
        return Anim.lerp(above, low.add(side().scale(0.12)).add(0, -0.12, 0), Anim.inCubic((t - 0.7) / 0.3));
    }

    /** The record stands on its edge in the slot's line, its face to the player; on the way it leans a little. */
    private Quaternionf recordTurn() {
        double t = progress();
        double lean = move == Move.INSERT ? 1 - Anim.smoother(Anim.window(t, 0.2, 0.42)) : Anim.smoother(Anim.window(t, 0.7, 1.0));
        return Axis.YP.rotationDegrees(-side.toYRot() + RemainingLayouts.rotation(kind(), 0)).mul(Axis.XP.rotationDegrees((float) (-35 * lean)));
    }

    @Nullable
    @Override
    public Vec3[] handsOverride() {
        if (move == Move.NONE) {
            return null;
        }
        double t = progress();
        Vec3 restRight = rest(true);
        Vec3 restLeft = rest(false);
        Vec3 hand;
        Vec3 disc = recordPoint();
        Vec3 press = layoutPoint(0).add(side().scale(0.05)).add(0, 0.045, 0);
        if (move == Move.INSERT) {
            if (disc != null && t < 0.72) {
                hand = edge(disc);
            } else if (t < 0.86) {
                // the fingers on its top edge push it home
                double k = Anim.smoother((t - 0.72) / 0.14);
                hand = Anim.lerp(edge(layoutPoint(0).add(0, DISC_R * geometry().scale(0) * 0.25, 0)), press, k);
            } else {
                hand = Anim.lerp(press, restRight, Anim.smoother((t - 0.86) / 0.14));
            }
        } else {
            if (t < 0.2) {
                // a press on the top, and the jukebox lets the record go
                hand = Anim.lerp(restRight, press, Anim.smoother(t / 0.1)).add(0, -0.03 * Anim.hump(t / 0.2), 0);
            } else if (t < 0.55) {
                hand = Anim.lerp(press, restRight, Anim.smoother((t - 0.2) / 0.2));
            } else if (disc != null && t < 0.7) {
                hand = Anim.lerp(restRight, edge(disc), Anim.smoother((t - 0.55) / 0.15));
            } else {
                hand = disc != null ? edge(disc) : restRight;
            }
        }
        return new Vec3[]{hand, restLeft};
    }

    /** Where the hand holds a record standing at {@code disc}: by its edge on the right, a little towards the player. */
    private Vec3 edge(Vec3 disc) {
        return disc.add(layoutAxis(0, true).scale(DISC_R * geometry().scale(0) * 0.88)).add(side().scale(0.03)).add(0, 0.01, 0);
    }

    @Override
    public double handsStiffness() {
        return 26;
    }

    // ---- drawing -------------------------------------------------------------------------------------

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        Vec3 at = recordPoint();
        if (at == null || record.isEmpty()) {
            return;
        }
        float size = RECORD_SIZE * geometry().scale(0);
        if (move == Move.EJECT) {
            size *= (float) (1 - 0.35 * Anim.smoother(Anim.window(progress(), 0.75, 1.0)));
        }
        ItemPlacer.drawOriented(pose, buffers, camera, record, at, recordTurn(), size, light);
    }

    @Override
    public Component hint() {
        if (move != Move.NONE && !record.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            Component song = mc.level == null ? null : net.minecraft.world.item.JukeboxSong.fromStack(record)
                    .map(holder -> holder.value().description()).orElse(null);
            return Component.literal("♪ ").append(song != null ? song : record.getHoverName());
        }
        if (playing()) {
            return Component.translatable("cinematic.jukebox.hint.take");
        }
        return Component.translatable(isRecord(Hands.carried()) ? "cinematic.jukebox.hint.put" : "cinematic.jukebox.hint.choose");
    }

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] pointer = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(pointer[0], pointer[1]);
        if (editorTip != null) return editorTip;
        if (!hoverTop || move != Move.NONE) {
            return null;
        }
        if (playing()) {
            return List.of(inside().getHoverName(), Component.translatable("cinematic.jukebox.take").withStyle(ChatFormatting.GRAY));
        }
        if (isRecord(carried)) {
            return List.of(carried.getHoverName(), Component.translatable("cinematic.jukebox.put").withStyle(ChatFormatting.GRAY));
        }
        return List.of(Component.translatable(carried.isEmpty() ? "cinematic.jukebox.empty" : "cinematic.jukebox.not_record")
                .withStyle(carried.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.RED));
    }

    /** For the automated walkthrough. */
    public String moveName() {
        return move.name().toLowerCase(java.util.Locale.ROOT) + String.format(" %.2f", move == Move.NONE ? 0 : progress());
    }

    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public java.util.List<Vec3> workSamples() { return RemainingLayouts.samples(this, super.workSamples()); }
}
