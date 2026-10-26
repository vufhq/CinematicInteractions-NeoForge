package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.dyslexic.cinematic.Cinematic;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The beacon scene. The powers the beacon can give hang in the air either side of its beam, each a ring of light round
 * its icon on a dark glass disc, laid out as in the beacon window: the primary ones on the left, tier above tier (those
 * the pyramid is too small for stay dim with the level they need), the second power on the right. A chosen power is
 * tied to the beam by a thread of light. Payment is laid on top of the beacon and floats in front of the beam; a click
 * on it with a power chosen lights the beacon: the hands are raised, the payment sinks into the glass, then a ring of
 * light runs out over the pyramid, rays burst from the beacon, sparks of the power's colour fly out every way and the
 * beam flares up into the sky while the camera draws back to watch. Underneath is the vanilla beacon menu; lighting
 * it sends the same packet as the window's confirm button.
 */
public final class BeaconStation extends MenuStation<BeaconMenu> implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    static final double HAND_Y = 0.06;
    static final double ITEM_REACH = 0.07;
    static final double LIGHT_LENGTH = 3.0;
    /** When the payment has sunk in and the light breaks out. */
    static final double BURST = 0.62;
    public static final Identifier RING = Cinematic.id("textures/fx/ring.png");
    public static final Identifier RAY = Cinematic.id("textures/fx/ray.png");

    public enum Target { NONE, POWER, PAYMENT, TOP }

    /** One power in the crown: the effect, the tier it needs (0..3), whether it is the second power. */
    private record Power(Holder<MobEffect> effect, int tier, boolean secondary, boolean upgrade) {}

    /**
     * A power's icon and its level as they hang in the air this frame: drawn over the frame where they are seen
     * ({@link #renderScreen}), as interface, not in the world. Shader packs light and shade whatever is drawn in the world,
     * and how they treat an icon depends on the pack (some left it nearly black, some nearly invisible); they never touch
     * the interface. The rings and the dark glass round the icons stay in the world.
     */
    private record Icon(TextureAtlasSprite sprite, Vec3 at, double half, float brightness, float alpha, String label, int labelColor, Vec3 labelAt, float rotation) {}

    private final List<Icon> icons = new ArrayList<>();

    private final Vec3 core, top;
    public Target target = Target.NONE;
    public int hoverPower = -1;
    @Nullable
    private Holder<MobEffect> primary, secondary;
    private boolean chosenRead;
    private int lastHover = -1;
    private double burstAt = -10;
    private float[] burstColor = {1f, 0.95f, 0.7f};
    private ItemStack lightingWith = ItemStack.EMPTY;
    private double pull;

    public BeaconStation(BlockPos pos, Direction side) {
        super(pos, side, BeaconMenu.class, 1);
        core = Vec3.atCenterOf(pos);
        top = core.add(0, 0.5, 0);
    }

    @Override
    public StationKind kind() {
        return StationKind.BEACON;
    }

    @Override
    public Vec3 center() {
        return top;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.GLASS_STEP;
    }

    // ---- the menu underneath ---------------------------------------------------------------------------

    private int levels() {
        BeaconMenu menu = menu();
        return menu == null ? 0 : menu.getLevels();
    }

    private ItemStack payment() {
        return slot(0);
    }

    /** The powers in the crown: the five primary ones, then regeneration and the chosen power made stronger. */
    private List<Power> powers() {
        List<Power> list = new ArrayList<>();
        for (int tier = 0; tier < 3; tier++) {
            for (Holder<MobEffect> effect : BeaconBlockEntity.BEACON_EFFECTS.get(tier)) {
                list.add(new Power(effect, tier, false, false));
            }
        }
        for (Holder<MobEffect> effect : BeaconBlockEntity.BEACON_EFFECTS.get(3)) {
            list.add(new Power(effect, 3, true, false));
        }
        if (primary != null) {
            list.add(new Power(primary, 3, true, true));
        }
        return list;
    }

    private boolean open(Power power) {
        return power.tier() < levels();
    }

    private boolean chosen(Power power) {
        return power.secondary() ? power.effect().equals(secondary) && (power.upgrade() == power.effect().equals(primary))
                : power.effect().equals(primary);
    }

    /** Whether this power is what the beacon gives now. */
    private boolean active(Power power) {
        BeaconMenu menu = menu();
        if (menu == null) {
            return false;
        }
        return power.secondary() ? power.effect().equals(menu.getSecondaryEffect()) && (power.upgrade() == power.effect().equals(menu.getPrimaryEffect()))
                : power.effect().equals(menu.getPrimaryEffect());
    }

    private boolean ready() {
        return primary != null && levels() > 0 && !payment().isEmpty();
    }

    // ---- geometry --------------------------------------------------------------------------------------

    /**
     * Where a power hangs: the primary ones left of the beam, two by two, tier I lowest and tier III alone on top; the
     * second power right of the beam, its stronger version above it. All a little in front of the beam.
     */
    Vec3 powerPos(int index) {
        return layoutPoint(index + 1).add(0, 0.012 * Math.sin(Scene.clock() * 1.4 + index * 1.7), 0);
    }

    /** Where the payment floats: over the glass, in front of the beam. */
    Vec3 paymentPos() {
        return layoutPoint(0).add(0, 0.012 * Math.sin(Scene.clock() * 1.9), 0);
    }

    /** The point of the beam level with something, which a thread of light runs to. */
    private Vec3 beamAt(Vec3 point) {
        return new Vec3(core.x, point.y, core.z);
    }

    @Override
    protected Vec3 restPoint(int slot) {
        return paymentPos();
    }

    // ---- staging ---------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(core.x + side.getStepX() * 1.05, player.getY(), core.z + side.getStepZ() * 1.05);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public CameraRig.Pose lean() {
        // facing the beam with the powers either side of it; while the light breaks out the camera draws back and up
        // to look down on the pyramid
        double d = 1.5 + 1.7 * pull, h = 0.62 + 1.5 * pull;
        Vec3 eye = top.add(side().scale(d)).add(0, h, 0);
        Vec3 look = top.add(0, 0.42 - 0.85 * pull, 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, 0);
    }

    @Override
    public double leanFov() {
        return 60 + 8 * pull;
    }

    /** Drawing back to watch the light run over the pyramid, the camera leaves the head for a moment. */
    @Override
    public boolean freeCamera() {
        return pull > 0.01;
    }

    @Override
    public float bodyPitch() {
        return 10f;
    }

    @Override
    public Vec3 rest(boolean right) {
        return top.add(side().scale(0.86)).add(right().scale(right ? 0.32 : -0.32)).add(0, -0.1, 0);
    }

    // ---- cursor ----------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || menu() == null || moving()) {
            return;
        }
        double[] mouse = Picking.mouseGui();
        ItemStack carried = Hands.carried();
        List<Power> list = powers();
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            int picked = layoutPick();
            if (picked > 0 && picked <= list.size() && carried.isEmpty()) { target = Target.POWER; hoverPower = picked - 1; aim = powerPos(hoverPower); }
            else if (picked == 0) { target = payment().isEmpty() ? carried.isEmpty() ? Target.NONE : Target.TOP : Target.PAYMENT; aim = paymentPos(); }
            return;
        }
        if (carried.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                if (GrindstoneStation.under(mouse, powerPos(i), 0.12)) {
                    target = Target.POWER;
                    hoverPower = i;
                    aim = powerPos(i);
                    return;
                }
            }
        }
        if (!payment().isEmpty() && GrindstoneStation.under(mouse, paymentPos(), 0.1)) {
            target = Target.PAYMENT;
            aim = paymentPos();
            return;
        }
        if (!carried.isEmpty() && GrindstoneStation.under(mouse, top.add(0, 0.05, 0), 0.32)) {
            target = Target.TOP;
            aim = paymentPos();
        }
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        hoverPower = -1;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        Vec3 dir = aim.subtract(shoulder).normalize();
        // a power hanging in the air is pointed at from well short of it; the payment is reached for
        double shortOf = target == Target.POWER ? 0.42 : carrying ? 0.05 : 0.1;
        return aim.subtract(dir.scale(shortOf - 0.05 * tap)).add(0, -0.03, 0);
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        Vec3 reach = new Vec3(hand.x - shoulder.x, 0, hand.z - shoulder.z).normalize();
        return hand.add(reach.scale(ITEM_REACH)).add(0, -HAND_Y + 0.02, 0);
    }

    /** Lighting the beacon: both hands rise to it, then the light pushes them back. */
    @Nullable
    @Override
    public Vec3[] handsOverride() {
        double t = moveTime();
        if (t < 0) {
            return null;
        }
        double raise = Anim.inOutSine(Anim.window(t, 0, BURST));
        // the light throws them down out of the frame while the camera draws back, then they come back
        double away = Anim.smoother(Anim.window(t, BURST - 0.05, BURST + 0.25)) * (1 - Anim.smoother(Anim.window(t, 2.45, LIGHT_LENGTH)));
        Vec3 cam = Picking.camera();
        float yaw = CameraRig.last() != null ? CameraRig.last().yaw() : 0;
        Vec3[] hands = new Vec3[2];
        for (int i = 0; i < 2; i++) {
            boolean right = i == 0;
            Vec3 rest = rest(right);
            Vec3 up = rest.add(side().scale(-0.12)).add(right().scale(right ? -0.06 : 0.06)).add(0, 0.16, 0);
            hands[i] = Anim.lerp(Anim.lerp(rest, up, raise * (1 - away)), Hands.hidden(right, cam, yaw), away);
        }
        return hands;
    }

    @Override
    public double handsStiffness() {
        return 22;
    }

    // ---- actions ---------------------------------------------------------------------------------------

    @Override
    protected void act(int button, boolean shift) {
        switch (target) {
            case POWER -> choose(hoverPower);
            case PAYMENT -> {
                if (button == 0 && !shift && Hands.carried().isEmpty() && ready()) {
                    light();
                } else {
                    slotClick(0, 0, false);
                }
            }
            case TOP -> {
                if (!Hands.carried().is(ItemTags.BEACON_PAYMENT_ITEMS)) {
                    deny();
                    return;
                }
                slotClick(0, 1, false);
                Scene.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 1.2f);
            }
            default -> {}
        }
    }

    private void choose(int index) {
        List<Power> list = powers();
        if (index < 0 || index >= list.size()) {
            return;
        }
        Power power = list.get(index);
        if (!open(power)) {
            deny();
            return;
        }
        if (power.secondary()) {
            secondary = power.effect();
        } else {
            boolean upgraded = secondary != null && secondary.equals(primary);
            primary = power.effect();
            if (upgraded) {
                // the "made stronger" choice follows the new primary power
                secondary = primary;
            }
        }
        Scene.sound(SoundEvents.BEACON_POWER_SELECT, 0.35f, 1.6f);
        Scene.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.5f, 1.4f + power.tier() * 0.15f);
        float[] c = color(power.effect());
        Fx.motes(powerPos(index), 0.06, 10, c[0], c[1], c[2], 0.15);
    }

    /** Lights the beacon with the chosen powers: the window's confirm, on the beat of the move. */
    private void light() {
        Holder<MobEffect> first = primary;
        Holder<MobEffect> second = levels() >= 4 ? secondary : null;
        lightingWith = payment().copy();
        burstColor = color(first);
        Scene.sound(SoundEvents.BEACON_AMBIENT, 1f, 1.4f);
        Scene.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.9f, 0.8f);
        startMove(LIGHT_LENGTH, BURST, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundSetBeaconPacket(Optional.ofNullable(first), Optional.ofNullable(second)));
            }
            burstAt = Scene.clock();
            burst(core, burstColor, levels());
            Scene.sound(SoundEvents.BEACON_ACTIVATE, 1f, 1.1f);
            Scene.sound(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 0.6f, 0.8f);
            Scene.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 0.6f);
        });
    }

    /** Light breaking out of a beacon: sparks flung every way, motes shooting up the beam. */
    static void burst(Vec3 core, float[] c, int levels) {
        for (int i = 0; i < 170; i++) {
            double a = Fx.RANDOM.nextDouble() * Math.PI * 2;
            double rise = -0.15 + Fx.RANDOM.nextDouble() * 0.75;
            Vec3 dir = new Vec3(Math.cos(a), rise, Math.sin(a)).normalize();
            double speed = 2.5 + Fx.RANDOM.nextDouble() * 4.5;
            float w = Fx.RANDOM.nextFloat() * 0.6f;
            Fx.spark(core.add(dir.scale(0.3)), dir.scale(speed), 0.55 + Fx.RANDOM.nextDouble() * 0.7, 0.012f + Fx.RANDOM.nextFloat() * 0.01f,
                    c[0] + (1 - c[0]) * w, c[1] + (1 - c[1]) * w, c[2] + (1 - c[2]) * w, 1.2, 0.05);
        }
        for (int i = 0; i < 70; i++) {
            Vec3 from = core.add(Fx.RANDOM.nextGaussian() * 0.12, 0.4, Fx.RANDOM.nextGaussian() * 0.12);
            Fx.spark(from, new Vec3(Fx.RANDOM.nextGaussian() * 0.15, 4 + Fx.RANDOM.nextDouble() * 9, Fx.RANDOM.nextGaussian() * 0.15),
                    1.2 + Fx.RANDOM.nextDouble() * 0.8, 0.02f + Fx.RANDOM.nextFloat() * 0.02f, c[0], c[1], c[2], -2, 0.07);
        }
        for (int i = 0; i < 40 + 20 * levels; i++) {
            // motes left hanging in the air round the pyramid, slowly drifting up
            double a = Fx.RANDOM.nextDouble() * Math.PI * 2;
            double r = 0.8 + Fx.RANDOM.nextDouble() * (1.5 + levels);
            Vec3 at = core.add(Math.cos(a) * r, -Fx.RANDOM.nextDouble() * levels * 0.8 + 0.2, Math.sin(a) * r);
            Fx.spark(at, new Vec3(0, 0.15 + Fx.RANDOM.nextDouble() * 0.3, 0), 1.2 + Fx.RANDOM.nextDouble() * 1.4,
                    0.015f + Fx.RANDOM.nextFloat() * 0.02f, c[0], c[1], c[2], -0.1, 0);
        }
    }

    @Override
    protected void deny() {
        denyAt = Scene.clock();
        Scene.sound(SoundEvents.BEACON_DEACTIVATE, 0.3f, 1.6f);
    }

    static float[] color(@Nullable Holder<MobEffect> effect) {
        int rgb = effect == null ? 0xFFF2B0 : effect.value().getColor();
        float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(0.1f, Math.max(r, Math.max(g, b)));
        return new float[]{r / max, g / max, b / max};
    }

    @Override
    public double cameraKick() {
        double d = Scene.clock() - burstAt;
        double t = moveTime();
        double build = t >= 0 && t < BURST ? 0.25 * Anim.smoother(t / BURST) : 0;
        return Math.max(build, d >= 0 && d < 1.2 ? Math.exp(-d * 3.2) : 0);
    }

    @Override
    public double flash() {
        double d = Scene.clock() - burstAt;
        return d >= 0 && d < 0.6 ? Math.pow(1 - d / 0.6, 2) * 0.32 : 0;
    }

    @Override
    public int flashColor() {
        int r = Math.round(255 * (0.6f + 0.4f * burstColor[0])), g = Math.round(255 * (0.6f + 0.4f * burstColor[1])),
                b = Math.round(255 * (0.6f + 0.4f * burstColor[2]));
        return r << 16 | g << 8 | b;
    }

    // ---- per tick and frame -----------------------------------------------------------------------------

    @Override
    public void tick() {
        BeaconMenu menu = menu();
        if (menu == null) {
            return;
        }
        if (!chosenRead) {
            // start from what the beacon gives now
            chosenRead = true;
            primary = menu.getPrimaryEffect();
            secondary = menu.getSecondaryEffect();
        }
        if (hoverPower != lastHover && hoverPower >= 0) {
            Scene.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.25f, 1.7f + hoverPower * 0.06f);
        }
        lastHover = hoverPower;
        if (!payment().isEmpty() && Minecraft.getInstance().player.tickCount % 4 == 0) {
            float[] c = ready() ? color(primary) : new float[]{1f, 0.9f, 0.6f};
            Fx.motes(paymentPos(), 0.06, 1, c[0], c[1], c[2], 0.1);
        }
    }

    @Override
    void frame() {
        super.frame();
        double t = moveTime();
        double target = t < 0 ? 0 : Anim.smoother(Anim.window(t, BURST - 0.05, BURST + 0.45)) * (1 - Anim.smoother(Anim.window(t, 1.8, 2.65)));
        pull = Anim.approach(pull, target, 5, Scene.frameDt);
        if (t >= 0 && t < BURST && Fx.RANDOM.nextDouble() < Scene.frameDt * 40) {
            // light gathering into the glass
            Vec3 dir = new Vec3(Fx.RANDOM.nextGaussian(), Fx.RANDOM.nextGaussian() * 0.6, Fx.RANDOM.nextGaussian()).normalize();
            double life = 0.35 + Fx.RANDOM.nextDouble() * 0.2;
            double r = 0.7 + Fx.RANDOM.nextDouble() * 0.4;
            Fx.spark(core.add(dir.scale(r)), dir.scale(-r / life * 1.5), life, 0.012f + Fx.RANDOM.nextFloat() * 0.01f, burstColor[0],
                    burstColor[1], burstColor[2], 0, 0.04);
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------------

    @Override
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        Vec3 cam = camera.position();
        double clock = Scene.clock();
        double t = moveTime();
        if (outro()) {
            drawLeaving(pose, buffers, camera, light);
        } else {
            ItemStack item = t >= 0 && t < BURST ? lightingWith : payment();
            if (!item.isEmpty()) {
                // the payment floats over the glass; lighting, it sinks into it spinning faster
                double sink = t >= 0 ? Anim.inCubic(Anim.window(t, 0.05, BURST)) : 0;
                Vec3 at = Anim.lerp(paymentPos(), core.add(side().scale(0.1)).add(0, 0.1, 0), sink);
                float spin = (float) (clock * 50 + (t >= 0 ? 1500 * sink * sink : 0));
                float g = (float) (0.22 + 0.5 * sink);
                float[] c = ready() || t >= 0 ? burstColor(t) : new float[]{1f, 0.85f, 0.5f};
                EnchantFx.billboard(pose, buffers, camera, at, 0.14 + 0.1 * sink, clock * 0.5, EnchantFx.GLOW, c[0] * g, c[1] * g, c[2] * g);
                EnchantStation.drawFloating(pose, buffers, cam, item, at, spin + (float) geometry().rotation(0), (float) (0.26 * geometry().scale(0) * (1 - 0.7 * sink)));
            }
        }
        double presence = Scene.phase == Scene.Phase.INTRO ? Anim.smoother(Anim.window(Scene.introProgress(), 0.4, 1))
                : outro() ? 1 - Anim.smoother(Scene.time / 0.5) : 1;
        if (presence > 0.01 && payment().isEmpty() && t < 0) {
            // where the payment goes: a faint ring on the glass
            float p = (float) ((0.22 + 0.06 * Math.sin(clock * 2)) * presence * (Hands.carried().is(ItemTags.BEACON_PAYMENT_ITEMS) ? 1.8 : 1));
            EnchantFx.disc(pose, buffers, cam, top.add(side().scale(0.27)).add(0, 0.004, 0), 0.17, clock * 0.4, RING, p, p * 0.85f, p * 0.55f);
        }
        drawLight(pose, buffers, camera);
    }

    private float[] burstColor(double t) {
        return t >= 0 ? burstColor : color(primary);
    }

    /** The light of the moment: the glass glowing, the ring over the pyramid, the rays and the flaring beam. */
    private void drawLight(PoseStack pose, SceneBuffers buffers, Camera camera) {
        Vec3 cam = camera.position();
        double clock = Scene.clock();
        double t = moveTime();
        float[] c = burstColor;
        if (t >= 0 && t < BURST) {
            float g = (float) (0.6 * Anim.smoother(t / BURST));
            EnchantFx.billboard(pose, buffers, camera, core, 0.5 + 0.3 * g, clock, EnchantFx.GLOW, c[0] * g, c[1] * g, c[2] * g);
        }
        double d = clock - burstAt;
        if (d < 0 || d > 2.4) {
            return;
        }
        drawBurst(pose, buffers, camera, core, d, c, levels());
    }

    /** What the light leaves in the world for {@code d} seconds after it broke out (also drawn for onlookers). */
    static void drawBurst(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 core, double d, float[] c, int levels) {
        Vec3 cam = camera.position();
        // the glass itself, blinding for a moment
        float glass = (float) Math.exp(-d * 2.5);
        EnchantFx.billboard(pose, buffers, camera, core, 0.9 * glass + 0.3, d, EnchantFx.GLOW, c[0] * glass, c[1] * glass, c[2] * glass);
        // a ring of light running out over the pyramid, one ring per step
        int steps = Math.max(1, levels);
        for (int k = 0; k <= steps; k++) {
            double dk = d - k * 0.09;
            if (dk < 0 || dk > 1.2) {
                continue;
            }
            double radius = 0.4 + k + 4.5 * Anim.outCubic(dk / 1.2);
            float fade = (float) ((1 - Anim.smoother(dk / 1.2)) * (k == 0 ? 1.0 : 0.7));
            Vec3 at = new Vec3(core.x, core.y - 0.5 - k + 0.02, core.z);
            EnchantFx.disc(pose, buffers, cam, at, radius, d * 0.3, RING, c[0] * fade, c[1] * fade, c[2] * fade);
        }
        // rays bursting out flat around the beacon
        double rays = Anim.outCubic(Anim.window(d, 0, 0.55));
        float rayFade = (float) (1 - Anim.smoother(Anim.window(d, 0.2, 1.3)));
        if (rayFade > 0.01) {
            VertexConsumer vc = buffers.getBuffer(Fx.glow(RAY));
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI / 12 + 0.13 * Math.sin(i * 3.7);
                double length = (1.4 + 1.2 * ((i * 7) % 5) / 4.0) * (0.3 + 2.6 * rays);
                double width = 0.12 + 0.08 * ((i * 3) % 4) / 3.0;
                Vec3 dir = new Vec3(Math.cos(a), 0.1 + 0.08 * ((i * 5) % 3), Math.sin(a)).normalize();
                float w = 0.45f;
                ray(pose, vc, cam, core.add(dir.scale(0.3)), dir, length, width, (c[0] + (1 - c[0]) * w) * rayFade,
                        (c[1] + (1 - c[1]) * w) * rayFade, (c[2] + (1 - c[2]) * w) * rayFade);
            }
        }
        // the beam flares up into the sky
        float flare = (float) (Anim.smoother(Anim.window(d, 0, 0.12)) * (1 - Anim.smoother(Anim.window(d, 0.4, 2.4))));
        if (flare > 0.01) {
            EnchantFx.beam(pose, buffers, cam, core.add(0, 0.3, 0), 48, 0.5 + 0.9 * flare, d * 2, c[0] * flare, c[1] * flare, c[2] * flare);
            EnchantFx.beam(pose, buffers, cam, core.add(0, 0.3, 0), 48, 0.22, -d * 3, flare, flare, flare);
        }
    }

    /** A soft streak of light from {@code from} along {@code dir}, turned to face the camera round its own axis. */
    private static void ray(PoseStack pose, VertexConsumer vc, Vec3 cam, Vec3 from, Vec3 dir, double length, double width, float r, float g, float b) {
        Vec3 across = dir.cross(from.subtract(cam)).normalize().scale(width / 2);
        Vec3 to = from.add(dir.scale(length));
        Vec3[] c = {from.subtract(across), to.subtract(across), to.add(across), from.add(across)};
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        int ri = channel(r), gi = channel(g), bi = channel(b);
        for (int[] order : new int[][]{{0, 1, 2, 3}, {3, 2, 1, 0}}) {
            for (int i : order) {
                Vec3 p = c[i].subtract(cam);
                vc.addVertex(pose.last(), (float) p.x, (float) p.y, (float) p.z).setColor(ri, gi, bi, 255).setUv(uv[i][0], uv[i][1])
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose.last(), 0, 1, 0);
            }
        }
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    /** The crown of powers, only while working. */
    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        BeaconMenu menu = menu();
        if (menu == null) {
            return;
        }
        Camera cam = Minecraft.getInstance().gameRenderer.mainCamera();
        icons.clear();
        double clock = Scene.clock();
        double t = moveTime();
        double appear = Anim.outBack(Anim.window(Scene.time, 0, 0.5), 1.4);
        List<Power> list = powers();
        for (int i = 0; i < list.size(); i++) {
            Power power = list.get(i);
            boolean chosen = chosen(power);
            double fly = t >= 0 && chosen ? Anim.inOutCubic(Anim.window(t, 0.1, BURST)) : 0;
            double fade = t >= 0 && !chosen ? 1 - 0.7 * Anim.smoother(Anim.window(t, 0, 0.4)) * (1 - Anim.smoother(Anim.window(t, 1.8, LIGHT_LENGTH))) : 1;
            if (fly >= 0.999) {
                continue;
            }
            // a chosen power flies into the beam as the beacon is lit
            Vec3 at = Anim.lerp(powerPos(i), top.add(0, 0.2, 0), fly);
            drawPower(pose, buffers, cam, power, at, (float) (appear * (1 - 0.6 * fly) * fade), i == hoverPower, chosen, active(power), open(power));
        }
        if (target == Target.TOP && aim != null) {
            boolean ok = Hands.carried().is(ItemTags.BEACON_PAYMENT_ITEMS);
            float p = (float) (0.25 + 0.08 * Math.sin(clock * 5));
            EnchantFx.disc(pose, buffers, camera, top.add(side().scale(0.27)).add(0, 0.006, 0), 0.2, -clock, RING, p, ok ? p * 0.85f : p * 0.2f,
                    ok ? p * 0.5f : p * 0.2f);
        }
    }

    private void drawPower(PoseStack pose, SceneBuffers buffers, Camera camera, Power power, Vec3 at, float appear, boolean hovered,
            boolean chosen, boolean active, boolean open) {
        if (appear <= 0.01f) {
            return;
        }
        double clock = Scene.clock();
        int layoutSlot = powers().indexOf(power) + 1;
        float[] c = color(power.effect());
        float k = open ? (chosen ? 1f : hovered ? 0.85f : active ? 0.65f : 0.45f) : 0.14f;
        k *= appear;
        double size = 0.1 * geometry().scale(layoutSlot) * appear * (hovered && open ? 1.15 : 1) * (chosen ? 1.1 : 1);
        // dark glass behind it, so the icon reads against the sky and the white beam
        Fx.shade(pose, buffers, camera, at, size * 1.4, 0.75f * appear);
        if (chosen) {
            // tied to the beam by a thread of light
            Vec3 to = beamAt(at);
            Vec3 along = to.subtract(at);
            ray(pose, buffers.getBuffer(Fx.glow(RAY)), camera.position(), at, along.normalize(), along.length(), 0.05,
                    c[0] * 0.6f * appear, c[1] * 0.6f * appear, c[2] * 0.6f * appear);
        }
        EnchantFx.billboard(pose, buffers, camera, at, size * 2.0, 0, EnchantFx.GLOW, c[0] * k * 0.35f, c[1] * k * 0.35f, c[2] * k * 0.35f);
        double turn = clock * (chosen ? 1.2 : 0.35) * (power.tier() % 2 == 0 ? 1 : -1) + Math.toRadians(geometry().rotation(layoutSlot));
        EnchantFx.billboard(pose, buffers, camera, at, size * 1.3, turn, chosen ? EnchantFx.CIRCLE : RING, c[0] * k, c[1] * k, c[2] * k);
        // the pyramid level a power needs, or the II of the stronger power
        String label = !open || power.upgrade() ? power.upgrade() ? "II" : "I".repeat(power.tier() + 1).replace("IIII", "IV") : "";
        icons.add(new Icon(Minecraft.getInstance().getAtlasManager().get(new net.minecraft.client.resources.model.sprite.SpriteId(net.minecraft.client.renderer.Sheets.GUI_SHEET, net.minecraft.client.gui.Hud.getMobEffectSprite(power.effect()))), at, size * 0.7, open ? 1f : 0.35f, appear, label,
                !open ? 0x9A9AA6 : 0xFFF2C0, at.add(0, -size * 1.62, 0), (float) geometry().rotation(layoutSlot)));
        if (chosen && Fx.RANDOM.nextDouble() < Scene.frameDt * 6) {
            double a = Fx.RANDOM.nextDouble() * Math.PI * 2;
            Fx.spark(at.add(Math.cos(a) * size, Math.sin(a) * size, 0), new Vec3(0, 0.05, 0), 0.6, 0.008f, c[0], c[1], c[2], -0.05, 0);
        }
    }

    /**
     * The powers' icons and levels, over the frame where they hang in the air (worked out with this frame's matrices):
     * each as large as it would be there, in its own colours whatever shader pack is in use.
     */
    @Override
    public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) {
        if (Scene.phase != Scene.Phase.ACTIVE) {
            return;
        }
        org.joml.Vector3fc up = Minecraft.getInstance().gameRenderer.mainCamera().upVector();
        Vec3 upward = new Vec3(up.x(), up.y(), up.z());
        for (Icon icon : icons) {
            double[] at = Picking.toGui(icon.at()), edge = Picking.toGui(icon.at().add(upward.scale(icon.half())));
            if (at == null || edge == null) {
                continue;
            }
            // GUI pixels for a block of the world there
            double perBlock = Math.hypot(edge[0] - at[0], edge[1] - at[1]) / icon.half();
            float a = Math.min(1, icon.alpha()) * alpha;
            g.pose().pushMatrix();
            g.pose().translate((float) (at[0]), (float) (at[1]));
            g.pose().rotate((float) Math.toRadians(-icon.rotation()));
            float s = (float) (icon.half() * perBlock / 9);
            g.pose().scale(s, s);
            g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, icon.sprite(), -9, -9, 18, 18,
                    net.minecraft.util.ARGB.colorFromFloat(a, icon.brightness(), icon.brightness(), icon.brightness()));
            g.pose().popMatrix();
            double[] label = icon.label().isEmpty() ? null : Picking.toGui(icon.labelAt());
            if (label != null) {
                g.pose().pushMatrix();
                g.pose().translate((float) label[0], (float) label[1]);
                float t = (float) (0.0055 * icon.alpha() * perBlock);
                g.pose().scale(t, t);
                int textAlpha = Math.max(8, Math.round(220 * a));
                g.text(font, icon.label(), -font.width(icon.label()) / 2, -4, (textAlpha << 24) | icon.labelColor(), false);
                g.pose().popMatrix();
            }
        }
        gridEditor.render(g, font, alpha);
    }

    // ---- what others see -------------------------------------------------------------------------------

    /** The payment floating over someone else's beacon. */
    static void drawContents(PoseStack pose, SceneBuffers buffers, Camera camera, BlockPos pos, Direction side, List<ItemStack> items) {
        if (items.isEmpty() || items.get(0).isEmpty()) {
            return;
        }
        Vec3 at = RemainingLayouts.point(StationKind.BEACON, Vec3.atBottomCenterOf(pos).add(0, 1, 0), side, 0)
                .add(0, 0.012 * Math.sin(Scene.clock() * 1.9), 0);
        EnchantFx.billboard(pose, buffers, camera, at, 0.14, Scene.clock() * 0.5, EnchantFx.GLOW, 0.22f, 0.19f, 0.11f);
        EnchantStation.drawFloating(pose, buffers, camera.position(), items.get(0), at, (float) (Scene.clock() * 50), 0.26f * RemainingLayouts.scale(StationKind.BEACON, 0));
    }

    /** Someone else lit their beacon: the light breaks out for onlookers too (in the beacon's own gold). */
    static void onlookerFx(PoseStack pose, SceneBuffers buffers, Camera camera, BlockPos pos, double age, double dt) {
        Vec3 core = Vec3.atCenterOf(pos);
        float[] c = {1f, 0.92f, 0.62f};
        // the cue comes when the light breaks out
        double d = age;
        if (d >= 0 && d < dt * 1.5) {
            burst(core, c, 2);
        }
        if (d >= 0 && d < 2.4) {
            drawBurst(pose, buffers, camera, core, d, c, 2);
        }
    }

    // ---- words -----------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] pointer = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(pointer[0], pointer[1]);
        if (editorTip != null) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        switch (target) {
            case POWER -> {
                List<Power> list = powers();
                if (hoverPower < 0 || hoverPower >= list.size()) {
                    return null;
                }
                Power power = list.get(hoverPower);
                List<Component> lines = new ArrayList<>();
                Component name = Component.translatable(power.effect().value().getDescriptionId());
                lines.add(power.upgrade() ? name.copy().append(" II") : name);
                lines.add(Component.translatable(power.secondary() ? "block.minecraft.beacon.secondary" : "block.minecraft.beacon.primary")
                        .withStyle(ChatFormatting.DARK_GRAY));
                if (levels() == 0) {
                    lines.add(Component.translatable("cinematic.beacon.no_pyramid").withStyle(ChatFormatting.RED));
                } else if (!open(power)) {
                    lines.add(Component.translatable("cinematic.beacon.needs", power.tier() + 1).withStyle(ChatFormatting.RED));
                } else if (chosen(power)) {
                    lines.add(Component.translatable("cinematic.beacon.chosen").withStyle(ChatFormatting.GOLD));
                } else {
                    lines.add(Component.translatable("cinematic.beacon.choose").withStyle(ChatFormatting.GRAY));
                }
                if (active(power)) {
                    lines.add(Component.translatable("cinematic.beacon.active").withStyle(ChatFormatting.AQUA));
                }
                return lines;
            }
            case PAYMENT -> {
                List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, payment()));
                if (ready()) {
                    lines.add(Component.translatable("cinematic.beacon.light").withStyle(ChatFormatting.GOLD));
                } else {
                    lines.add(Component.translatable(levels() == 0 ? "cinematic.beacon.no_pyramid" : "cinematic.beacon.pick_first")
                            .withStyle(ChatFormatting.YELLOW));
                }
                lines.add(Component.translatable("cinematic.beacon.take").withStyle(ChatFormatting.GRAY));
                return lines;
            }
            case TOP -> {
                return carried.is(ItemTags.BEACON_PAYMENT_ITEMS)
                        ? List.of(Component.translatable("cinematic.beacon.pay").withStyle(ChatFormatting.GRAY))
                        : List.of(Component.translatable("cinematic.beacon.refuse").withStyle(ChatFormatting.RED));
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.beacon");
    }

    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void onEnd() { gridEditor.cancel(); super.onEnd(); }
    @Override public boolean busy() { return super.busy() || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public java.util.List<Vec3> workSamples() { return RemainingLayouts.samples(this, super.workSamples()); }
}
