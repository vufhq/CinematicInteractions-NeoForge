package dev.dyslexic.cinematic.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import dev.dyslexic.cinematic.station.StationKind;

/** A small vanilla-widget fallback when Cloth Config is absent. */
public final class SettingsScreen extends Screen {
    private final Screen parent;
    private int page;
    private record Option(String key, ClientConfig.Value<?> value) {}
    private final List<Option> options = new ArrayList<>();

    public SettingsScreen(Screen parent) {
        super(Component.translatable("cinematic.settings.title"));
        this.parent = parent;
        options.add(new Option("heightLogic", Settings.HEIGHT_LOGIC));
        for (StationKind kind : StationKind.values()) if (kind != StationKind.ADDON)
            options.add(new Option(Settings.key(kind), Settings.SCENES.get(kind)));
        options.add(new Option("controlHints", Settings.CONTROL_HINTS));
        options.add(new Option("campfireInvite", Settings.CAMPFIRE_INVITE));
        options.add(new Option("approachSpeed", Settings.APPROACH_SPEED));
        options.add(new Option("leaveSpeed", Settings.LEAVE_SPEED));
        options.add(new Option("leanStrength", Settings.LEAN_STRENGTH));
        options.add(new Option("sceneZoom", Settings.SCENE_ZOOM));
        options.add(new Option("quickMode", Settings.QUICK_MODE));
        options.add(new Option("quickWindow", Settings.QUICK_WINDOW));
        options.add(new Option("armStyle", Settings.ARM_STYLE));
        options.add(new Option("lookToggles", Settings.LOOK_TOGGLES));
        options.add(new Option("letterbox", Settings.LETTERBOX));
        options.add(new Option("livingCamera", Settings.LIVING_CAMERA));
        options.add(new Option("cameraShake", Settings.CAMERA_SHAKE));
        options.add(new Option("screenFlash", Settings.SCREEN_FLASH));
    }

    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("cinematic.configuration.multiplayer"),
                b -> minecraft.gui.setScreen(new MultiplayerSettingsScreen(this)))
                .bounds(width - 248, 12, 116, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("cinematic.configuration.developer"),
                b -> minecraft.gui.setScreen(new DeveloperSettingsScreen(this)))
                .bounds(width - 124, 12, 116, 20).build());
        for (int row = 0; row < 8 && page * 8 + row < options.size(); row++) {
            Option option = options.get(page * 8 + row);
            Button button = Button.builder(label(option), clicked -> {
                if (option.value instanceof ClientConfig.BooleanValue value) value.set(!value.get());
                else if (option.value instanceof ClientConfig.IntValue value) {
                    int min = option.key.equals("quickMode") || option.key.equals("armStyle") ? 0 : option.key.equals("quickWindow") ? 5 : option.key.equals("leanStrength") || option.key.equals("sceneZoom") ? 0 : 50;
                    int max = option.key.equals("quickMode") || option.key.equals("armStyle") ? 2 : option.key.equals("quickWindow") ? 30 : min == 0 ? 100 : 200;
                    int next = value.get() + (option.key.equals("quickWindow") || option.key.equals("quickMode") || option.key.equals("armStyle") ? 1 : 10);
                    value.set(next > max ? min : next);
                }
                Settings.SPEC.save();
                rebuildWidgets();
            }).bounds(width / 2 - 155, 40 + row * 23, 310, 20)
                    .tooltip(Tooltip.create(Component.translatable("cinematic.configuration." + option.key + ".tooltip"))).build();
            if (option.key.equals("approachSpeed")) button.active = Settings.quickMode() != 2;
            if (option.key.equals("quickWindow")) button.active = Settings.quickMode() == 1;
            addRenderableWidget(button);
        }
        addRenderableWidget(Button.builder(Component.literal("←"), b -> { page = Math.floorMod(page - 1, (options.size() + 7) / 8); rebuildWidgets(); }).bounds(width / 2 - 155, height - 30, 45, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(width / 2 - 60, height - 30, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("→"), b -> { page = (page + 1) % ((options.size() + 7) / 8); rebuildWidgets(); }).bounds(width / 2 + 110, height - 30, 45, 20).build());
    }

    private Component label(Option option) {
        Component value = option.key.equals("quickMode") ? Component.translatable("cinematic.settings.quickMode." + Settings.quickMode())
                : option.key.equals("armStyle") ? Component.translatable("cinematic.settings.armStyle." + Settings.armStyle())
                : option.value.get() instanceof Boolean on ? Component.translatable(on ? "options.on" : "options.off") : Component.literal(option.value.get().toString());
        return Component.translatable("cinematic.configuration." + option.key).append(": ").append(value);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float tick) {
        super.extractRenderState(g, x, y, tick);
        g.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
    }
    @Override public void onClose() { Settings.SPEC.save(); minecraft.gui.setScreen(parent); }
}
