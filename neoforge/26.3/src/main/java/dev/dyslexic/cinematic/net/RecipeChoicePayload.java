package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Results competing for the current station input, resolved by the server's recipe registry. */
public record RecipeChoicePayload(BlockPos pos, StationKind kind, List<ItemStack> results, int selected)
        implements CustomPacketPayload {
    public static final Type<RecipeChoicePayload> TYPE = new Type<>(Cinematic.id("recipe_choices"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecipeChoicePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos);
                buf.writeEnum(payload.kind);
                ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, payload.results);
                buf.writeVarInt(payload.selected);
            },
            buf -> new RecipeChoicePayload(buf.readBlockPos(), buf.readEnum(StationKind.class),
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf), buf.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
