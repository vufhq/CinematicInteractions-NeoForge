package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;
import dev.dyslexic.cinematic.net.Network;

/**
 * What lies on an anvil, grindstone, smithing table or stonecutter while someone works at it (the menu's input
 * slots, in order), and the side they work from; an empty list once they step away.
 */
public record MenuDisplayPayload(BlockPos pos, StationKind kind, int side, List<ItemStack> items) implements CustomPacketPayload {
    public static final Type<MenuDisplayPayload> TYPE = new Type<>(Cinematic.id("menu_display"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MenuDisplayPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MenuDisplayPayload::pos,
            Network.enumCodec(StationKind.class), MenuDisplayPayload::kind,
            ByteBufCodecs.VAR_INT, MenuDisplayPayload::side,
            ItemStack.OPTIONAL_LIST_STREAM_CODEC, MenuDisplayPayload::items,
            MenuDisplayPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
