package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.station.StationKind;
import java.util.function.Function;
import javax.annotation.Nullable;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import me.shedaniel.clothconfig2.impl.builders.BooleanToggleBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import dev.dyslexic.cinematic.client.ClientConfig;

/**
 * The settings screen when Cloth Config is installed: scenes, interface, camera and developer tabs, each with a line
 * on what it is for, every switch with a tooltip and a value that says what it does rather than just yes or no.
 * Only loaded when Cloth Config is present ({@link CinematicClient}).
 */
final class ClothScreen {
    private ClothScreen() {}

    private static final int NOTE = 0xA8A296;
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/block/dark_oak_planks.png");

    static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.translatable("cinematic.settings.title"))
                .setDefaultBackgroundTexture(BACKGROUND)
                .setSavingRunnable(Settings.SPEC::save);
        builder.setGlobalized(false);
        ConfigEntryBuilder entries = builder.entryBuilder();

        // ---- scenes: where they play, and what happens when one cannot ----
        ConfigCategory scenes = builder.getOrCreateCategory(Component.translatable("cinematic.settings.scenes"));
        scenes.addEntry(note(entries, "cinematic.settings.scenes.note"));
        scenes.addEntry(toggle(entries, "heightLogic", Settings.HEIGHT_LOGIC,
                on -> value(on, "cinematic.settings.height.window", "cinematic.settings.height.notice")));
        SubCategoryBuilder blocks = entries.startSubCategory(Component.translatable("cinematic.settings.blocks"))
                .setTooltip(Component.translatable("cinematic.settings.blocks.tooltip"))
                .setExpanded(true);
        BooleanListEntry campfire = null;
        for (StationKind kind : StationKind.values()) {
            if (kind == StationKind.ADDON) continue;
            BooleanListEntry entry = toggle(entries, Settings.key(kind), Settings.SCENES.get(kind),
                    on -> value(on, "cinematic.settings.scene.on", kind == StationKind.CAMPFIRE ? "cinematic.settings.off" : "cinematic.settings.scene.off"));
            blocks.add(entry);
            if (kind == StationKind.CAMPFIRE) {
                campfire = entry;
            }
        }
        scenes.addEntry(blocks.build());

        // ---- interface: the text over the scene and the world ----
        ConfigCategory ui = builder.getOrCreateCategory(Component.translatable("cinematic.settings.interface"));
        ui.addEntry(note(entries, "cinematic.settings.interface.note"));
        ui.addEntry(toggle(entries, "controlHints", Settings.CONTROL_HINTS, on -> value(on, "cinematic.settings.show", "cinematic.settings.hide")));
        // there is nothing to invite to with the campfire scene off
        ui.addEntry(toggle(entries, "campfireInvite", Settings.CAMPFIRE_INVITE, on -> value(on, "cinematic.settings.show", "cinematic.settings.hide"),
                campfire == null ? null : Requirement.isTrue(campfire)));

        // ---- camera and effects: comfort ----
        ConfigCategory camera = builder.getOrCreateCategory(Component.translatable("cinematic.settings.camera"));
        camera.addEntry(note(entries, "cinematic.settings.camera.note"));
        var quick = entries.startSelector(Component.translatable("cinematic.configuration.quickMode"), new Integer[]{0, 1, 2}, Settings.QUICK_MODE.get())
                .setDefaultValue(Settings.QUICK_MODE.getDefault())
                .setTooltip(Component.translatable("cinematic.configuration.quickMode.tooltip"))
                .setNameProvider(ClothScreen::quickModeText)
                .setSaveConsumer(Settings.QUICK_MODE::set)
                .build();
        camera.addEntry(quick);
        var approach = slider(entries, "approachSpeed", Settings.APPROACH_SPEED);
        approach.setRequirement(Requirement.not(Requirement.isValue(quick, 2)));
        camera.addEntry(approach);
        camera.addEntry(entries.startIntSlider(Component.translatable("cinematic.configuration.quickWindow"), Settings.QUICK_WINDOW.get(), 5, 30)
                .setDefaultValue(Settings.QUICK_WINDOW.getDefault())
                .setTooltip(Component.translatable("cinematic.configuration.quickWindow.tooltip"))
                .setTextGetter(v -> Component.translatable("cinematic.settings.seconds", v))
                .setSaveConsumer(Settings.QUICK_WINDOW::set)
                .setRequirement(Requirement.isValue(quick, 1))
                .build());
        camera.addEntry(slider(entries, "leaveSpeed", Settings.LEAVE_SPEED));
        camera.addEntry(entries.startSelector(Component.translatable("cinematic.configuration.armStyle"), new Integer[]{0, 1, 2}, Settings.ARM_STYLE.get())
                .setDefaultValue(Settings.ARM_STYLE.getDefault())
                .setTooltip(Component.translatable("cinematic.configuration.armStyle.tooltip"))
                .setNameProvider(ClothScreen::armStyleText)
                .setSaveConsumer(Settings.ARM_STYLE::set)
                .build());
        camera.addEntry(slider(entries, "leanStrength", Settings.LEAN_STRENGTH));
        camera.addEntry(slider(entries, "sceneZoom", Settings.SCENE_ZOOM));
        camera.addEntry(toggle(entries, "lookToggles", Settings.LOOK_TOGGLES,
                on -> Component.translatable(on ? "cinematic.settings.look.toggle" : "cinematic.settings.look.hold").withStyle(ChatFormatting.WHITE)));
        Function<Boolean, Component> onOff = on -> value(on, "cinematic.settings.on", "cinematic.settings.off");
        SubCategoryBuilder effects = entries.startSubCategory(Component.translatable("cinematic.settings.effects"))
                .setTooltip(Component.translatable("cinematic.settings.effects.tooltip"))
                .setExpanded(true);
        effects.add(toggle(entries, "letterbox", Settings.LETTERBOX, onOff));
        effects.add(toggle(entries, "livingCamera", Settings.LIVING_CAMERA, onOff));
        effects.add(toggle(entries, "cameraShake", Settings.CAMERA_SHAKE, onOff));
        effects.add(toggle(entries, "screenFlash", Settings.SCREEN_FLASH, onOff));
        camera.addEntry(effects.build());
        ConfigCategory multiplayer = builder.getOrCreateCategory(Component.translatable("cinematic.configuration.multiplayer"));
        multiplayer.addEntry(note(entries, "cinematic.settings.multiplayer.note"));
        boolean inWorld = Minecraft.getInstance().level != null;
        BooleanListEntry clientOnlySwitch = toggle(entries, "clientOnlyMode", Settings.CLIENT_ONLY_MODE, onOff);
        clientOnlySwitch.setEditable(!inWorld);
        multiplayer.addEntry(clientOnlySwitch);
        if (inWorld) multiplayer.addEntry(note(entries, "cinematic.settings.clientOnlyMode.locked"));
        ConfigCategory developer = builder.getOrCreateCategory(Component.translatable("cinematic.configuration.developer"));
        developer.addEntry(note(entries, "cinematic.settings.developer.note"));
        developer.addEntry(toggle(entries, "developerMode", Settings.DEVELOPER_MODE, onOff));
        developer.addEntry(note(entries, "cinematic.settings.profiles.note"));
        for (StationKind kind : StationKind.values()) {
            if (kind == StationKind.ADDON) continue;
            developer.addEntry(entries.startIntSlider(LayoutProfiles.name(kind), LayoutProfiles.selected(kind) + 1, 1, 3)
                    .setDefaultValue(1)
                    .setTextGetter(v -> LayoutProfiles.value(v - 1))
                    .setTooltip(Component.translatable("cinematic.settings.profiles.tooltip"))
                    .setSaveConsumer(v -> LayoutProfiles.select(kind, v - 1))
                    .build());
        }
        return builder.build();
    }

    /** A switch for one setting, using the translations shared with the built-in settings screen. */
    private static BooleanListEntry toggle(ConfigEntryBuilder entries, String key, ClientConfig.BooleanValue setting,
            Function<Boolean, Component> value) {
        return toggle(entries, key, setting, value, null);
    }

    /** The same, greyed out unless {@code requirement} holds. */
    private static BooleanListEntry toggle(ConfigEntryBuilder entries, String key, ClientConfig.BooleanValue setting,
            Function<Boolean, Component> value, @Nullable Requirement requirement) {
        String base = "cinematic.configuration." + key;
        BooleanToggleBuilder toggle = entries.startBooleanToggle(Component.translatable(base), setting.get())
                .setDefaultValue(setting.getDefault())
                .setTooltip(Component.translatable(base + ".tooltip"))
                .setYesNoTextSupplier(value)
                .setSaveConsumer(setting::set);
        if (requirement != null) {
            toggle.setRequirement(requirement);
        }
        return toggle.build();
    }

    /** A percentage, shown with its sign; as made (100%) it is drawn plain, changed it is drawn warm. */
    private static me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry slider(ConfigEntryBuilder entries, String key, ClientConfig.IntValue setting) {
        String base = "cinematic.configuration." + key;
        int def = setting.getDefault();
        return entries.startIntSlider(Component.translatable(base), setting.get(), min(setting), max(setting))
                .setDefaultValue(def)
                .setTooltip(Component.translatable(base + ".tooltip"))
                .setTextGetter(v -> Component.literal(v + "%").withStyle(v == def ? ChatFormatting.WHITE : ChatFormatting.GOLD))
                .setSaveConsumer(setting::set)
                .build();
    }

    private static int min(ClientConfig.IntValue setting) {
        return setting == Settings.LEAN_STRENGTH || setting == Settings.SCENE_ZOOM ? 0 : 50;
    }

    private static int max(ClientConfig.IntValue setting) {
        return setting == Settings.LEAN_STRENGTH || setting == Settings.SCENE_ZOOM ? 100 : 200;
    }

    /** What a switch does in its current position: green when on, muted when off. */
    private static Component value(boolean on, String onKey, String offKey) {
        MutableComponent text = Component.translatable(on ? onKey : offKey);
        return text.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    private static Component quickModeText(int mode) {
        return Component.translatable(mode == 1 ? "cinematic.settings.quickMode.short.1" : "cinematic.settings.quickMode." + mode)
                .withStyle(mode == 2 ? ChatFormatting.GOLD : ChatFormatting.WHITE);
    }

    private static Component armStyleText(int style) {
        return Component.translatable(style == 1 ? "cinematic.settings.armStyle.short.1" : "cinematic.settings.armStyle." + style)
                .withStyle(style == 0 ? ChatFormatting.WHITE : ChatFormatting.GOLD);
    }

    private static me.shedaniel.clothconfig2.gui.entries.TextListEntry note(ConfigEntryBuilder entries, String key) {
        return entries.startTextDescription(Component.translatable(key)).setColor(NOTE).build();
    }
}
