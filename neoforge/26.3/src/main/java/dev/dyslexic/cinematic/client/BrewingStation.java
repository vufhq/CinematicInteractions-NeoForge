package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.phys.Vec3;

/**
 * The brewing stand scene. The player kneels at the stand. The potions stand on its feet (drawn for everyone by
 * {@link BrewingBottles}, as the real potions). The ingredient sits in the bowl on top, blaze powder lies at the foot
 * of the rod. While it brews, the
 * ingredient melts away in the bowl and runs down to the bottles in glowing drops, the potions bubble, and when it
 * is done each bottle flares up in its new colour. Underneath is the vanilla brewing stand menu; the stand keeps
 * brewing after the player steps away.
 */
public final class BrewingStation extends MenuStation<BrewingStandMenu> implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    static final double HAND_Y = 0.055;
    static final double ITEM_REACH = 0.07;
    /** Vertical tolerance makes the bowl selectable even when its contact plane is level with the eyes. */
    static final double BOTTLE_TOUCH_HALF_HEIGHT = 0.12, LOOSE_TOUCH_HALF_HEIGHT = 0.065;
    /**
     * The stand's three stone feet, in block pixels (x, z): east, north-west and south-west, the order of its bottle
     * slots (brewing_stand.json, brewing_stand_bottle0..2.json). The potions stand on them.
     */
    static final double[][] FEET = {{12, 8}, {4, 4}, {4, 12}};
    /** Height of the middle of a standing potion: its bottle's foot rests on the stone (2 px). */
    static final double BELLY = 2 / 16.0 + 7 / 16.0 * BrewingBottles.SIZE;
    static final int BOTTLES = 3, INGREDIENT = 3, FUEL = 4;
    static final int BREW_TIME = 400;

    public enum Target { NONE, PLACE, ITEM }

    public Target target = Target.NONE;
    public int hoverSlot = -1;
    private int placeSlot = -1;
    private final Vec3 base;
    private int lastTicks;
    private final double[] flaredAt = {-10, -10, -10};

    public BrewingStation(BlockPos pos, Direction side) {
        super(pos, side, BrewingStandMenu.class, 5);
        base = Vec3.atBottomCenterOf(pos);
    }

    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    @Override public ItemStack layoutItem(int slot) { return slot(slot); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public void renderScreen(net.minecraft.client.gui.GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public SpatialAppearance.Frame layoutFrame() {
        return SpatialAppearance.Frame.of(kind(), pos, side, Direction.UP);
    }
    @Override public float layoutHeldScale() {
        int slot = placeSlot >= 0 ? placeSlot : hoverSlot;
        return slot < 0 ? 1f : geometry().scale(slot);
    }
    @Override public List<Vec3> workSamples() { return layoutSamples(super.workSamples()); }

    @Override
    public StationKind kind() {
        return StationKind.BREWING;
    }

    @Override
    public Vec3 center() {
        return base.add(0, 0.45, 0);
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.METAL_STEP;
    }

    /** The middle of a bottle standing on its foot. */
    static Vec3 slotPoint(BlockPos pos, int slot) {
        return slotPoint(pos, Direction.SOUTH, slot);
    }

    static Vec3 slotPoint(BlockPos pos, Direction side, int slot) {
        return SpatialLayouts.current(StationKind.BREWING).point(
                SpatialAppearance.Frame.of(StationKind.BREWING, pos, side, Direction.UP), slot);
    }

    static float itemScale(int slot) { return SpatialLayouts.current(StationKind.BREWING).scale(slot); }
    static float itemRotation(int slot) { return (float) -SpatialLayouts.current(StationKind.BREWING).rotation(slot); }

    @Override
    protected Vec3 restPoint(int slot) {
        return slotPoint(pos, side, slot).add(right().scale(denyShake()));
    }

    /** Where the cursor and the hand go for a slot. */
    Vec3 handle(int slot) {
        return restPoint(slot);
    }

    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(base.x + side.getStepX() * 0.95, player.getY(), base.z + side.getStepZ() * 0.95);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public boolean crouches() {
        return true;
    }

    @Override
    public boolean workOnTop() {
        return false;
    }

    @Override
    public boolean worksAtAnAngle() {
        return true;
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 eye = base.add(side().scale(1.2)).add(0, 0.88, 0);
        Vec3 look = base.add(0, 0.46, 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, -2f);
    }

    @Override
    public float bodyPitch() {
        return 32f;
    }

    @Override
    public Vec3 rest(boolean right) {
        return base.add(side().scale(0.55)).add(right().scale(right ? 0.3 : -0.3)).add(0, 0.2, 0);
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || menu() == null) {
            return;
        }
        double[] mouse = Picking.mouseGui();
        ItemStack carried = Hands.carried();
        int nearest = -1;
        double best = Double.MAX_VALUE;
        int editedHit = geometry().equals(SpatialAppearance.VANILLA) ? -1 : layoutPick();
        for (int i = 0; i < 5; i++) {
            double[] at = Picking.toGui(handle(i));
            if (at == null) {
                continue;
            }
            double d = Math.hypot(mouse[0] - at[0], mouse[1] - at[1]);
            boolean near = geometry().equals(SpatialAppearance.VANILLA)
                    ? GrindstoneStation.under(mouse, handle(i), i < BOTTLES ? 0.1 : 0.09) : editedHit == i;
            // empty-handed only what lies there can be picked; carrying, the nearest spot anywhere over the stand
            boolean candidate = carried.isEmpty() ? near && !slot(i).isEmpty() : near || nearStand(mouse);
            if (candidate && d < best) {
                best = d;
                nearest = i;
            }
        }
        if (nearest < 0) {
            return;
        }
        if (carried.isEmpty()) {
            target = Target.ITEM;
            hoverSlot = nearest;
            aim = handle(nearest);
            return;
        }
        target = Target.PLACE;
        placeSlot = slotFor(carried, nearest);
        aim = handle(placeSlot < 0 ? nearest : placeSlot);
    }

    /** The cursor is somewhere over the stand. */
    private boolean nearStand(double[] mouse) {
        return GrindstoneStation.under(mouse, base.add(0, 0.4, 0), 0.5);
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        hoverSlot = -1;
        placeSlot = -1;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        Vec3 reach = new Vec3(aim.x - shoulder.x, 0, aim.z - shoulder.z).normalize();
        double hover = carrying ? 0.07 : 0.04;
        return aim.add(0, HAND_Y + hover - 0.05 * tap, 0).subtract(reach.scale(ITEM_REACH));
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        Vec3 reach = new Vec3(hand.x - shoulder.x, 0, hand.z - shoulder.z).normalize();
        return hand.add(reach.scale(ITEM_REACH)).add(0, -HAND_Y + 0.012, 0);
    }

    // ---- actions -------------------------------------------------------------------------------------

    @Override
    protected void act(int button, boolean shift) {
        switch (target) {
            case PLACE -> {
                if (placeSlot < 0) {
                    deny();
                    return;
                }
                slotClick(placeSlot, button, false);
                Scene.sound(placeSlot < BOTTLES ? SoundEvents.BOTTLE_FILL : SoundEvents.SAND_PLACE, 0.4f, 1.3f);
            }
            case ITEM -> slotClick(hoverSlot, 0, shift);
            default -> {}
        }
    }

    // ---- brewing -------------------------------------------------------------------------------------

    private int ticks() {
        BrewingStandMenu menu = menu();
        return menu == null ? 0 : menu.getBrewingTicks();
    }

    /** 0..1 of the current brew, or -1 when nothing brews. */
    private double progress() {
        int t = ticks();
        return t > 0 ? 1 - t / (double) BREW_TIME : -1;
    }

    static float[] color(ItemStack stack) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        int rgb = contents == null ? 0x3F76E4 : contents.getColor();
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(0.1f, Math.max(r, Math.max(g, b)));
        return new float[]{r / max, g / max, b / max};
    }

    @Override
    public void tick() {
        int ticks = ticks();
        if (lastTicks > 0 && ticks == 0) {
            // done: every bottle flares up in its new colour (the stand's own sound comes from the server)
            for (int i = 0; i < BOTTLES; i++) {
                if (!slot(i).isEmpty()) {
                    flaredAt[i] = Scene.clock();
                    float[] c = color(slot(i));
                    Fx.motes(handle(i), 0.06, 14, c[0], c[1], c[2], 0.3);
                }
            }
        }
        lastTicks = ticks;
    }

    @Override
    void frame() {
        super.frame();
        double p = progress();
        double dt = Scene.frameDt;
        if (p < 0) {
            return;
        }
        Vec3 bowl = restPoint(INGREDIENT).add(0, 0.03, 0);
        for (int i = 0; i < BOTTLES; i++) {
            ItemStack bottle = slot(i);
            if (bottle.isEmpty()) {
                continue;
            }
            float[] c = color(bottle);
            if (Fx.RANDOM.nextDouble() < dt * 6) {
                // bubbles rising in the bottle
                Fx.spark(handle(i).add(Fx.RANDOM.nextGaussian() * 0.015, -0.05, Fx.RANDOM.nextGaussian() * 0.015),
                        new Vec3(0, 0.08 + Fx.RANDOM.nextDouble() * 0.05, 0), 0.6, 0.006f, c[0] * 0.8f, c[1] * 0.8f, c[2] * 0.8f, -0.05, 0);
            }
            if (Fx.RANDOM.nextDouble() < dt * 3.5) {
                // a drop of the melting ingredient runs down the arm to the bottle
                double flight = 0.45;
                Vec3 to = handle(i).add(0, 0.1, 0);
                double gravity = 1.2;
                Vec3 v = to.subtract(bowl).scale(1 / flight).add(0, 0.5 * gravity * flight, 0);
                Fx.spark(bowl, v, flight, 0.008f, 0.85f, 0.6f, 1f, gravity, 0.03);
            }
        }
        if (Fx.RANDOM.nextDouble() < dt * 4) {
            Fx.steam(bowl.add(0, 0.02, 0), 0.03, 1);
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------

    @Override
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        if (outro()) {
            // the stand keeps what was put in it: BrewingBottles goes on drawing it there (nothing flies back)
            return;
        }
        double p = progress();
        List<ItemStack> items = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            items.add(slot(i));
        }
        draw(pose, buffers, camera, pos, side, items, hoverSlot, p, light);
        double clock = Scene.clock();
        for (int i = 0; i < BOTTLES; i++) {
            ItemStack bottle = slot(i);
            double flare = Math.exp(-Math.max(0, clock - flaredAt[i]) * 2.5);
            // a soft light of the potion's colour round its belly: stronger while it brews, a flare when it is done
            double glow = (p >= 0 ? 0.14 + 0.07 * Math.sin(clock * 4 + i) : 0.05) + 0.5 * flare + (i == hoverSlot ? 0.1 : 0);
            if (!bottle.isEmpty() && glow > 0.01) {
                float[] c = color(bottle);
                float g = (float) glow;
                EnchantFx.billboard(pose, buffers, camera, handle(i).add(0, -0.04, 0), 0.09 + 0.05 * flare, 0, EnchantFx.GLOW, c[0] * g, c[1] * g,
                        c[2] * g);
            }
        }
        BrewingStandMenu menu = menu();
        int fuel = menu == null ? 0 : menu.getFuel();
        if (fuel > 0) {
            // what is left of the fire, as a small glow at the powder
            float f = (float) (0.12 + 0.18 * fuel / 20.0) * (float) (0.85 + 0.15 * Math.sin(clock * 9));
            EnchantFx.billboard(pose, buffers, camera, restPoint(FUEL).add(0, 0.05, 0), 0.07, 0, EnchantFx.GLOW, f, f * 0.55f, f * 0.15f);
        }
    }

    /** Bottles on the feet, the ingredient in the bowl (melting by {@code progress}), the powder at the foot. */
    static void draw(PoseStack pose, SceneBuffers buffers, Camera camera, BlockPos pos, Direction side, List<ItemStack> items, int hover,
            double progress, int light) {
        Vec3 cam = camera.position();
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            Vec3 at = slotPoint(pos, side, i);
            float grow = i == hover ? 1.08f : 1f;
            if (i < BOTTLES) {
                // the potions are drawn by BrewingBottles, for everyone
                continue;
            }
            if (i == INGREDIENT) {
                float melt = progress >= 0 ? (float) (1 - 0.65 * progress) : 1f;
                boolean block = ItemPlacer.isBlock(stack);
                float size = (block ? 0.1f : 0.17f) * melt * grow * itemScale(i);
                ItemPlacer.drawOriented(pose, buffers, cam, stack, at.add(0, block ? size / 2 : 0.004, 0),
                        ItemPlacer.lying(ItemPlacer.awayYaw(side) + 20 + itemRotation(INGREDIENT), 0), size, light);
            } else {
                // blaze powder standing at the foot of the rod, turned to whoever looks at it
                Vec3 toEye = new Vec3(cam.x - at.x, 0, cam.z - at.z);
                float yaw = (float) (180 - Math.toDegrees(Math.atan2(-toEye.x, toEye.z)));
                ItemPlacer.drawOriented(pose, buffers, cam, stack, at, ItemPlacer.lying(yaw + itemRotation(FUEL), 90), 0.17f * grow * itemScale(FUEL), light);
            }
        }
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        if (target == Target.PLACE && aim != null) {
            float p = (float) (0.2 + 0.07 * Math.sin(Scene.clock() * 5));
            boolean ok = placeSlot >= 0;
            Minecraft mc = Minecraft.getInstance();
            EnchantFx.billboard(pose, buffers, mc.gameRenderer.mainCamera(), aim, 0.07, 0, EnchantFx.GLOW, p, ok ? p * 0.8f : p * 0.2f,
                    ok ? p * 0.6f : p * 0.2f);
        }
    }

    // ---- what others see -----------------------------------------------------------------------------

    static void drawContents(PoseStack pose, SceneBuffers buffers, Camera camera, BlockPos pos, Direction side, List<ItemStack> items, int light) {
        draw(pose, buffers, camera, pos, side, items, -1, -1, light);
    }

    /** The ingredient in the bowl and the blaze powder at the foot, at rest (drawn for every stand by BrewingBottles). */
    static void drawLoose(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction side, ItemStack ingredient, ItemStack fuel,
            int light) {
        if (!ingredient.isEmpty()) {
            Vec3 at = slotPoint(pos, side, INGREDIENT);
            boolean block = ItemPlacer.isBlock(ingredient);
            float size = (block ? 0.1f : 0.17f) * itemScale(INGREDIENT);
            ItemPlacer.drawOriented(pose, buffers, cam, ingredient, at.add(0, block ? size / 2 : 0.004, 0),
                    ItemPlacer.lying(ItemPlacer.awayYaw(side) + 20 + itemRotation(INGREDIENT), 0), size, light);
        }
        if (!fuel.isEmpty()) {
            Vec3 at = slotPoint(pos, side, FUEL);
            Vec3 toEye = new Vec3(cam.x - at.x, 0, cam.z - at.z);
            float yaw = (float) (180 - Math.toDegrees(Math.atan2(-toEye.x, toEye.z)));
            ItemPlacer.drawOriented(pose, buffers, cam, fuel, at, ItemPlacer.lying(yaw + itemRotation(FUEL), 90), 0.17f * itemScale(FUEL), light);
        }
    }

    // ---- words ---------------------------------------------------------------------------------------

    private static final String[] SLOT_KEYS = {"bottle", "bottle", "bottle", "ingredient", "fuel"};

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] editorMouse = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(editorMouse[0], editorMouse[1]);
        if (editorTip != null || gridEditor.editing()) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        if (target == Target.PLACE) {
            return placeSlot < 0 ? List.of(Component.translatable("cinematic.brewing.refuse").withStyle(ChatFormatting.RED))
                    : List.of(Component.translatable("cinematic.brewing.slot." + SLOT_KEYS[placeSlot]).withStyle(ChatFormatting.GRAY));
        }
        if (target != Target.ITEM) {
            return null;
        }
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, slot(hoverSlot)));
        double p = progress();
        if (p >= 0 && hoverSlot != FUEL) {
            lines.add(Component.translatable("cinematic.brewing.progress", (int) Math.round(p * 100)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (hoverSlot == FUEL) {
            BrewingStandMenu menu = menu();
            lines.add(Component.translatable("cinematic.brewing.fuel", menu == null ? 0 : menu.getFuel()).withStyle(ChatFormatting.GOLD));
        }
        lines.add(Component.translatable("cinematic.brewing.take").withStyle(ChatFormatting.GRAY));
        return lines;
    }

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.brewing");
    }
}
