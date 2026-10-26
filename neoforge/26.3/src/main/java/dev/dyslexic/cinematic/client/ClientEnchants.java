package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.net.EnchantDisplayPayload;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

/** What onlookers see over enchanting tables other players work at: the floating item, the lapis, the last flash. */
public final class ClientEnchants {
    private ClientEnchants() {}

    public static final class Entry {
        public ItemStack item = ItemStack.EMPTY;
        public int lapis;
        public Direction side = Direction.SOUTH;
        public double flashAt = -10;
    }

    public static final Map<BlockPos, Entry> TABLES = new HashMap<>();

    @Nullable
    public static Entry get(BlockPos pos) {
        return TABLES.get(pos);
    }

    public static void apply(EnchantDisplayPayload payload) {
        Entry entry = TABLES.computeIfAbsent(payload.pos().immutable(), p -> new Entry());
        entry.item = payload.item();
        entry.lapis = payload.lapis();
        entry.side = Direction.from2DDataValue(payload.side());
        if (entry.item.isEmpty() && entry.lapis == 0 && Scene.clock() - entry.flashAt > 2) {
            TABLES.remove(payload.pos());
        }
    }

    /** Someone enchanted at this table: light it up for onlookers. */
    public static void flash(BlockPos pos) {
        TABLES.computeIfAbsent(pos.immutable(), p -> new Entry()).flashAt = Scene.clock();
        EnchantFx.burst(EnchantStation.itemPosition(pos, TABLES.get(pos).side, Scene.clock()), 0.7);
    }

    public static void unloadChunk(ChunkPos chunk) {
        TABLES.keySet().removeIf(pos -> pos.getX() >> 4 == chunk.x() && pos.getZ() >> 4 == chunk.z());
    }

    public static void clear() {
        TABLES.clear();
    }
}
