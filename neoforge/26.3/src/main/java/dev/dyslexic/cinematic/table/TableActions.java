package dev.dyslexic.cinematic.table;

import dev.dyslexic.cinematic.net.StationActionPayload;
import dev.dyslexic.cinematic.net.TableContentsPayload;
import dev.dyslexic.cinematic.station.Sessions;
import dev.dyslexic.cinematic.station.Sessions.Session;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import dev.dyslexic.cinematic.net.PacketDistributor;

/** What the hands do on a crafting table top: lay items into cells, pick them up, craft. */
public final class TableActions {
    private TableActions() {}

    public static void handle(ServerPlayer player, ServerLevel level, Session session, StationActionPayload action) {
        switch (action.action()) {
            case CLICK -> click(player, level, session, action.index(), action.button(), action.shift());
            case PAINT -> paint(player, level, session, action.index());
            case CRAFT -> craft(player, level, session, action.shift());
            case PLACE -> place(player, level, session, action.index(), action.button(), action.side() == 0 ? 1 : action.side());
            case SPREAD -> spread(player, level, session, action.index());
            default -> {}
        }
    }

    private static void click(ServerPlayer player, ServerLevel level, Session session, int cell, int button, boolean shift) {
        if (cell < 0 || cell >= TableGrid.CELLS) {
            return;
        }
        TableStore store = TableStore.get(level);
        TableStore.Table table = store.getOrCreate(session.pos());
        AbstractContainerMenu menu = player.inventoryMenu;
        ItemStack carried = menu.getCarried();
        ItemStack onCell = table.items.get(cell);
        boolean placed = false;
        boolean taken = false;

        if (shift && button == 0) {
            if (!onCell.isEmpty()) {
                player.getInventory().add(onCell);
                taken = true;
                if (onCell.isEmpty()) {
                    table.items.set(cell, ItemStack.EMPTY);
                }
            }
        } else if (button == 0) {
            if (carried.isEmpty()) {
                if (!onCell.isEmpty()) {
                    menu.setCarried(onCell);
                    table.items.set(cell, ItemStack.EMPTY);
                    taken = true;
                }
            } else if (onCell.isEmpty()) {
                int count = Math.min(carried.getCount(), carried.getMaxStackSize());
                table.items.set(cell, carried.split(count));
                placed = true;
            } else if (ItemStack.isSameItemSameComponents(carried, onCell)) {
                int move = Math.min(carried.getCount(), onCell.getMaxStackSize() - onCell.getCount());
                if (move > 0) {
                    onCell.grow(move);
                    carried.shrink(move);
                    placed = true;
                }
            } else {
                table.items.set(cell, carried);
                menu.setCarried(onCell);
                placed = true;
            }
        } else if (button == 1) {
            if (carried.isEmpty()) {
                if (!onCell.isEmpty()) {
                    menu.setCarried(onCell.split((onCell.getCount() + 1) / 2));
                    if (onCell.isEmpty()) {
                        table.items.set(cell, ItemStack.EMPTY);
                    }
                    taken = true;
                }
            } else {
                placed = placeOne(table, cell, carried);
                if (!placed && !ItemStack.isSameItemSameComponents(carried, onCell)) {
                    table.items.set(cell, carried);
                    menu.setCarried(onCell);
                    placed = true;
                }
            }
        }

        if (placed) {
            table.facing[cell] = (byte) session.side().get2DDataValue();
        }
        if (menu.getCarried().isEmpty()) {
            menu.setCarried(ItemStack.EMPTY);
        }
        if (!placed && !taken) {
            store.cleanup(session.pos());
        } else {
            finish(player, level, store, session.pos());
            float pitch = 0.9f + level.getRandom().nextFloat() * 0.25f;
            if (placed) {
                level.playSound(null, session.pos(), SoundEvents.WOOD_HIT, SoundSource.PLAYERS, 0.35f, pitch * 1.3f);
            } else {
                level.playSound(null, session.pos(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.18f, pitch * 1.4f);
            }
        }
    }

    /** Right-drag across cells: drop a single item into each, never pick anything up. */
    private static void paint(ServerPlayer player, ServerLevel level, Session session, int cell) {
        if (cell < 0 || cell >= TableGrid.CELLS) {
            return;
        }
        TableStore store = TableStore.get(level);
        TableStore.Table table = store.getOrCreate(session.pos());
        ItemStack carried = player.inventoryMenu.getCarried();
        if (!carried.isEmpty() && placeOne(table, cell, carried)) {
            table.facing[cell] = (byte) session.side().get2DDataValue();
            if (carried.isEmpty()) {
                player.inventoryMenu.setCarried(ItemStack.EMPTY);
            }
            finish(player, level, store, session.pos());
            level.playSound(null, session.pos(), SoundEvents.WOOD_HIT, SoundSource.PLAYERS, 0.3f,
                    1.2f + level.getRandom().nextFloat() * 0.3f);
        } else {
            store.cleanup(session.pos());
        }
    }

    /** Share the carried stack between the selected table cells, checking every cell on the server. */
    private static void spread(ServerPlayer player, ServerLevel level, Session session, int mask) {
        ItemStack carried = player.inventoryMenu.getCarried();
        if (carried.isEmpty() || mask <= 0 || mask >= 1 << TableGrid.CELLS) return;
        TableStore store = TableStore.get(level);
        TableStore.Table table = store.getOrCreate(session.pos());
        List<Integer> cells = new ArrayList<>();
        for (int cell = 0; cell < TableGrid.CELLS; cell++) {
            ItemStack onCell = table.items.get(cell);
            if ((mask & 1 << cell) != 0 && (onCell.isEmpty() || ItemStack.isSameItemSameComponents(carried, onCell)))
                cells.add(cell);
        }
        int share = cells.isEmpty() ? 0 : carried.getCount() / cells.size();
        boolean placed = false;
        for (int cell : cells) {
            ItemStack onCell = table.items.get(cell);
            int move = Math.min(share, carried.getMaxStackSize() - onCell.getCount());
            if (move <= 0) continue;
            if (onCell.isEmpty()) table.items.set(cell, carried.split(move));
            else {
                onCell.grow(move);
                carried.shrink(move);
            }
            table.facing[cell] = (byte) session.side().get2DDataValue();
            placed = true;
        }
        if (!placed) {
            store.cleanup(session.pos());
            return;
        }
        if (carried.isEmpty()) player.inventoryMenu.setCarried(ItemStack.EMPTY);
        finish(player, level, store, session.pos());
        level.playSound(null, session.pos(), SoundEvents.WOOD_HIT, SoundSource.PLAYERS, 0.35f,
                1.15f + level.getRandom().nextFloat() * 0.25f);
    }

    /**
     * Laying out a recipe: one item from inventory slot {@code slot} (-1: the item in hand) into an empty cell, or onto
     * the same item there.
     */
    private static void place(ServerPlayer player, ServerLevel level, Session session, int cell, int slot, int count) {
        if (cell < 0 || cell >= TableGrid.CELLS || slot < -1 || slot >= 36 || count < 1 || count > 64) {
            return;
        }
        ItemStack source = slot < 0 ? player.inventoryMenu.getCarried() : player.getInventory().getItem(slot);
        if (source.isEmpty()) {
            return;
        }
        TableStore store = TableStore.get(level);
        TableStore.Table table = store.getOrCreate(session.pos());
        int laid = 0;
        while (laid < count && !source.isEmpty() && placeOne(table, cell, source)) laid++;
        if (laid == 0) {
            store.cleanup(session.pos());
            return;
        }
        if (slot >= 0) player.getInventory().setChanged();
        if (slot < 0 && source.isEmpty()) {
            player.inventoryMenu.setCarried(ItemStack.EMPTY);
        }
        table.facing[cell] = (byte) session.side().get2DDataValue();
        finish(player, level, store, session.pos());
        level.playSound(null, session.pos(), SoundEvents.WOOD_HIT, SoundSource.PLAYERS, 0.3f, 1.25f + level.getRandom().nextFloat() * 0.25f);
    }

    private static boolean placeOne(TableStore.Table table, int cell, ItemStack carried) {
        ItemStack onCell = table.items.get(cell);
        if (onCell.isEmpty()) {
            table.items.set(cell, carried.split(1));
            return true;
        }
        if (ItemStack.isSameItemSameComponents(carried, onCell) && onCell.getCount() < onCell.getMaxStackSize()) {
            onCell.grow(1);
            carried.shrink(1);
            return true;
        }
        return false;
    }

    private static void craft(ServerPlayer player, ServerLevel level, Session session, boolean all) {
        TableStore store = TableStore.get(level);
        TableStore.Table table = store.get(session.pos());
        if (table == null) {
            return;
        }
        List<ItemStack> consumed = new ArrayList<>();
        var chosen = dev.dyslexic.cinematic.station.RecipeChoices.chosen(player);
        ItemStack first = TableCrafting.match(level, table.items, session.side(), chosen).map(TableCrafting.Match::result).orElse(ItemStack.EMPTY);
        int crafts = 0;
        do {
            List<ItemStack> used = TableCrafting.craftOnce(player, level, table, session.side(), all);
            if (used == null) {
                break;
            }
            consumed.addAll(used);
            crafts++;
            if (!all) {
                break;
            }
            ItemStack next = TableCrafting.match(level, table.items, session.side(), chosen).map(TableCrafting.Match::result).orElse(ItemStack.EMPTY);
            if (!ItemStack.isSameItemSameComponents(first, next)) {
                break;
            }
        } while (crafts < 64);
        if (crafts == 0) {
            return;
        }
        finish(player, level, store, session.pos());
        BlockPos pos = session.pos();
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 1.05;
        double z = pos.getZ() + 0.5;
        for (int i = 0; i < consumed.size() && i < 9; i++) {
            level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, net.minecraft.world.item.ItemStackTemplate.fromNonEmptyStack(consumed.get(i))), x, y, z, 3, 0.12, 0.02, 0.12, 0.04);
        }
        level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.55f, 1.15f + level.getRandom().nextFloat() * 0.15f);
        level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3f, 0.75f);
    }

    private static void finish(ServerPlayer player, ServerLevel level, TableStore store, BlockPos pos) {
        store.setDirty();
        sync(level, pos);
        store.cleanup(pos);
        player.inventoryMenu.broadcastChanges();
    }

    // ---- syncing ----------------------------------------------------------------------------------

    public static TableContentsPayload contents(BlockPos pos, @Nullable TableStore.Table table) {
        if (table == null || table.isEmpty()) {
            return new TableContentsPayload(pos, List.of(), new byte[0]);
        }
        List<ItemStack> items = new ArrayList<>(TableGrid.CELLS);
        for (ItemStack stack : table.items) {
            items.add(stack.copy());
        }
        return new TableContentsPayload(pos, items, table.facing.clone());
    }

    public static void sync(ServerLevel level, BlockPos pos) {
        PacketDistributor.sendToPlayersTrackingChunk(level, ChunkPos.containing(pos), contents(pos, TableStore.get(level).get(pos)));
    }

    /** Move tabletop items into any ordinary crafting window opened at this table. */
    public static void intoWindow(ServerPlayer player, CraftingMenu menu) {
        ServerLevel level = player.level();
        BlockPos pos = ((dev.dyslexic.cinematic.mixin.CraftingMenuAccess) menu).cinematic$access()
                .evaluate((at, where) -> at == level ? where : null).orElse(null);
        if (pos == null || !TableGrid.isTable(level.getBlockState(pos))) return;
        TableStore store = TableStore.get(level);
        TableStore.Table table = store.get(pos);
        if (table == null || table.isEmpty()) return;
        for (ServerPlayer other : level.players()) {
            Session session = Sessions.get(other);
            if (session != null && session.pos().equals(pos)) return;
        }
        for (int i = 0; i < TableGrid.CELLS; i++) {
            if (menu.getSlot(i + 1).hasItem()) return;
        }
        List<ItemStack> grid = TableGrid.oriented(table.items, TableGrid.sideFacing(pos, player.getX(), player.getZ()));
        store.remove(pos);
        store.setDirty();
        for (int i = 0; i < TableGrid.CELLS; i++) menu.getSlot(i + 1).set(grid.get(i));
        sync(level, pos);
        menu.broadcastChanges();
    }

    /** Spills everything lying on a table that no longer exists. */
    public static void spill(ServerLevel level, BlockPos pos) {
        TableStore store = TableStore.get(level);
        TableStore.Table table = store.remove(pos);
        if (table == null) {
            return;
        }
        Containers.dropContents(level, pos, table.items);
        sync(level, pos);
        Sessions.endAllAt(level, pos);
    }
}
