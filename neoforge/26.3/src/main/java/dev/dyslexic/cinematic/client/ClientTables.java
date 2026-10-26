package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.net.TableContentsPayload;
import dev.dyslexic.cinematic.table.TableGrid;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

/** Client copy of what lies on nearby tables, plus per-cell timestamps for the "item lands" animation. */
public final class ClientTables {
    private ClientTables() {}

    public static final class Entry {
        public final ItemStack[] items = new ItemStack[TableGrid.CELLS];
        public final byte[] facing = new byte[TableGrid.CELLS];
        /** Seconds-clock time a cell last received something, for the landing bounce. */
        public final double[] landedAt = new double[TableGrid.CELLS];
        public int version;

        Entry() {
            Arrays.fill(items, ItemStack.EMPTY);
            Arrays.fill(landedAt, -10);
        }

        public List<ItemStack> list() {
            return Arrays.asList(items);
        }
    }

    public static final Map<BlockPos, Entry> TABLES = new HashMap<>();
    @Nullable private static Entry local;
    @Nullable private static BlockPos localPos;
    private static int localMenuId = -1;

    @Nullable
    public static Entry get(BlockPos pos) {
        if (Scene.clientOnly() && Scene.station instanceof TableStation table && table.pos.equals(pos)) {
            var player = Minecraft.getInstance().player;
            if (player == null || !(player.containerMenu instanceof CraftingMenu menu)) return null;
            if (local == null || !pos.equals(localPos) || localMenuId != menu.containerId) {
                local = new Entry();
                localPos = pos.immutable();
                localMenuId = menu.containerId;
            }
            for (int logical = 0; logical < TableGrid.CELLS; logical++) {
                int world = TableGrid.worldIndex(table.sideDirection(), logical / 3, logical % 3);
                ItemStack before = local.items[world];
                ItemStack after = menu.getSlot(logical + 1).getItem();
                if (!ItemStack.matches(before, after)) {
                    if (!after.isEmpty() && (before.isEmpty() || after.getCount() > before.getCount()))
                        local.landedAt[world] = Scene.clock();
                    local.items[world] = after.copy();
                    local.version++;
                }
                local.facing[world] = (byte) table.sideDirection().get2DDataValue();
            }
            return local;
        }
        return TABLES.get(pos);
    }

    public static void apply(TableContentsPayload payload) {
        BlockPos pos = payload.pos();
        if (payload.items().isEmpty()) {
            TABLES.remove(pos);
            return;
        }
        Entry entry = TABLES.computeIfAbsent(pos.immutable(), p -> new Entry());
        double now = Scene.clock();
        for (int i = 0; i < TableGrid.CELLS && i < payload.items().size(); i++) {
            ItemStack before = entry.items[i];
            ItemStack after = payload.items().get(i);
            boolean grew = !after.isEmpty()
                    && (before.isEmpty() || !ItemStack.isSameItemSameComponents(before, after) || after.getCount() > before.getCount());
            if (grew) {
                entry.landedAt[i] = now;
            }
            entry.items[i] = after;
            entry.facing[i] = i < payload.facing().length ? payload.facing()[i] : 0;
        }
        entry.version++;
    }

    public static void unloadChunk(ChunkPos chunk) {
        TABLES.keySet().removeIf(pos -> pos.getX() >> 4 == chunk.x() && pos.getZ() >> 4 == chunk.z());
    }

    public static void clear() {
        TABLES.clear();
        local = null;
        localPos = null;
        localMenuId = -1;
    }
}
