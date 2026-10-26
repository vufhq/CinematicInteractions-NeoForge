package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** The brewing stand's five slots (three bottles, ingredient, blaze powder), so everyone sees the real potions; empty = bare. */
public record BrewingContentsPayload(BlockPos pos, List<ItemStack> items) implements CustomPacketPayload {
    public static final Type<BrewingContentsPayload> TYPE = new Type<>(Cinematic.id("brewing_contents"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BrewingContentsPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BrewingContentsPayload::pos,
            ItemStack.OPTIONAL_LIST_STREAM_CODEC, BrewingContentsPayload::items,
            BrewingContentsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
