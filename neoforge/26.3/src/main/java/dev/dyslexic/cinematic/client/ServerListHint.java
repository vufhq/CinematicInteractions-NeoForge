package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.mixin.ScreenWidgetAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The client-only mode, told where it matters: in the server list. The first time the list opens with the mode off, a
 * small note asks whether to turn it on ({@link ClientOnlyHintScreen}); it shows once, whatever the answer. The list
 * itself always carries a quiet line with the mode's state that opens its setting.
 */
public final class ServerListHint {
    private ServerListHint() {}

    /** The pale blue of Cinematic Interactions' name (calm, close to the vanilla link colour). */
    static final int ACCENT = 0x8CC4EE;
    /** The line's own grey, a shade under the list's white title. */
    private static final int MUTED = 0xA8A8A8;
    /** Space kept between the line and the screen's title or edge. */
    private static final int GAP = 8;

    /** ScreenEvents.AFTER_INIT: the server list was laid out (it is again after every resize and every return to it). */
    static void onScreenInit(Minecraft mc, Screen screen) {
        if (!(screen instanceof JoinMultiplayerScreen list)) {
            return;
        }
        addStateLine(mc, list);
        if (!Settings.clientOnlyMode() && !Settings.SERVER_HINT_SEEN.get()) {
            // after this init is over: a screen opened from inside another's init would be laid out half-way
            mc.schedule(() -> {
                if (mc.gui.screen() == list) mc.gui.setScreen(new ClientOnlyHintScreen(list));
            });
        }
    }

    /** The note was answered (either way): it does not come back. */
    static void seen() {
        Settings.SERVER_HINT_SEEN.set(true);
        Settings.SPEC.save();
    }

    /** "Cinematic · Client-only mode: off" beside the title, a text link to the setting. */
    private static void addStateLine(Minecraft mc, JoinMultiplayerScreen list) {
        var font = mc.font;
        MutableComponent text = Component.empty().append(Component.literal("Cinematic").withColor(ACCENT))
                .append(Component.literal(" · ").withColor(MUTED))
                .append(Component.translatable("cinematic.serverHint.state",
                        Component.translatable(Settings.clientOnlyMode() ? "cinematic.settings.on" : "cinematic.settings.off"))
                        .withColor(MUTED));
        int width = font.width(text);
        int titleRight = list.width / 2 + font.width(list.getTitle()) / 2;
        // beside the title, on its line, when there is room; else above it
        int x = list.width - width - GAP;
        int y = 20;
        if (x < titleRight + GAP) {
            x = (list.width - width) / 2;
            y = 6;
        }
        PlainTextButton line = new PlainTextButton(x, y, width, font.lineHeight, text,
                button -> mc.gui.setScreen(new MultiplayerSettingsScreen(list)), font);
        line.setTooltip(Tooltip.create(Component.translatable("cinematic.serverHint.state.tooltip")));
        ((ScreenWidgetAccess) list).cinematic$addRenderableWidget(line);
    }
}
