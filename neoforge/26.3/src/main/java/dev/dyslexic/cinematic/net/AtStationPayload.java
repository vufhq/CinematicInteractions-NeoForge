package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Tells watchers that a player works at a station (or stopped), so their model can take the matching pose. */
public record AtStationPayload(int entityId, Optional<BlockPos> pos, StationKind kind) implements CustomPacketPayload {
    public static final Type<AtStationPayload> TYPE = new Type<>(Cinematic.id("at_station"));
    public static final StreamCodec<FriendlyByteBuf, AtStationPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, AtStationPayload::entityId,
            ByteBufCodecs.optional(BlockPos.STREAM_CODEC), AtStationPayload::pos,
            ByteBufCodecs.idMapper(i -> StationKind.values()[i], StationKind::ordinal), AtStationPayload::kind,
            AtStationPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
