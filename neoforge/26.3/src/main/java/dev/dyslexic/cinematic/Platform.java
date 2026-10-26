package dev.dyslexic.cinematic;

import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/** The few loader queries the shared sources make (FabricLoader's in the Fabric port). */
public final class Platform {
    private Platform() {}

    public static boolean isModLoaded(String id) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(id);
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** True when the mod is installed and its version starts with the given prefix. */
    public static boolean versionStartsWith(String id, String prefix) {
        ModList mods = ModList.get();
        return mods != null && mods.getModContainerById(id)
                .map(mod -> mod.getModInfo().getVersion().toString().startsWith(prefix)).orElse(false);
    }
}
