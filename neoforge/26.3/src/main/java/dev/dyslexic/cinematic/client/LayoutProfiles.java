package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.station.StationKind;
import net.minecraft.network.chat.Component;

/** The settings screens select the existing JSON profiles instead of keeping a second copy in TOML. */
final class LayoutProfiles {
    private LayoutProfiles() {}
    static Component name(StationKind kind) { return Component.translatable("cinematic.configuration." + Settings.key(kind)); }
    static Component value(int selected) { return TableGridEditor.text("profile", selected + 1); }
    static int selected(StationKind kind) {
        return switch (kind) {
            case TABLE -> TableLayouts.selected();
            case FURNACE, SMOKER, BLAST_FURNACE -> FurnaceLayouts.selected(kind);
            case SMITHING, CARTOGRAPHY -> SurfaceLayouts.selected(kind);
            default -> SpatialLayouts.selected(kind);
        };
    }
    static boolean select(StationKind kind, int selected) {
        if (selected < 0 || selected >= 3) return false;
        if (selected == selected(kind)) return true;
        return switch (kind) {
            case TABLE -> TableLayouts.save(TableLayouts.profiles(), selected);
            case FURNACE, SMOKER, BLAST_FURNACE -> FurnaceLayouts.save(kind, FurnaceLayouts.profiles(kind), selected);
            case SMITHING, CARTOGRAPHY -> SurfaceLayouts.save(kind, SurfaceLayouts.profiles(kind), selected);
            default -> SpatialLayouts.save(kind, SpatialLayouts.profiles(kind), selected);
        };
    }
}
