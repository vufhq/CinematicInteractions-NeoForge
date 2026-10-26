package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** What lies on one table; an empty list means the table is bare. */
public record TableContentsPayload(BlockPos pos, List<ItemStack> items, byte[] facing) implements CustomPacketPayload {
    public static final Type<TableContentsPayload> TYPE = new Type<>(Cinematic.id("table_contents"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TableContentsPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, TableContentsPayload::pos,
            ItemStack.OPTIONAL_LIST_STREAM_CODEC, TableContentsPayload::items,
            ByteBufCodecs.BYTE_ARRAY, TableContentsPayload::facing,
            TableContentsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
