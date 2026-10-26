package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** An enchantment just happened at a table: onlookers get the burst of light and runes. */
public record EnchantFxPayload(BlockPos pos, int entityId) implements CustomPacketPayload {
    public static final Type<EnchantFxPayload> TYPE = new Type<>(Cinematic.id("enchant_fx"));
    public static final StreamCodec<FriendlyByteBuf, EnchantFxPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, EnchantFxPayload::pos,
            ByteBufCodecs.VAR_INT, EnchantFxPayload::entityId,
            EnchantFxPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
