package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Original model anchors for the remaining stations. Renderers and actions share these transforms. */
final class RemainingLayouts {
    private RemainingLayouts() {}
    static boolean supports(StationKind kind) {
        return switch (kind) {
            case ANVIL, GRINDSTONE, STONECUTTER, CAMPFIRE, LECTERN, BEACON, JUKEBOX -> true;
            default -> false;
        };
    }
    static boolean billboard(StationKind kind, int slot) {
        return kind == StationKind.BEACON && slot > 0 || kind == StationKind.ANVIL && slot == 2 || kind == StationKind.STONECUTTER && slot == 2;
    }
    static SpatialAppearance.Frame frame(StationKind kind, BlockPos pos, Direction side) {
        var level = Minecraft.getInstance().level;
        if (kind == StationKind.CAMPFIRE) {
            Direction facing = level != null && level.getBlockState(pos).hasProperty(CampfireBlock.FACING)
                    ? level.getBlockState(pos).getValue(CampfireBlock.FACING) : Direction.NORTH;
            return new SpatialAppearance.Frame(kind, pos, facing, Direction.UP, Vec3.atBottomCenterOf(pos).add(0, 0.44921875, 0),
                    new Vec3(1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 1, 0));
        }
        if (kind == StationKind.LECTERN) {
            Vec3 out = Vec3.atLowerCornerOf(side.getUnitVec3i()), across = ShelfBooks.right(side);
            double angle = Math.toRadians(22.5);
            Vec3 slope = new Vec3(0, Math.sin(angle), 0).subtract(out.scale(Math.cos(angle)));
            Vec3 normal = new Vec3(0, Math.cos(angle), 0).add(out.scale(Math.sin(angle)));
            Vec3 origin = Vec3.atBottomCenterOf(pos).add(0, 15.96 / 16, 0).add(out.scale(1.67 / 16)).add(normal.scale(OpenBook.COVER));
            return new SpatialAppearance.Frame(kind, pos, side, Direction.UP, origin, across, slope, normal);
        }
        Vec3 top = Vec3.atBottomCenterOf(pos).add(0, kind == StationKind.STONECUTTER ? StonecutterStation.TABLE : 1, 0);
        if (kind == StationKind.GRINDSTONE) top = GrindstoneStation.wheelCenter(pos,
                level == null ? Blocks.GRINDSTONE.defaultBlockState() : level.getBlockState(pos)).add(0, GrindstoneStation.HALF, 0);
        return frameAt(kind, top, side);
    }
    static SpatialAppearance.Frame frameAt(StationKind kind, Vec3 top, Direction side) {
        Vec3 out = Vec3.atLowerCornerOf(side.getUnitVec3i());
        return new SpatialAppearance.Frame(kind, BlockPos.containing(top.add(0, -0.01, 0)), side, Direction.UP, top,
                ShelfBooks.right(side), kind == StationKind.BEACON ? new Vec3(0, 1, 0) : out,
                kind == StationKind.BEACON ? out : new Vec3(0, 1, 0));
    }
    static Vec3 base(SpatialAppearance.Frame f, int slot) {
        Vec3 at = f.origin(), right = f.across(), out = Vec3.atLowerCornerOf(f.side().getUnitVec3i());
        return switch (f.kind()) {
            case ANVIL -> slot == 0 ? at.add(right.scale(-0.02)) : slot == 1
                    ? at.add(right.scale(0.07)).add(out.scale(-0.03)).add(0, 0.012, 0)
                    : at.add(out.scale(0.02)).add(0, 0.2, 0);
            case GRINDSTONE -> slot < 2 ? at.add(right.scale(slot == 0 ? GrindstoneStation.SLOT_SIDE : -GrindstoneStation.SLOT_SIDE))
                    : at.add(out.scale(0.04)).add(0, 0.03, 0);
            case STONECUTTER -> slot == 0 ? at.add(right.scale(-0.25)).add(out.scale(0.24))
                    : slot == 1 ? at : at.add(out.scale(-0.06)).add(0, 0.29, 0);
            case CAMPFIRE -> CampfireStation.vanillaSpot(f.pos(), f.side(), slot);
            case LECTERN, JUKEBOX -> at;
            case BEACON -> {
                if (slot == 0) yield at.add(out.scale(0.27)).add(0, 0.14, 0);
                int i = slot - 1;
                double x = i < 4 ? (i % 2 == 0 ? -0.8 : -0.45) : i == 4 ? -0.62 : 0.6;
                double y = i < 5 ? 0.2 + 0.31 * (i / 2) : 0.36 + 0.31 * (i - 5);
                yield at.add(out.scale(0.14 - 0.08 * Math.abs(x))).add(right.scale(x)).add(0, y, 0);
            }
            default -> throw new IllegalArgumentException("No station anchors: " + f.kind());
        };
    }
    static double[] half(StationKind kind, int slot) {
        return switch (kind) {
            case LECTERN -> new double[]{OpenBook.GUTTER + OpenBook.PAGE_W + 0.04, OpenBook.PAGE_H / 2 + 0.03};
            case JUKEBOX -> new double[]{0.25, 0.25};
            case ANVIL -> slot == 2 ? new double[]{0.3, 0.08} : new double[]{0.2, 0.14};
            case STONECUTTER -> slot == 2 ? new double[]{0.72, 0.28} : new double[]{0.15, 0.15};
            default -> new double[]{0.13, 0.13};
        };
    }
    static Vec3 point(StationKind kind, Vec3 top, Direction side, int slot) {
        return SpatialLayouts.current(kind).point(frameAt(kind, top, side), slot);
    }
    static Vec3 transform(StationKind kind, Vec3 top, Direction side, int slot, Vec3 point) {
        var frame = frameAt(kind, top, side); var layout = SpatialLayouts.current(kind);
        Vec3 delta = point.subtract(frame.base(slot));
        return layout.point(frame, slot).add(layout.axis(frame, slot, true).scale(delta.dot(frame.across()) * layout.group().width() * layout.region(slot).width()))
                .add(layout.axis(frame, slot, false).scale(delta.dot(frame.up()) * layout.group().depth() * layout.region(slot).depth()))
                .add(frame.normal().scale(delta.dot(frame.normal()) * layout.scale(slot)));
    }
    static float scale(StationKind kind, int slot) { return SpatialLayouts.current(kind).scale(slot); }
    static float rotation(StationKind kind, int slot) { return (float) SpatialLayouts.current(kind).rotation(slot) * (kind == StationKind.LECTERN || kind == StationKind.BEACON ? 1 : -1); }
    static List<Vec3> samples(SpatialTarget target, List<Vec3> old) {
        if (target.geometry().equals(SpatialAppearance.VANILLA)) return old;
        List<Vec3> samples = new ArrayList<>(old);
        int count = switch (target.layoutStation().kind()) { case ANVIL, GRINDSTONE -> 2; case BEACON, STONECUTTER -> 1; default -> SpatialAppearance.count(target.layoutStation().kind()); };
        for (int i = 0; i < count; i++) samples.add(target.layoutPoint(i).add(target.layoutFrame().normal().scale(0.04)));
        return samples;
    }
    static ItemStack item(ClientStation station, int slot) {
        if (station instanceof CampfireStation && Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getBlockEntity(station.pos) instanceof CampfireBlockEntity fire) return fire.getItems().get(slot);
        if (station instanceof LecternStation) return new ItemStack(Items.WRITTEN_BOOK);
        if (station instanceof MenuStation<?> menu) {
            int inputs = station.kind() == StationKind.ANVIL || station.kind() == StationKind.GRINDSTONE ? 2 : 1;
            if (slot < inputs) return menu.slot(slot);
            // Powers are already displayed by the real beacon renderer, including their GUI icons.
            if (station.kind() == StationKind.BEACON && slot < 7) return new ItemStack(Items.NETHER_STAR);
        }
        return ItemStack.EMPTY;
    }
    static void ghost(SpatialTarget target, PoseStack pose, SceneBuffers buffers, Vec3 camera, int slot, int light) {
        ClientStation station = target.layoutStation(); var kind = station.kind();
        ItemStack example = new ItemStack(switch (kind) {
            case ANVIL -> slot == 0 ? Items.IRON_PICKAXE : slot == 1 ? Items.ENCHANTED_BOOK : Items.NAME_TAG;
            case GRINDSTONE -> slot < 2 ? Items.IRON_SWORD : Items.FLINT;
            case STONECUTTER -> slot == 0 ? Items.STONE : slot == 1 ? Items.STONE_SLAB : Items.STONE_STAIRS;
            case CAMPFIRE -> Items.BEEF;
            case LECTERN -> Items.WRITTEN_BOOK;
            case BEACON -> slot == 0 ? Items.GOLD_INGOT : Items.NETHER_STAR;
            case JUKEBOX -> Items.MUSIC_DISC_13;
            default -> Items.PAPER;
        });
        float scale = target.geometry().scale(slot), rotation = (float) target.geometry().rotation(slot);
        if (kind == StationKind.ANVIL && slot < 2) {
            ItemPlacer.drawOriented(pose, buffers, camera, example, target.layoutPoint(slot).add(0, 0.004, 0),
                    ItemPlacer.lying(ItemPlacer.awayYaw(station.sideDirection()) + AnvilStation.MAIN_TURN + (slot == 1 ? 70 : 0) + rotation(kind, slot), 0),
                    (slot == 0 ? AnvilStation.MAIN_SIZE : AnvilStation.ADDITION_SIZE) * scale, light);
        } else if (kind == StationKind.GRINDSTONE && slot < 2) {
            GrindstoneStation.drawLying(pose, buffers, camera, example, target.layoutPoint(slot), station.sideDirection(), slot, 1, light);
        } else if (kind == StationKind.STONECUTTER && slot == 0) {
            StonecutterStation.drawStack(pose, buffers, camera, example, target.layoutPoint(slot), station.sideDirection(), light);
        } else if (kind == StationKind.STONECUTTER) {
            float size = (slot == 1 ? StonecutterStation.PIECE_SIZE : StonecutterStation.SHAPE_SIZE * 0.85f) * scale;
            ItemPlacer.drawOriented(pose, buffers, camera, example, target.layoutPoint(slot).add(0, slot == 1 ? size / 2 : 0, 0),
                    Axis.YP.rotationDegrees(ItemPlacer.awayYaw(station.sideDirection()) + rotation(kind, slot)), size, light);
        } else if (kind == StationKind.BEACON && slot == 0) {
            EnchantStation.drawFloating(pose, buffers, camera, example, target.layoutPoint(slot), (float) (Scene.clock() * 50), 0.26f * scale);
        } else if (kind == StationKind.BEACON || kind == StationKind.ANVIL && slot == 2) {
            var cameraRotation = new org.joml.Quaternionf(Minecraft.getInstance().gameRenderer.mainCamera().rotation());
            cameraRotation.mul(Axis.ZP.rotationDegrees(rotation));
            ItemPlacer.drawOriented(pose, buffers, camera, example, target.layoutPoint(slot), cameraRotation, 0.16f * scale, light);
        } else if (kind == StationKind.CAMPFIRE) {
            Direction direction = Direction.from2DDataValue((slot + target.layoutFrame().side().get2DDataValue()) % 4);
            ItemPlacer.drawOriented(pose, buffers, camera, example, target.layoutPoint(slot),
                    Axis.YP.rotationDegrees(-direction.toYRot() - rotation).mul(Axis.XP.rotationDegrees(90)), 0.375f * scale, light);
        } else {
            ItemPlacer.drawOriented(pose, buffers, camera, example, target.layoutPoint(slot).add(0, kind == StationKind.JUKEBOX ? 0.2 * scale : 0.015, 0),
                    kind == StationKind.JUKEBOX ? Axis.YP.rotationDegrees(-station.sideDirection().toYRot() + rotation(kind, slot))
                            : ItemPlacer.lying(ItemPlacer.awayYaw(station.sideDirection()) + rotation, 0),
                    (kind == StationKind.JUKEBOX ? 0.6f : 0.25f) * scale, light);
        }
    }
}
