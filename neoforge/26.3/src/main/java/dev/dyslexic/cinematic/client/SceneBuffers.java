package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Captures scene geometry during extraction; the game's feature renderer prepares and draws it in its normal passes. */
public interface SceneBuffers {
    VertexConsumer getBuffer(RenderType type);

    final class BufferSource implements SceneBuffers {
        private final SubmitNodeCollector collector;
        private final Map<RenderType, Recording> pending = new LinkedHashMap<>();

        public BufferSource(SubmitNodeCollector collector) { this.collector = collector; }
        @Override public VertexConsumer getBuffer(RenderType type) { return pending.computeIfAbsent(type, ignored -> new Recording()); }
        public void endBatch() {
            pending.forEach((type, recording) -> {
                List<Vertex> vertices = List.copyOf(recording.vertices);
                collector.submitCustomGeometry(new PoseStack(), type, (pose, target) -> vertices.forEach(vertex -> vertex.write(target)));
            });
            pending.clear();
        }
        public void endBatch(RenderType type) {
            Recording recording = pending.remove(type);
            if (recording == null) return;
            List<Vertex> vertices = List.copyOf(recording.vertices);
            collector.submitCustomGeometry(new PoseStack(), type, (pose, target) -> vertices.forEach(vertex -> vertex.write(target)));
        }
    }

    final class Vertex {
        float x, y, z, u, v, uv3x, uv3y, nx, ny, nz, line = 1;
        int color = -1, overlay, light;
        int attributes;
        void write(VertexConsumer target) {
            target.addVertex(x, y, z);
            if ((attributes & 1) != 0) target.setColor(color);
            if ((attributes & 2) != 0) target.setUv(u, v);
            if ((attributes & 4) != 0) target.setOverlay(overlay);
            if ((attributes & 8) != 0) target.setLight(light);
            if ((attributes & 16) != 0) target.setNormal(nx, ny, nz);
            if ((attributes & 32) != 0) target.setLineWidth(line);
            if ((attributes & 64) != 0) target.setUv3(uv3x, uv3y);
        }
    }

    final class Recording implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private Vertex current;
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = new Vertex(); current.x=x; current.y=y; current.z=z; vertices.add(current); return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return setColor(a << 24 | r << 16 | g << 8 | b); }
        @Override public VertexConsumer setColor(int color) { current.color=color; current.attributes |= 1; return this; }
        @Override public VertexConsumer setUv(float u, float v) { current.u=u; current.v=v; current.attributes |= 2; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { current.overlay=u & 65535 | v << 16; current.attributes |= 4; return this; }
        @Override public VertexConsumer setUv2(int u, int v) { current.light=u & 65535 | v << 16; current.attributes |= 8; return this; }
        @Override public VertexConsumer setUv3(float u, float v) { current.uv3x=u; current.uv3y=v; current.attributes |= 64; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { current.nx=x; current.ny=y; current.nz=z; current.attributes |= 16; return this; }
        @Override public VertexConsumer setLineWidth(float width) { current.line=width; current.attributes |= 32; return this; }
    }
}
