package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** The three furnace slots (input, fuel, result), so everyone sees what sits in the openings; empty = bare. */
public record FurnaceContentsPayload(BlockPos pos, List<ItemStack> items) implements CustomPacketPayload {
    public static final Type<FurnaceContentsPayload> TYPE = new Type<>(Cinematic.id("furnace_contents"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceContentsPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FurnaceContentsPayload::pos,
            ItemStack.OPTIONAL_LIST_STREAM_CODEC, FurnaceContentsPayload::items,
            FurnaceContentsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
