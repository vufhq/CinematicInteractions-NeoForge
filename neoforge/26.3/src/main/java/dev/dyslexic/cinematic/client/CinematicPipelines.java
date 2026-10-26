package dev.dyslexic.cinematic.client;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import dev.dyslexic.cinematic.Cinematic;
import net.minecraft.client.renderer.RenderPipelines;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

/** Register both passes, then explicitly assign their shader programs through Iris's optional public API. */
public final class CinematicPipelines {
    private CinematicPipelines() {}

    private static final java.util.List<RenderPipeline> STATIC = new java.util.ArrayList<>();

    private static RenderPipeline register(RenderPipeline pipeline) {
        STATIC.add(pipeline);
        return pipeline;
    }

    public static final RenderPipeline HIGHLIGHT = register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Cinematic.id("pipeline/highlight"))
                    .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 1, 10)).build());
    private static final java.util.Map<RenderPipeline, RenderPipeline> FADING = new java.util.IdentityHashMap<>();

    /** Preserve the original item shader, including tint, foil and custom material definitions. */
    static RenderPipeline fading(RenderPipeline original) {
        return FADING.computeIfAbsent(original, source -> {
            var values = new java.util.HashMap<>(source.getShaderDefines().values());
            values.put("ALPHA_CUTOUT", "0.001");
            var defines = new net.minecraft.client.renderer.ShaderDefines(values, source.getShaderDefines().flags());
            var snippet = new RenderPipeline.Snippet(source.getShaders(), java.util.Optional.of(defines),
                    java.util.Optional.of(source.getBindGroupLayouts()),
                    source.getColorTargetStates().toArray(ColorTargetState[]::new), source.getColorTargetStates().size(),
                    java.util.Optional.ofNullable(source.getDepthStencilState()), java.util.Optional.of(source.getPolygonMode()),
                    java.util.Optional.of(false), source.getVertexFormatBindings().toArray(com.mojang.renderpearl.api.vertex.VertexFormat[]::new),
                    java.util.Optional.of(source.getPrimitiveTopology()), source.pushConstantSize());
            var depth = source.getDepthStencilState();
            var builder = RenderPipeline.builder(snippet).withLocation(Cinematic.id("pipeline/fading_item_" + FADING.size()))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT));
            if (depth != null) builder.withDepthStencilState(new DepthStencilState(depth.depthTest(), false, depth.depthBiasScaleFactor(), depth.depthBiasConstant()));
            // built after pipeline registration closes; the device compiles it on first use
            RenderPipeline result = builder.build();
            if (dev.dyslexic.cinematic.Platform.isModLoaded("iris")) IrisCompatibility.copyBinding(source, result);
            return result;
        });
    }
    /** The eyes pipeline as it blended before 1.21.2: added light (see {@link Fx#glow}). */
    public static final RenderPipeline GLOW = register(
            RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
                    .withLocation(Cinematic.id("pipeline/glow"))
                    .withVertexShader("core/entity").withFragmentShader("core/entity")
                    .withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY").withShaderDefine("NO_CARDINAL_LIGHTING")
                    .withColorTargetState(new ColorTargetState(BlendFunction.ADDITIVE))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .build());

    /** The circle is emissive, but its translucent pixels must retain depth testing. */
    public static final RenderPipeline ENCHANT_CIRCLE = register(
            RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET, RenderPipelines.EYES_SNIPPET)
                    .withLocation(Cinematic.id("pipeline/enchant_circle"))
                    .withVertexShader("core/entity").withFragmentShader("core/entity")
                    .withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY").withShaderDefine("NO_CARDINAL_LIGHTING")
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
                    .build());

    /** RegisterRenderPipelinesEvent: the fixed passes are precompiled with vanilla's. */
    static void registerAll(RegisterRenderPipelinesEvent event) {
        STATIC.forEach(event::registerPipeline);
    }

    public static void initialize() {
        if (dev.dyslexic.cinematic.Platform.isModLoaded("iris")) IrisCompatibility.initialize();
    }
}
