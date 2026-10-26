package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.station.StationKind;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The same local profile controls remain available without Cloth Config. */
final class DeveloperSettingsScreen extends Screen {
    private final Screen parent;
    private int page;
    private static final int PER_PAGE = 6;

    DeveloperSettingsScreen(Screen parent) {
        super(Component.translatable("cinematic.configuration.developer"));
        this.parent = parent;
    }

    @Override protected void init() {
        addRenderableWidget(Button.builder(modeLabel(), button -> {
            Settings.DEVELOPER_MODE.set(!Settings.DEVELOPER_MODE.get());
            Settings.SPEC.save();
            button.setMessage(modeLabel());
        }).bounds(width / 2 - 155, 38, 310, 20)
                .tooltip(Tooltip.create(Component.translatable("cinematic.configuration.developerMode.tooltip"))).build());
        var kinds = java.util.Arrays.stream(StationKind.values()).filter(kind -> kind != StationKind.ADDON).toArray(StationKind[]::new);
        for (int row = 0; row < PER_PAGE && page * PER_PAGE + row < kinds.length; row++) {
            StationKind kind = kinds[page * PER_PAGE + row];
            addRenderableWidget(Button.builder(profileLabel(kind), button -> {
                LayoutProfiles.select(kind, (LayoutProfiles.selected(kind) + 1) % 3);
                button.setMessage(profileLabel(kind));
            }).bounds(width / 2 - 155, 70 + row * 22, 310, 20)
                    .tooltip(Tooltip.create(Component.translatable("cinematic.settings.profiles.tooltip"))).build());
        }
        int pages = (kinds.length + PER_PAGE - 1) / PER_PAGE;
        addRenderableWidget(Button.builder(Component.literal("←"), b -> {
            page = Math.floorMod(page - 1, pages); rebuildWidgets();
        }).bounds(width / 2 - 155, height - 28, 45, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 60, height - 28, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("→"), b -> {
            page = (page + 1) % pages; rebuildWidgets();
        }).bounds(width / 2 + 110, height - 28, 45, 20).build());
    }

    private Component modeLabel() {
        return Component.translatable("cinematic.configuration.developerMode").append(": ")
                .append(Component.translatable(Settings.DEVELOPER_MODE.get() ? "options.on" : "options.off"));
    }

    private Component profileLabel(StationKind kind) {
        return LayoutProfiles.name(kind).copy().append(": ").append(LayoutProfiles.value(LayoutProfiles.selected(kind)));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        super.extractRenderState(graphics, x, y, tick);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
