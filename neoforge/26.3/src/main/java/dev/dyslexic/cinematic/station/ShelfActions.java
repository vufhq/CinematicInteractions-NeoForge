package dev.dyslexic.cinematic.station;

import dev.dyslexic.cinematic.net.MenuDisplayPayload;
import dev.dyslexic.cinematic.net.MenuFxPayload;
import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.station.Sessions.Session;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import dev.dyslexic.cinematic.net.PacketDistributor;

/**
 * The chiseled bookshelf's scene on the server. A book is taken off into the hand (or with shift into the inventory) and
 * one is put into an empty slot from the hand or a hotbar slot, by the shelf's own rules, with its sounds; reading a book
 * takes nothing off the shelf: the client only tells onlookers. The player at the shelf is told what stands in it (the
 * client is not told otherwise). Without the scene (turned off, or out of reach) using the shelf does what it does in
 * vanilla.
 */
public final class ShelfActions {
    private ShelfActions() {}

    /** What each player at a shelf was last told stands in it. */
    private static final Map<UUID, Integer> SHOWN = new HashMap<>();
    /** The slot each player is reading a book from, so onlookers can be told when they stop however that happens. */
    private static final Map<UUID, Integer> READING = new HashMap<>();

    /**
     * CLICK: the book in slot {@code index} into the hand (shift: into the inventory). PLACE: one book from the hand
     * ({@code button} -1) or inventory slot {@code button} (0..35) into empty slot {@code index}. CRAFT: the player starts
     * ({@code button} 1) or stops reading the book in slot {@code index}.
     */
    public static void handle(ServerPlayer player, ServerLevel level, Session session, StationActionPayload action) {
        BlockPos pos = session.pos();
        int slot = action.index();
        if (slot < 0 || slot >= ChiseledBookShelfBlockEntity.MAX_BOOKS_IN_STORAGE || !(level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity shelf)) {
            return;
        }
        switch (action.action()) {
            case CLICK -> take(player, level, session, shelf, slot, action.shift());
            case PLACE -> put(player, level, session, shelf, slot, action.button());
            case CRAFT -> {
                boolean start = action.button() == 1 && !shelf.getItem(slot).isEmpty();
                if (start) {
                    READING.put(player.getUUID(), slot);
                } else if (READING.remove(player.getUUID()) == null) {
                    return;
                }
                cue(player, session, start ? MenuFxPayload.Cue.READ : MenuFxPayload.Cue.READ_END, slot, shelf.getItem(slot));
            }
            default -> {}
        }
    }

    private static void take(ServerPlayer player, ServerLevel level, Session session, ChiseledBookShelfBlockEntity shelf, int slot, boolean toInventory) {
        ItemStack there = shelf.getItem(slot);
        ItemStack carried = player.inventoryMenu.getCarried();
        boolean fits = carried.isEmpty() || ItemStack.isSameItemSameComponents(carried, there) && carried.getCount() < carried.getMaxStackSize();
        if (there.isEmpty() || !toInventory && !fits) {
            show(player, session);
            return;
        }
        ItemStack book = shelf.removeItem(slot, 1);
        level.playSound(null, session.pos(), sound(book, false), SoundSource.BLOCKS, 1.0f, 1.0f);
        // a book read and then taken: onlookers see it go from the hands that held it
        if (Integer.valueOf(slot).equals(READING.get(player.getUUID()))) {
            READING.remove(player.getUUID());
        }
        cue(player, session, MenuFxPayload.Cue.PULL, slot, book);
        if (toInventory) {
            player.getInventory().placeItemBackInInventory(book, net.minecraft.util.Prediction.SERVER_ONLY);
        } else if (carried.isEmpty()) {
            player.inventoryMenu.setCarried(book);
        } else {
            carried.grow(1);
        }
        player.inventoryMenu.broadcastChanges();
        show(player, session);
    }

    private static void put(ServerPlayer player, ServerLevel level, Session session, ChiseledBookShelfBlockEntity shelf, int slot, int from) {
        ItemStack source = from < 0 ? player.inventoryMenu.getCarried() : from < 36 ? player.getInventory().getItem(from) : ItemStack.EMPTY;
        if (source.isEmpty() || !source.is(ItemTags.BOOKSHELF_BOOKS) || !shelf.getItem(slot).isEmpty()) {
            show(player, session);
            return;
        }
        player.awardStat(Stats.ITEM_USED.get(source.getItem()));
        ItemStack book = source.split(1);
        if (from < 0 && source.isEmpty()) {
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
        }
        shelf.setItem(slot, book);
        level.playSound(null, session.pos(), sound(book, true), SoundSource.BLOCKS, 1.0f, 1.0f);
        cue(player, session, MenuFxPayload.Cue.PUT, slot, book);
        player.inventoryMenu.broadcastChanges();
        show(player, session);
    }

    private static SoundEvent sound(ItemStack book, boolean in) {
        boolean enchanted = book.is(Items.ENCHANTED_BOOK);
        return in ? enchanted ? SoundEvents.CHISELED_BOOKSHELF_INSERT_ENCHANTED : SoundEvents.CHISELED_BOOKSHELF_INSERT
                : enchanted ? SoundEvents.CHISELED_BOOKSHELF_PICKUP_ENCHANTED : SoundEvents.CHISELED_BOOKSHELF_PICKUP;
    }

    /** Tells the players nearby what happened to a book (the one who did it sees it in their scene already). */
    private static void cue(ServerPlayer player, Session session, MenuFxPayload.Cue cue, int slot, ItemStack book) {
        PacketDistributor.sendToPlayersTrackingChunk(player.level(), ChunkPos.containing(session.pos()),
                new MenuFxPayload(session.pos(), StationKind.CHISELED_BOOKSHELF, player.getId(), cue, slot, List.of(), book.copy()));
    }

    /** Tells the player at the shelf what stands in it. */
    public static void show(ServerPlayer player, Session session) {
        List<ItemStack> items = new ArrayList<>(ChiseledBookShelfBlockEntity.MAX_BOOKS_IN_STORAGE);
        if (player.level().getBlockEntity(session.pos()) instanceof ChiseledBookShelfBlockEntity shelf) {
            for (int i = 0; i < ChiseledBookShelfBlockEntity.MAX_BOOKS_IN_STORAGE; i++) {
                items.add(shelf.getItem(i).copy());
            }
        }
        SHOWN.put(player.getUUID(), fingerprint(items));
        PacketDistributor.sendToPlayer(player, new MenuDisplayPayload(session.pos(), StationKind.CHISELED_BOOKSHELF, session.side().get2DDataValue(), items));
    }

    /** Someone else (or a hopper) changed the shelf meanwhile: the player is told again. */
    public static void tick(ServerPlayer player, Session session) {
        if (!(player.level().getBlockEntity(session.pos()) instanceof ChiseledBookShelfBlockEntity shelf)) {
            return;
        }
        int fingerprint = 1;
        for (int i = 0; i < ChiseledBookShelfBlockEntity.MAX_BOOKS_IN_STORAGE; i++) {
            ItemStack stack = shelf.getItem(i);
            fingerprint = fingerprint * 31 + (stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack));
        }
        Integer last = SHOWN.get(player.getUUID());
        if (last == null || last != fingerprint) {
            show(player, session);
        }
    }

    private static int fingerprint(List<ItemStack> items) {
        int fingerprint = 1;
        for (ItemStack stack : items) {
            fingerprint = fingerprint * 31 + (stack.isEmpty() ? 0 : ItemStack.hashItemAndComponents(stack));
        }
        return fingerprint;
    }

    /** The session is over: a book being read is back on the shelf for onlookers too. */
    public static void close(ServerPlayer player, Session session) {
        SHOWN.remove(player.getUUID());
        Integer slot = READING.remove(player.getUUID());
        if (slot != null && player.level().getBlockEntity(session.pos()) instanceof ChiseledBookShelfBlockEntity shelf) {
            cue(player, session, MenuFxPayload.Cue.READ_END, slot, shelf.getItem(slot));
        }
    }

    /** Using the shelf without the scene, on the book (or the empty place) at slot {@code slot}: the vanilla way. */
    public static void vanillaUse(ServerPlayer player, BlockPos pos, int slot) {
        ServerLevel level = player.level();
        BlockState state = level.getBlockState(pos);
        if (slot < 0 || slot >= ChiseledBookShelfBlockEntity.MAX_BOOKS_IN_STORAGE || !(state.getBlock() instanceof ChiseledBookShelfBlock)
                || !(level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity shelf)) {
            return;
        }
        if (state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot))) {
            ItemStack book = shelf.removeItem(slot, 1);
            level.playSound(null, pos, sound(book, false), SoundSource.BLOCKS, 1.0f, 1.0f);
            if (!player.getInventory().add(book)) {
                player.drop(book, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
            level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
        } else if (player.getMainHandItem().is(ItemTags.BOOKSHELF_BOOKS)) {
            ItemStack hand = player.getMainHandItem();
            player.awardStat(Stats.ITEM_USED.get(hand.getItem()));
            ItemStack book = hand.consumeAndReturn(1, player);
            shelf.setItem(slot, book);
            level.playSound(null, pos, sound(book, true), SoundSource.BLOCKS, 1.0f, 1.0f);
        }
    }
}
