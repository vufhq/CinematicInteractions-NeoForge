package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.Cinematic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Manual opt-in check against a separate unmodded server. */
public final class ClientOnlySmoke {
    private ClientOnlySmoke() {}
    private static final boolean ENABLED = Boolean.getBoolean("cinematic.clientOnlySmoke");
    private static final BlockPos TABLE = new BlockPos(0, 64, 1);
    private static final BlockPos FURNACE = new BlockPos(1, 64, 1);
    private static final BlockPos ANVIL = new BlockPos(-1, 64, 0);
    private static final BlockPos ENCHANTING = new BlockPos(1, 64, -1);
    private static int stage, ticks;
    private static Vec3 original;

    public static void onScreen(Screen screen) {
        if (!ENABLED || stage != 0 || !(screen instanceof TitleScreen)) return;
        stage = 1;
        Settings.CLIENT_ONLY_MODE.set(true);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString("127.0.0.1:25587"),
                new ServerData("Client-only smoke", "127.0.0.1:25587", ServerData.Type.OTHER), false, null));
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (ENABLED && stage == 0 && mc.gui.screen() instanceof TitleScreen) onScreen(mc.gui.screen());
        if (!ENABLED || stage == 0 || stage == 99 || stage == -1) return;
        if (++ticks > 1000) { fail("timeout at " + stage); return; }
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (ClientOnly.serverHasMod()) { fail("server unexpectedly has Cinematic"); return; }
        if (Scene.clientOnly() && CameraRig.last() != null
                && CameraRig.last().pos().distanceTo(mc.player.getEyePosition()) > 0.38) {
            fail("camera exceeded local eye limit"); return;
        }
        switch (stage) {
            case 1 -> {
                if (!mc.level.getBlockState(TABLE).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                        || mc.player.getInventory().getItem(0).getItem() != Items.OAK_LOG
                        || mc.player.distanceToSqr(new Vec3(0.5, 64, 0.5)) > 0.1) return;
                original = mc.player.position();
                clickAsPlayer(mc, new BlockHitResult(new Vec3(0.5, 65, 1.5), Direction.UP, TABLE, false));
                if (mc.player.isSwinging()) { fail("vanilla hand swung before the client-only scene"); return; }
                advance();
            }
            case 2 -> {
                if (ticks == 80) Cinematic.LOGGER.info("[ClientOnlySmoke] screen={}, menu={}, phase={}, refused={}",
                        mc.gui.screen(), mc.player.containerMenu.getClass().getSimpleName(), Scene.phase, Scene.refused);
                if (!(mc.player.containerMenu instanceof CraftingMenu menu) || !Scene.clientOnly()
                        || Scene.phase != Scene.Phase.ACTIVE) return;
                if (mc.player.position().distanceToSqr(original) > 0.01) { fail("moved on approach"); return; }
                TableStation table = Scene.station(TableStation.class);
                var plankGuide = table.recipeBook.guide.entries.stream()
                        .filter(entry -> entry.result.getItem() == Items.OAK_PLANKS).findFirst();
                if (plankGuide.isEmpty()) return;
                table.recipeBook.guide.select(plankGuide.get());
                if (java.util.Arrays.stream(table.recipeBook.guide.grid).allMatch(TableRecipeGuide::empty)) {
                    fail("vanilla recipe guide has no ingredient layout"); return;
                }
                Screenshot.grab(mc.gameDirectory, "client-only-table.png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
                mc.gameMode.handleContainerInput(menu.containerId, 37, 0, ContainerInput.PICKUP, mc.player);
                table.hoverCell = 4;
                table.click(1, false);
                mc.gameMode.handleContainerInput(menu.containerId, 37, 0, ContainerInput.PICKUP, mc.player);
                advance();
            }
            case 3 -> {
                if (!(mc.player.containerMenu instanceof CraftingMenu menu)) { fail("crafting menu closed"); return; }
                if (menu.getSlot(0).getItem().getItem() != Items.OAK_PLANKS) return;
                Scene.station(TableStation.class).craft(false);
                advance();
            }
            case 4 -> {
                if (!(mc.player.containerMenu instanceof CraftingMenu menu)) { fail("crafting menu closed before result"); return; }
                if (menu.getCarried().getItem() != Items.OAK_PLANKS) return;
                Scene.leave();
                advance();
            }
            case 5 -> {
                if (Scene.active() || mc.player.containerMenu != mc.player.inventoryMenu) return;
                if (mc.player.position().distanceToSqr(original) > 0.01) { fail("moved on exit"); return; }
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(1.5, 64.5, 1.0), Direction.NORTH, FURNACE, false));
                advance();
            }
            case 6 -> {
                if (!Scene.clientOnly() || Scene.phase != Scene.Phase.ACTIVE
                        || !(mc.player.containerMenu instanceof AbstractFurnaceMenu)) return;
                Screenshot.grab(mc.gameDirectory, "client-only-furnace.png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
                Scene.leave();
                advance();
            }
            case 7 -> {
                if (Scene.active() || mc.player.containerMenu != mc.player.inventoryMenu) return;
                if (mc.player.position().distanceToSqr(original) > 0.01) { fail("moved at furnace"); return; }
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(0.0, 64.5, 0.5), Direction.EAST, ANVIL, false));
                advance();
            }
            case 8 -> {
                if (!Scene.clientOnly() || Scene.phase != Scene.Phase.ACTIVE
                        || !(mc.player.containerMenu instanceof AnvilMenu)) return;
                Screenshot.grab(mc.gameDirectory, "client-only-anvil.png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
                Scene.leave();
                advance();
            }
            case 9 -> {
                if (Scene.active() || mc.player.containerMenu != mc.player.inventoryMenu) return;
                if (mc.player.position().distanceToSqr(original) > 0.01) { fail("moved at anvil"); return; }
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(new Vec3(1.5, 65, -0.5), Direction.UP, ENCHANTING, false));
                advance();
            }
            case 10 -> {
                if (ticks == 80) Cinematic.LOGGER.info("[ClientOnlySmoke] enchanting screen={}, menu={}, phase={}, refused={}",
                        mc.gui.screen(), mc.player.containerMenu.getClass().getSimpleName(), Scene.phase, Scene.refused);
                if (!Scene.clientOnly() || Scene.phase != Scene.Phase.ACTIVE
                        || !(mc.player.containerMenu instanceof EnchantmentMenu)) return;
                Screenshot.grab(mc.gameDirectory, "client-only-enchanting.png", mc.gameRenderer.mainRenderTarget(), 1, message -> {});
                Scene.leave();
                advance();
            }
            case 11 -> {
                if (Scene.active() || mc.player.containerMenu != mc.player.inventoryMenu) return;
                if (mc.player.position().distanceToSqr(original) > 0.01) { fail("moved at enchanting table"); return; }
                Cinematic.LOGGER.info("[ClientOnlySmoke] PASS: vanilla server, recipe guide, craft result, furnace, anvil and enchanting table, menu close, stationary player");
                stage = 99;
                mc.stop();
            }
            default -> {}
        }
    }

    /** Exercise Minecraft's use-key path, including its predicted hand animation. */
    private static void clickAsPlayer(Minecraft mc, BlockHitResult hit) {
        mc.hitResult = hit;
        try {
            var use = Minecraft.class.getDeclaredMethod("startUseItem");
            use.setAccessible(true);
            use.invoke(mc);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot simulate the use key", e);
        }
    }

    private static void advance() { stage++; ticks = 0; }
    private static void fail(String reason) {
        Cinematic.LOGGER.error("[ClientOnlySmoke] FAIL: {}", reason);
        stage = -1;
        Minecraft.getInstance().stop();
    }
}
