package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.net.MenuDisplayPayload;
import dev.dyslexic.cinematic.net.MenuFxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

/**
 * What onlookers see at anvils, grindstones, smithing tables and stonecutters other players work at: what lies on the
 * block, when the last big moment happened there (sparks), and the whole move under way at an anvil, a grindstone or a
 * stonecutter (forging, grinding, cutting), as the player at it sees it.
 */
public final class ClientMenus {
    private ClientMenus() {}

    public static final class Entry {
        public StationKind kind;
        public Direction side = Direction.SOUTH;
        public List<ItemStack> items = List.of();
        public double fxAt = -10;
        /** Someone's move here: when it began, what lay on the block then, what came of it, whether it went into the bag. */
        public double moveAt = -10;
        public List<ItemStack> moveItems = List.of();
        public ItemStack moveResult = ItemStack.EMPTY;
        public boolean moveToBag;
        public int moveBy = -1;
        /** The grindstone's wheel as onlookers see it: its speed and how far its texture has run round. */
        public double spin, run;

        /** What lay in an input slot when the move began. */
        public ItemStack moved(int slot) {
            return slot < moveItems.size() ? moveItems.get(slot) : ItemStack.EMPTY;
        }

        /** Where the one doing the move has the result go: their chest (or over their head, into the bag). */
        public Vec3 taker(Vec3 fallback) {
            Minecraft mc = Minecraft.getInstance();
            Entity who = mc.level == null ? null : mc.level.getEntity(moveBy);
            if (who == null) {
                return fallback;
            }
            Vec3 eye = who.getEyePosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            return moveToBag ? eye.add(0, 0.25, 0) : eye.add(0, -0.55, 0);
        }
    }

    public static final Map<BlockPos, Entry> BLOCKS = new HashMap<>();
    /** When each player's move began (forging, grinding, cutting), for their arms seen from outside. */
    public static final Map<Integer, Double> MOVE_BY_ENTITY = new HashMap<>();

    public static void apply(MenuDisplayPayload payload) {
        if (payload.items().stream().allMatch(ItemStack::isEmpty)) {
            Entry entry = BLOCKS.get(payload.pos());
            if (entry != null && (Scene.clock() - entry.fxAt < 2 || Scene.clock() - entry.moveAt < 3)) {
                // let the sparks of a last blow (or the move under way) play out
                entry.items = List.of();
            } else {
                BLOCKS.remove(payload.pos());
            }
            return;
        }
        Entry entry = BLOCKS.computeIfAbsent(payload.pos().immutable(), p -> new Entry());
        entry.kind = payload.kind();
        entry.side = Direction.from2DDataValue(payload.side());
        entry.items = payload.items();
    }

    public static void fx(MenuFxPayload payload) {
        Entry entry = BLOCKS.computeIfAbsent(payload.pos().immutable(), p -> new Entry());
        entry.kind = payload.kind();
        entry.fxAt = Scene.clock();
    }

    /** Someone began a move: onlookers play the whole of it from here. */
    public static void move(MenuFxPayload payload) {
        Entry entry = BLOCKS.computeIfAbsent(payload.pos().immutable(), p -> new Entry());
        entry.kind = payload.kind();
        entry.moveAt = Scene.clock();
        entry.moveItems = payload.items();
        entry.moveResult = payload.result();
        entry.moveToBag = payload.variant() == 1;
        entry.moveBy = payload.entityId();
        MOVE_BY_ENTITY.put(payload.entityId(), Scene.clock());
    }

    /** Seconds since this player's move began at a station, or a large number. */
    public static double sinceMove(int entityId) {
        Double at = MOVE_BY_ENTITY.get(entityId);
        return at == null ? 1e9 : Scene.clock() - at;
    }

    /** A player went out of sight: their last big moment is of no use any more. */
    public static void forgetEntity(int entityId) {
        MOVE_BY_ENTITY.remove(entityId);
    }

    public static void unloadChunk(ChunkPos chunk) {
        BLOCKS.keySet().removeIf(pos -> pos.getX() >> 4 == chunk.x() && pos.getZ() >> 4 == chunk.z());
    }

    public static void clear() {
        BLOCKS.clear();
        MOVE_BY_ENTITY.clear();
    }
}
