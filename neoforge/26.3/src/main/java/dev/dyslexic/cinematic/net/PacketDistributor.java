package dev.dyslexic.cinematic.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** The scene's audience is the set of players tracking the block's chunk. */
public final class PacketDistributor {
    private PacketDistributor() {}

    /** True when the player's client has the mod's channel for this payload. */
    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return NetworkRegistry.hasChannel(player.connection, type.id());
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (canSend(player, payload.type())) net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload) {
        for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk, false)) sendToPlayer(player, payload);
    }

    public static void sendToPlayersTrackingEntityAndSelf(ServerPlayer entity, CustomPacketPayload payload) {
        sendToPlayer(entity, payload);
        if (!(entity.level() instanceof ServerLevel level)) return;
        for (ServerPlayer watcher : level.getChunkSource().chunkMap.getPlayers(entity.chunkPosition(), false))
            if (watcher != entity) sendToPlayer(watcher, payload);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
        if (connection != null && NetworkRegistry.hasChannel(connection, payload.type().id()))
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload);
    }
}
