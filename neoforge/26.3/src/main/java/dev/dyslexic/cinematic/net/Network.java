package dev.dyslexic.cinematic.net;

import dev.dyslexic.cinematic.station.Sessions;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class Network {
    private Network() {}

    public static <E extends Enum<E>> StreamCodec<RegistryFriendlyByteBuf, E> enumCodec(Class<E> type) {
        return StreamCodec.of((buf, value) -> buf.writeEnum(value), buf -> buf.readEnum(type));
    }

    /** Client handlers are registered by CinematicClient, so a dedicated server never loads client classes. */
    public static void register(RegisterPayloadHandlersEvent event) {
        // Missing channels are expected when a client uses only vanilla menus on an unmodded server.
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playBidirectional(ProtocolPayload.TYPE, ProtocolPayload.CODEC, (payload, context) -> {});
        registrar.playToClient(RecipeSyncPayload.TYPE, RecipeSyncPayload.CODEC);
        registrar.playToClient(RecipeChoicePayload.TYPE, RecipeChoicePayload.CODEC);
        registrar.playToServer(StationActionPayload.TYPE, StationActionPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) Sessions.handle(player, payload);
        });
        registrar.playToClient(TableContentsPayload.TYPE, TableContentsPayload.CODEC);
        registrar.playToClient(FurnaceContentsPayload.TYPE, FurnaceContentsPayload.CODEC);
        registrar.playToClient(FurnaceStatePayload.TYPE, FurnaceStatePayload.CODEC);
        registrar.playToClient(BrewingContentsPayload.TYPE, BrewingContentsPayload.CODEC);
        registrar.playToClient(CampfireProgressPayload.TYPE, CampfireProgressPayload.CODEC);
        registrar.playToClient(EnchantDisplayPayload.TYPE, EnchantDisplayPayload.CODEC);
        registrar.playToClient(EnchantFxPayload.TYPE, EnchantFxPayload.CODEC);
        registrar.playToClient(MenuDisplayPayload.TYPE, MenuDisplayPayload.CODEC);
        registrar.playToClient(MenuFxPayload.TYPE, MenuFxPayload.CODEC);
        registrar.playToClient(SessionClosePayload.TYPE, SessionClosePayload.CODEC);
        registrar.playToClient(AtStationPayload.TYPE, AtStationPayload.CODEC);
    }
}
