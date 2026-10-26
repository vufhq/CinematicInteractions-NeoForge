package dev.dyslexic.cinematic.api.client;

import dev.dyslexic.cinematic.client.MenuStation;
import dev.dyslexic.cinematic.station.StationKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Base for an add-on scene whose slots and recipes belong to its real block menu. */
public abstract class AddonMenuStation<M extends AbstractContainerMenu> extends MenuStation<M> {
    protected AddonMenuStation(BlockPos pos, Direction side, Class<M> menuType, int inputSlots) {
        super(pos, side, menuType, inputSlots);
    }

    @Override
    public final StationKind kind() {
        return StationKind.ADDON;
    }
}
