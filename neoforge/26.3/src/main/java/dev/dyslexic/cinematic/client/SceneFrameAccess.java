package dev.dyslexic.cinematic.client;

import net.minecraft.client.renderer.SubmitNodeStorage;

/** A captured world frame owns its scene submissions, just like its entities and block entities. */
public interface SceneFrameAccess {
    SubmitNodeStorage cinematic$getScene();
    void cinematic$setScene(SubmitNodeStorage scene);
}
