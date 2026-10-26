package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The lectern scene: reading the book that lies on it. The camera bends over the open book until its pages fill the
 * view and the text, in the game's own book layout, can be read as in the book window. A click on the right-hand page
 * turns a leaf forward, on the left-hand page back (the wheel and the arrow keys turn them too): the hand takes the
 * leaf by its lower corner, the leaf lifts corner first, bends and lies down on the other side. On the way in the
 * book is riffled open to the page it was left at; Shift+click closes it and takes it. The book itself is an
 * {@link OpenBook}.
 * <p>
 * Underneath is the vanilla lectern menu: turning sets its page, so comparators read the lectern as before, and taking
 * the book is the menu's own button.
 */
public final class LecternStation extends MenuStation<LecternMenu> implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    public enum Target { NONE, LEFT, RIGHT }

    /** The desk: towards the reader, across, up the slope, out of it. */
    private final Vec3 f, a, s, n;
    /** The middle of the spine, on the cover board, as the book lies on the desk. */
    private final Vec3 origin;
    private final OpenBook book = new OpenBook();

    public Target target = Target.NONE;
    private int sentPage = -1;

    private double takeAt = -10;
    private boolean takeSent;
    private boolean riffleDue;

    public LecternStation(BlockPos pos, Direction side) {
        super(pos, side, LecternMenu.class, 0);
        f = side();
        a = right();
        Vec3 up = new Vec3(0, 1, 0);
        // the desk of the lectern is tilted 22.5 degrees down towards the reader (lectern.json)
        double c = Math.cos(Math.toRadians(22.5)), sn = Math.sin(Math.toRadians(22.5));
        s = up.scale(sn).subtract(f.scale(c));
        n = up.scale(c).add(f.scale(sn));
        origin = Vec3.atBottomCenterOf(pos).add(0, 15.96 / 16, 0).add(f.scale(1.67 / 16)).add(n.scale(OpenBook.COVER));
        book.place(origin, a, s, n);
    }

    @Override
    public StationKind kind() {
        return StationKind.LECTERN;
    }

    @Override
    public Vec3 center() {
        return origin;
    }

    @Override
    protected Vec3 restPoint(int slot) {
        return origin;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.BOOK_PAGE_TURN;
    }

    @Override
    public boolean showsInventory() {
        return false;
    }

    /** Whether the scene draws the book on this lectern (the lectern's own book is then left out). */
    public static boolean drawsBookAt(BlockPos pos) {
        LecternStation station = Scene.station(LecternStation.class);
        return station != null && station.pos.equals(pos) && (station.book.spread >= 0 || station.taking());
    }

    // ---- the book --------------------------------------------------------------------------------------

    private ItemStack book() {
        LecternMenu menu = menu();
        return menu == null ? ItemStack.EMPTY : menu.getBook();
    }

    /** The lectern's page for a spread: its left-hand page, or the first page on the first spread. */
    private static int pageOf(int spread) {
        return Math.max(0, 2 * spread - 1);
    }

    private void sendPage() {
        Minecraft mc = Minecraft.getInstance();
        LecternMenu menu = menu();
        int page = pageOf(book.spread);
        if (menu != null && mc.gameMode != null && page != sentPage && page < Math.max(1, book.pageCount())) {
            sentPage = page;
            mc.gameMode.handleInventoryButtonClick(menu.containerId, LecternMenu.BUTTON_PAGE_JUMP_RANGE_START + page);
            // our copy follows at once, so the server's answer is not mistaken for someone else turning it
            menu.setData(0, page);
        }
    }

    @Override
    protected void deny() {
        denyAt = Scene.clock();
        Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.35f, 0.55f);
    }

    // ---- turning -----------------------------------------------------------------------------------------

    private boolean taking() {
        return takeAt > 0;
    }

    private void turn(int dir) {
        if (gridEditor.visible() || taking() || book.spread < 0 || Scene.phase != Scene.Phase.ACTIVE) {
            return;
        }
        switch (book.turn(dir)) {
            case NO_PAGE -> deny();
            case YES -> {
                sendPage();
                Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.9f, 0.9f + Fx.RANDOM.nextFloat() * 0.25f);
            }
            default -> {}
        }
    }

    private void take() {
        if (taking() || book.turning() || book().isEmpty()) {
            return;
        }
        takeAt = Scene.clock();
        takeSent = false;
        Scene.sound(SoundEvents.BOOK_PAGE_TURN, 1f, 0.6f);
    }

    /** 0..1 of closing the book over, then 0..1 of lifting it away. */
    private double fold() {
        return taking() ? Anim.inOutCubic(Anim.window(Scene.clock() - takeAt, 0, 0.5)) : 0;
    }

    private double lift() {
        return taking() ? Anim.inOutSine(Anim.window(Scene.clock() - takeAt, 0.5, 1.05)) : 0;
    }

    // ---- per frame ---------------------------------------------------------------------------------------

    @Override
    public void beforeFrame() {
        gridEditor.frame();
        book.read(book());
        LecternMenu menu = menu();
        if (book.spread < 0 && menu != null && !book().isEmpty()) {
            book.openAt((menu.getPage() + 1) / 2);
            sentPage = menu.getPage();
            riffleDue = Scene.phase == Scene.Phase.INTRO;
        }
        // taken: shut, then lifted off the desk towards the reader's chest, past the lower edge of the frame
        book.closing = taking();
        book.fold = fold();
        double k = lift();
        Vec3 editedOrigin = layoutPoint(0);
        Vec3 editedAcross = layoutAxis(0, true), editedSlope = layoutAxis(0, false);
        Vec3 to = editedOrigin.add(n.scale(0.1)).add(editedSlope.scale(-0.75));
        double scale = (1 - 0.75 * k) * geometry().scale(0);
        book.place(Anim.lerp(editedOrigin, to, k), editedAcross.scale(scale), editedSlope.scale(scale), n.scale(scale));
        if (book.spread < 0) {
            return;
        }
        if (riffleDue && Scene.arrived()) {
            // riffled open once the player is at the lectern and bending over it
            riffleDue = false;
            if (Scene.phase == Scene.Phase.INTRO) {
                book.riffle(Scene.clock() + Scene.introSecondsUntil(0.42));
            }
        }
        OpenBook.Side hover = target == Target.LEFT ? OpenBook.Side.LEFT : target == Target.RIGHT ? OpenBook.Side.RIGHT : OpenBook.Side.NONE;
        book.settle(hover, Scene.phase == Scene.Phase.ACTIVE, Scene.frameDt);
        book.preparePages();
    }

    @Override
    void frame() {
        super.frame();
        if (taking()) {
            double t = Scene.clock() - takeAt;
            LecternMenu menu = menu();
            Minecraft mc = Minecraft.getInstance();
            if (!takeSent && t >= 0.5 && menu != null && mc.gameMode != null) {
                takeSent = true;
                mc.gameMode.handleInventoryButtonClick(menu.containerId, LecternMenu.BUTTON_TAKE_BOOK);
                Scene.sound(SoundEvents.BOOK_PUT, 0.9f, 1.1f);
            }
            if (t > 2.2 && !book().isEmpty() && Scene.phase == Scene.Phase.ACTIVE) {
                // the lectern kept its book (not allowed to take it here): it falls open again
                takeAt = -10;
            }
        }
    }

    @Override
    public void tick() {
        LecternMenu menu = menu();
        if (menu == null || book.spread < 0 || book.turning() || taking()) {
            return;
        }
        // someone else turned the lectern's page (or a command did): follow it
        int page = menu.getPage();
        if (page != sentPage && page >= 0) {
            sentPage = page;
            book.follow((page + 1) / 2);
        }
    }

    /** The spread the book lies open at, -1 before it arrives (for the showcase). */
    public int spread() {
        return book.spread;
    }

    /** The middle of a page as it lies (for the showcase). */
    public Vec3 pageCenter(boolean left) {
        return book.pageCenter(left);
    }

    // ---- staging -----------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 c = Vec3.atBottomCenterOf(pos);
        Vec3 stand = new Vec3(c.x + f.x * 0.98, player.getY(), c.z + f.z * 0.98);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public CameraRig.Pose lean() {
        // bent over the book, looking down onto the pages almost square on
        Vec3 eye = origin.add(n.scale(0.66)).add(f.scale(0.07)).add(s.scale(-0.02));
        Vec3 look = origin.add(s.scale(-0.012)).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, 0);
    }

    @Override
    public double leanFov() {
        return 52;
    }

    @Override
    public float bodyPitch() {
        return 42f;
    }

    /** Hands below the lower edge of the book on the desk, near its outer corners. */
    @Override
    public Vec3 rest(boolean right) {
        double x = OpenBook.GUTTER + OpenBook.PAGE_W * 0.9;
        return layoutPoint(0).add(layoutAxis(0, true).scale((right ? x : -x) * geometry().scale(0)))
                .add(layoutAxis(0, false).scale((-OpenBook.PAGE_H / 2 - 0.018) * geometry().scale(0))).add(n.scale(0.035));
    }

    // ---- cursor ------------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || gridEditor.visible() || book.spread < 0 || taking()) {
            return;
        }
        OpenBook.Side side = book.under();
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            Vec3 hit = Picking.cursorOnPlane(layoutPoint(0), n);
            side = hit != null && layoutPick() == 0 ? hit.subtract(layoutPoint(0)).dot(layoutAxis(0, true)) < 0 ? OpenBook.Side.LEFT : OpenBook.Side.RIGHT : OpenBook.Side.NONE;
        }
        if (side == OpenBook.Side.NONE) {
            return;
        }
        boolean left = side == OpenBook.Side.LEFT;
        target = left ? Target.LEFT : Target.RIGHT;
        aim = book.corner(left);
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        return aim.add(n.scale(0.03 - 0.02 * tap));
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        return hand;
    }

    /** While a leaf turns, the hand that turns it holds its corner; taking the book, both hands gather it. */
    @Nullable
    @Override
    public Vec3[] handsOverride() {
        if (Scene.phase != Scene.Phase.ACTIVE) {
            return null;
        }
        if (taking()) {
            double t = Scene.clock() - takeAt;
            Vec3 closed = book.at(OpenBook.GUTTER + OpenBook.PAGE_W * 0.5, -OpenBook.PAGE_H / 2 - 0.02, 0.05);
            double gather = Anim.smoother(Anim.window(t, 0.35, 0.6));
            Vec3 grip = layoutAxis(0, true).scale(0.2 * geometry().scale(0));
            Vec3 l = Anim.lerp(book.foldCorner(), closed.subtract(grip), gather);
            Vec3 r = Anim.lerp(rest(true), closed.add(grip), gather);
            return new Vec3[]{r, l};
        }
        return book.turningHands();
    }

    @Override
    public double handsStiffness() {
        return 34;
    }

    // ---- actions -----------------------------------------------------------------------------------------

    @Override
    protected void act(int button, boolean shift) {
        if (shift) {
            take();
            return;
        }
        switch (target) {
            case RIGHT -> turn(1);
            case LEFT -> turn(-1);
            default -> {}
        }
    }

    @Override
    public void scroll(double delta) {
        turn(delta < 0 ? 1 : -1);
    }

    @Override
    public boolean keyPressed(int key) {
        switch (key) {
            case InputConstants.KEY_RIGHT, InputConstants.KEY_PAGEDOWN -> turn(1);
            case InputConstants.KEY_LEFT, InputConstants.KEY_PAGEUP -> turn(-1);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- drawing -----------------------------------------------------------------------------------------

    @Override
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        if (book.spread < 0 && !taking()) {
            return;
        }
        if (taking() && Scene.clock() - takeAt > 1.05) {
            return;
        }
        // the book is lit well enough to read, whatever the room
        int lit = LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(light), 11), LightCoordsUtil.sky(light));
        book.render(pose, buffers, camera.position(), lit);
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);}

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.lectern");
    }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing() || taking() || book.turning(); }
    @Override public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public List<Component> tooltip(ItemStack carried) { double[] mouse = Picking.mouseGui(); return gridEditor.tooltip(mouse[0], mouse[1]); }
    @Override public java.util.List<Vec3> workSamples() { return RemainingLayouts.samples(this, super.workSamples()); }
}
