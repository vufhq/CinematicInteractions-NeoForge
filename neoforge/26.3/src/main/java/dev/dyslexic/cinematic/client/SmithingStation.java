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
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The smithing table scene. Template, gear and material are laid out on the iron plate. Touching the gear
 * smiths it: both hands carry the template over and lay it on the gear, then the right hand fetches the material
 * and lays it on top of the template. Both hands press the stack together; it glows red, then white, molten metal
 * (netherite like lava, a trim material in its own colour) runs down its sides, and the template and the material
 * sink into the gear until the three are one: the new piece, white-hot. It is quenched in a hiss of steam, and the
 * hand takes it. Underneath is the vanilla smithing menu.
 */
public final class SmithingStation extends SurfaceStation<SmithingMenu> {
    static final double HAND_Y = 0.062;
    static final double ITEM_REACH = 0.075;
    static final double LEAN_BACK = 0.7;
    static final double LEAN_UP = 0.62;

    /** Template, gear and material: across the plate, the gear in front in the middle (r right, d towards the player). */
    static final double[][] LAYOUT = {{-0.26, -0.12}, {0.0, 0.06}, {0.27, -0.12}};
    static final float[] SIZES = {0.22f, 0.34f, 0.2f};

    public static final double SMITH_LENGTH = 2.6;
    /** The template lies on the gear. */
    static final double TEMPLATE_DOWN = 0.38;
    /** The material is picked up, then lies on the template. */
    static final double MATERIAL_UP = 0.5;
    static final double MATERIAL_DOWN = 0.76;
    /** Both hands press the stack together. */
    static final double PRESS = 0.95;
    /** White heat: the three have become one piece, and the server smiths now. */
    static final double PEAK = 1.35;
    static final double COOLED = 2.15;
    /** Heights of the middles of the template and the material lying on the gear (each item is 1/16 of its size thick). */
    static final double ON_GEAR = 0.025, ON_TEMPLATE = 0.041;

    public enum Target { NONE, PLACE, ITEM }

    public final Vec3 top;
    public Target target = Target.NONE;
    public int hoverSlot = -1;
    private int placeSlot = -1;

    // the move, as it was when it began
    private final ItemStack[] before = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    private ItemStack after = ItemStack.EMPTY;
    private boolean toInventory;
    private float[] molten = {1f, 0.55f, 0.2f};
    private boolean templateLaid, materialLaid, pressed, peaked, hissed;

    public SmithingStation(BlockPos pos, Direction side) {
        super(pos, side, SmithingMenu.class, 3);
        top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    @Override
    public StationKind kind() {
        return StationKind.SMITHING;
    }

    @Override
    public Vec3 center() {
        return top;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.METAL_STEP;
    }

    static Vec3 slotPoint(Vec3 top, Direction side, int slot) {
        return SurfaceLayouts.current(StationKind.SMITHING).point(StationKind.SMITHING, top, side, slot);
    }

    @Override float heldItemScale() { return heldItemScale(placeSlot >= 0 ? placeSlot : hoverSlot); }

    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(top.x + side.getStepX() * 0.86, player.getY(), top.z + side.getStepZ() * 0.86);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public boolean worksAtAnAngle() {
        return true;
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 at = top.add(side().scale(LEAN_BACK)).add(0, LEAN_UP, 0);
        float pitch = (float) Math.toDegrees(Math.atan2(LEAN_UP, LEAN_BACK + 0.04));
        return new CameraRig.Pose(at, side.getOpposite().toYRot(), pitch, 0);
    }

    @Override
    public Vec3 rest(boolean right) {
        return top.add(side().scale(0.4)).add(right().scale(right ? 0.25 : -0.25)).add(0, HAND_Y, 0);
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || gridEditor.editing() || menu() == null || moving()) {
            return;
        }
        if (!geometry().equals(SurfaceAppearance.VANILLA)) {
            int selected = surfaceSlot(Hands.carried().isEmpty());
            if (selected >= 0) {
                if (Hands.carried().isEmpty()) { target = Target.ITEM; hoverSlot = selected; }
                else { target = Target.PLACE; placeSlot = slotFor(Hands.carried(), selected); }
                aim = restPoint(placeSlot < 0 ? selected : placeSlot);
            }
            return;
        }
        ItemStack carried = Hands.carried();
        double[] mouse = Picking.mouseGui();
        if (carried.isEmpty()) {
            for (int i = 0; i < 3; i++) {
                if (!slot(i).isEmpty() && GrindstoneStation.under(mouse, restPoint(i), SIZES[i] * 0.42)) {
                    target = Target.ITEM;
                    hoverSlot = i;
                    aim = restPoint(i);
                    return;
                }
            }
            return;
        }
        Vec3 hit = Picking.cursorOnPlane(top.y);
        if (hit == null) {
            return;
        }
        Vec3 rel = hit.subtract(top);
        if (Math.abs(rel.x) > 0.52 || Math.abs(rel.z) > 0.52) {
            return;
        }
        int nearest = 0;
        for (int i = 1; i < 3; i++) {
            if (hit.distanceToSqr(restPoint(i)) < hit.distanceToSqr(restPoint(nearest))) {
                nearest = i;
            }
        }
        target = Target.PLACE;
        placeSlot = slotFor(carried, nearest);
        aim = restPoint(placeSlot < 0 ? nearest : placeSlot);
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
        double hover = carrying ? 0.075 : 0.045;
        return aim.add(0, HAND_Y + hover - 0.06 * tap, 0).subtract(reach.scale(ITEM_REACH));
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
                Scene.sound(SoundEvents.CHAIN_PLACE, 0.5f, 1.0f + placeSlot * 0.15f);
            }
            case ITEM -> {
                if (button == 0 && !slot(3).isEmpty()) {
                    smith(shift);
                } else {
                    slotClick(hoverSlot, 0, shift);
                }
            }
            default -> {}
        }
    }

    private void smith(boolean shift) {
        for (int i = 0; i < 3; i++) {
            before[i] = slot(i).copyWithCount(1);
        }
        after = slot(3).copy();
        toInventory = shift;
        molten = moltenColor(after);
        templateLaid = materialLaid = pressed = peaked = hissed = false;
        startMove(SMITH_LENGTH, PEAK, () -> {
            SmithingMenu menu = menu();
            Minecraft mc = Minecraft.getInstance();
            if (slot(3).isEmpty()) {
                cancelMove();
                return;
            }
            mc.gameMode.handleContainerInput(menu.containerId, 3, 0, toInventory ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        });
        Scene.sound(SoundEvents.ARMOR_EQUIP_NETHERITE.value(), 0.5f, 1.0f);
    }

    /** A trim runs in the colour of its material; anything else (the netherite upgrade) like molten metal. */
    static float[] moltenColor(ItemStack result) {
        ArmorTrim trim = result.get(DataComponents.TRIM);
        if (trim != null) {
            TextColor color = trim.material().value().description().getStyle().getColor();
            if (color != null) {
                int rgb = color.getValue();
                float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
                float max = Math.max(0.05f, Math.max(r, Math.max(g, b)));
                return new float[]{r / max, g / max, b / max};
            }
        }
        return new float[]{1f, 0.5f, 0.16f};
    }

    // ---- the move ------------------------------------------------------------------------------------

    /** 0..1: how far the template and the material have sunk into the gear, pressed together. */
    private static double fuse(double t) {
        return Anim.smoother(Anim.window(t, PRESS + 0.05, PEAK - 0.03));
    }

    // Keep the original contact heights at 100%, accounting for each scaled item's half-thickness.
    private double templateHeight() {
        return ON_GEAR + (SIZES[1] * (surfaceScale(1) - 1) + SIZES[0] * (surfaceScale(0) - 1)) / 32;
    }

    private double materialHeight() {
        return ON_TEMPLATE + SIZES[1] * (surfaceScale(1) - 1) / 32
                + SIZES[0] * (surfaceScale(0) - 1) / 16 + SIZES[2] * (surfaceScale(2) - 1) / 32;
    }

    private Vec3 movingAxis(double t, int slot, double from, double to, boolean across) {
        double k = Anim.inOutCubic(Anim.window(t, from, to));
        double angle = surfaceRotation(slot) + (surfaceRotation(1) - surfaceRotation(slot)) * k;
        return (across ? right() : side()).yRot((float) Math.toRadians(angle));
    }

    /** The template: lifted off the plate by both hands, carried over the gear and laid on it. */
    private Vec3 templatePos(double t) {
        Vec3 from = restPoint(0).add(0, 0.004, 0);
        Vec3 over = restPoint(1).add(0, 0.14, 0);
        double height = templateHeight();
        Vec3 on = restPoint(1).add(0, height, 0);
        Vec3 at = Anim.lerp(from, from.add(0, 0.1, 0), Anim.inOutCubic(Anim.window(t, 0.06, 0.24)));
        at = Anim.lerp(at, over, Anim.inOutCubic(Anim.window(t, 0.14, 0.3)));
        at = Anim.lerp(at, on, Anim.inCubic(Anim.window(t, 0.28, TEMPLATE_DOWN)));
        return at.add(0, -(height - 0.006) * fuse(t) - 0.004 * Anim.hump(Anim.window(t, PRESS, PRESS + 0.12)), 0);
    }

    /** The material: fetched by the right hand and laid on top of the template. */
    private Vec3 materialPos(double t) {
        Vec3 from = restPoint(2).add(0, 0.004, 0);
        double height = materialHeight();
        Vec3 over = restPoint(1).add(0, 0.17, 0).add(surfaceRight(1).scale(0.012));
        Vec3 on = restPoint(1).add(0, height, 0).add(surfaceRight(1).scale(0.012));
        Vec3 at = Anim.lerp(from, from.add(0, 0.1, 0), Anim.inOutCubic(Anim.window(t, MATERIAL_UP - 0.04, MATERIAL_UP + 0.08)));
        at = Anim.lerp(at, over, Anim.inOutCubic(Anim.window(t, MATERIAL_UP + 0.02, MATERIAL_DOWN - 0.06)));
        at = Anim.lerp(at, on, Anim.inCubic(Anim.window(t, MATERIAL_DOWN - 0.09, MATERIAL_DOWN)));
        return at.add(0, -(height - 0.008) * fuse(t) - 0.006 * Anim.hump(Anim.window(t, PRESS, PRESS + 0.12)), 0);
    }

    /** Lying flat, turned from how it lay on the plate to lie square on the gear. */
    private Quaternionf turn(double t, float laidYaw, float stackYaw, double from, double to, int slot) {
        double k = Anim.inOutCubic(Anim.window(t, from, to));
        laidYaw += surfaceRotation(slot); stackYaw += surfaceRotation(1);
        return ItemPlacer.lying(ItemPlacer.awayYaw(side) + (float) (laidYaw + (stackYaw - laidYaw) * k), 0);
    }

    private double heat(double t) {
        return Anim.smoother(Anim.window(t, MATERIAL_DOWN, PEAK)) * (1 - Anim.smoother(Anim.window(t, PEAK, COOLED)));
    }

    private Vec3 resultPos(double t, Camera camera) {
        Vec3 at = restPoint(1);
        if (toInventory) {
            Vec3 drawer = camera.position().add(0, 0.55, 0).add(Scene.yawForward(camera.yRot()).scale(0.6));
            return Anim.lerp(at, drawer, Anim.inCubic(Anim.window(t, COOLED + 0.05, SMITH_LENGTH)));
        }
        Vec3 held = heldItemPoint(Hands.position(true), Hands.shoulder(true, camera.position(), camera.yRot()));
        return Anim.lerp(at, held, Anim.inOutCubic(Anim.window(t, COOLED + 0.12, SMITH_LENGTH)));
    }

    @Nullable
    @Override
    public Vec3[] handsOverride() {
        double t = moveTime();
        if (t < 0) {
            return null;
        }
        Vec3 r = rest(true), l = rest(false);
        // both hands carry the template over and lay it on the gear
        double carry = Anim.inOutCubic(Anim.window(t, 0, 0.12)) * (1 - Anim.inOutCubic(Anim.window(t, TEMPLATE_DOWN, TEMPLATE_DOWN + 0.1)));
        Vec3 template = templatePos(Math.max(t, 0.06));
        Vec3 across = movingAxis(t, 0, 0.14, TEMPLATE_DOWN, true);
        Vec3 out = movingAxis(t, 0, 0.14, TEMPLATE_DOWN, false);
        r = Anim.lerp(r, template.add(across.scale(0.09 * surfaceScale(0))).add(out.scale(0.04)).add(0, HAND_Y, 0), carry);
        l = Anim.lerp(l, template.add(across.scale(-0.09 * surfaceScale(0))).add(out.scale(0.04)).add(0, HAND_Y, 0), carry);
        // the right hand fetches the material and lays it on top, while the left keeps the template in place
        double fetch = Anim.inOutCubic(Anim.window(t, TEMPLATE_DOWN + 0.02, MATERIAL_UP - 0.03))
                * (1 - Anim.inOutCubic(Anim.window(t, MATERIAL_DOWN, MATERIAL_DOWN + 0.08)));
        r = Anim.lerp(r, materialPos(t).add(movingAxis(t, 2, MATERIAL_UP, MATERIAL_DOWN, false).scale(ITEM_REACH)).add(0, HAND_Y, 0), fetch);
        double steady = Anim.inOutCubic(Anim.window(t, TEMPLATE_DOWN, TEMPLATE_DOWN + 0.12)) * (1 - Anim.inOutCubic(Anim.window(t, MATERIAL_DOWN, PRESS - 0.05)));
        l = Anim.lerp(l, restPoint(1).add(surfaceRight(1).scale(-0.14 * surfaceScale(1))).add(surfaceOut(1).scale(0.05)).add(0, HAND_Y + 0.012, 0), steady);
        // both hands press the stack together, then back away from the heat
        double press = Anim.inOutCubic(Anim.window(t, MATERIAL_DOWN + 0.02, PRESS - 0.03)) * (1 - Anim.inOutCubic(Anim.window(t, PRESS + 0.16, PRESS + 0.34)));
        Vec3 stack = restPoint(1).add(surfaceOut(1).scale(0.03)).add(0, HAND_Y + 0.05 - 0.03 * Anim.hump(Anim.window(t, PRESS - 0.07, PRESS + 0.15)), 0);
        r = Anim.lerp(r, stack.add(surfaceRight(1).scale(0.07 * surfaceScale(1))), press);
        l = Anim.lerp(l, stack.add(surfaceRight(1).scale(-0.07 * surfaceScale(1))), press);
        double away = Anim.hump(Anim.window(t, PRESS + 0.25, COOLED - 0.1));
        r = r.add(side().scale(0.06 * away)).add(0, 0.04 * away, 0);
        l = l.add(side().scale(0.06 * away)).add(0, 0.04 * away, 0);
        if (t > COOLED - 0.1) {
            // the right hand takes the new piece
            Vec3 grab = restPoint(1).add(0, HAND_Y + 0.02, 0).add(surfaceOut(1).scale(ITEM_REACH));
            double take = Anim.inOutCubic(Anim.window(t, COOLED - 0.1, COOLED + 0.12));
            Vec3 end = rest(true).add(0, toInventory ? 0 : 0.05, 0);
            r = Anim.lerp(Anim.lerp(r, grab, take), end, Anim.inOutCubic(Anim.window(t, COOLED + 0.12, SMITH_LENGTH)));
        }
        return new Vec3[]{r, l};
    }

    @Override
    public double cameraKick() {
        double t = moveTime();
        if (t < 0) {
            return 0;
        }
        return 0.35 * Math.exp(-Math.max(0, t - PRESS) * 14) * (t >= PRESS ? 1 : 0) + 0.3 * Math.exp(-Math.max(0, t - PEAK) * 9) * (t >= PEAK ? 1 : 0);
    }

    @Override
    public double flash() {
        double t = moveTime();
        return t < PEAK ? 0 : 0.2 * Math.exp(-(t - PEAK) * 7);
    }

    @Override
    public int flashColor() {
        return 0xFFE8C8;
    }

    @Override
    void frame() {
        super.frame();
        double t = moveTime();
        if (t < 0) {
            return;
        }
        double dt = Scene.frameDt;
        Vec3 gear = restPoint(1);
        if (!templateLaid && t >= TEMPLATE_DOWN) {
            templateLaid = true;
            Scene.sound(SoundEvents.CHAIN_PLACE, 0.5f, 1.1f);
        }
        if (!materialLaid && t >= MATERIAL_DOWN) {
            materialLaid = true;
            Scene.sound(SoundEvents.CHAIN_PLACE, 0.5f, 1.45f);
        }
        if (!pressed && t >= PRESS) {
            pressed = true;
            Scene.sound(SoundEvents.SMITHING_TABLE_USE, 0.6f, 0.9f);
            Fx.motes(gear.add(0, 0.05, 0), 0.08, 10, 0.6f, 0.85f, 1f, 0.1);
        }
        if (t >= PRESS && t < PEAK) {
            // pressed together, the stack runs: molten metal trickles down its sides onto the plate
            int drops = (int) Math.round(dt * 40 + Fx.RANDOM.nextDouble());
            for (int i = 0; i < drops; i++) {
                double a = Fx.RANDOM.nextDouble() * Math.PI * 2;
                Vec3 edge = gear.add(Math.cos(a) * 0.1, 0.04, Math.sin(a) * 0.1);
                Vec3 v = new Vec3(Math.cos(a) * 0.25, 0.1, Math.sin(a) * 0.25);
                float k = 0.9f + 0.1f * Fx.RANDOM.nextFloat();
                Fx.spark(edge, v, 0.18 + Fx.RANDOM.nextDouble() * 0.08, 0.009f, molten[0] * k, molten[1] * k, molten[2] * k, 2.4, 0.04);
            }
            if (Fx.RANDOM.nextDouble() < dt * 24) {
                // embers rising off the heating stack
                Fx.motes(gear.add(0, 0.05, 0), 0.08, 1, 1f, 0.55f, 0.2f, 0.25);
            }
        } else if (t >= MATERIAL_DOWN && t < PRESS && Fx.RANDOM.nextDouble() < dt * 14) {
            Fx.motes(gear.add(0, 0.05, 0), 0.07, 1, molten[0], molten[1] * 0.7f, molten[2] * 0.4f, 0.15);
        }
        if (!peaked && t >= PEAK) {
            peaked = true;
            Fx.motes(gear.add(0, 0.04, 0), 0.1, 18, molten[0], molten[1], molten[2], 0.25);
            Fx.blowSparks(gear.add(0, 0.03, 0), 10, 1.4);
        }
        if (!hissed && t >= PEAK + 0.08) {
            hissed = true;
            Scene.sound(SoundEvents.LAVA_EXTINGUISH, 0.4f, 1.1f);
        }
        if (t >= PEAK + 0.05 && t < COOLED - 0.2) {
            // quenching: steam off the hot metal
            double rate = 70 * (1 - Anim.window(t, PEAK, COOLED - 0.2));
            Fx.steam(gear.add(0, 0.03, 0), 0.07, (int) Math.round(dt * rate + Fx.RANDOM.nextDouble()));
        }
    }

    // ---- drawing -------------------------------------------------------------------------------------

    @Override
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        Vec3 cam = camera.position();
        if (outro()) {
            drawLeaving(pose, buffers, camera, light);
            return;
        }
        double t = moveTime();
        if (t < 0) {
            for (int i = 0; i < 3; i++) {
                drawLaid(pose, buffers, cam, slot(i), restPoint(i), side, i, (i == hoverSlot ? 1.06f : 1f) * surfaceScale(i), light, surfaceRotation(i));
            }
            return;
        }
        // what stays behind of each stack while one of each is used
        for (int i = 0; i < 3; i++) {
            ItemStack rest = slot(i);
            int left = t < PEAK ? rest.getCount() - 1 : rest.getCount();
            if (i != 1 && left > 0) {
                drawLaid(pose, buffers, cam, rest.copyWithCount(left), restPoint(i), side, i, surfaceScale(i), light, surfaceRotation(i));
            }
        }
        double heat = heat(t);
        // the gear: then the new piece, white-hot at first
        ItemStack gear = t < PEAK ? before[1] : after;
        Vec3 at = t < PEAK ? restPoint(1).add(0, 0.004, 0) : resultPos(t, camera).add(0, 0.004, 0);
        float size = t < COOLED || toInventory ? SIZES[1] * surfaceScale(1) : (float) (SIZES[1] * surfaceScale(1) + (0.205 - SIZES[1] * surfaceScale(1)) * Anim.window(t, COOLED + 0.1, SMITH_LENGTH));
        if (toInventory) {
            size *= (float) (1 - 0.85 * Anim.inCubic(Anim.window(t, COOLED + 0.05, SMITH_LENGTH)));
        }
        float tilt = (float) (25 * Anim.window(t, COOLED, SMITH_LENGTH));
        ItemPlacer.drawOriented(pose, buffers, cam, gear, at, ItemPlacer.lying(ItemPlacer.awayYaw(side) + surfaceRotation(1), tilt), size, light);
        // the template on it and the material on top, sinking into it as they are pressed together
        double left = 1 - fuse(t);
        if (left > 0.02 && t < PEAK) {
            ItemPlacer.drawOriented(pose, buffers, cam, before[0], templatePos(t), turn(t, -12f, 0f, 0.14, TEMPLATE_DOWN, 0),
                    (float) (SIZES[0] * surfaceScale(0) * (0.55 + 0.45 * left)), light);
            ItemPlacer.drawOriented(pose, buffers, cam, before[2], materialPos(t), turn(t, 18f, 8f, MATERIAL_UP, MATERIAL_DOWN, 2),
                    (float) (SIZES[2] * surfaceScale(2) * (0.5 + 0.5 * left)), light);
            float glow = (float) (Anim.smoother(Anim.window(t, MATERIAL_DOWN - 0.1, PRESS)) * left);
            if (glow > 0.01) {
                // the material on top glows in its molten colour as it is pressed in
                float k = glow * (float) (0.85 + 0.15 * Math.sin(Scene.clock() * 13));
                EnchantFx.billboard(pose, buffers, camera, materialPos(t).add(0, 0.02, 0), 0.12, Scene.clock(), EnchantFx.GLOW, molten[0] * k,
                        molten[1] * 0.75f * k, molten[2] * 0.55f * k);
            }
        }
        if (heat > 0.01) {
            // red, then orange, then white heat round the stack, cooling back after the quench
            float h = (float) heat;
            float white = (float) Anim.smoother(Anim.window(t, PRESS, PEAK));
            float flicker = (float) (0.92 + 0.08 * Math.sin(Scene.clock() * 17));
            float r = h * flicker, g = (0.3f + 0.6f * white) * h * flicker, b = (0.08f + 0.6f * white * white) * h * flicker;
            EnchantFx.billboard(pose, buffers, camera, restPoint(1).add(0, 0.05, 0), 0.17 + 0.08 * h, Scene.clock() * 0.3, EnchantFx.GLOW, r, g, b);
            EnchantFx.disc(pose, buffers, cam, restPoint(1).add(0, 0.003, 0), 0.2, 0, EnchantFx.GLOW, r * 0.7f, g * 0.7f, b * 0.7f);
        }
        if (t >= PRESS - 0.02 && t < PRESS + 0.4) {
            // the press lights up the plate under the stack
            float k = (float) Math.exp(-(t - PRESS) * 7) * 0.6f;
            EnchantFx.disc(pose, buffers, cam, restPoint(1).add(0, 0.004, 0), 0.2, 0, EnchantFx.GLOW, 0.6f * k, 0.85f * k, k);
        }
    }

    /** One of the three laid on the plate. */
    static void drawLaid(PoseStack pose, SceneBuffers buffers, Vec3 cam, ItemStack stack, Vec3 at, Direction side, int slot, float scale,
            int light) {
        drawLaid(pose, buffers, cam, stack, at, side, slot, scale, light, 0);
    }

    static void drawLaid(PoseStack pose, SceneBuffers buffers, Vec3 cam, ItemStack stack, Vec3 at, Direction side, int slot,
            float scale, int light, float rotation) {
        if (stack.isEmpty()) {
            return;
        }
        boolean block = ItemPlacer.isBlock(stack);
        float size = (block ? 0.13f : SIZES[slot]) * scale;
        float yaw = ItemPlacer.awayYaw(side) + rotation + (slot == 0 ? -12f : slot == 2 ? 18f : 0f);
        ItemPlacer.drawOriented(pose, buffers, cam, stack, at.add(0, block ? size / 2 : 0.004, 0), ItemPlacer.lying(yaw, 0), size, light);
        if (stack.getCount() > 1 && !block) {
            ItemPlacer.drawOriented(pose, buffers, cam, stack, at.add(0.02, 0.011, -0.015), ItemPlacer.lying(yaw + 25, 0), size, light);
        }
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        if (gridEditor.visible()) return;
        if (target != Target.PLACE) {
            return;
        }
        // where things go: the spot the carried item would take glows, the other empty ones show faintly
        double clock = Scene.clock();
        for (int i = 0; i < 3; i++) {
            boolean dest = i == placeSlot;
            if (!dest && !slot(i).isEmpty()) {
                continue;
            }
            float p = dest ? (float) (0.2 + 0.07 * Math.sin(clock * 5)) : 0.05f;
            EnchantFx.disc(pose, buffers, camera, restPoint(i).add(0, 0.003, 0), SIZES[i] * 0.55 * surfaceScale(i), 0, EnchantFx.GLOW, p, p * 0.8f, p * 0.6f);
        }
        if (placeSlot < 0 && aim != null) {
            float p = (float) (0.2 + 0.06 * Math.sin(clock * 5));
            EnchantFx.disc(pose, buffers, camera, aim.add(0, 0.004, 0), 0.12, 0, EnchantFx.GLOW, p, p * 0.2f, p * 0.2f);
        }
    }

    // ---- what others see -----------------------------------------------------------------------------

    static void drawContents(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction side, List<ItemStack> items, int light) {
        Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            drawLaid(pose, buffers, cam, items.get(i), slotPoint(top, side, i), side, i, (float) SurfaceLayouts.current(StationKind.SMITHING).region(i).itemScale(), light, (float) SurfaceLayouts.current(StationKind.SMITHING).region(i).rotation());
        }
    }

    /** Someone else smiths here: a glow and a breath of steam off the gear. */
    static void onlookerFx(BlockPos pos, Direction side, double age, double dt) {
        Vec3 gear = slotPoint(new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5), side, 1);
        if (age >= 0 && age < dt * 1.5) {
            Fx.motes(gear.add(0, 0.04, 0), 0.1, 14, 1f, 0.6f, 0.25f, 0.25);
        }
        if (age < 0.8) {
            Fx.steam(gear.add(0, 0.03, 0), 0.07, (int) Math.round(dt * 50 + Fx.RANDOM.nextDouble()));
        }
    }

    // ---- words ---------------------------------------------------------------------------------------

    private static final String[] SLOT_KEYS = {"template", "base", "addition"};

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] mouse = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(mouse[0], mouse[1]);
        if (editorTip != null || gridEditor.editing()) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        if (target == Target.PLACE) {
            return placeSlot < 0 ? List.of(Component.translatable("cinematic.smithing.refuse").withStyle(ChatFormatting.RED))
                    : List.of(Component.translatable("cinematic.smithing.slot." + SLOT_KEYS[placeSlot]).withStyle(ChatFormatting.GRAY));
        }
        if (target != Target.ITEM) {
            return null;
        }
        List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, slot(hoverSlot)));
        lines.add(Component.empty());
        ItemStack result = slot(3);
        if (!result.isEmpty()) {
            lines.add(Component.translatable("cinematic.smithing.result", result.getHoverName()).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("cinematic.smithing.smith").withStyle(ChatFormatting.GOLD));
        } else {
            for (int i = 0; i < 3; i++) {
                if (slot(i).isEmpty()) {
                    lines.add(Component.translatable("cinematic.smithing.missing." + SLOT_KEYS[i]).withStyle(ChatFormatting.DARK_GRAY));
                }
            }
            lines.add(Component.translatable("cinematic.smithing.take").withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    @Override
    public Component hint() {
        if (gridEditor.editing()) return SurfaceEditor.text("hint");
        return Component.translatable("cinematic.hint.smithing");
    }
}
