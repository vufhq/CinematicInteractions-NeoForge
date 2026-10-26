package dev.dyslexic.cinematic.station;

import dev.dyslexic.cinematic.api.AddonStations;

import dev.dyslexic.cinematic.campfire.CampfireActions;
import dev.dyslexic.cinematic.enchant.EnchantActions;
import dev.dyslexic.cinematic.furnace.FurnaceActions;
import dev.dyslexic.cinematic.net.AtStationPayload;
import dev.dyslexic.cinematic.net.SessionClosePayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.table.TableActions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import dev.dyslexic.cinematic.net.PacketDistributor;

/**
 * Server side of "working at a station". The cursor stack of the open menu is the item in the player's hand: the
 * inventory menu's at tables and furnaces, the block's own menu at stations that run on one ({@link MenuActions}),
 * so vanilla clicks and syncing keep working while no container screen is open.
 */
public final class Sessions {
    private Sessions() {}

    public record Session(ResourceKey<Level> dimension, BlockPos pos, Direction side, StationKind kind,
            @Nullable net.minecraft.resources.Identifier addonId) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final double REACH_SQR = 6.0 * 6.0;

    @Nullable
    public static Session get(ServerPlayer player) {
        return SESSIONS.get(player.getUUID());
    }

    public static void handle(ServerPlayer player, StationActionPayload action) {
        if (action.action() == StationActionPayload.Action.BEGIN) {
            begin(player, action.pos(), action.kind(), Direction.from2DDataValue(action.side()));
            return;
        }
        if (action.action() == StationActionPayload.Action.WINDOW) {
            openWindow(player, action.pos(), action.kind(), action.index());
            return;
        }
        Session session = get(player);
        if (session == null || !session.pos().equals(action.pos()) || session.kind() != action.kind()) {
            if (action.action() != StationActionPayload.Action.LEAVE) {
                PacketDistributor.sendToPlayer(player, new SessionClosePayload());
            }
            return;
        }
        if (action.action() == StationActionPayload.Action.LEAVE) {
            end(player, false);
            return;
        }
        if (!valid(player, session, true)) {
            end(player, true);
            return;
        }
        ServerLevel level = player.level();
        if (action.action() == StationActionPayload.Action.CHOOSE) {
            RecipeChoices.choose(player, level, session, action.index());
            RecipeChoices.sync(player, session);
            return;
        }
        if (action.action() == StationActionPayload.Action.QUICK) {
            int slot = action.index();
            if (slot >= 0 && slot < 36 && !player.getInventory().getItem(slot).isEmpty()) {
                if (session.kind().usesMenu()) MenuActions.quickMove(player, session, slot);
                else if (session.kind().isFurnace()) FurnaceActions.quickMove(player, level, session, slot);
                else if (session.kind() == StationKind.CAMPFIRE) CampfireActions.quickMove(player, level, session, slot);
            }
            return;
        }
        switch (session.kind()) {
            case TABLE -> TableActions.handle(player, level, session, action);
            case FURNACE, SMOKER, BLAST_FURNACE -> FurnaceActions.handle(player, level, session, action);
            case ENCHANTING -> {
                // everything at the enchanting table goes through the vanilla menu the session opened
            }
            case ANVIL, GRINDSTONE, SMITHING, STONECUTTER, LOOM, CARTOGRAPHY, BREWING, CRAFTER, LECTERN, BEACON ->
                    MenuActions.handle(player, session, action);
            case CAMPFIRE -> CampfireActions.handle(player, level, session, action);
            case JUKEBOX -> JukeboxActions.handle(player, level, session, action);
            case CHISELED_BOOKSHELF -> ShelfActions.handle(player, level, session, action);
            case ADDON -> { /* The add-on's actual menu owns its slot and recipe actions. */ }
        }
    }

    private static void begin(ServerPlayer player, BlockPos pos, StationKind kind, Direction side) {
        BlockState state = player.level().getBlockState(pos);
        AddonStations.Definition addon = kind == StationKind.ADDON ? AddonStations.forBlock(state) : null;
        if (!kind.matches(state) || kind == StationKind.ADDON && addon == null) {
            PacketDistributor.sendToPlayer(player, new SessionClosePayload());
            return;
        }
        List<Direction> sides = kind.sides(state);
        if (sides.isEmpty()) {
            PacketDistributor.sendToPlayer(player, new SessionClosePayload());
            return;
        }
        if (!sides.contains(side)) {
            side = sides.get(0);
        }
        Session session = new Session(player.level().dimension(), pos.immutable(), side, kind,
                addon == null ? null : addon.id());
        if (!valid(player, session, false) || !kind.usable(player.level(), pos, state)) {
            PacketDistributor.sendToPlayer(player, new SessionClosePayload());
            return;
        }
        if (get(player) != null) {
            end(player, false);
        }
        if (kind.usesMenu() && !MenuActions.open(player, session)) {
            PacketDistributor.sendToPlayer(player, new SessionClosePayload());
            return;
        }
        SESSIONS.put(player.getUUID(), session);
        RecipeChoices.sync(player, session);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new AtStationPayload(player.getId(), Optional.of(session.pos()), kind));
        if (kind.isFurnace()) {
            FurnaceActions.sendState(player, session);
        }
        if (kind == StationKind.CAMPFIRE) CampfireActions.sendProgress(player, session);
        if (kind == StationKind.JUKEBOX) {
            JukeboxActions.show(player, session);
        }
        if (kind == StationKind.CHISELED_BOOKSHELF) {
            ShelfActions.show(player, session);
        }
    }

    /**
     * The scene cannot be played where the player is (the work out of reach, no room, no way there): the block's own
     * window opens, as vanilla would open it ({@code slot}: the book of a chiseled bookshelf the use was on).
     */
    private static void openWindow(ServerPlayer player, BlockPos pos, StationKind kind, int slot) {
        if (get(player) != null) {
            end(player, false);
        }
        BlockState state = player.level().getBlockState(pos);
        if (player.isSpectator() || !kind.matches(state) || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > REACH_SQR) {
            return;
        }
        if (kind == StationKind.JUKEBOX) {
            // a jukebox has no window: it does what it does without the scene
            JukeboxActions.vanillaUse(player, pos);
            return;
        }
        if (kind == StationKind.CHISELED_BOOKSHELF) {
            ShelfActions.vanillaUse(player, pos, slot);
            return;
        }
        MenuProvider provider = MenuActions.provider(player, pos, kind);
        if (provider != null) {
            player.openMenu(provider);
        }
    }

    /** Ends a session; the item still in hand goes back to the inventory. */
    public static void end(ServerPlayer player, boolean notifyClient) {
        Session session = SESSIONS.remove(player.getUUID());
        RecipeChoices.forget(player);
        if (session == null) {
            return;
        }
        if (session.kind().usesMenu()) {
            MenuActions.close(player, session);
        }
        if (session.kind() == StationKind.ENCHANTING) {
            EnchantActions.close(player, session);
        }
        if (session.kind() == StationKind.CHISELED_BOOKSHELF) {
            ShelfActions.close(player, session);
        }
        AbstractContainerMenu menu = player.inventoryMenu;
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            menu.setCarried(ItemStack.EMPTY);
            player.getInventory().placeItemBackInInventory(carried, net.minecraft.util.Prediction.SERVER_ONLY);
        }
        menu.broadcastChanges();
        if (notifyClient) {
            PacketDistributor.sendToPlayer(player, new SessionClosePayload());
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new AtStationPayload(player.getId(), Optional.empty(), session.kind()));
    }

    public static void tick(ServerPlayer player) {
        Session session = get(player);
        if (session == null) {
            return;
        }
        if (player.tickCount % 5 == 0 && !valid(player, session, true)) {
            end(player, true);
            return;
        }
        if (session.kind().isFurnace() && player.tickCount % 4 == 0) {
            FurnaceActions.sendState(player, session);
        }
        if (player.tickCount % 10 == 0) RecipeChoices.sync(player, session);
        if (session.kind() == StationKind.CAMPFIRE && player.tickCount % 4 == 0)
            CampfireActions.sendProgress(player, session);
        if (session.kind() == StationKind.CHISELED_BOOKSHELF && player.tickCount % 4 == 0) {
            ShelfActions.tick(player, session);
        }
        if (session.kind() == StationKind.ENCHANTING && player.tickCount % 2 == 0) {
            EnchantActions.tick(player, session);
        } else if (session.kind().usesMenu() && player.tickCount % 2 == 0) {
            MenuActions.tick(player, session);
        }
    }

    public static void endAllAt(ServerLevel level, BlockPos pos) {
        for (ServerPlayer player : level.players()) {
            Session session = get(player);
            if (session != null && session.pos().equals(pos)) {
                end(player, true);
            }
        }
    }

    public static void clear() {
        SESSIONS.clear();
        RecipeChoices.clear();
    }

    public static void sendAtStation(ServerPlayer target, ServerPlayer watcher) {
        Session session = get(target);
        if (session != null) {
            PacketDistributor.sendToPlayer(watcher, new AtStationPayload(target.getId(), Optional.of(session.pos()), session.kind()));
        }
    }

    /** {@code opened}: the session's menu should be up by now (the block's own, or just the inventory). */
    private static boolean valid(ServerPlayer player, Session session, boolean opened) {
        boolean menu = session.kind().usesMenu() && opened
                ? MenuActions.isOpen(session, player.containerMenu)
                : player.containerMenu == player.inventoryMenu;
        return player.isAlive()
                && !player.isSpectator()
                && player.level().dimension() == session.dimension()
                && menu
                && player.level().isLoaded(session.pos())
                && session.kind().matches(player.level().getBlockState(session.pos()))
                && (session.kind() != StationKind.ADDON || AddonStations.forBlock(player.level().getBlockState(session.pos())) != null
                        && session.addonId().equals(AddonStations.forBlock(player.level().getBlockState(session.pos())).id()))
                && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(session.pos())) <= REACH_SQR;
    }
}
