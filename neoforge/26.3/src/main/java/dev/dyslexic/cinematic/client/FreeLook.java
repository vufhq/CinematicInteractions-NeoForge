package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Looking round while at work: with a key (Left Alt, rebindable; held, or pressed on and off, as set), the camera lifts
 * off the scene's pose, over the head, and settles on an orbit a couple of blocks behind and to the side of the player,
 * showing them at work; the mouse turns the orbit right round (behind the player too), the wheel brings it nearer or
 * further, and walls keep it in. Meanwhile the station takes no clicks, its panels fade out and the player is drawn as
 * others see them. Letting go, the camera returns along the same arc.
 */
public final class FreeLook {
    private FreeLook() {}

    public static final KeyMapping LOOK = new KeyMapping("key.cinematic.look", InputConstants.Type.KEYBOARD, InputConstants.KEY_LALT,
            CinematicKeys.CATEGORY);

    /** Where the orbit sets off: turned this far from the way the player faces, looking down this much. */
    private static final float START_TURN = 38f, START_PITCH = 24f;
    private static final double START_DISTANCE = 2.5, NEAREST = 1.3, FURTHEST = 4.5;
    /** How quickly the camera goes over and back (1/s). */
    private static final double RATE = 5.5;

    private static boolean wanted;
    private static double amount;
    private static float yaw, pitch;
    private static double distance = START_DISTANCE;
    private static boolean grabbed;
    private static double savedX, savedY, lastX, lastY;
    /** The game's camera is in third person because of the look round (so the player's body is drawn). */
    private static boolean third;

    /** 0..1: how far the camera has gone over to the orbit (eased). */
    public static double amount() {
        return Anim.smoother(amount);
    }

    /** Looking round or on the way there or back: the station takes no clicks and its panels give way. */
    public static boolean looking() {
        return wanted || amount > 0.02;
    }

    /** Whether the player's own body is drawn (by the game, as others see it) instead of the scene's arms. */
    public static boolean showsBody() {
        return third;
    }

    /** The look key pressed over the scene; true when it was taken. */
    static boolean keyPressed(int key, int scanCode) {
        if (!LOOK.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0)) || Scene.phase != Scene.Phase.ACTIVE) {
            return false;
        }
        wanted = !Settings.lookToggles() || !wanted;
        return true;
    }

    static boolean keyReleased(int key, int scanCode) {
        if (!LOOK.matches(new net.minecraft.client.input.KeyEvent(key, scanCode, 0))) {
            return false;
        }
        if (!Settings.lookToggles()) {
            wanted = false;
        }
        return true;
    }

    /** Back to work (Esc or a step key while looking round). */
    static void stop() {
        wanted = false;
    }

    /** Nearer or further, with the wheel. */
    static void scroll(double amount) {
        distance = Mth.clamp(distance - amount * 0.35, NEAREST, FURTHEST);
    }

    /** What the bottom line says while looking round. */
    static Component hint() {
        return Component.translatable(Settings.lookToggles() ? "cinematic.look.toggle" : "cinematic.look.hold", KeyLabels.of(LOOK));
    }

    /** Every frame: eases over and back, turns the orbit with the mouse, draws the body while the camera is away. */
    static void frame(double dt) {
        Minecraft mc = Minecraft.getInstance();
        if (Scene.phase != Scene.Phase.ACTIVE || !(mc.gui.screen() instanceof SceneScreen) || !mc.isWindowActive() && !Showcase.ENABLED) {
            // (Alt+Tab presses Alt on its way out of the game: leaving the window ends the look round; the scripted
            // walkthrough presses its keys whichever window has the focus)
            wanted = false;
        }
        if (wanted && !grabbed) {
            grab(mc);
        } else if (!wanted && grabbed) {
            release(mc);
        }
        if (grabbed) {
            double dx = mc.mouseHandler.xpos() - lastX, dy = mc.mouseHandler.ypos() - lastY;
            lastX = mc.mouseHandler.xpos();
            lastY = mc.mouseHandler.ypos();
            // as the game turns the player's head
            double s = mc.options.sensitivity().get() * 0.6 + 0.2;
            double k = s * s * s * 8.0 * 0.15;
            yaw += (float) (dx * k);
            pitch = Mth.clamp(pitch + (float) (dy * k), -20f, 80f);
        }
        amount = Anim.approach(amount, wanted ? 1 : 0, RATE, dt);
        if (amount < 1e-3 && !wanted) {
            amount = 0;
        }
        // the body is drawn once the camera is well away from the head (and while it comes back from afar)
        boolean needThird = Scene.station != null && (Scene.phase == Scene.Phase.ACTIVE ? amount() > 0.3 : third && outroFar(mc));
        if (needThird != third) {
            third = needThird;
            mc.options.setCameraType(third ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        }
    }

    /** Stepping away while looking round: still far from the head, keep the body drawn. */
    private static boolean outroFar(Minecraft mc) {
        CameraRig.Pose last = CameraRig.last();
        return Scene.phase == Scene.Phase.OUTRO && last != null && mc.player != null && last.pos().distanceTo(mc.player.getEyePosition()) > 0.7;
    }

    private static void grab(Minecraft mc) {
        savedX = mc.mouseHandler.xpos();
        savedY = mc.mouseHandler.ypos();
        InputConstants.grabMouse(mc.getWindow(), savedX, savedY);
        lastX = mc.mouseHandler.xpos();
        lastY = mc.mouseHandler.ypos();
        grabbed = true;
        if (amount < 0.05 && mc.player != null) {
            // behind the player, a little to the side and from above: the work shows past the shoulder
            yaw = mc.player.getYRot() + START_TURN;
            pitch = START_PITCH;
            distance = START_DISTANCE;
        }
    }

    private static void release(Minecraft mc) {
        InputConstants.releaseMouse(mc.getWindow(), savedX, savedY);
        mc.mouseHandler.xpos = savedX;
        mc.mouseHandler.ypos = savedY;
        grabbed = false;
    }

    /** The scene is over: the cursor and the eyes' view back at once. */
    static void reset() {
        Minecraft mc = Minecraft.getInstance();
        wanted = false;
        amount = 0;
        if (grabbed) {
            release(mc);
        }
        if (third) {
            third = false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
    }

    /** The camera while looking round: the scene's pose carried over an arc above the head to the orbit. */
    static CameraRig.Pose apply(CameraRig.Pose scene) {
        double w = amount();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientStation station = Scene.station;
        if (w <= 1e-3 || player == null || station == null || mc.level == null) {
            return scene;
        }
        Vec3 focus = focus(player, station);
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        double d = distance;
        // walls keep the camera in, as in the game's own third person
        BlockHitResult hit = mc.level.clip(new ClipContext(focus, focus.subtract(dir.scale(d + 0.3)), ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.MISS) {
            d = Math.max(0.5, hit.getLocation().distanceTo(focus) - 0.3);
        }
        Vec3 orbit = focus.subtract(dir.scale(d));
        Vec3 pos = Anim.lerp(scene.pos(), orbit, w).add(0, 0.35 * Anim.hump(w), 0);
        float turnedYaw = scene.yaw() + Mth.wrapDegrees(yaw - scene.yaw()) * (float) w;
        return new CameraRig.Pose(pos, turnedYaw, Mth.lerp((float) w, scene.pitch(), pitch), Mth.lerp((float) w, scene.roll(), 0f));
    }

    /** What the orbit turns round: the player's chest, drawn a little towards the work, so both stay in the frame. */
    private static Vec3 focus(LocalPlayer player, ClientStation station) {
        double chest = station instanceof CampfireStation ? 0.45 : player.isCrouching() ? 0.85 : 1.05;
        return Anim.lerp(player.position().add(0, chest, 0), station.workPoint(), 0.3);
    }
}
