package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.net.BrewingContentsPayload;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/** Client copy of what stands in nearby brewing stands (the server sends it; the stand's own data does not carry it). */
public final class ClientBrewing {
    private ClientBrewing() {}

    private static final Map<BlockPos, ItemStack[]> STANDS = new HashMap<>();

    public static void apply(BrewingContentsPayload payload) {
        ItemStack[] slots = new ItemStack[BrewingStation.FUEL + 1];
        for (int i = 0; i < slots.length; i++) slots[i] = i < payload.items().size() ? payload.items().get(i) : ItemStack.EMPTY;
        STANDS.put(payload.pos().immutable(), slots);
    }

    /**
     * A slot of the stand as the server last told it. A stand the server never mentioned is bare, unless another mod
     * syncs the stand's own items (Amendments does): those are the same stacks.
     */
    public static ItemStack item(BrewingStandBlockEntity stand, int slot) {
        ItemStack[] slots = STANDS.get(stand.getBlockPos());
        return slots != null ? slots[slot] : stand.getItem(slot);
    }

    public static void unloadChunk(ChunkPos chunk) {
        STANDS.keySet().removeIf(pos -> pos.getX() >> 4 == chunk.x() && pos.getZ() >> 4 == chunk.z());
    }

    public static void clear() {
        STANDS.clear();
    }
}
