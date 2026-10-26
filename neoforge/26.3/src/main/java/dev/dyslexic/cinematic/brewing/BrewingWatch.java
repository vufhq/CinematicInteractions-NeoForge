package dev.dyslexic.cinematic.brewing;

import dev.dyslexic.cinematic.furnace.FurnaceWatch;
import dev.dyslexic.cinematic.net.BrewingContentsPayload;
import dev.dyslexic.cinematic.net.PacketDistributor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The brewing stand's bottles are drawn by the mod as the real potions (the stand's own bottle pictures are taken
 * out of its model), so clients need to know what stands in it. Vanilla sends nothing; every server tick a cheap
 * fingerprint of the slots is compared, and when it changes (a potion put in or taken, a brew finished) the slots go
 * to the players watching the chunk in the mod's own packet, as for furnaces. The stand's own update packet is left
 * alone: other mods (Amendments) give the stand one of their own, and two mixins adding the same method to the block
 * entity cannot both apply.
 */
public final class BrewingWatch {
    private BrewingWatch() {}

    public static final int SLOTS = 5;
    public static final int BOTTLES = 3;

    /** Mixed into the brewing stand block entity so the last sent fingerprint lives with the stand itself. */
    public interface Fingerprinted {
        int cinematic$fingerprint();

        void cinematic$setFingerprint(int fingerprint);
    }

    /** Called at the end of every server tick of a brewing stand. */
    public static void afterTick(Level level, BlockPos pos, BlockState state, BrewingStandBlockEntity stand) {
        if (!(level instanceof ServerLevel server) || !(stand instanceof Fingerprinted marked)) {
            return;
        }
        int fingerprint = fingerprint(stand);
        if (fingerprint != marked.cinematic$fingerprint()) {
            marked.cinematic$setFingerprint(fingerprint);
            PacketDistributor.sendToPlayersTrackingChunk(server, ChunkPos.containing(pos), contents(pos, stand));
        }
    }

    /** Cheap enough for every tick of every stand: which stacks stand in it and how many (as for furnaces). */
    private static int fingerprint(BrewingStandBlockEntity stand) {
        return FurnaceWatch.contentsFingerprint(stand, SLOTS);
    }

    public static BrewingContentsPayload contents(BlockPos pos, BrewingStandBlockEntity stand) {
        List<ItemStack> items = new ArrayList<>(SLOTS);
        boolean any = false;
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = stand.getItem(slot);
            any |= !stack.isEmpty();
            items.add(stack.copy());
        }
        return new BrewingContentsPayload(pos, any ? items : List.of());
    }

    /** A chunk reached a player: tell them what stands in its brewing stands. */
    public static void chunkSent(ServerPlayer player, LevelChunk chunk) {
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            if (entity instanceof BrewingStandBlockEntity stand) {
                BrewingContentsPayload payload = contents(entity.getBlockPos(), stand);
                if (!payload.items().isEmpty()) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
    }
}
