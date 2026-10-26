package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** What floats over an enchanting table (and how much lapis lies in its corner) while someone works at it. */
public record EnchantDisplayPayload(BlockPos pos, ItemStack item, int lapis, int side) implements CustomPacketPayload {
    public static final Type<EnchantDisplayPayload> TYPE = new Type<>(Cinematic.id("enchant_display"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EnchantDisplayPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, EnchantDisplayPayload::pos,
            ItemStack.OPTIONAL_STREAM_CODEC, EnchantDisplayPayload::item,
            ByteBufCodecs.VAR_INT, EnchantDisplayPayload::lapis,
            ByteBufCodecs.VAR_INT, EnchantDisplayPayload::side,
            EnchantDisplayPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
