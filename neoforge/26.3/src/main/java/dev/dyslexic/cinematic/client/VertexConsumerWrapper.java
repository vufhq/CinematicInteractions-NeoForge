package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Delegates the complete vertex format, allowing a single draw to change alpha without changing the model. */
public class VertexConsumerWrapper implements VertexConsumer {
    protected final VertexConsumer parent;
    public VertexConsumerWrapper(VertexConsumer parent) { this.parent = parent; }
    @Override public VertexConsumer addVertex(float x, float y, float z) { parent.addVertex(x, y, z); return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { parent.setColor(r, g, b, a); return this; }
    // Packed colors must pass through setColor(r,g,b,a), so a fading draw's alpha override is still applied.
    @Override public VertexConsumer setColor(int color) {
        return setColor(net.minecraft.util.ARGB.red(color), net.minecraft.util.ARGB.green(color),
                net.minecraft.util.ARGB.blue(color), net.minecraft.util.ARGB.alpha(color));
    }
    @Override public VertexConsumer setUv(float u, float v) { parent.setUv(u, v); return this; }
    @Override public VertexConsumer setUv1(int u, int v) { parent.setUv1(u, v); return this; }
    @Override public VertexConsumer setUv2(int u, int v) { parent.setUv2(u, v); return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { parent.setNormal(x, y, z); return this; }
    @Override public VertexConsumer setUv3(float u, float v) { parent.setUv3(u, v); return this; }
    @Override public VertexConsumer setLineWidth(float width) { parent.setLineWidth(width); return this; }
}
