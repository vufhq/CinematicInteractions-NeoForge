package dev.dyslexic.cinematic.client.compat.jei;

import dev.dyslexic.cinematic.Cinematic;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.Identifier;

/** Found by JEI only when JEI is installed (on Fabric through the "jei_mod_plugin" entrypoint); it hands the runtime to the field guide and registers nothing of its own. */
@JeiPlugin
public final class CinematicJeiPlugin implements IModPlugin {
    @Override
    public Identifier getPluginUid() {
        return Cinematic.id("field_guide");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiRecipeViewer.available(runtime);
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiRecipeViewer.available(null);
    }
}
