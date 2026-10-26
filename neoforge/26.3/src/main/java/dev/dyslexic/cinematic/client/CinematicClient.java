package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.ClientBridge;
import dev.dyslexic.cinematic.Platform;
import dev.dyslexic.cinematic.client.compat.FirstPersonBodyCompat;
import dev.dyslexic.cinematic.net.*;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

@Mod(value = Cinematic.MODID, dist = Dist.CLIENT)
public final class CinematicClient {
    /** HUD layers a scene hides (the Fabric port cancels the same Hud methods). */
    private static final Set<Identifier> HIDDEN_IN_SCENE = Set.of(VanillaGuiLayers.CAMERA_OVERLAYS, VanillaGuiLayers.CROSSHAIR,
            VanillaGuiLayers.HOTBAR, VanillaGuiLayers.PLAYER_HEALTH, VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL,
            VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.AIR_LEVEL, VanillaGuiLayers.CONTEXTUAL_INFO_BAR_BACKGROUND,
            VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.CONTEXTUAL_INFO_BAR, VanillaGuiLayers.SELECTED_ITEM_NAME,
            VanillaGuiLayers.SPECTATOR_TOOLTIP, VanillaGuiLayers.EFFECTS, VanillaGuiLayers.SLEEP_OVERLAY, VanillaGuiLayers.DEMO_OVERLAY,
            VanillaGuiLayers.SCOREBOARD_SIDEBAR, VanillaGuiLayers.OVERLAY_MESSAGE, VanillaGuiLayers.TITLE);

    public CinematicClient(IEventBus modBus, ModContainer container) {
        CinematicPipelines.initialize();
        ClientBridge.useStation = Scene::begin;
        ClientBridge.vanillaUse = ClientOnly::onVanillaUse;
        Settings.SPEC.load();
        FirstPersonBodyCompat.register();
        // the settings screen: Cloth Config's when it is installed, the built-in one otherwise
        container.registerExtensionPoint(IConfigScreenFactory.class, (IConfigScreenFactory) (mod, parent) ->
                Platform.isModLoaded("cloth_config") ? ClothScreen.create(parent) : new SettingsScreen(parent));

        modBus.addListener((RegisterKeyMappingsEvent event) -> {
            event.register(CampfireHint.SIT);
            event.register(FreeLook.LOOK);
        });
        modBus.addListener(StationModels::wrapAll);
        modBus.addListener(CinematicPipelines::registerAll);
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> {
            event.registerBlockEntityRenderer(BlockEntityTypes.BREWING_STAND, BrewingBottles::new);
            event.registerBlockEntityRenderer(BlockEntityTypes.CHISELED_BOOKSHELF, ShelfLayoutBooks::new);
        });
        modBus.addListener((RegisterPictureInPictureRenderersEvent event) -> event.register(LoomFlags.State.class, LoomFlags.Renderer::new));
        // World geometry joins the vanilla feature queue; overlay extraction follows the ordinary HUD layers.
        modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(Cinematic.id("overlay"), (g, ticks) -> Overlay.render(g)));
        modBus.addListener(CinematicClient::registerPayloads);

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((RenderGuiLayerEvent.Pre event) -> {
            if (Scene.active() && HIDDEN_IN_SCENE.contains(event.getName())) event.setCanceled(true);
        });
        bus.addListener((ScreenEvent.Init.Post event) -> ServerListHint.onScreenInit(Minecraft.getInstance(), event.getScreen()));
        bus.addListener((ClientTickEvent.Post event) -> {
            Minecraft mc = Minecraft.getInstance();
            Scene.tick();
            ClientPayloads.faceStations(mc.level);
            CampfireHint.tick();
            if (mc.level != null && mc.level.getGameTime() % 40 == 0) ClientFurnaces.prune(mc.level);
            Showcase.tick();
            ClientOnlySmoke.tick();
        });
        bus.addListener((ChunkEvent.Unload event) -> {
            if (!event.getLevel().isClientSide()) return;
            var chunk = event.getChunk().getPos();
            ClientTables.unloadChunk(chunk);
            ClientFurnaces.unloadChunk(chunk);
            ClientBrewing.unloadChunk(chunk);
            ClientEnchants.unloadChunk(chunk);
            ClientMenus.unloadChunk(chunk);
            ShelfBooks.unloadChunk(chunk);
            ShelfLayoutBooks.unloadChunk(chunk);
        });
        bus.addListener((EntityLeaveLevelEvent event) -> {
            if (event.getLevel().isClientSide() && event.getEntity() instanceof Player entity) {
                ClientPayloads.AT_STATION.remove(entity.getId());
                ClientMenus.forgetEntity(entity.getId());
                ShelfBooks.forgetEntity(entity.getId());
            }
        });
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> clear());
        bus.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> ClientOnly.onLogin());
    }

    private static void registerPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(RecipeSyncPayload.TYPE, (p, c) -> ClientRecipes.accept(p));
        event.register(ProtocolPayload.TYPE, (p, c) -> {});
        event.register(RecipeChoicePayload.TYPE, (p, c) -> ClientPayloads.recipeChoices(p));
        event.register(BrewingContentsPayload.TYPE, (p, c) -> ClientBrewing.apply(p));
        event.register(TableContentsPayload.TYPE, (p, c) -> ClientPayloads.tableContents(p));
        event.register(FurnaceContentsPayload.TYPE, (p, c) -> ClientPayloads.furnaceContents(p));
        event.register(FurnaceStatePayload.TYPE, (p, c) -> ClientPayloads.furnaceState(p));
        event.register(CampfireProgressPayload.TYPE, (p, c) -> ClientPayloads.campfireProgress(p));
        event.register(EnchantDisplayPayload.TYPE, (p, c) -> ClientPayloads.enchantDisplay(p));
        event.register(EnchantFxPayload.TYPE, (p, c) -> ClientPayloads.enchantFx(p));
        event.register(MenuDisplayPayload.TYPE, (p, c) -> ClientPayloads.menuDisplay(p));
        event.register(MenuFxPayload.TYPE, (p, c) -> ClientPayloads.menuFx(p));
        event.register(SessionClosePayload.TYPE, (p, c) -> ClientPayloads.close(p));
        event.register(AtStationPayload.TYPE, (p, c) -> ClientPayloads.atStation(p));
    }

    public static boolean screenOpening(Screen next) {
        if (!Scene.active() && ClientOnly.onScreenOpening(next)) return true;
        if (!Scene.active() || next instanceof SceneScreen) return false;
        var player = Minecraft.getInstance().player;
        if (next instanceof MenuAccess<?> window && Scene.station.kind().usesMenu() && player != null && window.getMenu() == player.containerMenu) return true;
        if (next instanceof InventoryScreen) return true;
        if (Scene.phase == Scene.Phase.ACTIVE && next != null && !next.isPauseScreen()) Scene.drop();
        return false;
    }

    public static void clear() {
        Scene.drop();
        ClientOnly.clear();
        ClientRecipes.clear();
        ClientTables.clear();
        ClientFurnaces.clear();
        ClientBrewing.clear();
        ClientEnchants.clear();
        ClientMenus.clear();
        ShelfBooks.clear();
        ShelfLayoutBooks.clear();
        Fx.clearSparks();
        ClientPayloads.clear();
    }
}
