package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.net.AtStationPayload;
import dev.dyslexic.cinematic.net.CampfireProgressPayload;
import dev.dyslexic.cinematic.net.EnchantDisplayPayload;
import dev.dyslexic.cinematic.net.EnchantFxPayload;
import dev.dyslexic.cinematic.net.FurnaceContentsPayload;
import dev.dyslexic.cinematic.net.FurnaceStatePayload;
import dev.dyslexic.cinematic.net.MenuDisplayPayload;
import dev.dyslexic.cinematic.net.MenuFxPayload;
import dev.dyslexic.cinematic.net.SessionClosePayload;
import dev.dyslexic.cinematic.net.TableContentsPayload;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class ClientPayloads {
    private ClientPayloads() {}

    /** Where a player works and at what. */
    public record AtStation(StationKind kind, BlockPos pos) {}

    /** Players currently working at a station, by entity id, for their third-person pose. */
    public static final Map<Integer, AtStation> AT_STATION = new HashMap<>();

    public static void tableContents(TableContentsPayload payload) {
        ClientTables.apply(payload);
    }

    public static void furnaceContents(FurnaceContentsPayload payload) {
        ClientFurnaces.apply(payload);
    }

    public static void furnaceState(FurnaceStatePayload payload) {
        FurnaceStation station = Scene.station(FurnaceStation.class);
        if (station != null && station.pos.equals(payload.pos())) {
            station.applyState(payload);
        }
    }

    public static void recipeChoices(dev.dyslexic.cinematic.net.RecipeChoicePayload payload) {
        ClientStation station = Scene.station;
        if (station != null && station.pos.equals(payload.pos()) && station.kind() == payload.kind()) {
            station.choices().set(payload.results(), payload.selected());
            if (station instanceof TableStation table) table.refreshResult();
        }
    }

    public static void campfireProgress(CampfireProgressPayload payload) {
        CampfireStation station = Scene.station(CampfireStation.class);
        if (station != null && station.pos.equals(payload.pos())) {
            station.applyProgress(payload);
        }
    }

    public static void close(SessionClosePayload payload) {
        Scene.serverClosed();
    }

    public static void atStation(AtStationPayload payload) {
        if (payload.pos().isPresent()) {
            AT_STATION.put(payload.entityId(), new AtStation(payload.kind(), payload.pos().get()));
        } else {
            AT_STATION.remove(payload.entityId());
        }
    }

    @Nullable
    public static StationKind atStation(int entityId) {
        AtStation at = AT_STATION.get(entityId);
        return at == null ? null : at.kind();
    }

    /** Keep a working player's body facing the station in third person. */
    public static void faceStations(@Nullable net.minecraft.client.multiplayer.ClientLevel level) {
        if (level == null) return;
        for (Map.Entry<Integer, AtStation> entry : AT_STATION.entrySet()) {
            if (!(level.getEntity(entry.getKey()) instanceof net.minecraft.world.entity.player.Player player)) continue;
            BlockPos pos = entry.getValue().pos();
            double dx = pos.getX() + 0.5 - player.getX();
            double dz = pos.getZ() + 0.5 - player.getZ();
            if (dx * dx + dz * dz < 1e-4) continue;
            float target = (float) (net.minecraft.util.Mth.atan2(dz, dx) * net.minecraft.util.Mth.RAD_TO_DEG) - 90f;
            player.yBodyRot += net.minecraft.util.Mth.wrapDegrees(target - player.yBodyRot) * 0.35f;
        }
    }

    /** Is anybody working at this block right now? */
    public static boolean someoneAt(BlockPos pos) {
        for (AtStation at : AT_STATION.values()) {
            if (at.pos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    public static void enchantDisplay(EnchantDisplayPayload payload) {
        ClientEnchants.apply(payload);
    }

    public static void enchantFx(EnchantFxPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        boolean mine = mc.player != null && payload.entityId() == mc.player.getId() && Scene.station(EnchantStation.class) != null;
        if (!mine) {
            ClientEnchants.flash(payload.pos());
        }
    }

    public static void menuDisplay(MenuDisplayPayload payload) {
        ClientMenus.apply(payload);
    }

    public static void menuFx(MenuFxPayload payload) {
        if (payload.kind() == StationKind.CHISELED_BOOKSHELF) {
            ShelfBooks.cue(payload);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean mine = mc.player != null && payload.entityId() == mc.player.getId() && Scene.active() && Scene.station.pos.equals(payload.pos());
        if (mine) {
            return;
        }
        if (payload.cue() == MenuFxPayload.Cue.MOVE) {
            ClientMenus.move(payload);
        } else {
            ClientMenus.fx(payload);
        }
    }

    public static void clear() {
        AT_STATION.clear();
    }
}
