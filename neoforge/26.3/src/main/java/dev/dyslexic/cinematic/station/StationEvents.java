package dev.dyslexic.cinematic.station;

import dev.dyslexic.cinematic.ClientBridge;
import dev.dyslexic.cinematic.campfire.CampfireActions;
import dev.dyslexic.cinematic.furnace.FurnaceWatch;
import dev.dyslexic.cinematic.net.PacketDistributor;
import dev.dyslexic.cinematic.table.TableActions;
import dev.dyslexic.cinematic.table.TableGrid;
import dev.dyslexic.cinematic.table.TableStore;
import dev.dyslexic.cinematic.net.RecipeSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class StationEvents {
    private StationEvents() {}

    public static void register() {
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(StationEvents::onRightClickBlock);
        bus.addListener((ServerTickEvent.Post event) -> {
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) Sessions.tick(player);
        });
        // Repair a lost cooking timer before the vanilla campfire ticker can treat zero as "finished".
        bus.addListener((LevelTickEvent.Pre event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            for (ServerPlayer player : level.players()) {
                var session = Sessions.get(player);
                if (session != null && session.kind() == StationKind.CAMPFIRE
                        && session.dimension().equals(level.dimension())) CampfireActions.tick(level, session);
            }
        });
        bus.addListener((LevelTickEvent.Post event) -> {
            if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 40 != 0) return;
            for (BlockPos pos : TableStore.get(level).positions()) {
                if (level.isLoaded(pos) && !TableGrid.isTable(level.getBlockState(pos))) TableActions.spill(level, pos);
            }
        });
        bus.addListener((PlayerEvent.StartTracking event) -> {
            if (event.getTarget() instanceof ServerPlayer player && event.getEntity() instanceof ServerPlayer watcher)
                Sessions.sendAtStation(player, watcher);
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) Sessions.end(player, false);
        });
        bus.addListener(EventPriority.LOWEST, (LivingDeathEvent event) -> {
            if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player) Sessions.end(player, true);
        });
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) Sessions.end(player, true);
        });
        // On join and after every /reload: the recipe reference the client-side guides read.
        bus.addListener((OnDatapackSyncEvent event) -> event.getRelevantPlayers().forEach(RecipeSyncPayload::send));
        bus.addListener((ServerStoppedEvent event) -> Sessions.clear());
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockHitResult hit = event.getHitVec();
        var state = level.getBlockState(hit.getBlockPos());
        StationKind kind = StationKind.of(state);
        if (kind == null || !kind.startsOnUse() || event.getHand() != InteractionHand.MAIN_HAND || event.getUseBlock() == TriState.FALSE
                || player.isSecondaryUseActive() || player.isSpectator() || !kind.usable(level, hit.getBlockPos(), state)
                || !kind.usedOn(state, hit.getDirection())) {
            return;
        }
        if (!level.isClientSide()) {
            if (player instanceof ServerPlayer serverPlayer && !PacketDistributor.canSend(serverPlayer, RecipeSyncPayload.TYPE)) return;
            // no swing shown to others
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            return;
        }
        if (ClientBridge.vanillaUse.test(hit.getBlockPos(), kind)) return;
        ClientBridge.useAt = hit.getLocation();
        ClientBridge.useStation.accept(hit.getBlockPos().immutable(), kind);
        ClientBridge.swallowSwing = true;
        // the use packet is still sent to the server (it predicts the use), where the scene's session begins
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * A block changed and tells its neighbours (as NeoForge's NeighborNotifyEvent): a table that is gone, broken, blown up
     * or replaced, drops what lay on it at once.
     */
    public static void neighbourNotify(ServerLevel level, BlockPos pos) {
        if (!TableGrid.isTable(level.getBlockState(pos)) && TableStore.get(level).get(pos) != null) {
            TableActions.spill(level, pos);
        }
    }

    /** Called after the chunk packet, so contents never arrive before the client's block data. */
    public static void chunkSent(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
        TableStore store = TableStore.get(level);
        for (BlockPos pos : store.inChunk(chunk.getPos())) PacketDistributor.sendToPlayer(player, TableActions.contents(pos, store.get(pos)));
        FurnaceWatch.chunkSent(player, chunk);
        dev.dyslexic.cinematic.brewing.BrewingWatch.chunkSent(player, chunk);
    }
}
