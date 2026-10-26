package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.dyslexic.cinematic.station.StationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.EnchantmentNames;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.LightCoordsUtil;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * The enchanting scene. The item is laid on the table and rises to hover before the book; lapis goes into the
 * floating socket beside the book; the three offers hang in the air as rune sigils with their level cost. Touching an
 * affordable sigil starts the rite: the sigil and the lapis are drawn into the item, the hands rise around it,
 * the glyphs of the bookshelves pour in, then light bursts out and the item turns, enchanted.
 * Everything underneath is the vanilla enchanting menu, opened by the server without its window.
 */
public final class EnchantStation extends ClientStation implements SpatialTarget {
    final SpatialEditor gridEditor = new SpatialEditor(this);
    static final double OFFER_HIGH = 0.92, OFFER_LOW = 0.74, OFFER_FAR = -0.12, OFFER_NEAR = 0.02, OFFER_SPREAD = 0.4;
    static final double HAND_Y = 0.062;
    static final double ITEM_REACH = 0.075;
    public static final double RITUAL_LENGTH = 1.5;
    public static final double RITUAL_PEAK = 0.5;
    private static final float ITEM_SIZE = 0.34f;

    public enum Target { NONE, SURFACE, ITEM, LAPIS, OFFER }

    public final Vec3 top;
    private final List<Vec3> shelves;
    private final RandomSource random = RandomSource.create();

    public Target target = Target.NONE;
    public int hoverOffer = -1;
    private boolean aimAir;
    private int lastHoverOffer = -1;

    private int runeSeed = Integer.MIN_VALUE;
    private final FormattedCharSequence[] runes = new FormattedCharSequence[3];
    private double offersAt = -10;
    private int offersFingerprint;

    // the rite
    private double ritualAt = -10;
    private int ritualOffer;
    private ItemStack ritualItem = ItemStack.EMPTY;
    private int ritualLapis;
    private Vec3 ritualFrom = Vec3.ZERO;
    private boolean burstDone;
    private double burstAt = -10;
    private double denyAt = -10;
    private int denyOffer = -1;

    // how strongly the circle burns, eased
    private double glow;
    private double lapisFocus;
    // what floated over the table when the player stepped away, fading out
    private ItemStack leavingItem = ItemStack.EMPTY;
    private int leavingLapis;

    public EnchantStation(BlockPos pos, Direction side) {
        super(pos, side);
        top = EnchantFx.top(pos);
        Minecraft mc = Minecraft.getInstance();
        shelves = mc.level == null ? List.of() : EnchantFx.shelves(mc.level, pos);
    }

    @Override
    public StationKind kind() {
        return StationKind.ENCHANTING;
    }

    @Override
    public Vec3 center() {
        return top;
    }

    @Override
    public SoundEvent touchSound() {
        return SoundEvents.STONE_STEP;
    }

    @Override public SpatialEditor layoutEditor() { return gridEditor; }
    @Override public SpatialAppearance.Frame layoutFrame() { return SpatialAppearance.Frame.of(kind(), pos, side, Direction.UP); }
    @Override public ItemStack layoutItem(int slot) {
        return slot == 0 ? item() : slot == 1 ? lapis() > 0 ? new ItemStack(Items.LAPIS_LAZULI) : ItemStack.EMPTY
                : offered(slot - 2) ? new ItemStack(Items.ENCHANTED_BOOK) : ItemStack.EMPTY;
    }
    @Override public Vec3 layoutPoint(int slot) { return slot == 0 ? itemPos() : slot == 1 ? corner() : sigilPos(slot - 2); }
    @Override public Vec3 layoutNormal(int slot) { return Picking.camera().subtract(layoutPoint(slot)).normalize(); }
    @Override public Vec3 layoutAxis(int slot, boolean across) {
        Vec3 normal = layoutNormal(slot), a = new Vec3(0, 1, 0).cross(normal).normalize(), b = normal.cross(a);
        double angle = Math.toRadians(geometry().rotation(slot));
        return across ? a.scale(Math.cos(angle)).add(b.scale(Math.sin(angle))) : b.scale(Math.cos(angle)).subtract(a.scale(Math.sin(angle)));
    }
    @Override public boolean busy() { return ritualTime() >= 0 || gridEditor.editing(); }
    @Override public boolean showsInventory() { return !gridEditor.visible(); }
    @Override public void beforeFrame() { gridEditor.frame(); }
    @Override public void renderScreen(GuiGraphicsExtractor g, Font font, float alpha) { gridEditor.render(g, font, alpha); }
    @Override public List<Vec3> workSamples() {
        // The floating offers can sit above the player's reach; availability is checked at the actual table.
        List<Vec3> points = new ArrayList<>(super.workSamples());
        if (!geometry().equals(SpatialAppearance.VANILLA)) points.add(corner());
        return points;
    }
    @Override public float layoutHeldScale() { return target == Target.LAPIS ? geometry().scale(1) : target == Target.ITEM || target == Target.SURFACE ? geometry().scale(0) : 1; }
    @Override public void layoutGhost(PoseStack pose, SceneBuffers buffers, Vec3 camera, int slot, int light) {
        if (slot == 0) drawFloating(pose, buffers, camera, new ItemStack(Items.DIAMOND_SWORD), itemPos(),
                (float) (Scene.clock() * 40 + geometry().rotation(0)), ITEM_SIZE * geometry().scale(0));
        else if (slot == 1) drawLapis(pose, buffers, camera, corner(), 1, light, geometry().scale(1), (float) -geometry().rotation(1));
        else drawSigil(pose, buffers, Minecraft.getInstance().gameRenderer.mainCamera(), slot - 2, sigilPos(slot - 2),
                gridEditor.presence(), false, true, 0.6f * gridEditor.presence(), slot - 1);
    }
    static Vec3 itemPosition(BlockPos pos, Direction side, double clock) {
        return SpatialLayouts.current(StationKind.ENCHANTING).point(SpatialAppearance.Frame.of(StationKind.ENCHANTING, pos, side, Direction.UP), 0)
                .add(0, EnchantFx.itemCenter(pos, side, clock).y - EnchantFx.itemCenter(pos, side, 0).y, 0);
    }
    static Vec3 lapisPosition(BlockPos pos, Direction side) {
        return SpatialLayouts.current(StationKind.ENCHANTING).point(SpatialAppearance.Frame.of(StationKind.ENCHANTING, pos, side, Direction.UP), 1);
    }

    // ---- staging -------------------------------------------------------------------------------------

    @Override
    public List<Vec3> approach(LocalPlayer player) {
        Vec3 stand = new Vec3(top.x + side.getStepX() * 0.86, player.getY(), top.z + side.getStepZ() * 0.86);
        boolean walk = Math.hypot(stand.x - player.getX(), stand.z - player.getZ()) > 0.06;
        return walk ? List.of(stand) : List.of();
    }

    @Override
    public CameraRig.Pose lean() {
        Vec3 eye = top.add(side().scale(1.15)).add(0, 1.0, 0);
        Vec3 look = top.add(0, 0.5, 0).subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
        return new CameraRig.Pose(eye, yaw, pitch, 0);
    }

    @Override
    public double leanFov() {
        return 68;
    }

    @Override
    public float bodyPitch() {
        return 30f;
    }

    @Override
    public Vec3 rest(boolean right) {
        return top.add(side().scale(0.36)).add(right().scale(right ? 0.24 : -0.24)).add(0, HAND_Y, 0);
    }

    // ---- the menu underneath ---------------------------------------------------------------------

    @Nullable
    public static EnchantmentMenu menu() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.containerMenu instanceof EnchantmentMenu menu ? menu : null;
    }

    /** The item over the table: during the rite, the one being enchanted until the light breaks. */
    public ItemStack item() {
        double t = ritualTime();
        if (t >= 0 && t < RITUAL_PEAK) {
            return ritualItem;
        }
        EnchantmentMenu menu = menu();
        return menu == null ? ItemStack.EMPTY : menu.getSlot(0).getItem();
    }

    public int lapis() {
        EnchantmentMenu menu = menu();
        return menu == null ? 0 : menu.getSlot(1).getItem().getCount();
    }

    private boolean offered(int k) {
        EnchantmentMenu menu = menu();
        return menu != null && menu.costs[k] > 0 && !item().isEmpty();
    }

    private boolean affordable(int k) {
        EnchantmentMenu menu = menu();
        LocalPlayer player = Minecraft.getInstance().player;
        if (menu == null || player == null || menu.costs[k] <= 0 || menu.enchantClue[k] < 0) {
            return false;
        }
        if (player.getAbilities().instabuild) {
            return true;
        }
        return player.experienceLevel >= menu.costs[k] && player.experienceLevel >= k + 1 && lapis() >= k + 1;
    }

    // ---- geometry ------------------------------------------------------------------------------------

    public Vec3 itemPos() {
        double t = ritualTime();
        double lift = t >= 0 ? 0.1 * Anim.hump(Anim.window(t * RITUAL_LENGTH, 0.2, 1.3)) : 0;
        return itemPosition(pos, side, Scene.clock()).add(0, lift, 0);
    }

    /** The offers hang in a crown over the item: the middle one highest, the outer ones beside it and towards the player. */
    public Vec3 sigilPos(int k) {
        double clock = Scene.clock();
        Vec3 at = geometry().point(layoutFrame(), k + 2).add(0, 0.015 * Math.sin(clock * 1.3 + k * 2.1), 0);
        if (k == denyOffer) {
            double d = clock - denyAt;
            if (d >= 0 && d < 0.35) {
                at = at.add(right().scale(0.02 * Math.sin(d * 60) * (1 - d / 0.35)));
            }
        }
        return at;
    }

    public Vec3 corner() {
        return lapisPosition(pos, side);
    }

    /** Projected radius in GUI pixels of a sphere of {@code radius} blocks at {@code center}. */
    private static double guiRadius(Vec3 center, double radius) {
        double[] a = Picking.toGui(center);
        double[] b = Picking.toGui(center.add(0, radius, 0));
        return a == null || b == null ? 0 : Math.max(8, Math.hypot(a[0] - b[0], a[1] - b[1]));
    }

    private static boolean under(double[] mouse, Vec3 center, double radius) {
        double[] at = Picking.toGui(center);
        if (at == null) {
            return false;
        }
        double r = guiRadius(center, radius);
        return Math.hypot(mouse[0] - at[0], mouse[1] - at[1]) < r;
    }

    // ---- cursor --------------------------------------------------------------------------------------

    @Override
    public void updateCursor(boolean overPanel) {
        clearCursor();
        if (overPanel || gridEditor.visible() || menu() == null) {
            return;
        }
        double[] mouse = Picking.mouseGui();
        if (!geometry().equals(SpatialAppearance.VANILLA)) {
            int hit = layoutPick();
            if (hit >= 2 && offered(hit - 2) && ritualTime() < 0) {
                target = Target.OFFER; hoverOffer = hit - 2; aim = sigilPos(hoverOffer); aimAir = true; return;
            }
            if (hit == 0) {
                target = item().isEmpty() ? Target.SURFACE : Target.ITEM; aim = itemPos(); aimAir = true; return;
            }
            if (hit == 1) { target = Target.LAPIS; aim = corner(); aimAir = true; return; }
        }
        if (geometry().equals(SpatialAppearance.VANILLA) && ritualTime() < 0) {
            for (int k = 0; k < 3; k++) {
                if (offered(k) && under(mouse, sigilPos(k), 0.15)) {
                    target = Target.OFFER;
                    hoverOffer = k;
                    aim = sigilPos(k);
                    aimAir = true;
                    return;
                }
            }
        }
        if (geometry().equals(SpatialAppearance.VANILLA) && !item().isEmpty() && under(mouse, itemPos(), 0.15)) {
            target = Target.ITEM;
            aim = itemPos();
            aimAir = true;
            return;
        }
        // The empty crystal and the count float a little above the socket, but both point the hand to it.
        if (geometry().equals(SpatialAppearance.VANILLA) && under(mouse, corner().add(0, 0.06, 0), 0.09)) {
            target = Target.LAPIS;
            aim = corner();
            aimAir = true;
            return;
        }
        Vec3 socketHit = Picking.cursorOnPlane(corner().y);
        if (geometry().equals(SpatialAppearance.VANILLA) && socketHit != null && socketHit.distanceToSqr(corner()) <= EnchantFx.LAPIS_RADIUS * EnchantFx.LAPIS_RADIUS) {
            target = Target.LAPIS;
            aim = corner();
            aimAir = true;
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
        aimAir = false;
        target = Target.SURFACE;
        aim = new Vec3(top.x + Math.max(-0.43, Math.min(0.43, rel.x)), top.y, top.z + Math.max(-0.43, Math.min(0.43, rel.z)));
    }

    @Override
    public void clearCursor() {
        super.clearCursor();
        target = Target.NONE;
        hoverOffer = -1;
    }

    @Override
    public Vec3 handTarget(Vec3 aim, Vec3 shoulder, boolean carrying, double tap) {
        if (aimAir) {
            // a hand reaching out to something floating stops just short of it
            Vec3 dir = aim.subtract(shoulder).normalize();
            return aim.subtract(dir.scale(0.14 - 0.05 * tap)).add(0, -0.03, 0);
        }
        Vec3 reach = new Vec3(aim.x - shoulder.x, 0, aim.z - shoulder.z).normalize();
        double hover = carrying ? 0.075 : 0.045;
        return aim.add(0, HAND_Y + hover - 0.06 * tap, 0).subtract(reach.scale(ITEM_REACH));
    }

    @Override
    public Vec3 heldItemPoint(Vec3 hand, Vec3 shoulder) {
        Vec3 reach = new Vec3(hand.x - shoulder.x, 0, hand.z - shoulder.z).normalize();
        return hand.add(reach.scale(ITEM_REACH)).add(0, -HAND_Y + 0.012, 0);
    }

    /** During the rite both hands rise around the item. */
    @Nullable
    @Override
    public Vec3[] handsOverride() {
        double t = ritualTime();
        if (t < 0) {
            return null;
        }
        double raise = Anim.hump(Anim.window(t, 0, 0.85));
        Vec3 c = itemPos().add(side().scale(0.07)).add(0, -0.1 + 0.05 * raise, 0);
        double spread = (0.17 - 0.04 * raise) * Math.max(0.8, geometry().scale(0));
        return new Vec3[]{c.add(right().scale(spread)), c.add(right().scale(-spread))};
    }

    // ---- actions -------------------------------------------------------------------------------------

    @Override
    public void click(int button, boolean shift) {
        if (gridEditor.visible()) return;
        EnchantmentMenu menu = menu();
        if (menu == null) {
            return;
        }
        switch (target) {
            case OFFER -> {
                if (ritualTime() >= 0) {
                    return;
                }
                if (affordable(hoverOffer)) {
                    startRitual(menu, hoverOffer);
                } else {
                    denyAt = Scene.clock();
                    denyOffer = hoverOffer;
                    Scene.sound(SoundEvents.CANDLE_EXTINGUISH, 0.7f, 0.7f);
                }
            }
            case ITEM, SURFACE -> slotClick(menu, 0, button, shift);
            case LAPIS -> slotClick(menu, 1, button, shift);
            default -> {}
        }
    }

    private void slotClick(EnchantmentMenu menu, int slot, int button, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null || ritualTime() >= 0) {
            return;
        }
        Hands.tap();
        boolean placing = !Hands.carried().isEmpty();
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
        if (placing) {
            Scene.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.5f, slot == 0 ? 1.5f : 1.1f);
        }
    }

    private void startRitual(EnchantmentMenu menu, int k) {
        Minecraft mc = Minecraft.getInstance();
        ritualFrom = sigilPos(k);
        ritualItem = item().copy();
        ritualOffer = k;
        ritualLapis = k + 1;
        ritualAt = Scene.clock();
        burstDone = false;
        mc.gameMode.handleInventoryButtonClick(menu.containerId, k);
        Scene.sound(SoundEvents.BEACON_ACTIVATE, 0.45f, 1.7f);
        Scene.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.8f, 0.9f);
    }

    /** How far into the rite we are, 0..1 of {@link #RITUAL_LENGTH}, or -1. */
    public double ritualTime() {
        double t = (Scene.clock() - ritualAt) / RITUAL_LENGTH;
        return t >= 0 && t <= 1 ? t : -1;
    }

    @Override
    public double cameraKick() {
        double t = ritualTime();
        if (t < 0) {
            return 0;
        }
        double peak = RITUAL_PEAK / RITUAL_LENGTH;
        return t < peak ? 0.6 * Anim.smoother(t / peak) : Math.exp(-(t - peak) * RITUAL_LENGTH * 3.2);
    }

    /** The white-violet flash of the burst. */
    @Override
    public double flash() {
        double d = Scene.clock() - burstAt;
        return d >= 0 && d < 0.5 ? Math.pow(1 - d / 0.5, 2) * 0.26 : 0;
    }

    @Override
    public int flashColor() {
        return 0xE8DCFF;
    }

    @Override
    public void onEnd() {
        gridEditor.cancel();
        leavingItem = item().copy();
        leavingLapis = lapis();
        LocalPlayer player = Minecraft.getInstance().player;
        if (!Scene.clientOnly() && player != null && player.containerMenu instanceof EnchantmentMenu) {
            // the server closes its copy on LEAVE; ours goes quietly, without a window to close
            player.containerMenu = player.inventoryMenu;
        }
    }

    // ---- per tick ------------------------------------------------------------------------------------

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        EnchantmentMenu menu = menu();
        if (menu == null || mc.level == null) {
            return;
        }
        int seed = menu.getEnchantmentSeed();
        int fingerprint = seed * 31 + menu.costs[0] * 7 + menu.costs[1] * 5 + menu.costs[2] * 3 + (item().isEmpty() ? 0 : 1);
        if (seed != runeSeed || fingerprint != offersFingerprint) {
            runeSeed = seed;
            offersFingerprint = fingerprint;
            offersAt = Scene.clock();
            EnchantmentNames.getInstance().initSeed(seed);
            for (int k = 0; k < 3; k++) {
                runes[k] = Language.getInstance().getVisualOrder(EnchantmentNames.getInstance().getRandomName(mc.font, 66));
            }
        }
        if (hoverOffer != lastHoverOffer && hoverOffer >= 0) {
            Scene.sound(SoundEvents.BOOK_PAGE_TURN, 0.5f, 0.9f + random.nextFloat() * 0.3f);
            Scene.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.25f, 1.6f + hoverOffer * 0.2f);
        }
        lastHoverOffer = hoverOffer;

        double t = ritualTime();
        boolean stirring = hoverOffer >= 0 || t >= 0;
        if (stirring && mc.level.getBlockEntity(pos) instanceof EnchantingTableBlockEntity book && mc.player.tickCount % 3 == 0) {
            book.flipT += (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(2));
        }
        ItemStack item = item();
        if (!item.isEmpty() && mc.player.tickCount % 3 == 0) {
            EnchantFx.sparkle(itemPos(), 0.2);
        }
        if (hoverOffer >= 0) {
            Vec3 sigil = sigilPos(hoverOffer);
            EnchantFx.stream(source(), sigil, 1);
            EnchantFx.stream(sigil, itemPos(), 1);
        }
        if (t >= 0 && t * RITUAL_LENGTH < RITUAL_PEAK) {
            for (int i = 0; i < 3; i++) {
                EnchantFx.stream(source(), itemPos(), 1);
                EnchantFx.gather(itemPos(), 0.55);
            }
        }
        if (t >= 0 && !burstDone && t * RITUAL_LENGTH >= RITUAL_PEAK) {
            burstDone = true;
            burstAt = Scene.clock();
            EnchantFx.burst(itemPos(), 1.4);
            Scene.sound(SoundEvents.BEACON_POWER_SELECT, 0.7f, 1.3f);
            Scene.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 0.7f);
        }
    }

    /** A bookshelf to draw glyphs from, or a point in the air around the table when there are none. */
    private Vec3 source() {
        if (!shelves.isEmpty()) {
            return shelves.get(random.nextInt(shelves.size())).add(0, 0.3, 0);
        }
        double a = random.nextDouble() * Math.PI * 2;
        return top.add(Math.cos(a) * 1.6, 0.6 + random.nextDouble() * 0.8, Math.sin(a) * 1.6);
    }

    // ---- drawing -------------------------------------------------------------------------------------

    /** The circle, the hovering item, the lapis and the shaft of light; drawn in every phase of the scene. */
    public void renderScene(PoseStack pose, SceneBuffers buffers, Camera camera, int light) {
        Vec3 cam = camera.position();
        double clock = Scene.clock();
        double t = ritualTime();
        double target = switch (Scene.phase) {
            case INTRO -> 0.5 * Anim.smoother(Anim.window(Scene.introProgress(), 0.45, 1.0));
            case ACTIVE -> 0.45 + (item().isEmpty() ? 0 : 0.2) + (hoverOffer >= 0 ? 0.3 : 0)
                    + (t >= 0 ? 1.4 * Anim.hump(Anim.window(t, 0, 0.75)) : 0);
            default -> 0;
        };
        glow = Anim.approach(glow, target, target > glow ? 6 : 2.5, Scene.frameDt);
        double spin = clock * 0.25 + (t >= 0 ? 4 * Anim.smoother(t) : 0);
        float g = (float) glow;
        EnchantFx.CirclePalette palette = item().isEmpty() || t >= 0 || lapis() > 0 ? EnchantFx.CirclePalette.VIOLET : EnchantFx.CirclePalette.RED;
        EnchantFx.circle(pose, buffers, cam, top.add(0, EnchantFx.OUTER_LIFT, 0), 0.47, spin, palette, 0.85f * g);
        EnchantFx.circle(pose, buffers, cam, top.add(0, EnchantFx.INNER_LIFT, 0), 0.25, -spin * 1.7, palette, 0.55f * g);

        boolean outro = Scene.phase == Scene.Phase.OUTRO;
        double fade = outro ? 1 - Anim.smoother(Scene.time / 0.45) : 1;
        ItemStack item = outro ? leavingItem : item();
        Vec3 at = itemPos();
        if (!item.isEmpty() && fade > 0.01) {
            EnchantFx.billboard(pose, buffers, camera, at, 0.24, clock * 0.4, EnchantFx.GLOW, 0.3f * g, 0.18f * g, 0.5f * g);
            double extraSpin = t >= 0 ? 900 * Anim.outCubic(t) : 0;
            double pulse = t >= 0 && t * RITUAL_LENGTH > RITUAL_PEAK ? 1 + 0.18 * Anim.hump(Anim.window(t, RITUAL_PEAK / RITUAL_LENGTH, 0.8)) : 1;
            drawFloating(pose, buffers, cam, item, at, (float) (clock * 40 + extraSpin + geometry().rotation(0)), (float) (ITEM_SIZE * fade * pulse * geometry().scale(0)));
        }
        // the server takes the lapis for the rite at once; what flies into the item is drawn in renderWorld
        int lapis = outro ? leavingLapis : lapis();
        double presence = Scene.phase == Scene.Phase.INTRO
                ? Anim.smoother(Anim.window(Scene.introProgress(), 0.45, 1)) : fade;
        lapisFocus = Anim.approach(lapisFocus, t >= 0 ? 0 : this.target == Target.LAPIS ? 1
                : Hands.carried().is(Items.LAPIS_LAZULI) ? 0.65 : 0, 12, Scene.frameDt);
        if (presence > 0.01) {
            float socket = (float) ((0.50 + 0.42 * lapisFocus) * presence);
            boolean wrong = this.target == Target.LAPIS && !Hands.carried().isEmpty() && !Hands.carried().is(Items.LAPIS_LAZULI);
            EnchantFx.circle(pose, buffers, cam, corner().add(0, EnchantFx.SOCKET_LIFT, 0), 0.10 * geometry().scale(1), clock * 0.3 + Math.toRadians(geometry().rotation(1)),
                    wrong ? EnchantFx.CirclePalette.RED : EnchantFx.CirclePalette.LAPIS, socket);
        }
        if (lapis > 0 && fade > 0.01) {
            drawLapis(pose, buffers, cam, corner(), lapis, light, (float) fade * geometry().scale(1), (float) -geometry().rotation(1));
            drawLapisCount(pose, buffers, camera, corner().add(0, 0.115 * geometry().scale(1), 0), lapis, (float) presence);
        }
        if (t >= 0) {
            double peak = RITUAL_PEAK / RITUAL_LENGTH;
            double beam = t < peak ? 0 : 1 - Anim.smoother((t - peak) / (1 - peak));
            if (beam > 0.01) {
                float b = (float) (beam * 1.3);
                EnchantFx.beam(pose, buffers, cam, new Vec3(itemPos().x, top.y, itemPos().z), 1.9, 0.5 + 0.3 * beam, clock * 0.9, 0.7f * b, 0.5f * b, 1.0f * b);
            }
        }
    }

    /** Sigils, the hover glow and the things flying into the item; only while working. */
    @Override
    public void renderWorld(PoseStack pose, SceneBuffers buffers, Vec3 camera, int light) {
        gridEditor.renderWorld(pose, buffers, camera, light);
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.mainCamera();
        EnchantmentMenu menu = menu();
        if (menu == null) {
            return;
        }
        double clock = Scene.clock();
        double t = ritualTime();
        double appear = Anim.outBack(Anim.window(clock - offersAt, 0, 0.45), 1.6);
        for (int k = 0; k < 3; k++) {
            if (!offered(k) || (t >= 0 && k == ritualOffer)) {
                continue;
            }
            float dim = t >= 0 ? 0.4f : 1f;
            drawSigil(pose, buffers, cam, k, sigilPos(k), (float) appear, k == hoverOffer, affordable(k), dim, menu.costs[k]);
        }
        if (t >= 0) {
            double flight = Anim.inOutCubic(Anim.window(t * RITUAL_LENGTH, 0, RITUAL_PEAK - 0.05));
            if (flight < 1) {
                Vec3 to = itemPos();
                Vec3 p = Anim.lerp(ritualFrom, to, flight).add(0, 0.12 * Anim.hump(flight), 0);
                drawSigil(pose, buffers, cam, ritualOffer, p, (float) (1 - 0.8 * flight), true, true, 1.6f, menu.costs[ritualOffer]);
            }
            for (int i = 0; i < ritualLapis; i++) {
                double f = Anim.inOutSine(Anim.window(t * RITUAL_LENGTH, 0.04 + i * 0.07, RITUAL_PEAK - 0.02));
                if (f > 0 && f < 1) {
                    Vec3 p = Anim.lerp(corner().add(0, 0.02, 0), itemPos(), f).add(0, 0.18 * Anim.hump(f), 0);
                    drawFloating(pose, buffers, camera, new ItemStack(Items.LAPIS_LAZULI), p, (float) (clock * 300 + i * 90), (float) (0.16 * (1 - 0.6 * f) * geometry().scale(1)));
                }
            }
        }
        if (target == Target.SURFACE && !Hands.carried().isEmpty() && aim != null) {
            float p = (float) (0.25 + 0.1 * Math.sin(clock * 5));
            EnchantFx.circle(pose, buffers, camera, top.add(0, EnchantFx.HINT_LIFT, 0), 0.16, -clock, EnchantFx.CirclePalette.VIOLET, p);
        }
    }

    private static void drawLapisCount(PoseStack pose, SceneBuffers buffers, Camera camera, Vec3 at, int count, float presence) {
        int alpha = Math.round(230 * presence);
        if (alpha < 8) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        String label = "×" + count;
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(camera.rotation()));
        pose.scale(0.0045f, -0.0045f, 0.0045f);
        SceneText.draw(font, label, -font.width(label) / 2f, -4f, (alpha << 24) | 0x9CC8FF, true, pose.last().pose(), buffers,
                Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
        pose.popPose();
    }

    private void drawSigil(PoseStack pose, SceneBuffers buffers, Camera camera, int k, Vec3 at, float appear, boolean hovered,
            boolean affordable, float brightness, int cost) {
        if (appear <= 0.01f) {
            return;
        }
        double clock = Scene.clock();
        float size = (float) (0.13 * appear * (hovered ? 1.2 : 1.0) * geometry().scale(k + 2));
        float b = brightness * (hovered ? 1.35f : 0.85f) * Math.min(1f, appear);
        float r = affordable ? 0.55f : 0.5f;
        float g = affordable ? 0.45f : 0.12f;
        float bl = affordable ? 1.0f : 0.16f;
        EnchantFx.billboard(pose, buffers, camera, at, size * 1.8, 0, EnchantFx.GLOW, r * b * 0.35f, g * b * 0.35f, bl * b * 0.35f);
        double turn = clock * (hovered ? 1.8 : 0.5) * (k % 2 == 0 ? 1 : -1);
        EnchantFx.circleBillboard(pose, buffers, camera, at, size, turn + Math.toRadians(geometry().rotation(k + 2)),
                affordable ? EnchantFx.CirclePalette.VIOLET : EnchantFx.CirclePalette.RED, b);

        Font font = Minecraft.getInstance().font;
        Vec3 cam = camera.position();
        pose.pushPose();
        org.joml.Vector3fc look = camera.forwardVector();
        Vec3 text = at.subtract(look.x() * 0.01, look.y() * 0.01, look.z() * 0.01);
        pose.translate(text.x - cam.x, text.y - cam.y, text.z - cam.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(camera.rotation()));
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.ZP.rotationDegrees((float) geometry().rotation(k + 2))));
        float s = 0.01f * appear * (hovered ? 1.2f : 1f) * geometry().scale(k + 2);
        pose.scale(s, -s, s);
        String number = String.valueOf(cost);
        int numberColor = affordable ? 0xFFF4EEFF : 0xFFFF6B6B;
        SceneText.draw(font, number, -font.width(number) / 2f, -4.5f, numberColor, true, pose.last().pose(), buffers, Font.DisplayMode.NORMAL,
                0, LightCoordsUtil.FULL_BRIGHT);
        FormattedCharSequence rune = runes[k];
        if (rune != null) {
            pose.scale(0.55f, 0.55f, 0.55f);
            SceneText.draw(font, rune, -font.width(rune) / 2f, 26f, affordable ? 0xFFB9A6FF : 0xFF7A4A55, false, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
            String gems = "◆".repeat(k + 1);
            SceneText.draw(font, gems, -font.width(gems) / 2f, 37f, 0xFF5B86FF, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0,
                    LightCoordsUtil.FULL_BRIGHT);
        }
        pose.popPose();
    }

    static void drawFloating(PoseStack pose, SceneBuffers buffers, Vec3 cam, ItemStack item, Vec3 at, float spin, float size) {
        if (size <= 0.002f) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees(spin)));
        boolean block = ItemPlacer.isBlock(item);
        float scale = block ? size * 1.4f : size;
        pose.scale(scale, scale, scale);
        ItemModels.drawStatic(item, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers);
        pose.popPose();
    }

    static void drawLapis(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 corner, int count, int light, float scale) {
        drawLapis(pose, buffers, cam, corner, count, light, scale, 0);
    }

    static void drawLapis(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3 corner, int count, int light, float scale, float rotation) {
        Vec3 pivot = corner.subtract(cam);
        pose.pushPose(); pose.translate(pivot.x, pivot.y, pivot.z);
        pose.mulPose(new org.joml.Matrix4f().rotation(Axis.YP.rotationDegrees(rotation))); pose.translate(-pivot.x, -pivot.y, -pivot.z);
        int copies = count >= 16 ? 3 : count >= 4 ? 2 : 1;
        for (int i = 0; i < copies; i++) {
            Vec3 at = corner.add((i - (copies - 1) / 2.0) * 0.05 * scale, (0.001 + i * 0.008) * scale, (i % 2) * 0.03 * scale);
            ItemPlacer.render(pose, buffers, cam, new ItemStack(Items.LAPIS_LAZULI), at, Direction.NORTH, 4242L + i * 17L, 0.8f * scale, 0f, light);
        }
        pose.popPose();
    }

    // ---- words ---------------------------------------------------------------------------------------

    @Nullable
    @Override
    public List<Component> tooltip(ItemStack carried) {
        double[] mouse = Picking.mouseGui();
        List<Component> editorTip = gridEditor.tooltip(mouse[0], mouse[1]);
        if (editorTip != null || gridEditor.editing()) return editorTip;
        Minecraft mc = Minecraft.getInstance();
        EnchantmentMenu menu = menu();
        if (menu == null) {
            return null;
        }
        switch (target) {
            case OFFER -> {
                return offerTooltip(menu, hoverOffer);
            }
            case ITEM -> {
                if (!carried.isEmpty()) {
                    return null;
                }
                List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(mc, item()));
                lines.add(Component.translatable("cinematic.enchanting.take").withStyle(ChatFormatting.GRAY));
                return lines;
            }
            case SURFACE -> {
                return carried.isEmpty() && item().isEmpty()
                        ? List.of(Component.translatable("cinematic.enchanting.empty.item").withStyle(ChatFormatting.GRAY)) : null;
            }
            case LAPIS -> {
                if (!carried.isEmpty() && !carried.is(Items.LAPIS_LAZULI)) {
                    return List.of(Component.translatable("cinematic.enchanting.only_lapis").withStyle(ChatFormatting.RED));
                }
                if (!carried.isEmpty()) {
                    return null;
                }
                int lapis = lapis();
                return lapis > 0 ? List.of(new ItemStack(Items.LAPIS_LAZULI).getHoverName().copy().append(" ×" + lapis))
                        : List.of(Component.translatable("cinematic.enchanting.empty.lapis").withStyle(ChatFormatting.GRAY));
            }
            default -> {
                return null;
            }
        }
    }

    /** The same clue and requirements the enchanting window would show. */
    private List<Component> offerTooltip(EnchantmentMenu menu, int k) {
        Minecraft mc = Minecraft.getInstance();
        List<Component> lines = new ArrayList<>();
        Optional<Holder.Reference<Enchantment>> clue = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(menu.enchantClue[k]);
        lines.add(Component.translatable("container.enchant.clue", clue.isEmpty() ? "" : Enchantment.getFullname(clue.get(), menu.levelClue[k]))
                .withStyle(ChatFormatting.WHITE));
        if (clue.isEmpty()) {
            lines.add(Component.translatable("cinematic.enchant.no_available").withStyle(ChatFormatting.RED));
            return lines;
        }
        if (mc.player.getAbilities().instabuild) {
            return lines;
        }
        lines.add(CommonComponents.EMPTY);
        int need = k + 1;
        if (mc.player.experienceLevel < menu.costs[k]) {
            lines.add(Component.translatable("container.enchant.level.requirement", menu.costs[k]).withStyle(ChatFormatting.RED));
        } else {
            MutableComponent lapisLine = need == 1 ? Component.translatable("container.enchant.lapis.one")
                    : Component.translatable("container.enchant.lapis.many", need);
            lines.add(lapisLine.withStyle(lapis() >= need ? ChatFormatting.GRAY : ChatFormatting.RED));
            MutableComponent levelLine = need == 1 ? Component.translatable("container.enchant.level.one")
                    : Component.translatable("container.enchant.level.many", need);
            lines.add(levelLine.withStyle(ChatFormatting.GRAY));
        }
        if (affordable(k)) {
            lines.add(Component.translatable("cinematic.enchanting.touch").withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return lines;
    }

    @Override
    public Component hint() {
        return Component.translatable("cinematic.hint.enchanting");
    }
}
