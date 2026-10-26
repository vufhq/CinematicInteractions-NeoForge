package dev.dyslexic.cinematic.furnace;

import dev.dyslexic.cinematic.net.FurnaceContentsPayload;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import dev.dyslexic.cinematic.net.PacketDistributor;

/**
 * Vanilla never tells clients what is inside a furnace. To show coal in the fire box and ore in the upper
 * opening to everyone, each furnace tick compares a cheap fingerprint of its three slots and, when it changes,
 * sends the slots to the players watching that chunk.
 */
public final class FurnaceWatch {
    private FurnaceWatch() {}

    /** Mixed into the furnace block entity so the last sent fingerprint lives with the furnace itself. */
    public interface Fingerprinted {
        int cinematic$fingerprint();

        void cinematic$setFingerprint(int fingerprint);
    }

    /** Called at the end of every server tick of a furnace. */
    public static void afterTick(Level level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity furnace) {
        if (!(level instanceof ServerLevel server) || !shown(state) || !(furnace instanceof Fingerprinted marked)) {
            return;
        }
        int fingerprint = fingerprint(furnace);
        if (fingerprint != marked.cinematic$fingerprint()) {
            marked.cinematic$setFingerprint(fingerprint);
            sync(server, pos, furnace);
        }
    }

    /** Furnaces whose contents are drawn: the three vanilla ones. */
    public static boolean shown(BlockState state) {
        StationKind kind = StationKind.of(state);
        return kind != null && kind.isFurnace();
    }

    /**
     * Which stacks lie in the slots and how many of each: a furnace shrinks and grows its stacks in place and puts a new
     * stack in a slot for anything else, so this changes whenever what is shown does. It runs every tick of every
     * furnace, so it does not hash the items' components (an equal stack put in place of another only costs a resend).
     */
    public static int contentsFingerprint(Container container, int slots) {
        int hash = 1;
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = container.getItem(slot);
            hash = hash * 31 + (stack.isEmpty() ? 0 : System.identityHashCode(stack) * 67 + stack.getCount());
        }
        return hash;
    }

    private static int fingerprint(AbstractFurnaceBlockEntity furnace) {
        return contentsFingerprint(furnace, 3);
    }

    public static FurnaceContentsPayload contents(BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        List<ItemStack> items = new ArrayList<>(3);
        boolean any = false;
        for (int slot = 0; slot < 3; slot++) {
            ItemStack stack = furnace.getItem(slot);
            any |= !stack.isEmpty();
            items.add(stack.copy());
        }
        return new FurnaceContentsPayload(pos, any ? items : List.of());
    }

    public static void sync(ServerLevel level, BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        if (furnace instanceof Fingerprinted marked) {
            marked.cinematic$setFingerprint(fingerprint(furnace));
        }
        PacketDistributor.sendToPlayersTrackingChunk(level, ChunkPos.containing(pos), contents(pos, furnace));
    }

    /** A chunk reached a player: tell them what lies in its furnaces. */
    public static void chunkSent(ServerPlayer player, LevelChunk chunk) {
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (entity instanceof AbstractFurnaceBlockEntity furnace && shown(entity.getBlockState())) {
                FurnaceContentsPayload payload = contents(entity.getBlockPos(), furnace);
                if (!payload.items().isEmpty()) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
    }
}
