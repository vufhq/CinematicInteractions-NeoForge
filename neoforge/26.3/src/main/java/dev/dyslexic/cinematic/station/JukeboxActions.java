package dev.dyslexic.cinematic.station;

import dev.dyslexic.cinematic.net.MenuDisplayPayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.Sessions.Session;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import dev.dyslexic.cinematic.net.PacketDistributor;

/**
 * The jukebox's scene on the server: a record is put in (from the item in hand or a hotbar slot) by the same rules as
 * using the jukebox with it, or the one playing is taken out, into the inventory rather than dropped. Without the scene
 * (turned off, or out of reach) using the jukebox does what it does in vanilla.
 */
public final class JukeboxActions {
    private JukeboxActions() {}

    public static void handle(ServerPlayer player, ServerLevel level, Session session, StationActionPayload action) {
        BlockPos pos = session.pos();
        if (action.action() != StationActionPayload.Action.CLICK || !(level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getValue(JukeboxBlock.HAS_RECORD)) {
            ItemStack record = jukebox.removeTheItem();
            if (!record.isEmpty()) {
                player.getInventory().placeItemBackInInventory(record, net.minecraft.util.Prediction.SERVER_ONLY);
            }
            level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, state));
        } else {
            ItemStack source = action.index() == 0 ? player.inventoryMenu.getCarried()
                    : action.button() >= 0 && action.button() < 36 ? player.getInventory().getItem(action.button()) : ItemStack.EMPTY;
            if (source.has(DataComponents.JUKEBOX_PLAYABLE)) {
                JukeboxPlayable.tryInsertIntoJukebox(level, pos, source, player);
                if (action.index() == 0 && source.isEmpty()) {
                    player.inventoryMenu.setCarried(ItemStack.EMPTY);
                }
            }
        }
        player.inventoryMenu.broadcastChanges();
        show(player, session);
    }

    /** Tells the clients nearby which record is in the jukebox (they are not told otherwise), for the scene to show it. */
    public static void show(ServerPlayer player, Session session) {
        ServerLevel level = player.level();
        ItemStack record = level.getBlockEntity(session.pos()) instanceof JukeboxBlockEntity jukebox ? jukebox.getTheItem().copy() : ItemStack.EMPTY;
        PacketDistributor.sendToPlayersTrackingChunk(level, ChunkPos.containing(session.pos()),
                new MenuDisplayPayload(session.pos(), StationKind.JUKEBOX, session.side().get2DDataValue(), List.of(record)));
    }

    /** Using the jukebox without the scene: the vanilla way (the record pops out, or the one in hand goes in). */
    public static void vanillaUse(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.JUKEBOX)) {
            return;
        }
        if (state.getValue(JukeboxBlock.HAS_RECORD)) {
            if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox) {
                jukebox.popOutTheItem();
            }
        } else if (player.getMainHandItem().has(DataComponents.JUKEBOX_PLAYABLE)) {
            JukeboxPlayable.tryInsertIntoJukebox(level, pos, player.getMainHandItem(), player);
        }
    }
}
