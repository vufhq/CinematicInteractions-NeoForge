package dev.dyslexic.cinematic.station;

import dev.dyslexic.cinematic.api.AddonStations;

import dev.dyslexic.cinematic.net.MenuDisplayPayload;
import dev.dyslexic.cinematic.net.MenuFxPayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.Sessions.Session;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import dev.dyslexic.cinematic.net.PacketDistributor;

/**
 * Stations that run on the block's real vanilla menu, opened without its window (enchanting table, anvil,
 * grindstone, smithing table, stonecutter). The scene clicks the menu's slots and buttons, so recipes, costs and
 * rewards stay vanilla's, and so does what other mods hook into those menus. This class opens and closes the
 * menu and tells onlookers what lies on the block and when the big moment happens.
 */
public final class MenuActions {
    private MenuActions() {}

    private static final Map<UUID, Integer> SHOWN = new HashMap<>();
    private static final Map<UUID, Long> LAST_FX = new HashMap<>();
    /** Game ticks between two effects a player can show onlookers. */
    private static final int FX_COOLDOWN = 10;

    /** Opens the block's menu for the player; the client keeps it but shows the scene instead of the window. */
    public static boolean open(ServerPlayer player, Session session) {
        MenuProvider provider = provider(player, session.pos(), session.kind());
        return provider != null && player.openMenu(provider).isPresent() && isOpen(session, player.containerMenu);
    }

    /** What opens the block's menu, as using the block would (null when it has none, or will not open now). */
    @Nullable
    public static MenuProvider provider(ServerPlayer player, BlockPos pos, StationKind kind) {
        return player.level().getBlockState(pos).getMenuProvider(player.level(), pos);
    }

    /** Closes the menu quietly (the client drops its own copy); vanilla hands back what lay in it. */
    public static void close(ServerPlayer player, Session session) {
        LAST_FX.remove(player.getUUID());
        if (isOpen(session, player.containerMenu)) {
            player.doCloseContainer();
        }
        if (SHOWN.remove(player.getUUID()) != null) {
            show(player, session, List.of());
        }
    }

    /** Shift-click into a station, ignoring vanilla's fallback shuffle between hotbar and inventory. */
    public static void quickMove(ServerPlayer player, Session session, int inventorySlot) {
        AbstractContainerMenu menu = player.containerMenu;
        if (!isOpen(session, menu) || !menu.stillValid(player) || player.isSpectator()) return;
        int index = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                index = i;
                break;
            }
        }
        if (index < 0 || !menu.getSlot(index).hasItem()) return;
        if (menu instanceof BeaconMenu && beaconPayment(menu, index)) {
            menu.broadcastChanges();
            return;
        }
        List<ItemStack> before = new ArrayList<>(menu.slots.size());
        for (var slot : menu.slots) before.add(slot.getItem().copy());
        menu.clicked(index, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, player);
        boolean intoStation = false;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).container != player.getInventory()
                    && !ItemStack.matches(before.get(i), menu.slots.get(i).getItem())) {
                intoStation = true;
                break;
            }
        }
        if (!intoStation) {
            for (int i = 0; i < menu.slots.size(); i++) {
                var slot = menu.slots.get(i);
                if (slot.container == player.getInventory() && !ItemStack.matches(before.get(i), slot.getItem()))
                    slot.set(before.get(i));
            }
        }
        menu.broadcastChanges();
    }

    private static boolean beaconPayment(AbstractContainerMenu menu, int index) {
        var payment = menu.getSlot(0);
        ItemStack stack = menu.getSlot(index).getItem();
        if (index == 0 || payment.hasItem() || stack.getCount() <= 1 || !payment.mayPlace(stack)) return false;
        payment.setByPlayer(stack.split(1));
        menu.getSlot(index).setChanged();
        return true;
    }

    public static boolean isOpen(StationKind kind, AbstractContainerMenu menu) {
        return switch (kind) {
            case ENCHANTING -> menu instanceof EnchantmentMenu;
            case ANVIL -> menu instanceof AnvilMenu;
            case GRINDSTONE -> menu instanceof GrindstoneMenu;
            case SMITHING -> menu instanceof SmithingMenu;
            case STONECUTTER -> menu instanceof StonecutterMenu;
            case LOOM -> menu instanceof LoomMenu;
            case CARTOGRAPHY -> menu instanceof CartographyTableMenu;
            case BREWING -> menu instanceof BrewingStandMenu;
            case CRAFTER -> menu instanceof CrafterMenu;
            case LECTERN -> menu instanceof LecternMenu;
            case BEACON -> menu instanceof BeaconMenu;
            default -> false;
        };
    }

    public static boolean isOpen(Session session, AbstractContainerMenu menu) {
        if (session.kind() != StationKind.ADDON) return isOpen(session.kind(), menu);
        AddonStations.Definition definition = AddonStations.byId(session.addonId());
        return definition != null && definition.accepts(menu);
    }

    /** How many of the menu's first slots are inputs that lie on the block for everyone to see. */
    public static int inputs(StationKind kind) {
        return switch (kind) {
            case ANVIL, GRINDSTONE, CARTOGRAPHY -> 2;
            case SMITHING, LOOM -> 3;
            case STONECUTTER, BEACON -> 1;
            case BREWING -> 5;
            case CRAFTER -> 9;
            default -> 0;
        };
    }

    /** Keeps onlookers' view of the block's inputs up to date. */
    public static void tick(ServerPlayer player, Session session) {
        int inputs = inputs(session.kind());
        if (inputs == 0 || !isOpen(session, player.containerMenu)) {
            return;
        }
        int fingerprint = 1;
        for (int i = 0; i < inputs; i++) {
            ItemStack stack = player.containerMenu.getSlot(i).getItem();
            fingerprint = fingerprint * 31 + (stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack) * 7 + stack.getCount());
        }
        Integer last = SHOWN.put(player.getUUID(), fingerprint);
        if (last == null || last != fingerprint) {
            // copied only when something changed
            List<ItemStack> items = new ArrayList<>(inputs);
            for (int i = 0; i < inputs; i++) {
                items.add(player.containerMenu.getSlot(i).getItem().copy());
            }
            show(player, session, items);
        }
    }

    private static void show(ServerPlayer player, Session session, List<ItemStack> items) {
        PacketDistributor.sendToPlayersTrackingChunk(player.level(), ChunkPos.containing(session.pos()),
                new MenuDisplayPayload(session.pos(), session.kind(), session.side().get2DDataValue(), items));
    }

    /** The menu's result slot, for stations whose whole move onlookers see (-1: none). */
    private static int resultSlot(StationKind kind) {
        return switch (kind) {
            case ANVIL, GRINDSTONE -> 2;
            case STONECUTTER -> 1;
            default -> -1;
        };
    }

    /**
     * The client reports a moment of its scene: the big moment (index 0: the hammer falls, the wheel bites) or the start
     * of the big move (index 1; {@code button} 1: it ends in the bag). Only a cue for onlookers, so it is merely
     * rate-limited; what lies on the block and what comes of it are read from the menu here. The real work goes through
     * the vanilla menu.
     */
    public static void handle(ServerPlayer player, Session session, StationActionPayload action) {
        if (action.action() != StationActionPayload.Action.CRAFT) {
            return;
        }
        long now = player.level().getGameTime();
        Long last = LAST_FX.get(player.getUUID());
        if (last != null && now - last < FX_COOLDOWN) {
            return;
        }
        LAST_FX.put(player.getUUID(), now);
        MenuFxPayload fx = MenuFxPayload.beat(session.pos(), session.kind(), player.getId());
        int result = resultSlot(session.kind());
        if (action.index() == 1 && result >= 0 && isOpen(session, player.containerMenu)) {
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < inputs(session.kind()); i++) {
                items.add(player.containerMenu.getSlot(i).getItem().copy());
            }
            fx = new MenuFxPayload(session.pos(), session.kind(), player.getId(), MenuFxPayload.Cue.MOVE, action.button(), items,
                    player.containerMenu.getSlot(result).getItem().copy());
        }
        PacketDistributor.sendToPlayersTrackingChunk(player.level(), ChunkPos.containing(session.pos()), fx);
    }
}
