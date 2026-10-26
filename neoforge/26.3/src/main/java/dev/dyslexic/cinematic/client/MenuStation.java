package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.net.StationActionPayload;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import dev.dyslexic.cinematic.net.PacketDistributor;

/**
 * A station worked through the block's own vanilla menu, which the server opens without its window (anvil,
 * grindstone, smithing table, stonecutter; see {@code MenuActions}). Items are put in and taken out with
 * ordinary slot clicks, so recipes, costs and rewards stay vanilla's.
 * <p>
 * Each station has one big move (forging, grinding, smithing, cutting) played on a timeline while it drives
 * the hands. The click that does the work on the server comes on the move's beat, when the hammer falls or the
 * blade bites, so the server's sound, the experience and the picture meet.
 */
public abstract class MenuStation<M extends AbstractContainerMenu> extends ClientStation {
    private final Class<M> menuType;
    private final int inputs;

    private double moveAt = -10;
    private double moveLength = 1;
    private double beatAt;
    @Nullable
    private Runnable beat;
    /** Onlookers were told when this move began (they play the whole of it): its beat is no cue for them. */
    private boolean moveShown;
    /** When a touch was refused (too expensive, nothing to do): the thing shakes its head. */
    protected double denyAt = -10;

    /** What lay on the block when the player stepped away: it flies back to them. */
    private List<ItemStack> leaving = List.of();

    protected MenuStation(BlockPos pos, Direction side, Class<M> menuType, int inputs) {
        super(pos, side);
        this.menuType = menuType;
        this.inputs = inputs;
    }

    // ---- the menu underneath -------------------------------------------------------------------------

    @Nullable
    public M menu() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && menuType.isInstance(player.containerMenu) ? menuType.cast(player.containerMenu) : null;
    }

    public ItemStack slot(int index) {
        M menu = menu();
        return menu == null ? ItemStack.EMPTY : menu.getSlot(index).getItem();
    }

    /** A click on one of the menu's own slots, as the window would make it. */
    protected void slotClick(int slot, int button, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        M menu = menu();
        if (menu == null || mc.gameMode == null || mc.player == null || moving()) {
            return;
        }
        Hands.tap();
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
    }

    /** Whether the menu would let this stack into the slot. */
    protected boolean accepts(int slot, ItemStack stack) {
        M menu = menu();
        return menu != null && menu.getSlot(slot).mayPlace(stack);
    }

    /** The slot a carried stack goes to: the preferred one if it fits there, else the first that takes it. */
    protected int slotFor(ItemStack stack, int preferred) {
        if (preferred >= 0 && fits(preferred, stack)) {
            return preferred;
        }
        for (int i = 0; i < inputs; i++) {
            if (fits(i, stack)) {
                return i;
            }
        }
        return -1;
    }

    private boolean fits(int slot, ItemStack stack) {
        ItemStack there = slot(slot);
        return accepts(slot, stack) && (there.isEmpty() || ItemStack.isSameItemSameComponents(there, stack) && there.getCount() < there.getMaxStackSize());
    }

    // ---- the big move --------------------------------------------------------------------------------

    /** Starts the move; {@code atBeat} runs {@code beat} seconds in (the server click), unless the scene ends first. */
    protected void startMove(double length, double beat, Runnable atBeat) {
        moveAt = Scene.clock();
        moveLength = length;
        beatAt = beat;
        this.beat = atBeat;
        moveShown = false;
    }

    /**
     * Tells onlookers the move begins, so they see the whole of it, not just its sparks (the hammer swung, the blade
     * drawn over the wheel, the stone pushed through the saw); what lies on the block and what comes of it the server
     * reads from the menu. {@code bag}: the result goes into the inventory rather than the hand.
     */
    protected void showMove(boolean bag) {
        moveShown = true;
        if (!Scene.clientOnly())
            PacketDistributor.sendToServer(new StationActionPayload(pos, kind(), StationActionPayload.Action.CRAFT, 1, bag ? 1 : 0, false, 0));
    }

    /** Seconds into the move, or -1 when there is none. */
    public double moveTime() {
        double t = Scene.clock() - moveAt;
        return t >= 0 && t <= moveLength ? t : -1;
    }

    public boolean moving() {
        return moveTime() >= 0;
    }

    /** Every frame: the beat lands on time, and onlookers get their cue. */
    void frame() {
        if (beat == null || Scene.clock() - moveAt < beatAt) {
            return;
        }
        Runnable action = beat;
        beat = null;
        if (Scene.phase == Scene.Phase.ACTIVE && menu() != null) {
            action.run();
            if (!moveShown && !Scene.clientOnly()) {
                PacketDistributor.sendToServer(StationActionPayload.of(pos, kind(), StationActionPayload.Action.CRAFT));
            }
        } else {
            moveAt = -10;
        }
    }

    /** The move stops where it is (the work vanished from under it). */
    protected void cancelMove() {
        moveAt = -10;
        beat = null;
    }

    protected void deny() {
        denyAt = Scene.clock();
        Scene.sound(SoundEvents.CHAIN_HIT, 0.5f, 0.7f);
    }

    /** A sideways shake after a refused touch, 0 when calm. */
    protected double denyShake() {
        double d = Scene.clock() - denyAt;
        return d >= 0 && d < 0.35 ? 0.012 * Math.sin(d * 60) * (1 - d / 0.35) : 0;
    }

    @Override
    public void click(int button, boolean shift) {
        if (!moving() && menu() != null) {
            act(button, shift);
        }
    }

    @Override
    public boolean busy() {
        return moving();
    }

    /** A click while nothing is moving. */
    protected abstract void act(int button, boolean shift);

    @Override
    public void onEnd() {
        List<ItemStack> left = new ArrayList<>(inputs);
        for (int i = 0; i < inputs; i++) {
            left.add(slot(i).copy());
        }
        leaving = left;
        beat = null;
        LocalPlayer player = Minecraft.getInstance().player;
        if (!Scene.clientOnly() && player != null && menuType.isInstance(player.containerMenu)) {
            // the server closes its copy on LEAVE; ours goes quietly, without a window to close
            player.containerMenu = player.inventoryMenu;
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------

    /** What lies on the block and the move; drawn in every phase of the scene (renderWorld only while working). */
    public abstract void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light);

    /** What lay in the inputs when the player stepped away. */
    protected List<ItemStack> leftBehind() {
        return leaving;
    }

    /** Where an input lies on the block, for the flight back to the player. */
    protected abstract Vec3 restPoint(int slot);

    /**
     * Stepping away from a block whose menu hands its inputs back (an anvil, a grindstone...): whatever was left on it
     * flies back to the player and shrinks into them. Blocks that keep their contents (a brewing stand, a crafter) do
     * not call this.
     */
    protected void drawLeaving(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        Vec3 cam = camera.position();
        Vec3 to = cam.add(Scene.yawForward(camera.yRot()).scale(0.25)).add(0, -0.5, 0);
        for (int i = 0; i < leaving.size(); i++) {
            ItemStack stack = leaving.get(i);
            double k = Anim.inOutCubic(Anim.window(Scene.time, 0.06 + i * 0.07, 0.5 + i * 0.07));
            if (stack.isEmpty() || k >= 1) {
                continue;
            }
            Vec3 at = Anim.lerp(restPoint(i), to, k).add(0, 0.14 * Anim.hump(k), 0);
            float size = (float) ((ItemPlacer.isBlock(stack) ? 0.16 : 0.24) * (1 - k * 0.85));
            ItemPlacer.drawOriented(pose, buffers, cam, stack, at, ItemPlacer.lying(ItemPlacer.awayYaw(side), (float) (30 * k)), size, light);
        }
    }

    protected boolean outro() {
        return Scene.phase == Scene.Phase.OUTRO;
    }
}
