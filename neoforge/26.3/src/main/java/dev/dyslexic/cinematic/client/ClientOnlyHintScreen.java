package dev.dyslexic.cinematic.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A short note over the server list, laid out as Minecraft's own confirm screens: what the client-only mode is for, a
 * button that turns the existing setting on (saved at once, before any connection) and one that just goes on. Either
 * way it is answered and does not come back ({@link ServerListHint}); joining a server is never held up by it.
 */
final class ClientOnlyHintScreen extends Screen {
    /** The message wraps to this many GUI pixels at most, so its lines stay short enough to read at a glance. */
    private static final int TEXT_WIDTH = 300;
    private static final int TITLE_GAP = 14;
    private static final int BUTTONS_GAP = 20;
    private static final int BUTTON_SPACING = 8;
    private static final int MESSAGE_COLOR = 0xD8D8D8;

    private final Screen list;
    private MultiLineLabel message = MultiLineLabel.EMPTY;
    private int top;

    ClientOnlyHintScreen(Screen list) {
        // from an empty root: a part appended to a coloured one would take on its colour
        super(Component.empty().append(Component.literal("Cinematic Interactions").withColor(ServerListHint.ACCENT))
                .append(Component.literal(" · ").withColor(0xA8A8A8))
                .append(Component.translatable("cinematic.serverHint.title")));
        this.list = list;
    }

    @Override
    protected void init() {
        message = MultiLineLabel.create(font, Component.translatable("cinematic.serverHint.message"), Math.min(width - 40, TEXT_WIDTH));
        Component enable = Component.translatable("cinematic.serverHint.enable");
        Component carryOn = Component.translatable("cinematic.serverHint.continue");
        int buttonWidth = Math.max(150, Math.max(font.width(enable), font.width(carryOn)) + 20);
        boolean sideBySide = buttonWidth * 2 + BUTTON_SPACING <= width - 20;
        int buttonsHeight = sideBySide ? 20 : 20 * 2 + 4;
        int block = font.lineHeight + TITLE_GAP + message.getLineCount() * font.lineHeight + BUTTONS_GAP + buttonsHeight;
        top = Math.max(10, (height - block) / 2);
        int buttonsTop = top + font.lineHeight + TITLE_GAP + message.getLineCount() * font.lineHeight + BUTTONS_GAP;
        Button on = Button.builder(enable, button -> {
            Settings.CLIENT_ONLY_MODE.set(true);
            ServerListHint.seen();
            minecraft.gui.setScreen(list);
        }).width(buttonWidth).build();
        Button off = Button.builder(carryOn, button -> onClose()).width(buttonWidth).build();
        if (sideBySide) {
            on.setPosition(width / 2 - buttonWidth - BUTTON_SPACING / 2, buttonsTop);
            off.setPosition(width / 2 + BUTTON_SPACING / 2, buttonsTop);
        } else {
            on.setPosition((width - buttonWidth) / 2, buttonsTop);
            off.setPosition((width - buttonWidth) / 2, buttonsTop + 24);
        }
        addRenderableWidget(on);
        addRenderableWidget(off);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, top, 0xFFFFFFFF);
        int line = 0;
        for (var text : font.split(Component.translatable("cinematic.serverHint.message"), Math.min(width - 40, TEXT_WIDTH))) {
            graphics.centeredText(font, text, width / 2,
                    top + font.lineHeight + TITLE_GAP + line++ * font.lineHeight, 0xFFD8D8D8);
        }
    }

    /** "Continue", and Esc: the mode stays as it is, the server list comes back. */
    @Override
    public void onClose() {
        ServerListHint.seen();
        minecraft.gui.setScreen(list);
    }

    @Override
    public Component getNarrationMessage() {
        return Component.empty().append(title).append(". ").append(Component.translatable("cinematic.serverHint.message"));
    }
}
