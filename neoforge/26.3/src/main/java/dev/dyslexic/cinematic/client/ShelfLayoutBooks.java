package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Custom resting books also remain in place after leaving the scene, without changing the real block state. */
final class ShelfLayoutBooks implements BlockEntityRenderer<ChiseledBookShelfBlockEntity, net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> {
    private static final OpenBook book = new OpenBook();
    private static final Set<BlockPos> VISIBLE_SHELVES = new HashSet<>();
    private static double restoringUntil = -1;
    static boolean drawsRestingBooks() { return ClientOnly.serverHasMod() && (SpatialLayouts.customShelf() || Scene.clock() < restoringUntil); }
    static void layoutChanged(boolean custom) {
        if (!custom) restoringUntil = Scene.clock() + 0.35;
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        // Refresh only sections with shelves, rather than rebuilding the whole world while a slider moves.
        Set<SectionPos> sections = new HashSet<>();
        for (BlockPos pos : VISIBLE_SHELVES) sections.add(SectionPos.of(pos));
        for (SectionPos section : sections) mc.levelExtractor.setSectionDirty(section.x(), section.y(), section.z());
    }
    static void unloadChunk(ChunkPos chunk) { VISIBLE_SHELVES.removeIf(pos -> pos.getX() >> 4 == chunk.x() && pos.getZ() >> 4 == chunk.z()); }
    static void clear() { VISIBLE_SHELVES.clear(); restoringUntil = -1; }
    ShelfLayoutBooks(BlockEntityRendererProvider.Context context) {}
    @Override public net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState createRenderState() {
        return new net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState();
    }
    @Override public void extractRenderState(ChiseledBookShelfBlockEntity shelf,
            net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state, float partial, Vec3 camera,
            net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay overlay) {
        BlockEntityRenderer.super.extractRenderState(shelf, state, partial, camera, overlay);
        VISIBLE_SHELVES.add(shelf.getBlockPos());
    }
    @Override public void submit(net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState state, PoseStack pose,
            net.minecraft.client.renderer.SubmitNodeCollector collector, net.minecraft.client.renderer.state.level.CameraRenderState camera) {}

    static void renderAll(PoseStack pose, SceneBuffers buffers, net.minecraft.client.Camera camera,
            net.minecraft.client.renderer.culling.Frustum frustum, net.minecraft.client.multiplayer.ClientLevel level) {
        if (!drawsRestingBooks()) return;
        for (BlockPos pos : VISIBLE_SHELVES) {
            if (!frustum.isVisible(new net.minecraft.world.phys.AABB(pos).inflate(1))) continue;
            if (!(level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity shelf)) continue;
            render(shelf, pose, buffers, camera.position(), net.minecraft.util.LightCoordsUtil.getLightCoords(level, pos));
        }
    }
    private static void render(ChiseledBookShelfBlockEntity shelf, PoseStack pose, SceneBuffers buffers, Vec3 origin, int light) {
        var pos = shelf.getBlockPos();
        VISIBLE_SHELVES.add(pos);
        if (!drawsRestingBooks()) return;
        ShelfStation mine = Scene.station(ShelfStation.class);
        if (mine != null && mine.pos.equals(pos)) return;
        var state = shelf.getBlockState();
        var facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        for (int i = 0; i < 6; i++) {
            if (!state.getValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i)) || ShelfBooks.flight(pos, i) != null) continue;
            // Vanilla syncs occupancy, but not book contents. The slot's spine is enough to draw its resting book.
            ShelfBooks.draw(pose, buffers, origin, i, new ItemStack(Items.BOOK), ShelfBooks.inSlot(pos, facing, i, 0), book, light);
        }
    }
}
