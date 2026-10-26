package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The anvil scene. The player stands at the long side of the anvil. The item is laid along the face; whatever
 * goes into it (a book, a material, a second tool) is laid on top of it. Touching the pile forges it: the right
 * hand drops out of frame and comes back with a hammer, three blows ring out in showers of sparks, with each one
 * the top piece sinks further into the item until they are one, and the new item rises off the anvil into the
 * hand. The item's name hangs over it; touching the name lets the player type a new one. Underneath is the
 * vanilla anvil menu, so costs, repairs and the anvil wearing out are vanilla's.
 */
public final class AnvilStation extends MenuStation<AnvilMenu> implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    static final double HAND_Y = 0.062;
    static final double ITEM_REACH = 0.075;
    static final float MAIN_SIZE = 0.4f;
    static final float ADDITION_SIZE = 0.3f;
    /** Turns a tool's blade (a diagonal of the sprite) along the anvil. */
    static final float MAIN_TURN = 45f;
    static final float TEXT_SCALE = 0.006f;

    public static final double FORGE_LENGTH = 2.05;
    /** The three blows; the first is the beat: the server forges then. */
    static final double[] BLOWS = {0.7, 1.02, 1.3};
    static final double SWING = 0.09;
    /** The hammer is put away, the hand comes back for the new item. */
    static final double PUT_AWAY = BLOWS[2] + 0.32;

    public enum Target { NONE, SURFACE, STACK, NAME }

    public final Vec3 top;
    public Target target = Target.NONE;
    private int layoutSlot = -1;

    // the move, as it was when it began
    private ItemStack mainBefore = ItemStack.EMPTY;
    private ItemStack additionBefore = ItemStack.EMPTY;
    private ItemStack after = ItemStack.EMPTY;
    private boolean toInventory;
    private int blowsDone;

    /** The item last seen in the first slot: a new one brings its own name into the name plate. */
    private ItemStack lastMain = ItemStack.EMPTY;
    private double renamedAt = -10;

    public AnvilStation(BlockPos pos, Direction side) {
        super(pos, side, AnvilMenu.class, 2);
        top = topOf(pos);
    }

    static Vec3 topOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    @Override
    public StationKind kind() {
        return StationKind.ANVIL;
    }

    @Override
    public Vec3 center() {
        return top;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.METAL_STEP;
    }

    // ---- layout --------------------------------------------------------------------------------------

    static Vec3 rightOf(Direction side) {
        return Vec3.atLowerCornerOf(side.getOpposite().getClockWise().getUnitVec3i());
    }

    static Vec3 mainPoint(Vec3 top, Direction side) {
        return RemainingLayouts.point(StationKind.ANVIL, top, side, 0);
    }

    /** What goes into the item lies on top of it, a little to the right and askew. */
    static Vec3 additionPoint(Vec3 top, Direction side) {
        return RemainingLayouts.point(StationKind.ANVIL, top, side, 1);
    }

    @Override
    protected Vec3 restPoint(int slot) {
        Vec3 at = slot == 0 ? mainPoint(top, side) : additionPoint(top, side);
        return at.add(right().scale(denyShake()));
    }

    Vec3 namePoint() {
        return layoutPoint(2);
    }

    private Vec3 costPoint() {
        return namePoint().add(side().scale(-0.06)).add(0, 0.1, 0);
    }

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
        Vec3 eye = top.add(side().scale(0.66)).add(0, 0.7, 0);
        Vec3 look = top.add(side().scale(-0.06)).add(0, 0.12, 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, 0);
    }

    @Override
    public float bodyPitch() {
        return 45f;
    }

    /** Hands on the near edge of the face, either side of the work. */
    @Override
    public Vec3 rest(boolean right) {
        return top.add(side().scale(0.24)).add(right().scale(right ? 0.3 : -0.3)).add(0, HAND_Y, 0);
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || menu() == null || moving()) {
            return;
        }
        ItemStack carried = Hands.carried();
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            int picked = layoutPick();
            layoutSlot = picked;
            if (picked == 2 && carried.isEmpty() && !slot(0).isEmpty()) { target = Target.NAME; aim = namePoint(); }
            else if (picked >= 0 && picked < 2) {
                target = carried.isEmpty() ? !slot(picked).isEmpty() ? Target.STACK : Target.NONE : Target.SURFACE;
                aim = layoutPoint(picked);
            }
            return;
        }
        double[] mouse = Picking.mouseGui();
        boolean pile = !slot(0).isEmpty() || !slot(1).isEmpty();
        if (carried.isEmpty() && !slot(0).isEmpty() && overName(mouse)) {
            target = Target.NAME;
            aim = namePoint().add(0, -0.08, 0);
            return;
        }
        if (carried.isEmpty() && pile && GrindstoneStation.under(mouse, slot(0).isEmpty() ? restPoint(1) : restPoint(0), 0.16)) {
            target = Target.STACK;
            aim = slot(1).isEmpty() ? restPoint(0) : restPoint(1);
            return;
        }
        Vec3 hit = Picking.cursorOnPlane(top.y);
        if (hit == null || carried.isEmpty()) {
            return;
        }
        Vec3 rel = hit.subtract(top);
        if (Math.abs(rel.dot(right())) < 0.52 && Math.abs(rel.dot(side())) < 0.36) {
            target = Target.SURFACE;
            aim = slot(0).isEmpty() ? restPoint(0) : restPoint(1);
        }
    }

    private boolean overName(double[] mouse) {
        Font font = Minecraft.getInstance().font;
        double half = font.width(nameText()) / 2.0 * TEXT_SCALE + 0.03;
        double[] a = Picking.toGui(namePoint().add(right().scale(-half)));
        double[] b = Picking.toGui(namePoint().add(right().scale(half)));
        double[] c = Picking.toGui(namePoint());
        double[] d = Picking.toGui(namePoint().add(0, 0.05, 0));
        if (a == null || b == null || c == null || d == null) {
            return false;
        }
        double h = Math.max(6, Math.abs(c[1] - d[1]));
        return mouse[0] > Math.min(a[0], b[0]) && mouse[0] < Math.max(a[0], b[0]) && Math.abs(mouse[1] - c[1]) < h;
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        layoutSlot = -1;
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

    private int cost() {
        AnvilMenu menu = menu();
        return menu == null ? 0 : menu.getCost();
    }

    private boolean affordable() {
        // The result slot owns the pickup rule. Other anvil mods may allow valid work at cost 0.
        AnvilMenu menu = menu();
        LocalPlayer player = Minecraft.getInstance().player;
        return menu != null && player != null && menu.getSlot(menu.getResultSlot()).mayPickup(player);
    }

    /** Both pieces are there but vanilla refuses: the work would cost 40 levels or more. */
    private boolean tooExpensive() {
        LocalPlayer player = Minecraft.getInstance().player;
        return slot(2).isEmpty() && cost() >= 40 && player != null && !player.getAbilities().instabuild;
    }

    @Override
    protected void act(int button, boolean shift) {
        switch (target) {
            case SURFACE -> {
                // the item first; then what goes into it, on top (a different one there is swapped into the hand)
                int slot = layoutSlot >= 0 && layoutSlot < 2 ? layoutSlot : slot(0).isEmpty() ? 0 : 1;
                slotClick(slot, button, false);
                Scene.sound(SoundEvents.CHAIN_PLACE, 0.6f, slot == 0 ? 0.9f : 1.2f);
            }
            case STACK -> {
                if (button == 0 && !slot(2).isEmpty()) {
                    if (affordable()) {
                        forge(shift);
                    } else {
                        deny();
                    }
                } else {
                    slotClick(layoutSlot >= 0 && layoutSlot < 2 ? layoutSlot : slot(1).isEmpty() ? 0 : 1, 0, shift);
                }
            }
            case NAME -> {
                Hands.tap();
                SceneScreen.edit(nameText().getString(), AnvilMenu.MAX_NAME_LENGTH, this::rename);
            }
            default -> {}
        }
    }

    /** The player typed a new name for the item. */
    public void rename(String name) {
        if (sendName(name)) {
            renamedAt = Scene.clock();
            Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.6f, 1.1f);
        }
    }

    /** Sends a name as the anvil window's text field would: its own name means "keep it as it is". */
    private boolean sendName(String name) {
        AnvilMenu menu = menu();
        Minecraft mc = Minecraft.getInstance();
        ItemStack item = slot(0);
        if (menu == null || item.isEmpty() || mc.player == null) {
            return false;
        }
        String value = !item.has(DataComponents.CUSTOM_NAME) && name.equals(item.getHoverName().getString()) ? "" : name;
        if (!menu.setItemName(value)) {
            return false;
        }
        mc.player.connection.send(new ServerboundRenameItemPacket(value));
        return true;
    }

    /** The name over the item: the new one once the anvil would give it one. */
    private Component nameText() {
        ItemStack result = slot(2);
        return result.isEmpty() ? slot(0).getHoverName() : result.getHoverName();
    }

    private boolean renaming() {
        ItemStack result = slot(2);
        return !result.isEmpty() && !result.getHoverName().getString().equals(slot(0).getHoverName().getString());
    }

    private void forge(boolean shift) {
        mainBefore = slot(0).copy();
        additionBefore = slot(1).copy();
        after = slot(2).copy();
        toInventory = shift;
        blowsDone = 0;
        startMove(FORGE_LENGTH, BLOWS[0], () -> {
            AnvilMenu menu = menu();
            Minecraft mc = Minecraft.getInstance();
            if (slot(2).isEmpty() || !affordable()) {
                cancelMove();
                return;
            }
            mc.gameMode.handleContainerInput(menu.containerId, 2, 0, toInventory ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        });
        showMove(shift);
        Scene.sound(SoundEvents.ARMOR_EQUIP_IRON.value(), 0.5f, 0.9f);
    }

    @Override
    public void tick() {
        // a new item on the anvil brings its own name, as the window's text field does
        ItemStack main = slot(0);
        if (!ItemStack.matches(main, lastMain)) {
            lastMain = main.copy();
            if (!main.isEmpty() && !moving()) {
                sendName(main.getHoverName().getString());
            }
        }
    }

    // ---- the move ------------------------------------------------------------------------------------

    private record Swing(Vec3 grip, double angle) {}

    /** The blow lands on the middle of the pile. */
    private Vec3 anvilPoint() {
        return anvilPoint(top, side);
    }

    static Vec3 anvilPoint(Vec3 top, Direction side) {
        return mainPoint(top, side).add(rightOf(side).scale(0.03));
    }

    /** Horizontal direction the hammer swings in: from the near right towards the work. */
    private Vec3 swingDirection() {
        return side().scale(-0.3).add(right().scale(-0.12)).normalize();
    }

    private Vec3 handleDirection(double angle) {
        double a = Math.toRadians(angle);
        return swingDirection().scale(Math.cos(a)).add(0, Math.sin(a), 0);
    }

    private Swing impact() {
        double angle = -12;
        // Land the imported head's striking edge on the work instead of burying its larger head in the anvil.
        Vec3 grip = anvilPoint().subtract(handleDirection(angle).scale(HammerModel.STRIKE_ALONG))
                .add(headAxis(angle).scale(HammerModel.STRIKE_ACROSS));
        return new Swing(grip, angle);
    }

    private Swing raised() {
        Swing hit = impact();
        return new Swing(hit.grip().add(0, 0.16, 0).add(side().scale(0.05)), 95);
    }

    private Swing rebound() {
        Swing hit = impact();
        return new Swing(hit.grip().add(0, 0.05, 0).add(side().scale(0.02)), 28);
    }

    private Swing hidden() {
        CameraRig.Pose cam = CameraRig.last() != null ? CameraRig.last() : lean();
        return new Swing(Hands.hidden(true, cam.pos(), cam.yaw()), 80);
    }

    private static Swing mix(Swing a, Swing b, double k) {
        return new Swing(Anim.lerp(a.grip(), b.grip(), k), a.angle() + (b.angle() - a.angle()) * k);
    }

    /** Where the hand and hammer are at time {@code t} of the move; null when the hammer is not out. */
    @Nullable
    private Swing hammer(double t) {
        if (t < 0.3 || t > PUT_AWAY) {
            return null;
        }
        if (t < 0.55) {
            return mix(hidden(), raised(), Anim.outCubic(Anim.window(t, 0.3, 0.55)));
        }
        for (int k = 0; k < BLOWS.length; k++) {
            double blow = BLOWS[k];
            double windFrom = k == 0 ? 0.55 : BLOWS[k - 1] + 0.12;
            if (t < blow - SWING) {
                Swing from = k == 0 ? raised() : rebound();
                return mix(from, raised(), Anim.inOutSine(Anim.window(t, windFrom, blow - SWING)));
            }
            if (t < blow) {
                return mix(raised(), impact(), Anim.inCubic(Anim.window(t, blow - SWING, blow)));
            }
            if (t < blow + 0.12 || k == BLOWS.length - 1) {
                if (k == BLOWS.length - 1 && t >= blow + 0.12) {
                    return mix(rebound(), hidden(), Anim.inCubic(Anim.window(t, blow + 0.12, PUT_AWAY)));
                }
                return mix(impact(), rebound(), Anim.outCubic(Anim.window(t, blow, blow + 0.12)));
            }
        }
        return null;
    }

    /** The new item rises off the anvil, then goes to the hand (or the bag). */
    private Vec3 resultPos(double t, Camera camera) {
        Vec3 lifted = mainPoint(top, side).add(0, 0.13, 0);
        Vec3 at = Anim.lerp(mainPoint(top, side), lifted, Anim.inOutCubic(Anim.window(t, BLOWS[2] + 0.18, PUT_AWAY + 0.08)));
        if (toInventory) {
            Vec3 drawer = camera.position().add(0, 0.55, 0).add(Scene.yawForward(camera.yRot()).scale(0.6));
            return Anim.lerp(at, drawer, Anim.inCubic(Anim.window(t, PUT_AWAY + 0.12, FORGE_LENGTH)));
        }
        Vec3 held = heldItemPoint(Hands.position(true), Hands.shoulder(true, camera.position(), camera.yRot()));
        return Anim.lerp(at, held, Anim.inOutCubic(Anim.window(t, PUT_AWAY + 0.12, FORGE_LENGTH)));
    }

    @Nullable
    @Override
    public Vec3[] handsOverride() {
        double t = moveTime();
        if (t < 0) {
            return null;
        }
        Vec3 right;
        Swing swing = hammer(t);
        if (t < 0.3) {
            right = Anim.lerp(rest(true), hidden().grip(), Anim.inCubic(Anim.window(t, 0, 0.3)));
        } else if (swing != null) {
            right = swing.grip();
        } else {
            // empty-handed again: up from below to the new item, then settle with it
            Vec3 lifted = mainPoint(top, side).add(0, 0.13 + HAND_Y, 0).add(side().scale(ITEM_REACH));
            Vec3 up = Anim.lerp(hidden().grip(), lifted, Anim.outCubic(Anim.window(t, PUT_AWAY, PUT_AWAY + 0.16)));
            right = Anim.lerp(up, rest(true).add(0, toInventory ? 0 : 0.05, 0), Anim.inOutCubic(Anim.window(t, PUT_AWAY + 0.16, FORGE_LENGTH)));
        }
        // the other hand steadies the work at its left end
        double hold = Anim.inOutCubic(Anim.window(t, 0.05, 0.4)) * (1 - Anim.inOutCubic(Anim.window(t, BLOWS[2] + 0.1, BLOWS[2] + 0.4)));
        Vec3 steady = mainPoint(top, side).add(layoutAxis(0, true).scale(-0.2 * geometryScale(0)))
                .add(layoutAxis(0, false).scale(0.05)).add(0, HAND_Y + 0.015, 0);
        return new Vec3[]{right, Anim.lerp(rest(false), steady, hold)};
    }

    @Override
    public double handsStiffness() {
        return moving() ? 55 : 20;
    }

    private static double sinceBlow(double t) {
        double since = Double.MAX_VALUE;
        for (double blow : BLOWS) {
            if (t >= blow) {
                since = t - blow;
            }
        }
        return since;
    }

    @Override
    public double cameraKick() {
        double t = moveTime();
        return t < 0 ? 0 : 0.5 * Math.exp(-sinceBlow(t) * 13);
    }

    @Override
    public double flash() {
        double t = moveTime();
        return t < 0 ? 0 : 0.11 * Math.exp(-sinceBlow(t) * 16);
    }

    @Override
    public int flashColor() {
        return 0xFFE6B8;
    }

    @Override
    void frame() {
        super.frame();
        double t = moveTime();
        if (t < 0 || blowsDone >= BLOWS.length || t < BLOWS[blowsDone]) {
            return;
        }
        blow(top, side, blowsDone++, additionBefore, after);
    }

    /**
     * Blow {@code k} lands: a shower of sparks, its ring (the first rings from the server, with the anvil's own wear and
     * tear), chips of light off a book going in, and after the last the new item's glow.
     */
    static void blow(Vec3 top, Direction side, int k, ItemStack addition, ItemStack after) {
        Vec3 at = anvilPoint(top, side).add(0, 0.03, 0);
        Fx.blowSparks(at, 34, 2.4);
        Fx.hotSparks(at, Vec3.atLowerCornerOf(side.getUnitVec3i()).scale(-1).add(0, 0.7, 0), 0.5, 12, 2.6);
        if (k > 0) {
            ring(at, SoundEvents.ANVIL_USE, 0.45f, 1.05f + k * 0.12f);
        }
        if (addition.is(Items.ENCHANTED_BOOK)) {
            for (int i = 0; i < 10; i++) {
                EnchantFx.spark(at.add(0, 0.02, 0), new Vec3(Fx.RANDOM.nextGaussian() * 0.2, 0.3 + Fx.RANDOM.nextDouble() * 0.3,
                        Fx.RANDOM.nextGaussian() * 0.2), 0.7 + Fx.RANDOM.nextDouble() * 0.5, 0.012f);
            }
        }
        if (k == BLOWS.length - 1) {
            Vec3 at2 = mainPoint(top, side).add(0, 0.05, 0);
            if (after.isEnchanted()) {
                Fx.motes(at2, 0.12, 24, 0.8f, 0.5f, 1f, 0.3);
            }
            Fx.motes(at2, 0.1, 10, 1f, 0.75f, 0.4f, 0.2);
            ring(at2, SoundEvents.AMETHYST_BLOCK_CHIME, 0.7f, 0.8f);
        }
    }

    private static void ring(Vec3 at, SoundEvent sound, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.BLOCKS, volume, pitch, false);
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
            drawPile(pose, buffers, cam, top, side, slot(0), slot(1), denyShake(), 1, light);
            return;
        }
        if (t < BLOWS[2] + 0.04) {
            drawForging(pose, buffers, camera, top, side, mainBefore, additionBefore, t, light);
        } else {
            float size = toInventory ? (float) (MAIN_SIZE * (1 - 0.85 * Anim.inCubic(Anim.window(t, PUT_AWAY + 0.12, FORGE_LENGTH))))
                    : (float) (MAIN_SIZE + (0.205 - MAIN_SIZE) * Anim.window(t, PUT_AWAY + 0.1, FORGE_LENGTH));
            drawForged(pose, buffers, camera, side, after, resultPos(t, camera), t, size, light);
        }
        Swing swing = hammer(t);
        if (swing != null) {
            drawHammer(pose, buffers, cam, Hands.position(true), swing.angle(), light);
        }
    }

    /** The pile under the blows: what goes into the item sinks further into it with every blow, glowing; after the last they are one. */
    static void drawForging(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 top, Direction side, ItemStack main, ItemStack addition,
            double t, int light) {
        double sunk = 0;
        for (double blow : BLOWS) {
            sunk += Anim.smoother(Anim.window(t, blow, blow + 0.07)) / BLOWS.length;
        }
        drawPile(pose, buffers, camera.position(), top, side, main, addition, 0, 1 - sunk, light);
        double heat = sunk + 0.25 * Math.exp(-sinceBlow(t) * 10);
        if (!addition.isEmpty() && heat > 0.02) {
            float g = (float) (0.22 * heat);
            boolean book = addition.is(Items.ENCHANTED_BOOK);
            EnchantFx.billboard(pose, buffers, camera, anvilPoint(top, side).add(0, 0.03, 0), 0.16 + 0.05 * heat, Scene.clock(), EnchantFx.GLOW,
                    book ? 0.7f * g : g, book ? 0.4f * g : 0.6f * g, book ? g : 0.25f * g);
        }
    }

    /** The new item off the anvil, at {@code at}: turned over as it rises, glowing as it cools. */
    static void drawForged(PoseStack pose, SceneBuffers buffers, Camera camera, Direction side, ItemStack after, Vec3 at, double t, float size,
            int light) {
        size *= geometryScale(0);
        double turn = 200 * Anim.inOutCubic(Anim.window(t, BLOWS[2] + 0.1, PUT_AWAY + 0.1));
        float tilt = (float) (35 * Anim.hump(Anim.window(t, BLOWS[2] + 0.1, FORGE_LENGTH)));
        ItemPlacer.drawOriented(pose, buffers, camera.position(), after, at.add(0, 0.004, 0),
                ItemPlacer.lying((float) (ItemPlacer.awayYaw(side) + MAIN_TURN + RemainingLayouts.rotation(StationKind.ANVIL, 0) + turn), tilt), size, light);
        double glow = 1 - Anim.window(t, BLOWS[2], PUT_AWAY + 0.2);
        if (glow > 0.01) {
            float g = (float) (0.4 * glow);
            EnchantFx.billboard(pose, buffers, camera, at, 0.2, Scene.clock(), EnchantFx.GLOW, g, 0.8f * g, 0.5f * g);
        }
    }

    /** The item along the face and what goes into it on top; {@code addition} scales the top piece (sinking in). */
    static void drawPile(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 top, Direction side, ItemStack main, ItemStack addition,
            double shake, double additionScale, int light) {
        Vec3 shift = rightOf(side).scale(shake);
        float yaw = ItemPlacer.awayYaw(side) + MAIN_TURN + RemainingLayouts.rotation(StationKind.ANVIL, 0);
        if (!main.isEmpty()) {
            boolean block = ItemPlacer.isBlock(main);
            float size = (block ? 0.2f : MAIN_SIZE) * geometryScale(0);
            Vec3 at = mainPoint(top, side).add(shift).add(0, block ? size / 2 : 0.004, 0);
            ItemPlacer.drawOriented(pose, buffers, cam, main, at, ItemPlacer.lying(yaw, 0), size, light);
        }
        if (!addition.isEmpty() && additionScale > 0.01) {
            boolean block = ItemPlacer.isBlock(addition);
            float size = (float) ((block ? 0.16 : ADDITION_SIZE) * (0.35 + 0.65 * additionScale) * geometryScale(1));
            Vec3 home = main.isEmpty() && SpatialLayouts.current(StationKind.ANVIL).equals(SpatialAppearance.VANILLA)
                    ? mainPoint(top, side) : additionPoint(top, side);
            Vec3 at = Anim.lerp(mainPoint(top, side), home, additionScale).add(shift)
                    .add(0, (block ? size / 2 : 0.006) - 0.01 * (1 - additionScale), 0);
            ItemPlacer.drawOriented(pose, buffers, cam, addition, at, ItemPlacer.lying(yaw + 70 + RemainingLayouts.rotation(StationKind.ANVIL, 1) - RemainingLayouts.rotation(StationKind.ANVIL, 0), 0), size, light);
            if (addition.getCount() > 1) {
                ItemPlacer.drawOriented(pose, buffers, cam, addition, at.add(rightOf(side).scale(0.03)).add(0, 0.008, 0),
                        ItemPlacer.lying(yaw + 95 + RemainingLayouts.rotation(StationKind.ANVIL, 1) - RemainingLayouts.rotation(StationKind.ANVIL, 0), 0), size, light);
            }
        }
    }

    private static float geometryScale(int slot) { return RemainingLayouts.scale(StationKind.ANVIL, slot); }

    private Vec3 headAxis(double angle) {
        double a = Math.toRadians(angle);
        return new Vec3(0, Math.cos(a), 0).subtract(swingDirection().scale(Math.sin(a)));
    }

    /** The imported hammer follows the same grip and three-blow timeline as the working hand. */
    private void drawHammer(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 grip, double angle, int light) {
        HammerModel.render(pose, buffers, cam, grip, handleDirection(angle), headAxis(angle), light);
    }

    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.mainCamera();
        double clock = Scene.clock();
        if (target == Target.SURFACE && aim != null) {
            float p = (float) (0.16 + 0.06 * Math.sin(clock * 5));
            EnchantFx.disc(pose, buffers, camera, aim.add(0, 0.004, 0), 0.14, 0, EnchantFx.GLOW, p, p * 0.75f, p * 0.45f);
        }
        if (moving() || slot(0).isEmpty()) {
            return;
        }
        // the name plate over the item and, once there is work to do, its price in levels
        boolean renamed = renaming();
        double pulse = Anim.hump(Anim.window(clock - renamedAt, 0, 0.5));
        int nameColor = renamed ? 0xFFFFE07A : 0xFFF2EAD8;
        drawText(pose, buffers, cam, nameText(), namePoint(), TEXT_SCALE * (float) (1 + 0.15 * pulse), nameColor,
                target == Target.NAME ? 0.55f : 0.35f);
        if (target == Target.NAME) {
            drawText(pose, buffers, cam, Component.literal("✎"), namePoint().add(right().scale(-(mc.font.width(nameText()) / 2.0 * TEXT_SCALE + 0.035))),
                    TEXT_SCALE, 0xFFFFE07A, 0);
        }
        int cost = cost();
        if (!slot(2).isEmpty() && cost > 0) {
            boolean ok = affordable();
            Vec3 at = costPoint();
            float g = (float) (0.22 + 0.06 * Math.sin(clock * 3));
            EnchantFx.billboard(pose, buffers, cam, at, 0.06, clock * 0.5, EnchantFx.GLOW, ok ? 0.45f * g : g, ok ? g : 0.25f * g, ok ? 0.2f * g : 0.2f * g);
            drawText(pose, buffers, cam, Component.literal(String.valueOf(cost)), at, 0.012f, ok ? 0xFF9CFF5A : 0xFFFF6B6B, 0);
        } else if (tooExpensive()) {
            drawText(pose, buffers, cam, Component.translatable("container.repair.expensive"), costPoint(), TEXT_SCALE, 0xFFFF6B6B, 0.35f);
        }
    }

    private static void drawText(PoseStack pose, SceneBuffers buffers, Camera camera, Component text, Vec3 at, float scale, int color,
            float backdrop) {
        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(camera.rotation()));
        pose.mulPose(new org.joml.Matrix4f().rotation(com.mojang.math.Axis.ZP.rotationDegrees((float) SpatialLayouts.current(StationKind.ANVIL).rotation(2))));
        scale *= geometryScale(2);
        pose.scale(scale, -scale, scale);
        int background = backdrop > 0 ? (int) (backdrop * 255) << 24 : 0;
        SceneText.draw(font, text, -font.width(text) / 2f, -4f, color, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, background,
                LightCoordsUtil.FULL_BRIGHT);
        pose.popPose();
    }

    // ---- what others see -----------------------------------------------------------------------------

    static void drawContents(PoseStack pose, SceneBuffers buffers, Vec3 cam, BlockPos pos, Direction side, List<ItemStack> items, int light) {
        Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        drawPile(pose, buffers, cam, top, side, items.isEmpty() ? ItemStack.EMPTY : items.get(0), items.size() < 2 ? ItemStack.EMPTY : items.get(1),
                0, 1, light);
    }

    /**
     * Someone else forges here, {@code t} seconds in: the pile under the blows (the hammer is in their hand,
     * {@link PropLayer}), then the new item rising off the anvil and away to them.
     */
    static void drawOnlooker(PoseStack pose, SceneBuffers buffers, Camera camera, BlockPos pos, ClientMenus.Entry entry, double t, int light) {
        Vec3 top = topOf(pos);
        if (t < BLOWS[2] + 0.04) {
            drawForging(pose, buffers, camera, top, entry.side, entry.moved(0), entry.moved(1), t, light);
            return;
        }
        Vec3 lifted = mainPoint(top, entry.side).add(0, 0.13, 0);
        Vec3 at = Anim.lerp(mainPoint(top, entry.side), lifted, Anim.inOutCubic(Anim.window(t, BLOWS[2] + 0.18, PUT_AWAY + 0.08)));
        double away = Anim.inCubic(Anim.window(t, PUT_AWAY + 0.12, FORGE_LENGTH));
        at = Anim.lerp(at, entry.taker(lifted.add(Vec3.atLowerCornerOf(entry.side.getUnitVec3i()).scale(0.8))), away);
        drawForged(pose, buffers, camera, entry.side, entry.moveResult, at, t, (float) (MAIN_SIZE * (1 - 0.8 * away)), light);
    }

    /** Someone else forges here: the sparks and the ring of each blow as it lands. */
    static void onlookerFx(BlockPos pos, ClientMenus.Entry entry, double t, double dt) {
        for (int k = 0; k < BLOWS.length; k++) {
            if (t - dt < BLOWS[k] && t >= BLOWS[k]) {
                blow(topOf(pos), entry.side, k, entry.moved(1), entry.moveResult);
            }
        }
    }

    // ---- the swing seen from outside ---------------------------------------------------------------------

    /** The smith's right arm, as others see it: the model's pitch at rest, at the belt (the hammer), raised, striking, bouncing back. */
    private static final float ARM_REST = -0.98f, ARM_HIP = 0.2f, ARM_RAISED = -2.75f, ARM_IMPACT = -1.26f, ARM_REBOUND = -1.6f;

    /**
     * The pitch of the right arm swinging the hammer at time {@code t} of the move, as others see it: the same blows on
     * the same timeline as in the scene. At {@link #ARM_IMPACT} the hammer's head lies flat on the work.
     */
    public static float armPitch(double t) {
        if (t < 0.3) {
            return Mth.lerp((float) Anim.smoother(t / 0.3), ARM_REST, ARM_HIP);
        }
        if (t < 0.55) {
            return Mth.lerp((float) Anim.outCubic(Anim.window(t, 0.3, 0.55)), ARM_HIP, ARM_RAISED);
        }
        if (t >= PUT_AWAY) {
            return Mth.lerp((float) Anim.inOutCubic(Anim.window(t, PUT_AWAY, FORGE_LENGTH)), ARM_HIP, ARM_REST);
        }
        for (int k = 0; k < BLOWS.length; k++) {
            double blow = BLOWS[k];
            double windFrom = k == 0 ? 0.55 : BLOWS[k - 1] + 0.12;
            if (t < blow - SWING) {
                return Mth.lerp((float) Anim.inOutSine(Anim.window(t, windFrom, blow - SWING)), k == 0 ? ARM_RAISED : ARM_REBOUND, ARM_RAISED);
            }
            if (t < blow) {
                return Mth.lerp((float) Anim.inCubic(Anim.window(t, blow - SWING, blow)), ARM_RAISED, ARM_IMPACT);
            }
            if (t < blow + 0.12) {
                return Mth.lerp((float) Anim.outCubic(Anim.window(t, blow, blow + 0.12)), ARM_IMPACT, ARM_REBOUND);
            }
        }
        return Mth.lerp((float) Anim.inCubic(Anim.window(t, BLOWS[2] + 0.12, PUT_AWAY)), ARM_REBOUND, ARM_HIP);
    }

    /** How much of the hammer is in the hand at time {@code t}: it comes off the belt and goes back, 0..1. */
    public static double hammerShown(double t) {
        return Anim.smoother(Anim.window(t, 0.26, 0.36)) * (1 - Anim.smoother(Anim.window(t, PUT_AWAY - 0.1, PUT_AWAY)));
    }

    // ---- words ---------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] pointer = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(pointer[0], pointer[1]);
        if (editorTip != null) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        switch (target) {
            case NAME -> {
                return List.of(Component.translatable("cinematic.anvil.rename").withStyle(ChatFormatting.GRAY));
            }
            case STACK -> {
                ItemStack result = slot(2);
                List<Component> lines = new ArrayList<>();
                if (!result.isEmpty()) {
                    lines.addAll(Screen.getTooltipFromItem(mc, result));
                    lines.add(Component.empty());
                    lines.add(Component.translatable("container.repair.cost", cost()).withStyle(affordable() ? ChatFormatting.GREEN : ChatFormatting.RED));
                    lines.add(Component.translatable("cinematic.anvil.forge").withStyle(ChatFormatting.GOLD));
                    return lines;
                }
                lines.addAll(Screen.getTooltipFromItem(mc, slot(1).isEmpty() ? slot(0) : slot(1)));
                if (tooExpensive()) {
                    lines.add(Component.translatable("container.repair.expensive").withStyle(ChatFormatting.RED));
                } else if (slot(1).isEmpty()) {
                    lines.add(Component.translatable("cinematic.anvil.add").withStyle(ChatFormatting.GRAY));
                } else {
                    lines.add(Component.translatable("cinematic.anvil.no_match").withStyle(ChatFormatting.RED));
                }
                lines.add(Component.translatable("cinematic.anvil.take").withStyle(ChatFormatting.GRAY));
                return lines;
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.anvil");
    }

    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public java.util.List<Vec3> workSamples() { return RemainingLayouts.samples(this, super.workSamples()); }
    @Override public float layoutHeldScale() { return layoutSlot >= 0 && layoutSlot < 2 ? geometry().scale(layoutSlot) : geometry().scale(0); }
}
