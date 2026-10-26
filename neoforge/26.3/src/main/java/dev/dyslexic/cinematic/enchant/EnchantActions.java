package dev.dyslexic.cinematic.enchant;

import dev.dyslexic.cinematic.net.EnchantDisplayPayload;
import dev.dyslexic.cinematic.net.EnchantFxPayload;
import dev.dyslexic.cinematic.station.MenuActions;
import dev.dyslexic.cinematic.station.Sessions;
import dev.dyslexic.cinematic.station.Sessions.Session;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import dev.dyslexic.cinematic.net.PacketDistributor;

/**
 * The enchanting table runs on the real vanilla menu, opened without its window ({@link MenuActions}): the scene
 * places items with ordinary slot clicks and picks an offer with the menu's own button, so costs, offers and
 * rewards are vanilla's. This class tells onlookers what floats over the table and when it flares up.
 */
public final class EnchantActions {
    private EnchantActions() {}

    private static final Map<UUID, Integer> SHOWN = new HashMap<>();

    /** The player stepped away (the menu itself is closed by {@link MenuActions}): nothing floats over the table any more. */
    public static void close(ServerPlayer player, Session session) {
        SHOWN.remove(player.getUUID());
        show(player, session, ItemStack.EMPTY, 0);
    }

    /** Keeps onlookers' view of the floating item and the lapis in the corner up to date. */
    public static void tick(ServerPlayer player, Session session) {
        if (!(player.containerMenu instanceof EnchantmentMenu menu)) {
            return;
        }
        ItemStack item = menu.getSlot(0).getItem();
        int lapis = menu.getSlot(1).getItem().getCount();
        int fingerprint = (item.isEmpty() ? 0 : ItemStack.hashItemAndComponents(item)) * 31 + lapis;
        Integer last = SHOWN.put(player.getUUID(), fingerprint);
        if (last == null || last != fingerprint) {
            show(player, session, item, lapis);
        }
    }

    private static void show(ServerPlayer player, Session session, ItemStack item, int lapis) {
        PacketDistributor.sendToPlayersTrackingChunk(player.level(), ChunkPos.containing(session.pos()),
                new EnchantDisplayPayload(session.pos(), item.copy(), lapis, session.side().get2DDataValue()));
    }

    /** The menu enchanted something: everyone nearby sees the flash. */
    public static void onEnchant(ServerPlayer player) {
Session session = Sessions.get(player);
        if (session == null || session.kind() != StationKind.ENCHANTING) {
            return;
        }
        ServerLevel level = player.level();
        PacketDistributor.sendToPlayersTrackingChunk(level, ChunkPos.containing(session.pos()), new EnchantFxPayload(session.pos(), player.getId()));
        double x = session.pos().getX() + 0.5;
        double y = session.pos().getY() + 1.1;
        double z = session.pos().getZ() + 0.5;
        level.sendParticles(ParticleTypes.ENCHANT, x, y, z, 40, 0.3, 0.3, 0.3, 0.6);
    }
}
