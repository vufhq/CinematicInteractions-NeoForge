package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The four real cooking timers of the campfire the player is sitting beside. */
public record CampfireProgressPayload(BlockPos pos, boolean lit, int[] progress, int[] total) implements CustomPacketPayload {
    public static final Type<CampfireProgressPayload> TYPE = new Type<>(Cinematic.id("campfire_progress"));
    public static final StreamCodec<FriendlyByteBuf, CampfireProgressPayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeBoolean(payload.lit);
                for (int i = 0; i < 4; i++) {
                    buf.writeVarInt(payload.progress[i]);
                    buf.writeVarInt(payload.total[i]);
                }
            },
            buf -> {
                BlockPos pos = buf.readBlockPos();
                boolean lit = buf.readBoolean();
                int[] progress = new int[4], total = new int[4];
                for (int i = 0; i < 4; i++) {
                    progress[i] = buf.readVarInt();
                    total[i] = buf.readVarInt();
                }
                return new CampfireProgressPayload(pos, lit, progress, total);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
