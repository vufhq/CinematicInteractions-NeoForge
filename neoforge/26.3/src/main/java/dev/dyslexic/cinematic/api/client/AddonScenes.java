package dev.dyslexic.cinematic.api.client;

import dev.dyslexic.cinematic.api.AddonStations;
import dev.dyslexic.cinematic.client.ClientStation;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/** Client-only scene factories. Keep references to this class out of common and dedicated-server initializers. */
public final class AddonScenes {
    private AddonScenes() {}

    @FunctionalInterface
    public interface Factory {
        ClientStation create(BlockPos pos, BlockState state, LocalPlayer player, Direction side);
    }

    private static final Map<Identifier, Factory> FACTORIES = new HashMap<>();

    /** Register in the add-on's client setup, using the same id as its common AddonStations definition. */
    public static synchronized void register(Identifier id, Factory factory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        if (AddonStations.byId(id) == null) {
            throw new IllegalArgumentException("Register the add-on station before its client scene: " + id);
        }
        if (FACTORIES.putIfAbsent(id, factory) != null) {
            throw new IllegalArgumentException("Duplicate cinematic add-on scene: " + id);
        }
    }

    public static synchronized boolean available(BlockState state) {
        AddonStations.Definition definition = AddonStations.forBlock(state);
        return definition != null && FACTORIES.containsKey(definition.id());
    }

    @Nullable
    public static synchronized ClientStation create(BlockPos pos, BlockState state, LocalPlayer player, Direction side) {
        AddonStations.Definition definition = AddonStations.forBlock(state);
        Factory factory = definition == null ? null : FACTORIES.get(definition.id());
        if (factory == null) {
            return null;
        }
        ClientStation station = Objects.requireNonNull(factory.create(pos, state, player, side), "addon scene");
        if (station.kind() != dev.dyslexic.cinematic.station.StationKind.ADDON || !station.pos.equals(pos)) {
            throw new IllegalStateException("Addon scene " + definition.id() + " must target its registered block");
        }
        return station;
    }
}
