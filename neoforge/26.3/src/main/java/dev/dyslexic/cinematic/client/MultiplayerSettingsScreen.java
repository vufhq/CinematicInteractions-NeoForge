package dev.dyslexic.cinematic.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla-widget settings page when Cloth Config is absent. */
final class MultiplayerSettingsScreen extends Screen {
    private final Screen parent;

    MultiplayerSettingsScreen(Screen parent) {
        super(Component.translatable("cinematic.configuration.multiplayer"));
        this.parent = parent;
    }

    @Override protected void init() {
        boolean inWorld = minecraft.level != null;
        Button mode = Button.builder(label(), button -> {
            Settings.CLIENT_ONLY_MODE.set(!Settings.CLIENT_ONLY_MODE.get());
            Settings.SPEC.save();
            button.setMessage(label());
        }).bounds(width / 2 - 155, height / 2 - 10, 310, 20)
                .tooltip(Tooltip.create(Component.translatable(inWorld ? "cinematic.settings.clientOnlyMode.locked"
                        : "cinematic.configuration.clientOnlyMode.tooltip"))).build();
        mode.active = !inWorld;
        addRenderableWidget(mode);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 60, height - 30, 120, 20).build());
    }

    private Component label() {
        return Component.translatable("cinematic.configuration.clientOnlyMode").append(": ")
                .append(Component.translatable(Settings.CLIENT_ONLY_MODE.get() ? "cinematic.settings.on" : "cinematic.settings.off"));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
        int line = 0;
        for (var text : font.split(Component.translatable("cinematic.settings.multiplayer.note"), Math.min(width - 40, 540))) {
            g.centeredText(font, text, width / 2, height / 2 - 72 + line++ * 12, 0xFFA8A296);
        }
        if (minecraft.level != null) {
            line = 0;
            for (var text : font.split(Component.translatable("cinematic.settings.clientOnlyMode.locked"), Math.min(width - 40, 540))) {
                g.centeredText(font, text, width / 2, height / 2 + 26 + line++ * 12, 0xFFA8A296);
            }
        }
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
