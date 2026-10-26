package dev.dyslexic.cinematic.client;

import dev.dyslexic.cinematic.station.StationKind;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import dev.dyslexic.cinematic.client.ClientConfig;

/**
 * The player's own settings (config/cinematic-client.json). Mod Menu opens {@link SettingsScreen}, or
 * {@link ClothScreen} when Cloth Config is present. The client-only choice is captured on connection; other values
 * are read where used.
 */
public final class Settings {
    private Settings() {}

    public static final ClientConfig SPEC;

    static final ClientConfig.BooleanValue HEIGHT_LOGIC;
    static final Map<StationKind, ClientConfig.BooleanValue> SCENES = new EnumMap<>(StationKind.class);
    static final ClientConfig.BooleanValue CONTROL_HINTS;
    static final ClientConfig.BooleanValue CAMPFIRE_INVITE;
    static final ClientConfig.IntValue APPROACH_SPEED;
    static final ClientConfig.IntValue LEAVE_SPEED;
    static final ClientConfig.IntValue LEAN_STRENGTH;
    static final ClientConfig.IntValue SCENE_ZOOM;
    static final ClientConfig.IntValue QUICK_MODE;
    static final ClientConfig.IntValue QUICK_WINDOW;
    static final ClientConfig.IntValue ARM_STYLE;
    static final ClientConfig.BooleanValue LOOK_TOGGLES;
    static final ClientConfig.BooleanValue LETTERBOX;
    static final ClientConfig.BooleanValue LIVING_CAMERA;
    static final ClientConfig.BooleanValue CAMERA_SHAKE;
    static final ClientConfig.BooleanValue SCREEN_FLASH;
    static final ClientConfig.BooleanValue CLIENT_ONLY_MODE;
    static final ClientConfig.BooleanValue SERVER_HINT_SEEN;
    static final ClientConfig.BooleanValue DEVELOPER_MODE;

    static {
        ClientConfig.Builder b = new ClientConfig.Builder();
        b.translation("cinematic.configuration.scenes").push("scenes");
        HEIGHT_LOGIC = b.comment("When a block's work cannot be reached from where the player stands (too high, too far, covered),",
                        "true opens the block's own window instead of the scene; false only says why, over the crosshair.")
                .translation("cinematic.configuration.heightLogic").define("heightLogic", true);
        b.translation("cinematic.configuration.blocks").push("blocks");
        for (StationKind kind : StationKind.values()) {
            if (kind == StationKind.ADDON) continue;
            String key = key(kind);
            SCENES.put(kind, b.comment("Whether " + key + " plays its scene (false: the block keeps its usual window).")
                    .translation("cinematic.configuration." + key).define(key, true));
        }
        b.pop();
        b.pop();
        b.translation("cinematic.configuration.interface").push("interface");
        CONTROL_HINTS = b.comment("The line of controls at the bottom of the screen during a scene.")
                .translation("cinematic.configuration.controlHints").define("controlHints", true);
        CAMPFIRE_INVITE = b.comment("The \"[H] Sit by the fire\" invitation under the crosshair when looking at a campfire (the key works either way).")
                .translation("cinematic.configuration.campfireInvite").define("campfireInvite", true);
        b.pop();
        b.translation("cinematic.configuration.camera").push("camera");
        APPROACH_SPEED = b.comment("How fast the camera leans in to the block once the player has stepped up to it, in percent (100: 1.2 s).")
                .translation("cinematic.configuration.approachSpeed").defineInRange("approachSpeed", 100, 50, 200);
        LEAVE_SPEED = b.comment("How fast the player steps away and the camera comes back to the eyes, in percent (100: about a second).")
                .translation("cinematic.configuration.leaveSpeed").defineInRange("leaveSpeed", 100, 50, 200);
        LEAN_STRENGTH = b.comment("How far the camera leans in from the eyes towards the block, in percent (0: it stays at the eyes, looking at the work).")
                .translation("cinematic.configuration.leanStrength").defineInRange("leanStrength", 100, 0, 100);
        SCENE_ZOOM = b.comment("How much the view narrows on the work in a scene, in percent (0: the field of view stays your own).")
                .translation("cinematic.configuration.sceneZoom").defineInRange("sceneZoom", 100, 0, 100);
        QUICK_MODE = b.comment("0: cinematic entry; 1: quick on a recent return; 2: quick on every entry.")
                .translation("cinematic.configuration.quickMode").defineInRange("quickMode", 1, 0, 2);
        QUICK_WINDOW = b.comment("For how many seconds after stepping away coming back to the same station is quick.")
                .translation("cinematic.configuration.quickWindow").defineInRange("quickWindow", 12, 5, 30);
        ARM_STYLE = b.comment("0: original stretching arms; 1: short natural reach; 2: floating forearms.")
                .translation("cinematic.configuration.armStyle").defineInRange("armStyle", 0, 0, 2);
        LOOK_TOGGLES = b.comment("Looking round from behind while at work (Left Alt, rebindable): false while the key is held, true pressed on and off.")
                .translation("cinematic.configuration.lookToggles").define("lookToggles", false);
        LETTERBOX = b.comment("Black bars at the top and bottom while the camera moves in and out.")
                .translation("cinematic.configuration.letterbox").define("letterbox", true);
        LIVING_CAMERA = b.comment("The camera breathes slightly and drifts a little towards the cursor while working.")
                .translation("cinematic.configuration.livingCamera").define("livingCamera", true);
        CAMERA_SHAKE = b.comment("The camera trembles at big moments (a hammer blow, a spell, the beacon lighting up).")
                .translation("cinematic.configuration.cameraShake").define("cameraShake", true);
        SCREEN_FLASH = b.comment("The screen flashes at big moments (the light of a spell, white-hot metal).")
                .translation("cinematic.configuration.screenFlash").define("screenFlash", true);
        b.pop();
        b.translation("cinematic.configuration.multiplayer").push("multiplayer");
        CLIENT_ONLY_MODE = b.comment("Show local scenes over supported vanilla menus on a server without Cinematic Interactions. "
                        + "Change from the main menu; the choice takes effect on the next connection.")
                .translation("cinematic.configuration.clientOnlyMode").define("clientOnlyMode", false);
        SERVER_HINT_SEEN = b.comment("Whether the multiplayer list has already shown its client-only mode reminder.")
                .define("serverHintSeen", false);
        b.pop();
        b.translation("cinematic.configuration.developer").push("developer");
        DEVELOPER_MODE = b.comment("Show the local station layout editors and enable their G shortcut. Saved layouts apply even when this is false.")
                .translation("cinematic.configuration.developerMode").define("developerMode", false);
        b.pop();
        SPEC = b.build();
    }

    /** The key of a station in the settings file and the translations: "table", "blast_furnace", ... */
    static String key(StationKind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static boolean get(ClientConfig.BooleanValue value) {
        // before the file is read (very early in loading) the defaults apply
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    private static double percent(ClientConfig.IntValue value) {
        return (SPEC.isLoaded() ? value.get() : value.getDefault()) / 100.0;
    }

    /** How fast the camera leans in once the player has arrived: 1 as made, 2 twice as fast. */
    public static double approachSpeed() {
        return quickMode() == 2 ? 1.0 : percent(APPROACH_SPEED);
    }

    /** How fast the player steps away: 1 as made, 2 twice as fast. */
    public static double leaveSpeed() {
        return percent(LEAVE_SPEED);
    }

    /** How far the camera goes from the eyes towards the station's pose: 1 all the way, 0 not at all. */
    public static double leanStrength() {
        return percent(LEAN_STRENGTH);
    }

    /** How far the field of view goes to the station's: 1 all the way, 0 not at all. */
    public static double sceneZoom() {
        return percent(SCENE_ZOOM);
    }

    /** Out of reach: the block's own window (true) or just a notice saying why (false). */
    public static boolean heightLogic() {
        return get(HEIGHT_LOGIC);
    }

    /** Whether this block plays its scene; otherwise it keeps its usual window. */
    public static boolean scene(StationKind kind) {
        if (kind == StationKind.ADDON) return true;
        return get(SCENES.get(kind));
    }

    public static boolean controlHints() {
        return get(CONTROL_HINTS);
    }

    public static boolean clientOnlyMode() { return get(CLIENT_ONLY_MODE); }

    public static boolean developerMode() { return get(DEVELOPER_MODE); }

    public static boolean campfireInvite() {
        return get(CAMPFIRE_INVITE);
    }

    public static int quickMode() {
        return SPEC.isLoaded() ? QUICK_MODE.get() : QUICK_MODE.getDefault();
    }

    public static int armStyle() {
        return SPEC.isLoaded() ? ARM_STYLE.get() : ARM_STYLE.getDefault();
    }

    /** Seconds after stepping away that coming back is quick. */
    public static int quickWindow() {
        return SPEC.isLoaded() ? QUICK_WINDOW.get() : QUICK_WINDOW.getDefault();
    }

    /** Looking round from behind: pressed on and off (true) or while the key is held (false). */
    public static boolean lookToggles() {
        return get(LOOK_TOGGLES);
    }

    public static boolean letterbox() {
        return get(LETTERBOX);
    }

    public static boolean livingCamera() {
        return get(LIVING_CAMERA);
    }

    public static boolean cameraShake() {
        return get(CAMERA_SHAKE);
    }

    public static boolean screenFlash() {
        return get(SCREEN_FLASH);
    }
}
