package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The server ended the player's session (block broken, too far away, another menu opened...). */
public record SessionClosePayload() implements CustomPacketPayload {
    public static final Type<SessionClosePayload> TYPE = new Type<>(Cinematic.id("session_close"));
    public static final StreamCodec<FriendlyByteBuf, SessionClosePayload> CODEC = StreamCodec.unit(new SessionClosePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
