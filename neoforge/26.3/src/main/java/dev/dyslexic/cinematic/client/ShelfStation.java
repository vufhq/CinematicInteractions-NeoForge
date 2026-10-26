package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dyslexic.cinematic.ClientBridge;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import dev.dyslexic.cinematic.net.PacketDistributor;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The chiseled bookshelf: the player crouches at its front and looks over the books. The one under the cursor slides out
 * a little, and what it is shows by the cursor (an enchanted book's enchantments, a written book's title); enchanted books
 * shimmer on the shelf. A click draws a book out by its spine into the hand (Shift: into the inventory); a book with
 * writing in it (a book and quill, a written book) is drawn out, turned in the hands and opened, and read there as on a
 * lectern, leaf by leaf ({@link OpenBook}); a right click or Esc shuts it and slides it back. A book in hand goes into an
 * empty place. The books are {@link ShelfBooks}: the same book on the shelf, in the hands and open.
 * <p>
 * The server takes and puts books by the shelf's own rules ({@code ShelfActions}); reading takes nothing off the shelf.
 */
public final class ShelfStation extends ClientStation implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    private final OpenBook layoutGhost = new OpenBook();
    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    @Override public ItemStack layoutItem(int slot) { return occupied(slot) ? bookIn(slot).isEmpty() ? new ItemStack(Items.BOOK) : bookIn(slot) : ItemStack.EMPTY; }
    @Override public SpatialAppearance.Frame layoutFrame() { return SpatialAppearance.Frame.of(kind(), pos, side, side); }
    @Override public List<Vec3> workSamples() { return layoutSamples(super.workSamples()); }
    @Override public void renderScreen(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public void layoutGhost(PoseStack pose, SceneBuffers buffers, Vec3 camera, int slot, int light) {
        ShelfBooks.draw(pose, buffers, camera, slot, new ItemStack(Items.BOOK), ShelfBooks.inSlot(pos, facing, slot, 0), layoutGhost, light);
    }
    public enum Target { NONE, BOOK, PLACE, PAGE }

    private enum Move { NONE, TAKE, PUT, OPEN, CLOSE }

    /**
     * Taking a book: the hand has its spine, it is clear of the shelf, it is in the hand (or the bag); into the bag the
     * server is told as it reaches the drawer, so it shows up there as it arrives.
     */
    static final double GRIP = 0.24, OUT = 0.5, TAKEN = 0.95, BAGGED = 0.8;
    /** Putting one in: it is at its place, it is home, the hand is back. */
    static final double PUT_AT = 0.34, PUT_HOME = 0.62, PUT_END = 0.82;
    /** Taking one out to read: clear of the shelf, held before the eyes, open. */
    static final double OPEN_OUT = 0.52, OPEN_HELD = 1.05, OPEN_END = 1.45;
    /** Putting it back: shut, before its place, home, the hand back; into the bag instead, gone. */
    static final double CLOSE_SHUT = 0.45, CLOSE_AT = 0.95, CLOSE_HOME = 1.25, CLOSE_END = 1.42, CLOSE_GONE = 0.9;
    /** How far the book under the cursor slides out. */
    static final double PEEK = 0.05;
    /**
     * The book being read: in front of the eyes, a little low, and its size. It fills the view about as the lectern's book
     * does (the text as large on screen), the hands holding it at its lower corners.
     */
    static final double READ_DISTANCE = 0.52, READ_DROP = 0.04, READ_SCALE = 0.86;

    private final Direction facing;
    /** The middle of the shelf's face. */
    private final Vec3 front;
    /** The slot the use that started the scene was on (-1: none). */
    private final int usedSlot;

    public Target target = Target.NONE;
    public int hoverSlot = -1;
    private OpenBook.Side hoverPage = OpenBook.Side.NONE;
    private final double[] peek = new double[ShelfBooks.SPINES.length];
    private double denyAt = -10;

    private Move move = Move.NONE;
    private double moveAt;
    private int moveSlot = -1;
    private boolean sent, toBag, started;
    /** Where a book put in comes from: -1 the hand, otherwise a hotbar slot. */
    private int from = -1;
    private double peekAt;
    private ItemStack moving = ItemStack.EMPTY;
    /** Where the book put in sets off: in the hand. */
    private ShelfBooks.Pose fromHand;
    /** The book being read (or on its way to be read or back), and where it is held to be read. */
    private final OpenBook book = new OpenBook();
    private final OpenBook shut = new OpenBook();
    private boolean reading;
    @Nullable
    private ShelfBooks.Pose readPose;

    public ShelfStation(BlockPos pos, Direction side) {
        super(pos, side);
        facing = side;
        front = Vec3.atCenterOf(pos).add(side().scale(0.5));
        usedSlot = slotAt(pos, side, ClientBridge.useAt);
    }

    @Override
    public StationKind kind() {
        return StationKind.CHISELED_BOOKSHELF;
    }

    @Override
    public Vec3 center() {
        return front;
    }

    @Override
    public BlockPos lightPos() {
        return pos.relative(facing);
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.CHISELED_BOOKSHELF_STEP;
    }

    /** Reading, the book takes the whole view (as at a lectern): no drawer over it. */
    @Override
    public boolean showsInventory() {
        return !gridEditor.visible() && !reading && move != Move.OPEN && move != Move.CLOSE;
    }

    // ---- the shelf ---------------------------------------------------------------------------------------

    /** The slot a point of the shelf's face is in, as vanilla divides the face (-1: not on the face). */
    static int slotAt(BlockPos pos, Direction facing, Vec3 point) {
        SpatialAppearance layout = SpatialLayouts.current(StationKind.CHISELED_BOOKSHELF);
        if (!layout.equals(SpatialAppearance.VANILLA)) {
            var frame = SpatialAppearance.Frame.of(StationKind.CHISELED_BOOKSHELF, pos, facing, facing);
            Vec3 eye = Minecraft.getInstance().player == null ? point.add(frame.normal()) : Minecraft.getInstance().player.getEyePosition();
            Vec3 ray = point.subtract(eye);
            double denominator = ray.dot(frame.normal());
            if (Math.abs(denominator) < 1e-9) return -1;
            int best = -1; double distance = Double.MAX_VALUE;
            for (int i = 0; i < 6; i++) {
                Vec3 at = layout.point(frame, i);
                double t = at.subtract(eye).dot(frame.normal()) / denominator;
                Vec3 hit = eye.add(ray.scale(t));
                if (t >= 0 && layout.contains(frame, i, hit) && hit.distanceToSqr(at) < distance) {
                    best = i; distance = hit.distanceToSqr(at);
                }
            }
            return best;
        }
        Vec3 rel = point.subtract(Vec3.atCenterOf(pos).add(ShelfBooks.out(facing).scale(0.5)));
        double u = 8 + rel.dot(ShelfBooks.right(facing)) * 16, v = 8 - rel.y * 16;
        if (Math.abs(rel.dot(ShelfBooks.out(facing))) > 0.05 || u < 0 || u > 16 || v < 0 || v > 16) {
            return -1;
        }
        return (v < 8 ? 0 : 3) + (u < 6 ? 0 : u < 11 ? 1 : 2);
    }

    /** What stands in a slot, as the server told (empty until it has). */
    private ItemStack bookIn(int slot) {
        ClientMenus.Entry entry = ClientMenus.BLOCKS.get(pos);
        return entry == null || slot < 0 || slot >= entry.items.size() ? ItemStack.EMPTY : entry.items.get(slot);
    }

    /** Whether the shelf shows a book in this slot (the one the scene has out does not count). */
    private boolean occupied(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || slot < 0 || (slot == moveSlot && (move != Move.NONE || reading))) {
            return false;
        }
        BlockState state = mc.level.getBlockState(pos);
        return state.getBlock() instanceof ChiseledBookShelfBlock && state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot));
    }

    static boolean readable(ItemStack stack) {
        return stack.has(DataComponents.WRITABLE_BOOK_CONTENT) || stack.has(DataComponents.WRITTEN_BOOK_CONTENT);
    }

    private static boolean shelfBook(ItemStack stack) {
        return stack.is(ItemTags.BOOKSHELF_BOOKS);
    }

    /** The middle of the spine of a slot's book (or of where it would stand), for the showcase too. */
    public Vec3 slotPoint(int slot) {
        return ShelfBooks.spine(pos, facing, slot);
    }

    // ---- staging -----------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(front.x + side.getStepX() * 1.27, player.getY(), front.z + side.getStepZ() * 1.27);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public boolean crouches() {
        return true;
    }

    @Override
    public boolean workOnTop() {
        return false;
    }

    @Override
    public CameraRig.Pose lean() {
        // crouched before the shelf, the whole face in view: its top row clear of the inventory drawer
        Vec3 eye = front.add(side().scale(1.22)).add(0, 0.35, 0);
        Vec3 look = front.add(0, -0.02, 0).subtract(eye);
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, facing.getOpposite().toYRot(), pitch, 0);
    }

    @Override
    public double leanFov() {
        return 64;
    }

    @Override
    public float bodyPitch() {
        return 28f;
    }

    /** Hands on the knees, low at either side of the frame, clear of the books. */
    @Override
    public Vec3 rest(boolean right) {
        return front.add(side().scale(0.3)).add(right().scale(right ? 0.6 : -0.6)).add(0, -0.25, 0);
    }

    // ---- cursor ------------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || move != Move.NONE) {
            return;
        }
        if (reading) {
            hoverPage = book.under();
            if (hoverPage != OpenBook.Side.NONE) {
                target = Target.PAGE;
                aim = book.corner(hoverPage == OpenBook.Side.LEFT);
            }
            return;
        }
        Vec3 hit = Picking.cursorOnPlane(front, side());
        int slot = geometry().equals(SpatialAppearance.VANILLA) ? hit == null ? -1 : slotAt(pos, facing, hit) : layoutPick();
        if (slot < 0) {
            return;
        }
        hoverSlot = slot;
        target = occupied(slot) ? Target.BOOK : Target.PLACE;
        aim = slotPoint(slot);
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        hoverSlot = -1;
        hoverPage = OpenBook.Side.NONE;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        if (reading) {
            return aim.add(book.n.normalize().scale(0.03 - 0.02 * tap));
        }
        // the fist before the spine; a tap touches it
        Vec3 reach = aim.subtract(shoulder).normalize();
        return aim.add(side().scale((carrying ? 0.1 : 0.07) - 0.05 * tap)).subtract(reach.scale(0.05)).add(0, -0.03, 0);
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        Vec3 reach = hand.subtract(shoulder).normalize();
        return hand.add(reach.scale(0.07)).add(0, -0.04, 0);
    }

    // ---- actions -----------------------------------------------------------------------------------------

    @Override
    public void click(int button, boolean shift) {
        if (move != Move.NONE) {
            return;
        }
        if (reading) {
            if (button == 1 || shift) {
                close(shift);
            } else if (target == Target.PAGE) {
                turn(hoverPage == OpenBook.Side.LEFT ? -1 : 1);
            }
            return;
        }
        if (target == Target.BOOK) {
            ItemStack there = bookIn(hoverSlot);
            ItemStack carried = Hands.carried();
            boolean fits = carried.isEmpty() || ItemStack.isSameItemSameComponents(carried, there) && carried.getCount() < carried.getMaxStackSize();
            if (shift) {
                take(hoverSlot, true);
            } else if (button == 0 && readable(there)) {
                open(hoverSlot);
            } else if (fits) {
                take(hoverSlot, false);
            } else {
                deny();
            }
        } else if (target == Target.PLACE) {
            if (shelfBook(Hands.carried())) {
                put(hoverSlot, -1, Hands.carried());
            } else {
                deny();
            }
        }
    }

    private void deny() {
        denyAt = Scene.clock();
        Scene.sound(SoundEvents.CHISELED_BOOKSHELF_STEP, 0.6f, 0.6f);
    }

    private void begin(Move next, int slot, ItemStack stack) {
        move = next;
        moveAt = Scene.clock();
        moveSlot = slot;
        moving = stack.copyWithCount(1);
        sent = false;
        peekAt = peek[slot];
    }

    private void take(int slot, boolean bag) {
        toBag = bag;
        begin(Move.TAKE, slot, bookIn(slot));
    }

    private void put(int slot, int source, ItemStack stack) {
        from = source;
        // from the hand at the shelf, or (the book in hand when the scene began) from below
        fromHand = source < 0 ? handPose(slot, false) : lowPose(slot);
        begin(Move.PUT, slot, stack);
    }

    private void open(int slot) {
        begin(Move.OPEN, slot, bookIn(slot));
        book.spread = -1;
        book.bookId = 0;
        book.read(moving);
        book.openAt(0);
        CameraRig.Pose camera = CameraRig.last() != null ? CameraRig.last() : view();
        readPose = ShelfBooks.reading(camera, READ_DISTANCE, READ_DROP, READ_SCALE);
        Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.5f, 1.2f);
    }

    private void close(boolean bag) {
        toBag = bag;
        reading = false;
        move = Move.CLOSE;
        moveAt = Scene.clock();
        sent = false;
        Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.8f, 0.6f);
    }

    private void turn(int dir) {
        switch (book.turn(dir)) {
            case NO_PAGE -> Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.35f, 0.55f);
            case YES -> Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.9f, 0.9f + Fx.RANDOM.nextFloat() * 0.25f);
            default -> {}
        }
    }

    @Override
    public void scroll(double delta) {
        if (reading && move == Move.NONE) {
            turn(delta < 0 ? 1 : -1);
        }
    }

    @Override
    public boolean keyPressed(int key) {
        if (!reading || move != Move.NONE) {
            return false;
        }
        switch (key) {
            case InputConstants.KEY_RIGHT, InputConstants.KEY_PAGEDOWN -> turn(1);
            case InputConstants.KEY_LEFT, InputConstants.KEY_PAGEUP -> turn(-1);
            // the book goes back on the shelf first; the next Esc steps away
            case InputConstants.KEY_ESCAPE -> close(false);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean busy() {
        return move != Move.NONE || reading || gridEditor.editing();
    }

    private void send(StationActionPayload.Action action, int index, int button, boolean shift) {
        PacketDistributor.sendToServer(new StationActionPayload(pos, kind(), action, index, button, shift, 0));
    }

    @Override
    public void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!started && player != null && Scene.time > 0.1) {
            // the use that started the scene was a book in hand on an empty place: it goes in
            started = true;
            ItemStack hand = player.getMainHandItem();
            if (move == Move.NONE && usedSlot >= 0 && !occupied(usedSlot) && shelfBook(hand) && Hands.carried().isEmpty()) {
                put(usedSlot, player.getInventory().getSelectedSlot(), hand);
            }
        }
    }

    /** Every frame: the moves go on, the server is told at their moments, the book being read settles. */
    @Override
    public void beforeFrame() {
        gridEditor.frame();
        double t = Scene.clock() - moveAt;
        switch (move) {
            case TAKE -> {
                if (!sent && t >= (toBag ? BAGGED : GRIP)) {
                    sent = true;
                    send(StationActionPayload.Action.CLICK, moveSlot, 0, toBag);
                }
                if (t >= TAKEN) {
                    gone();
                }
            }
            case PUT -> {
                if (!sent && t >= PUT_AT) {
                    sent = true;
                    send(StationActionPayload.Action.PLACE, moveSlot, from, false);
                }
                if (t >= PUT_END) {
                    home();
                }
            }
            case OPEN -> {
                if (!sent && t >= GRIP) {
                    sent = true;
                    send(StationActionPayload.Action.CRAFT, moveSlot, 1, false);
                }
                if (t >= OPEN_END) {
                    move = Move.NONE;
                    reading = true;
                }
            }
            case CLOSE -> {
                if (toBag) {
                    if (!sent && t >= CLOSE_SHUT) {
                        // taken off the shelf from the hands (onlookers see it go from there, not back to the shelf first)
                        sent = true;
                        send(StationActionPayload.Action.CLICK, moveSlot, 0, true);
                    }
                    if (t >= CLOSE_GONE) {
                        gone();
                    }
                } else {
                    if (!sent && t >= CLOSE_HOME) {
                        sent = true;
                        send(StationActionPayload.Action.CRAFT, moveSlot, 0, false);
                    }
                    if (t >= CLOSE_END) {
                        home();
                    }
                }
            }
            default -> {}
        }
        double dt = Scene.frameDt;
        for (int i = 0; i < peek.length; i++) {
            boolean up = i == hoverSlot && target == Target.BOOK && move == Move.NONE && Scene.phase == Scene.Phase.ACTIVE;
            peek[i] = Anim.approach(peek[i], up ? PEEK : 0, 14, dt);
        }
        if (reading || move == Move.OPEN || move == Move.CLOSE) {
            book.read(moving);
            book.settle(hoverPage, reading && Scene.phase == Scene.Phase.ACTIVE, dt);
            pose().apply(book);
            book.preparePages();
        }
        int empty = 0;
        if (moveSlot >= 0 && (reading || move == Move.OPEN || move == Move.CLOSE || move == Move.PUT && t < PUT_HOME || move == Move.TAKE && t >= GRIP * 0.8)) {
            empty = 1 << moveSlot;
        }
        ShelfBooks.keepEmpty(pos, empty);
    }

    /** The book is gone from the shelf (into the hand or the bag): its place stays empty until the server says it is. */
    private void gone() {
        ShelfBooks.Pose there = handPose(moveSlot, toBag);
        ShelfBooks.fly(new ShelfBooks.Flight(pos, facing, moveSlot, moving, ownId(), List.of(new ShelfBooks.Key(0, () -> there)), false));
        move = Move.NONE;
        moveSlot = -1;
    }

    /** The book is home in its slot: it stays drawn there until the shelf shows it again. */
    private void home() {
        ShelfBooks.Pose there = ShelfBooks.inSlot(pos, facing, moveSlot, 0);
        ShelfBooks.fly(new ShelfBooks.Flight(pos, facing, moveSlot, moving, ownId(), List.of(new ShelfBooks.Key(0, () -> there)), true));
        move = Move.NONE;
        moveSlot = -1;
    }

    private static int ownId() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? -1 : player.getId();
    }

    /** Stepping away (or dropped) mid-move: the book finishes its way by itself. */
    @Override
    public void onEnd() {
        gridEditor.cancel();
        ShelfBooks.keepEmpty(pos, 0);
        if (moveSlot < 0 || move == Move.NONE && !reading) {
            return;
        }
        ShelfBooks.Pose now = pose();
        int slot = moveSlot;
        boolean gone = move == Move.TAKE && sent || move == Move.CLOSE && toBag && sent || move == Move.PUT && !sent;
        if (gone) {
            ShelfBooks.Pose away = handPose(slot, true);
            ShelfBooks.fly(new ShelfBooks.Flight(pos, facing, slot, moving, ownId(), List.of(new ShelfBooks.Key(0, () -> now), new ShelfBooks.Key(0.35, () -> away)),
                    false));
        } else {
            ShelfBooks.Pose clear = ShelfBooks.inSlot(pos, facing, slot, ShelfBooks.CLEAR);
            ShelfBooks.Pose home = ShelfBooks.inSlot(pos, facing, slot, 0);
            ShelfBooks.fly(new ShelfBooks.Flight(pos, facing, slot, moving, ownId(), List.of(new ShelfBooks.Key(0, () -> now),
                    new ShelfBooks.Key(0.3, () -> now.shut(1)), new ShelfBooks.Key(0.65, () -> clear), new ShelfBooks.Key(0.9, () -> home)), true));
        }
        move = Move.NONE;
        reading = false;
        moveSlot = -1;
    }

    // ---- where the book is ---------------------------------------------------------------------------------

    /**
     * Where a book taken into the hand ends up (just before the shelf, where the hand holds what it carries), or one into
     * the bag (up to the inventory drawer): small, shut, its cover up. A book put in from the hand sets off from there.
     */
    private ShelfBooks.Pose handPose(int slot, boolean bag) {
        CameraRig.Pose camera = CameraRig.last() != null ? CameraRig.last() : view();
        Vec3 at = bag ? camera.pos().add(0, 0.55, 0).add(Scene.yawForward(camera.yaw()).scale(0.6))
                : slotPoint(slot).add(side().scale(0.24)).add(0, -0.06, 0);
        return lying(at, slot, bag ? 0.25 : 0.55);
    }

    /** Low before the eyes, where the hand brings up a book it held when the scene began. */
    private ShelfBooks.Pose lowPose(int slot) {
        CameraRig.Pose camera = CameraRig.last() != null ? CameraRig.last() : view();
        return lying(camera.pos().add(Scene.yawForward(camera.yaw()).scale(0.45)).add(0, -0.38, 0), slot, 0.55);
    }

    /** A book of this slot lying shut with its cover up, its spine to the left, at {@code at}, {@code size} of itself. */
    private ShelfBooks.Pose lying(Vec3 at, int slot, double size) {
        return new ShelfBooks.Pose(at, ShelfBooks.basis(right(), forward(), new Vec3(0, 1, 0)), ShelfBooks.inSlot(pos, facing, slot, 0).size().scale(size), 1);
    }

    /** The book of the move under way (or being read) this frame. */
    private ShelfBooks.Pose pose() {
        double t = Scene.clock() - moveAt;
        ShelfBooks.Pose home = ShelfBooks.inSlot(pos, facing, moveSlot, 0);
        ShelfBooks.Pose clear = ShelfBooks.inSlot(pos, facing, moveSlot, ShelfBooks.CLEAR);
        if (reading && move == Move.NONE) {
            return readPose;
        }
        switch (move) {
            case TAKE -> {
                if (t < OUT) {
                    double out = peekAt + (ShelfBooks.CLEAR - peekAt) * Anim.inOutCubic(Anim.window(t, GRIP, OUT));
                    return ShelfBooks.inSlot(pos, facing, moveSlot, out);
                }
                double k = Anim.inOutCubic(Anim.window(t, OUT, TAKEN));
                return clear.to(handPose(moveSlot, toBag), k).moved(new Vec3(0, 0.07 * Anim.hump(k), 0));
            }
            case PUT -> {
                if (t < PUT_AT) {
                    double k = Anim.inOutCubic(Anim.window(t, 0, PUT_AT));
                    return fromHand.to(clear, k).moved(new Vec3(0, 0.06 * Anim.hump(k), 0));
                }
                double out = ShelfBooks.CLEAR * (1 - Anim.inOutCubic(Anim.window(t, PUT_AT, PUT_HOME)));
                return ShelfBooks.inSlot(pos, facing, moveSlot, out);
            }
            case OPEN -> {
                if (t < OPEN_OUT) {
                    double out = peekAt + (ShelfBooks.CLEAR - peekAt) * Anim.inOutCubic(Anim.window(t, GRIP, OPEN_OUT));
                    return ShelfBooks.inSlot(pos, facing, moveSlot, out);
                }
                if (t < OPEN_HELD) {
                    double k = Anim.inOutCubic(Anim.window(t, OPEN_OUT, OPEN_HELD));
                    return clear.to(readPose.shut(1), k).moved(new Vec3(0, 0.05 * Anim.hump(k), 0));
                }
                return readPose.shut(1 - Anim.smoother(Anim.window(t, OPEN_HELD + 0.02, OPEN_END)));
            }
            case CLOSE -> {
                if (t < CLOSE_SHUT) {
                    return readPose.shut(Anim.smoother(t / CLOSE_SHUT));
                }
                if (toBag) {
                    double k = Anim.inOutCubic(Anim.window(t, CLOSE_SHUT, CLOSE_GONE));
                    return readPose.shut(1).to(handPose(moveSlot, true), k);
                }
                if (t < CLOSE_AT) {
                    double k = Anim.inOutCubic(Anim.window(t, CLOSE_SHUT, CLOSE_AT));
                    return readPose.shut(1).to(clear, k).moved(new Vec3(0, 0.05 * Anim.hump(k), 0));
                }
                double out = ShelfBooks.CLEAR * (1 - Anim.inOutCubic(Anim.window(t, CLOSE_AT, CLOSE_HOME)));
                return ShelfBooks.inSlot(pos, facing, moveSlot, out);
            }
            default -> {
                return home;
            }
        }
    }

    // ---- hands ---------------------------------------------------------------------------------------------

    @Nullable
    @Override
    public Vec3[] handsOverride() {
        if (Scene.phase != Scene.Phase.ACTIVE || move == Move.NONE && !reading) {
            return null;
        }
        Vec3 restRight = rest(true), restLeft = rest(false);
        if (reading && move == Move.NONE) {
            readPose.apply(book);
            Vec3[] turning = book.turningHands();
            return turning != null ? turning : new Vec3[]{book.rest(true), book.rest(false)};
        }
        double t = Scene.clock() - moveAt;
        boolean leftWorks = moveSlot % 3 == 0;
        Vec3 working;
        Vec3 other = leftWorks ? restRight : restLeft;
        ShelfBooks.Pose where = pose();
        // the fist at the spine, a little towards the camera
        Vec3 spine = where.at(0, 0, OpenBook.STACK / 2);
        CameraRig.Pose camera = CameraRig.last() != null ? CameraRig.last() : view();
        Vec3 grip = spine.add(camera.pos().subtract(spine).normalize().scale(0.06)).add(0, -0.02, 0);
        Vec3 home = leftWorks ? restLeft : restRight;
        switch (move) {
            case TAKE -> {
                double reach = Anim.smoother(Anim.window(t, 0, GRIP));
                double back = Anim.smoother(Anim.window(t, TAKEN - 0.25, TAKEN));
                working = Anim.lerp(Anim.lerp(home, grip, reach), home, toBag ? back : back * 0.6);
            }
            case PUT -> {
                double back = Anim.smoother(Anim.window(t, PUT_HOME, PUT_END));
                working = Anim.lerp(grip, home, back);
            }
            case OPEN -> {
                double reach = Anim.smoother(Anim.window(t, 0, GRIP));
                working = Anim.lerp(home, grip, reach);
                if (t >= OPEN_HELD - 0.2) {
                    // both hands take the book and hold it open
                    readPose.apply(book);
                    double k = Anim.smoother(Anim.window(t, OPEN_HELD - 0.2, OPEN_HELD + 0.1));
                    Vec3 right = Anim.lerp(leftWorks ? restRight : working, book.rest(true), k);
                    Vec3 left = Anim.lerp(leftWorks ? working : restLeft, book.rest(false), k);
                    where.apply(book);
                    if (t >= OPEN_HELD) {
                        // the left hand turns the cover over
                        double cover = Anim.hump(Anim.window(t, OPEN_HELD, OPEN_END));
                        left = Anim.lerp(left, book.foldCorner(), cover * 0.8);
                    }
                    return new Vec3[]{right, left};
                }
            }
            case CLOSE -> {
                readPose.apply(book);
                Vec3 right = book.rest(true), left = book.rest(false);
                where.apply(book);
                if (t < CLOSE_SHUT) {
                    left = Anim.lerp(left, book.foldCorner(), Anim.hump(t / CLOSE_SHUT) * 0.8);
                    return new Vec3[]{right, left};
                }
                double k = Anim.smoother(Anim.window(t, CLOSE_SHUT, CLOSE_SHUT + 0.2));
                double back = Anim.smoother(Anim.window(t, toBag ? CLOSE_SHUT + 0.3 : CLOSE_HOME, toBag ? CLOSE_GONE : CLOSE_END));
                Vec3 carry = Anim.lerp(grip, home, back);
                Vec3 free = leftWorks ? Anim.lerp(right, restRight, k) : Anim.lerp(left, restLeft, k);
                Vec3 holding = Anim.lerp(leftWorks ? left : right, carry, k);
                return leftWorks ? new Vec3[]{free, holding} : new Vec3[]{holding, free};
            }
            default -> working = home;
        }
        return leftWorks ? new Vec3[]{other, working} : new Vec3[]{working, other};
    }

    @Override
    public double handsStiffness() {
        return 30;
    }

    // ---- drawing -------------------------------------------------------------------------------------------

    /** The books of this shelf the scene moves: the one under the cursor, the one in the hands. Every frame, every phase. */
    void renderBooks(PoseStack pose, SceneBuffers buffers, Camera camera, ClientLevel level) {
        Vec3 cam = camera.position();
        int light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, pos.relative(facing));
        double shake = Scene.clock() - denyAt < 0.3 ? 0.006 * Math.sin((Scene.clock() - denyAt) * 60) : 0;
        for (int i = 0; i < peek.length; i++) {
            boolean shaking = shake != 0 && i == hoverSlot;
            if ((peek[i] > 0.001 || shaking || ShelfLayoutBooks.drawsRestingBooks()) && occupied(i) && ShelfBooks.flight(pos, i) == null) {
                ShelfBooks.Pose there = ShelfBooks.inSlot(pos, facing, i, peek[i]).moved(right().scale(shaking ? shake : 0));
                ShelfBooks.draw(pose, buffers, cam, i, bookIn(i), there, shut, light);
            }
        }
        if (moveSlot >= 0 && (move != Move.NONE || reading)) {
            // open in the hands it is lit well enough to read, whatever the room
            boolean open = reading || move == Move.OPEN && Scene.clock() - moveAt > OPEN_HELD || move == Move.CLOSE;
            int lit = open ? LightCoordsUtil.pack(Math.max(LightCoordsUtil.block(light), 11), LightCoordsUtil.sky(light)) : light;
            if (move == Move.PUT || move == Move.TAKE) {
                ShelfBooks.draw(pose, buffers, cam, moveSlot, moving, pose(), shut, lit);
            } else {
                book.look = ShelfBooks.look(moveSlot);
                pose().apply(book);
                book.render(pose, buffers, cam, lit);
            }
        }
    }

    /** The place under the cursor framed, enchanted books shimmering on the shelf. */
    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        double clock = Scene.clock();
        if (!reading) {
            for (int i = 0; i < ShelfBooks.SPINES.length; i++) {
                if (occupied(i) && bookIn(i).is(Items.ENCHANTED_BOOK)) {
                    float g = (float) (0.13 + 0.06 * Math.sin(clock * 2.1 + i * 1.7));
                    Fx.facePatch(pose, buffers, camera, slotPoint(i).add(side().scale(0.004 + peek[i])), right(), new Vec3(0, 1, 0), 0.17,
                            0.75f * g, 0.38f * g, g);
                    if (Fx.RANDOM.nextDouble() < Scene.frameDt * 1.5) {
                        Fx.flakes(slotPoint(i).add(side().scale(0.04)), 0.07, 1);
                    }
                }
            }
        }
        if (hoverSlot < 0 || move != Move.NONE || reading) {
            return;
        }
        boolean ok = target == Target.BOOK || shelfBook(Hands.carried());
        int[] r = ShelfBooks.SPINES[hoverSlot];
        double out = target == Target.BOOK ? peek[hoverSlot] + ShelfBooks.PROUD + 0.002 : 0.003;
        Vec3 a = ShelfBooks.face(pos, facing, r[0] - 0.5, r[1] - 0.5, out), b = ShelfBooks.face(pos, facing, r[2] + 0.5, r[1] - 0.5, out);
        Vec3 c = ShelfBooks.face(pos, facing, r[2] + 0.5, r[3] + 0.5, out), d = ShelfBooks.face(pos, facing, r[0] - 0.5, r[3] + 0.5, out);
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            double[] half = geometry().half(layoutFrame(), hoverSlot);
            Vec3 at = slotPoint(hoverSlot).add(side().scale(out));
            Vec3 u = geometry().axis(layoutFrame(), hoverSlot, true).scale(half[0]);
            Vec3 v = geometry().axis(layoutFrame(), hoverSlot, false).scale(half[1]);
            a = at.subtract(u).add(v); b = at.add(u).add(v); c = at.add(u).subtract(v); d = at.subtract(u).subtract(v);
        }
        int[] tint = ok ? new int[]{255, 236, 200} : new int[]{255, 70, 60};
        VertexConsumer vc = buffers.getBuffer(Fx.HIGHLIGHT);
        int alpha = target == Target.BOOK ? 30 : 44;
        for (Vec3 p : new Vec3[]{a, b, c, d}) {
            vc.addVertex(pose.last().pose(), (float) (p.x - camera.x), (float) (p.y - camera.y), (float) (p.z - camera.z)).setColor(tint[0], tint[1], tint[2], alpha);
        }
    }

    // ---- words ---------------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] editorMouse = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(editorMouse[0], editorMouse[1]);
        if (editorTip != null || gridEditor.editing()) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        if (target == Target.BOOK) {
            ItemStack there = bookIn(hoverSlot);
            List<Component> lines = new ArrayList<>(there.isEmpty() ? List.of(Component.translatable("cinematic.shelf.book"))
                    : Screen.getTooltipFromItem(mc, there));
            lines.add(Component.empty());
            if (readable(there)) {
                lines.add(Component.translatable("cinematic.shelf.read").withStyle(ChatFormatting.GOLD));
                lines.add(Component.translatable("cinematic.shelf.take_right").withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("cinematic.shelf.take").withStyle(ChatFormatting.GOLD));
            }
            lines.add(Component.translatable("cinematic.shelf.take_bag").withStyle(ChatFormatting.GRAY));
            return lines;
        }
        if (target == Target.PLACE) {
            if (shelfBook(carried)) {
                return List.of(carried.getHoverName(), Component.translatable("cinematic.shelf.put").withStyle(ChatFormatting.GOLD));
            }
            return List.of(Component.translatable(carried.isEmpty() ? "cinematic.shelf.empty" : "cinematic.shelf.not_book")
                    .withStyle(carried.isEmpty() ? ChatFormatting.GRAY : ChatFormatting.RED));
        }
        return null;
    }

    @Override
    public Component hint() {
        if (reading || move == Move.OPEN || move == Move.CLOSE) {
            return Component.translatable("cinematic.shelf.hint.read");
        }
        return Component.translatable(shelfBook(Hands.carried()) ? "cinematic.shelf.hint.put" : "cinematic.shelf.hint");
    }

    // ---- for the showcase ----------------------------------------------------------------------------------

    public boolean reading() {
        return reading;
    }

    public int spread() {
        return book.spread;
    }

    public Vec3 pageCenter(boolean left) {
        return book.pageCenter(left);
    }

    /** The move under way and how far into it, in seconds. */
    public String moveName() {
        return move.name().toLowerCase(Locale.ROOT) + String.format(Locale.ROOT, " %.2f", move == Move.NONE ? 0 : Scene.clock() - moveAt);
    }
}
