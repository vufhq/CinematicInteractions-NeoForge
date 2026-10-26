package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Something in someone's scene for onlookers to see. BEAT: the big moment (the hammer falls, the wheel bites...),
 * sparks. MOVE: a big move begins (forging, grinding, cutting) with what lay on the block ({@code items}), what comes
 * of it ({@code result}) and how it ends ({@code variant}: 1 into the bag), so onlookers see the whole of it. At a
 * chiseled bookshelf, for the book in slot {@code variant} ({@code result}): PULL taken off, PUT put in, READ taken
 * out to read, READ_END put back.
 */
public record MenuFxPayload(BlockPos pos, StationKind kind, int entityId, Cue cue, int variant, List<ItemStack> items, ItemStack result)
        implements CustomPacketPayload {
    public enum Cue { BEAT, MOVE, PULL, PUT, READ, READ_END }

    public static final Type<MenuFxPayload> TYPE = new Type<>(Cinematic.id("menu_fx"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MenuFxPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeEnum(p.kind);
                buf.writeVarInt(p.entityId);
                buf.writeEnum(p.cue);
                buf.writeVarInt(p.variant);
                ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.items);
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.result);
            },
            buf -> new MenuFxPayload(buf.readBlockPos(), buf.readEnum(StationKind.class), buf.readVarInt(), buf.readEnum(Cue.class), buf.readVarInt(),
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)));

    public static MenuFxPayload beat(BlockPos pos, StationKind kind, int entityId) {
        return new MenuFxPayload(pos, kind, entityId, Cue.BEAT, 0, List.of(), ItemStack.EMPTY);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
