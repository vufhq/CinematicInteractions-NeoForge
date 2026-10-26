# Cinematic Interactions — NeoForge 26.3

NeoForge port of Cinematic Interactions **0.18.28** for Minecraft **26.3** (NeoForge **26.3.0.99**).

The game code is the Fabric 26.3 port (`fabric/26.3`), with the Fabric API calls replaced by NeoForge equivalents:

| Fabric 26.3 | NeoForge 26.3 |
|---|---|
| `ModInitializer` / `ClientModInitializer` | `@Mod` / `@Mod(dist = Dist.CLIENT)` |
| `PayloadTypeRegistry`, `ServerPlayNetworking`, `ClientPlayNetworking` | `RegisterPayloadHandlersEvent` (optional channel), `RegisterClientPayloadHandlersEvent`, `NetworkRegistry.hasChannel` |
| `UseBlockCallback` | `PlayerInteractEvent.RightClickBlock` |
| `ServerTickEvents`, lifecycle, death, dimension change, tracking | `ServerTickEvent`, `LevelTickEvent`, `ServerStoppedEvent`, `LivingDeathEvent`, `PlayerEvent.*` |
| join / `END_DATA_PACK_RELOAD` recipe sync | `OnDatapackSyncEvent` |
| `ModelLoadingPlugin` + `WrapperBlockStateModel` | `ModelEvent.ModifyBakingResult` + `DelegateBlockStateModel` |
| `HudElementRegistry`, Hud mixin | `RegisterGuiLayersEvent`, `RenderGuiLayerEvent.Pre` |
| `BlockEntityRendererRegistry`, `PictureInPictureRendererRegistry`, `KeyMappingHelper` | `EntityRenderersEvent.RegisterRenderers`, `RegisterPictureInPictureRenderersEvent`, `RegisterKeyMappingsEvent` |
| Mod Menu entry point | `IConfigScreenFactory` |
| `cinematic.accesswidener` | `META-INF/accesstransformer.cfg` |

Resources come from `src/main/resources` (root), `fabric/src/1.21.5/resources` and `fabric/26.3`, merged into `src/main/resources` here.
Settings and station profiles are stored as in the Fabric port (`config/cinematic-*.json`).

## Build

Needs **JDK 25**.

```powershell
$env:JAVA_HOME = 'path to JDK 25'
.\gradlew.bat build
```

Game JAR: `build/libs/Cinematic Interaction-NeoForge-26.3-0.18.28.jar`. Install the same JAR on the client and the server.
Cloth Config, JEI, Polymorph+, Inventory Particles and Inventory Interactions are optional.
