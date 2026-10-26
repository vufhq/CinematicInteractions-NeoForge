package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.net.MenuFxPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;

import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The books of chiseled bookshelves as real books, while they move: drawn out by the spine, carried, slid back in, held
 * open to be read. In a slot the shelf's own model shows a book as a flat picture of its spine
 * ({@code chiseled_bookshelf_occupied.png}); a book of ours is an {@link OpenBook} shut, squat and thick, its spine that
 * very picture and its boards that book's colour, so it is the same book.
 * <p>
 * While a book of ours is out of its slot, the slot is drawn empty: the shelf's model is wrapped and asked for the block
 * with those slots empty (the block itself is untouched). A book of ours standing in its slot stands a hair proud of the
 * shelf's face and covers the picture of its spine, so when the picture goes or comes back (the chunk is drawn again a
 * frame or two later) nothing shows. What other players do at a shelf comes as cues ({@link MenuFxPayload}): their
 * books move the same way, towards and from them.
 */
public final class ShelfBooks {
    private ShelfBooks() {}

    /**
     * The spine of the book in each slot as the occupied texture draws it, in its pixels from the top-left corner as seen
     * from the front: {u0, v0, u1, v1}.
     */
    static final int[][] SPINES = {{1, 2, 5, 7}, {6, 1, 10, 7}, {11, 1, 15, 7}, {1, 9, 5, 15}, {6, 10, 10, 15}, {11, 9, 15, 15}};
    private static final String TEXTURE = "block/chiseled_bookshelf_occupied";
    /** How deep a book reaches into the shelf from its spine. */
    static final double DEPTH = 0.32;
    /** How far a book stands out of the shelf's face in its slot, to cover the picture of its spine. */
    static final double PROUD = 0.004;
    /** Out of its slot: far enough that it no longer goes into the shelf. */
    static final double CLEAR = DEPTH + 0.05;
    private static final Vec3 UP = new Vec3(0, 1, 0);

    // ---- where a book is ------------------------------------------------------------------------------

    /** Where a book is: its frame's origin, the turn of its frame (a, s, n along x, y, z), each axis's length, how shut it is. */
    record Pose(Vec3 origin, Quaternionf turn, Vec3 size, double fold) {
        Pose to(Pose other, double k) {
            return new Pose(Anim.lerp(origin, other.origin, k), new Quaternionf(turn).slerp(other.turn, (float) k), Anim.lerp(size, other.size, k),
                    Mth.lerp(k, fold, other.fold));
        }

        Pose moved(Vec3 by) {
            return new Pose(origin.add(by), turn, size, fold);
        }

        Pose scaled(double k) {
            return new Pose(origin, turn, size.scale(k), fold);
        }

        Pose shut(double fold) {
            return new Pose(origin, turn, size, fold);
        }

        Vec3 axis(float x, float y, float z, double length) {
            Vector3f v = turn.transform(new Vector3f(x, y, z));
            return new Vec3(v.x(), v.y(), v.z()).scale(length);
        }

        void apply(OpenBook book) {
            book.place(origin, axis(1, 0, 0, size.x), axis(0, 1, 0, size.y), axis(0, 0, 1, size.z));
            book.fold = fold;
            book.closing = fold > 0.001;
        }

        /** A point of the book in its frame (see {@link OpenBook#at}). */
        Vec3 at(double x, double v, double h) {
            return origin.add(axis(1, 0, 0, size.x * x)).add(axis(0, 1, 0, size.y * v)).add(axis(0, 0, 1, size.z * h));
        }
    }

    /** The turn that lays a book's a, s, n along these three directions. */
    static Quaternionf basis(Vec3 a, Vec3 s, Vec3 n) {
        return new Matrix3f((float) a.x, (float) a.y, (float) a.z, (float) s.x, (float) s.y, (float) s.z, (float) n.x, (float) n.y, (float) n.z)
                .getNormalizedRotation(new Quaternionf());
    }

    static Vec3 out(Direction facing) {
        return Vec3.atLowerCornerOf(facing.getUnitVec3i());
    }

    /** To the right of someone facing the shelf. */
    static Vec3 right(Direction facing) {
        return Vec3.atLowerCornerOf(facing.getCounterClockWise().getUnitVec3i());
    }

    /** A point of the shelf's face: {@code u}, {@code v} pixels from its top-left corner as seen from the front, {@code out} in front of it. */
    static Vec3 face(BlockPos pos, Direction facing, double u, double v, double out) {
        return Vec3.atCenterOf(pos).add(out(facing).scale(0.5 + out)).add(right(facing).scale(u / 16 - 0.5)).add(0, 0.5 - v / 16, 0);
    }

    /** The middle of the spine of the book in a slot, as it stands in it. */
    static Vec3 spine(BlockPos pos, Direction facing, int slot) {
        return SpatialLayouts.current(dev.dyslexic.cinematic.station.StationKind.CHISELED_BOOKSHELF).point(
                SpatialAppearance.Frame.of(dev.dyslexic.cinematic.station.StationKind.CHISELED_BOOKSHELF, pos, facing, facing), slot);
    }

    /** The book in a slot, standing in it shut, its spine out, {@code out} drawn out of it. */
    static Pose inSlot(BlockPos pos, Direction facing, int slot, double out) {
        int[] r = SPINES[slot];
        Vec3 size = new Vec3(DEPTH / OpenBook.DEEP, (r[3] - r[1]) / 16.0 / OpenBook.TALL, (r[2] - r[0]) / 16.0 / OpenBook.THICK);
        SpatialAppearance layout = SpatialLayouts.current(dev.dyslexic.cinematic.station.StationKind.CHISELED_BOOKSHELF);
        var frame = SpatialAppearance.Frame.of(dev.dyslexic.cinematic.station.StationKind.CHISELED_BOOKSHELF, pos, facing, facing);
        size = size.scale(layout.scale(slot));
        Vec3 side = out(facing), right = layout.axis(frame, slot, true), up = layout.axis(frame, slot, false);
        out *= layout.scale(slot);
        // the frame's origin lies on the back board, half the pages' thickness from the middle of the spine
        Vec3 origin = spine(pos, facing, slot).add(side.scale(out)).subtract(right.scale(OpenBook.STACK / 2 * size.z));
        return new Pose(origin, basis(side.scale(-1), up, right), size, 1);
    }

    /**
     * How a book of this slot looks: its spine as the shelf draws it, its boards in its colour with the bands of its spine
     * running across them (one column of the spine, drawn across the board).
     */
    static OpenBook.Look look(int slot) {
        int[] r = SPINES[slot];
        float column = r[0] + 1;
        return new OpenBook.Look(TEXTURE, (column + 0.3f) / 16f, r[1] / 16f, (column + 0.7f) / 16f, r[3] / 16f,
                r[0] / 16f, r[1] / 16f, r[2] / 16f, r[3] / 16f);
    }

    /**
     * Held open to be read before a camera: the pages square to the view, {@code distance} in front of it. The book is
     * {@code scale} of its size every way (a leaf turning out of a squat book would come at the camera).
     */
    static Pose reading(CameraRig.Pose camera, double distance, double drop, double scale) {
        Vec3 forward = Vec3.directionFromRotation(camera.pitch(), camera.yaw());
        Vec3 right = forward.cross(UP).normalize();
        Vec3 up = right.cross(forward).normalize();
        return new Pose(camera.pos().add(forward.scale(distance)).add(up.scale(-drop)), basis(right, up, forward.scale(-1)),
                new Vec3(scale, scale, scale), 0);
    }

    /** Held open in front of someone's chest, tilted up towards their face (others see them read). */
    static Pose held(Entity reader, float partial) {
        float yaw = reader instanceof net.minecraft.world.entity.LivingEntity living ? Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot)
                : reader.getViewYRot(partial);
        Vec3 forward = Vec3.directionFromRotation(0, yaw);
        Vec3 right = forward.cross(UP).normalize();
        double tilt = Math.toRadians(52);
        Vec3 n = UP.scale(Math.sin(tilt)).subtract(forward.scale(Math.cos(tilt)));
        Vec3 s = UP.scale(Math.cos(tilt)).add(forward.scale(Math.sin(tilt)));
        Vec3 origin = reader.getEyePosition(partial).add(0, reader.isCrouching() ? -0.4 : -0.5, 0).add(forward.scale(0.34));
        return new Pose(origin, basis(right, s, n), new Vec3(0.5, 0.5, 0.5), 0);
    }

    /** Shut and small at someone's chest: where a book taken off the shelf goes, and one put in comes from. */
    static Pose atChest(Entity who, BlockPos pos, Direction facing, int slot, float partial) {
        Pose slotted = inSlot(pos, facing, slot, CLEAR);
        Vec3 chest = who.getEyePosition(partial).add(0, -0.55, 0).add(Vec3.directionFromRotation(0, who.getViewYRot(partial)).scale(0.25));
        return new Pose(chest, slotted.turn(), slotted.size().scale(0.6), 1);
    }

    // ---- drawing ------------------------------------------------------------------------------------

    /** A book of a shelf, wherever it is: shut, opening or open. */
    static void draw(PoseStack pose, SceneBuffers buffers, Vec3 cam, int slot, ItemStack stack, Pose where, OpenBook book, int light) {
        book.look = look(slot);
        where.apply(book);
        if (book.spread < 0) {
            book.openAt(0);
        }
        book.render(pose, buffers, cam, light);
        if (stack.is(Items.ENCHANTED_BOOK) && Fx.RANDOM.nextDouble() < Scene.frameDt * 6) {
            // an enchanted book shows what it is wherever it goes
            Fx.flakes(where.at(OpenBook.DEEP / 2, 0, OpenBook.STACK), 0.05, 1);
        }
    }

    // ---- books in flight ----------------------------------------------------------------------------

    /** A keyframe of a flight: where the book is this many seconds after it set off (worked out as it is drawn). */
    record Key(double t, Supplier<Pose> pose) {}

    /**
     * A book on its way: through keyframes, eased between them. One that ends in its slot shows there until the shelf's
     * own picture of it is back; one that ends elsewhere is gone at the end; one that is {@link #held} stays at its last
     * keyframe (being read) until another flight takes it on.
     */
    static final class Flight {
        final BlockPos pos;
        final Direction facing;
        final int slot;
        final ItemStack book;
        final int entityId;
        final double at;
        final List<Key> keys;
        final boolean intoSlot;
        boolean held;
        final OpenBook open = new OpenBook();

        Flight(BlockPos pos, Direction facing, int slot, ItemStack book, int entityId, List<Key> keys, boolean intoSlot) {
            this.pos = pos.immutable();
            this.facing = facing;
            this.slot = slot;
            this.book = book;
            this.entityId = entityId;
            this.at = Scene.clock();
            this.keys = keys;
            this.intoSlot = intoSlot;
        }

        double length() {
            return keys.get(keys.size() - 1).t();
        }

        double time() {
            return Scene.clock() - at;
        }

        Pose pose() {
            double t = time();
            for (int i = 1; i < keys.size(); i++) {
                Key next = keys.get(i);
                if (t < next.t()) {
                    Key from = keys.get(i - 1);
                    double k = Anim.inOutCubic(Anim.window(t, from.t(), next.t()));
                    return from.pose().get().to(next.pose().get(), k);
                }
            }
            return keys.get(keys.size() - 1).pose().get();
        }
    }

    private static final List<Flight> FLIGHTS = new ArrayList<>();
    /** Slots kept empty by the scene at a shelf (its books out, or on their way in). */
    private static final Map<BlockPos, Integer> LOCAL = new HashMap<>();
    /** Slots each shelf is drawn without (read by the chunk builder, perhaps on another thread). */
    private static final Map<BlockPos, Integer> HIDDEN = new ConcurrentHashMap<>();

    static void fly(Flight flight) {
        FLIGHTS.removeIf(f -> f.pos.equals(flight.pos) && f.slot == flight.slot);
        FLIGHTS.add(flight);
    }

    /** The flight of a book of this slot under way, if any. */
    @Nullable
    static Flight flight(BlockPos pos, int slot) {
        for (Flight f : FLIGHTS) {
            if (f.pos.equals(pos) && f.slot == slot) {
                return f;
            }
        }
        return null;
    }

    /** The scene at a shelf keeps these slots empty this frame (0: none). */
    static void keepEmpty(BlockPos pos, int slots) {
        if (slots == 0) {
            LOCAL.remove(pos);
        } else {
            LOCAL.put(pos.immutable(), slots);
        }
    }

    /** Someone else took a book off a shelf, put one in, or took one out to read it or put it back. */
    static void cue(MenuFxPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || payload.entityId() == mc.player.getId()) {
            return;
        }
        BlockState state = mc.level.getBlockState(payload.pos());
        int slot = payload.variant();
        if (!state.is(Blocks.CHISELED_BOOKSHELF) || slot < 0 || slot >= SPINES.length) {
            return;
        }
        Direction facing = state.getValue(HorizontalDirectionalBlock.FACING);
        BlockPos pos = payload.pos();
        int id = payload.entityId();
        Supplier<Pose> chest = () -> {
            Entity who = mc.level.getEntity(id);
            return who == null ? inSlot(pos, facing, slot, CLEAR + 0.5).scaled(0.6) : atChest(who, pos, facing, slot, 0);
        };
        Supplier<Pose> out = () -> inSlot(pos, facing, slot, CLEAR);
        Supplier<Pose> home = () -> inSlot(pos, facing, slot, 0);
        switch (payload.cue()) {
            case PULL -> {
                Flight was = flight(pos, slot);
                if (was != null && was.held) {
                    // taken from the hands that were reading it
                    Pose from = was.pose();
                    fly(new Flight(pos, facing, slot, payload.result(), id, List.of(new Key(0, () -> from), new Key(0.3, () -> from.shut(1)),
                            new Key(0.7, () -> chest.get().scaled(0.2))), false));
                } else {
                    fly(new Flight(pos, facing, slot, payload.result(), id, List.of(new Key(0, home), new Key(0.3, out),
                            new Key(0.75, () -> chest.get().scaled(0.2))), false));
                }
            }
            case PUT -> fly(new Flight(pos, facing, slot, payload.result(), id, List.of(new Key(0, () -> chest.get().scaled(0.2)), new Key(0.4, out),
                    new Key(0.75, home)), true));
            case READ -> {
                Supplier<Pose> hands = () -> {
                    Entity who = mc.level.getEntity(id);
                    return who == null ? inSlot(pos, facing, slot, CLEAR + 0.4).shut(0) : held(who, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
                };
                Flight reading = new Flight(pos, facing, slot, payload.result(), id, List.of(new Key(0, home), new Key(0.3, out),
                        new Key(0.8, () -> hands.get().shut(1)), new Key(1.2, hands)), false);
                reading.held = true;
                fly(reading);
            }
            case READ_END -> {
                Flight was = flight(pos, slot);
                Pose from = was != null ? was.pose() : inSlot(pos, facing, slot, CLEAR);
                fly(new Flight(pos, facing, slot, payload.result(), id, List.of(new Key(0, () -> from), new Key(0.35, () -> from.shut(1)),
                        new Key(0.8, out), new Key(1.1, home)), true));
            }
            default -> {}
        }
    }

    /** Draws the books on their way, the scene's own too, and keeps the slots they left drawn empty. */
    public static void renderAll(PoseStack pose, SceneBuffers buffers, Camera camera, Frustum frustum, ClientLevel level) {
        Vec3 cam = camera.position();
        ShelfStation mine = Scene.station(ShelfStation.class);
        if (mine != null) {
            mine.renderBooks(pose, buffers, camera, level);
        }
        if (FLIGHTS.isEmpty() && LOCAL.isEmpty() && HIDDEN.isEmpty()) {
            // no shelf book anywhere is out of its slot: nothing else to do
            return;
        }
        Map<BlockPos, Integer> masks = new HashMap<>(LOCAL);
        Iterator<Flight> it = FLIGHTS.iterator();
        while (it.hasNext()) {
            Flight f = it.next();
            BlockState state = level.getBlockState(f.pos);
            double t = f.time();
            boolean occupied = state.is(Blocks.CHISELED_BOOKSHELF) && state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(f.slot));
            // a book put in shows in its slot until the shelf draws it again (or for a while, should the shelf refuse it)
            boolean done = !state.is(Blocks.CHISELED_BOOKSHELF) || (f.intoSlot ? t > f.length() + 0.3 && occupied || t > f.length() + 1.5
                    : !f.held && t > f.length() && (!occupied || t > f.length() + 1.5));
            if (done) {
                it.remove();
                continue;
            }
            if (!f.intoSlot || t < f.length()) {
                masks.merge(f.pos, 1 << f.slot, (x, y) -> x | y);
            }
            if (f.intoSlot || f.held || t < f.length()) {
                if (frustum.isVisible(new AABB(f.pos).inflate(1.5))) {
                    int light = net.minecraft.util.LightCoordsUtil.getLightCoords(level, f.pos.relative(f.facing));
                    draw(pose, buffers, cam, f.slot, f.book, f.pose(), f.open, light);
                }
            }
        }
        applyMasks(masks);
    }

    private static void applyMasks(Map<BlockPos, Integer> masks) {
        Minecraft mc = Minecraft.getInstance();
        List<BlockPos> changed = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> e : masks.entrySet()) {
            Integer was = HIDDEN.get(e.getKey());
            if (was == null || !was.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (BlockPos pos : HIDDEN.keySet()) {
            if (!masks.containsKey(pos)) {
                changed.add(pos);
            }
        }
        for (BlockPos pos : changed) {
            Integer mask = masks.get(pos);
            if (mask == null || mask == 0) {
                HIDDEN.remove(pos);
            } else {
                HIDDEN.put(pos, mask);
            }
            // the shelf's chunk section is built again, with the slots as they are drawn now
            mc.levelExtractor.setSectionDirty(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                    SectionPos.blockToSectionCoord(pos.getZ()));
        }
    }

    /** Whether this player is reading a book taken off a shelf (for their pose). */
    public static boolean reading(int entityId) {
        for (Flight f : FLIGHTS) {
            if (f.held && f.entityId == entityId) {
                return true;
            }
        }
        return false;
    }

    /** Someone out of sight: a book they were reading is drawn back in its slot. */
    static void forgetEntity(int entityId) {
        FLIGHTS.removeIf(f -> f.entityId == entityId && f.held);
    }

    static void unloadChunk(ChunkPos chunk) {
        ShelfLayoutBooks.unloadChunk(chunk);
        FLIGHTS.removeIf(f -> f.pos.getX() >> 4 == chunk.x() && f.pos.getZ() >> 4 == chunk.z());
    }

    static void clear() {
        ShelfLayoutBooks.clear();
        FLIGHTS.clear();
        LOCAL.clear();
        HIDDEN.clear();
    }

    /** Slot pictures hidden while their physical books are out, read by the Fabric model wrapper. */
    public static int emptyMask(BlockPos pos) { return ClientOnly.serverHasMod() ? SpatialLayouts.customShelf() ? 63 : HIDDEN.getOrDefault(pos, 0) : 0; }
}
