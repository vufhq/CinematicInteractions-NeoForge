package dev.dyslexic.cinematic.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import dev.dyslexic.cinematic.client.SceneBuffers;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A book in the world that can be read: the pages lie in two stacks on the cover boards, the top page of each painted
 * with its text ({@link BookPages}), a leaf turns by its corner, and the book closes by folding its left half over onto
 * the right. The lectern's book lies open on its desk; a book taken off a chiseled bookshelf is held in the hands.
 * <p>
 * The book stands in a frame: {@link #origin} is the middle of the spine on the cover board, {@link #a} runs across the
 * right-hand page away from the spine, {@link #s} up the page and {@link #n} out of the pages towards the reader. The
 * frame's vectors need not be a block long: the book is drawn smaller, larger or squat with them (a book standing on a
 * shelf is squat and thick).
 * <p>
 * Pages come in spreads like a printed book: the first page is a right-hand one (the inside of the cover faces it), so
 * spread k shows pages 2k-1 and 2k.
 */
final class OpenBook {
    static final double PAGE_W = 0.40;
    static final double PAGE_H = PAGE_W * BookPages.H / BookPages.W;
    /** Half the gap the pages leave at the spine. */
    static final double GUTTER = 0.007;
    /** How far the cover reaches beyond the pages, and its thickness. */
    static final double MARGIN = 0.014, COVER = 0.012;
    /** All the pages of the book together. */
    static final double STACK = 0.032;
    /** One leaf lying on another. */
    static final double LEAF = 0.0012;
    static final double TURN = 0.62, RIFFLE = 0.34;
    static final int COLS = 16, ROWS = 6;
    /** The shut book: from its spine to the edge of the boards, from top to bottom, and through both boards. */
    static final double DEEP = GUTTER + PAGE_W + MARGIN, TALL = PAGE_H + 2 * MARGIN, THICK = STACK + 2 * COVER;
    private static final Identifier BOOK_GUI = Identifier.withDefaultNamespace("textures/gui/book.png");
    /** The edges of the pages in book.png. */
    private static final float PU0 = 70 / 256f, PU1 = 76 / 256f, PV0 = 60 / 256f, PV1 = 90 / 256f;

    /**
     * What the boards are made of: a rectangle of a block-atlas texture (as fractions of it) for the boards, and another
     * for the spine, shown when the book is shut.
     */
    record Look(String texture, float u0, float v0, float u1, float v1, float su0, float sv0, float su1, float sv1) {}

    /** The lectern's book: the leather of the enchanting table's book. */
    // Inset the crop: the atlas texture's outermost pixels are bright gold, which otherwise fills the thin board edges.
    static final Look LEATHER = new Look("entity/enchantment/enchanting_table_book", 1 / 64f, 1 / 32f, 9 / 64f, 8 / 32f,
            1 / 64f, 1 / 32f, 9 / 64f, 8 / 32f);

    enum Side { NONE, LEFT, RIGHT }

    /** What came of turning a leaf: it turns, it waits for the leaf in the air, or there is no page that way. */
    enum Turned { YES, WAIT, NO_PAGE }

    /** A leaf in the air: from spread {@code from} towards {@code from + dir}. */
    private record Turn(double at, double length, int from, int dir) {
        double phase() {
            return Anim.clamp01((Scene.clock() - at) / length);
        }
    }

    /** A leaf to draw this frame: how far it lies over ({@code p}: 0 on the right, 1 on the left), its bend, its pages. */
    private record Leaf(double p, double curl, int right, int left, double lift) {}

    Vec3 origin = Vec3.ZERO, a = new Vec3(1, 0, 0), s = new Vec3(0, 1, 0), n = new Vec3(0, 0, 1);
    Look look = LEATHER;
    /** The book being shut (or held shut): its left half folds over onto the right. */
    boolean closing;
    /** How far it is shut: 0 open, 1 shut. */
    double fold;
    /** The spread the book is open at (once every leaf in the air has landed); -1 until it is known. */
    int spread = -1;
    private final List<Turn> turns = new ArrayList<>();
    private double peekLeft, peekRight;
    private double leftShare = 0.5;

    int bookId;
    private List<FormattedText> text = List.of();

    void place(Vec3 origin, Vec3 a, Vec3 s, Vec3 n) {
        this.origin = origin;
        this.a = a;
        this.s = s;
        this.n = n;
    }

    // ---- the text -----------------------------------------------------------------------------------------

    /** Reads the pages of a book (kept after it is gone, for the last frames); true when they changed. */
    boolean read(ItemStack book) {
        if (book.isEmpty()) {
            return false;
        }
        int id = ItemStack.hashItemAndComponents(book);
        if (id == bookId) {
            return false;
        }
        bookId = id;
        BookViewScreen.BookAccess access = BookViewScreen.BookAccess.fromItem(book);
        text = access == null ? List.of() : new ArrayList<>(access.pages());
        spread = spread < 0 ? -1 : Math.min(spread, lastSpread());
        return true;
    }

    int lastSpread() {
        return text.size() / 2;
    }

    int pageCount() {
        return text.size();
    }

    /** Opens at a spread, the pages already lying as they would there. */
    void openAt(int spread) {
        this.spread = Mth.clamp(spread, 0, lastSpread());
        leftShare = share();
    }

    /** How much of the book lies on the left. */
    private double share() {
        return lastSpread() == 0 ? 0.5 : spread / (double) lastSpread();
    }

    // ---- turning ------------------------------------------------------------------------------------------

    /** A leaf turns towards {@code dir} (1 forward); leaves follow one another the same way, the other way waits. */
    Turned turn(int dir) {
        int to = spread + dir;
        if (to < 0 || to > lastSpread()) {
            return Turned.NO_PAGE;
        }
        if (!turns.isEmpty()) {
            Turn last = turns.get(turns.size() - 1);
            if (last.dir() != dir || last.phase() < 0.28) {
                return Turned.WAIT;
            }
        }
        turns.add(new Turn(Scene.clock(), TURN, spread, dir));
        spread = to;
        return Turned.YES;
    }

    /** Someone else turned the book to {@code to}: a leaf turns there. */
    void follow(int to) {
        to = Math.min(lastSpread(), to);
        if (to != spread && spread >= 0) {
            turns.add(new Turn(Scene.clock(), TURN, spread, to > spread ? 1 : -1));
            spread = to;
        }
    }

    /** The book riffled open: three leaves land on the spread it opens at, the first {@code start}. */
    void riffle(double start) {
        for (int j = 0; j < 3; j++) {
            turns.add(new Turn(start + j * 0.12, RIFFLE, spread - 3 + j, 1));
        }
    }

    boolean turning() {
        return !turns.isEmpty();
    }

    /** Every frame: leaves that have landed are done, the pages shift over, a corner lifts under the cursor. */
    void settle(Side hover, boolean idle, double dt) {
        while (!turns.isEmpty() && turns.get(0).phase() >= 1) {
            turns.remove(0);
        }
        leftShare = Anim.approach(leftShare, share(), 3, dt);
        idle &= turns.isEmpty() && !closing;
        peekRight = Anim.approach(peekRight, idle && hover == Side.RIGHT && spread < lastSpread() ? 1 : 0, 9, dt);
        peekLeft = Anim.approach(peekLeft, idle && hover == Side.LEFT && spread > 0 ? 1 : 0, 9, dt);
    }

    /** Before the frame: the pages that are in sight (and those either side, ready for the next turn) are painted. */
    void preparePages() {
        if (spread < 0 || fold >= 0.999) {
            return;
        }
        Set<Integer> pages = new LinkedHashSet<>();
        for (Leaf leaf : leaves()) {
            pages.add(leaf.right());
            pages.add(leaf.left());
        }
        int[] still = still();
        pages.add(still[0]);
        pages.add(still[1]);
        if (turns.isEmpty()) {
            for (int k = -2; k <= 1; k++) {
                pages.add(2 * spread + k + (k < 0 ? -1 : 1));
            }
        }
        BookPages.prepare(bookId, text, pages.stream().mapToInt(Integer::intValue).toArray());
    }

    /** The leaves to draw: those turning, or one lifted at the corner under the cursor. */
    private List<Leaf> leaves() {
        List<Leaf> leaves = new ArrayList<>();
        int count = turns.size();
        for (int j = 0; j < count; j++) {
            Turn turn = turns.get(j);
            double phase = turn.phase();
            double eased = Anim.inOutCubic(Anim.window(phase, 0.16, 0.94));
            // corner first, then the leaf trails behind as it falls
            double curl = 0.95 * Anim.hump(Anim.window(phase, 0, 0.55)) - 0.55 * Anim.hump(Anim.window(phase, 0.5, 1));
            double p = turn.dir() > 0 ? eased : 1 - eased;
            double lift = Mth.lerp(p, LEAF * (count - j + 1), LEAF * (j + 2));
            if (turn.dir() > 0) {
                leaves.add(new Leaf(p, curl, 2 * turn.from(), 2 * turn.from() + 1, lift));
            } else {
                leaves.add(new Leaf(p, -curl, 2 * turn.from() - 2, 2 * turn.from() - 1, lift));
            }
        }
        if (count == 0 && !closing) {
            if (peekRight > 0.01) {
                leaves.add(new Leaf(0, 0.55 * Anim.smoother(peekRight), 2 * spread, 2 * spread + 1, LEAF * 2));
            }
            if (peekLeft > 0.01) {
                leaves.add(new Leaf(1, -0.55 * Anim.smoother(peekLeft), 2 * spread - 2, 2 * spread - 1, LEAF * 2));
            }
        }
        return leaves;
    }

    /** The pages lying still under the leaves: {left, right}. */
    private int[] still() {
        if (!turns.isEmpty()) {
            int from = turns.get(0).from();
            return turns.get(0).dir() > 0 ? new int[]{2 * from - 1, 2 * spread} : new int[]{2 * spread - 1, 2 * from};
        }
        int left = peekLeft > 0.01 ? 2 * spread - 3 : 2 * spread - 1;
        int right = peekRight > 0.01 ? 2 * spread + 2 : 2 * spread;
        return new int[]{left, right};
    }

    // ---- where things are ---------------------------------------------------------------------------------

    /** The page under the cursor, if it is over the open book. */
    Side under() {
        Vec3 unit = n.normalize();
        Vec3 hit = Picking.cursorOnPlane(origin.add(n.scale(0.02)), unit);
        if (hit == null) {
            return Side.NONE;
        }
        Vec3 rel = hit.subtract(origin);
        double u = rel.dot(a) / a.lengthSqr(), v = rel.dot(s) / s.lengthSqr();
        if (Math.abs(v) > PAGE_H / 2 + 0.03 || Math.abs(u) > GUTTER + PAGE_W + 0.04) {
            return Side.NONE;
        }
        return u < 0 ? Side.LEFT : Side.RIGHT;
    }

    /** The lower outer corner of a page, where the hand takes a leaf. */
    Vec3 corner(boolean left) {
        // right at the corner, so the hand waiting there hides no text
        double x = GUTTER + PAGE_W + 0.01;
        return lying(left, x, -PAGE_H / 2 - 0.005, surface(x, stack(left)) + 0.004);
    }

    /** Where a hand rests holding the book: just below its lower edge, near the outer corner. */
    Vec3 rest(boolean right) {
        return lying(!right, GUTTER + PAGE_W * 0.9, -PAGE_H / 2 - 0.018, 0.035);
    }

    /** The middle of a page as it lies. */
    Vec3 pageCenter(boolean left) {
        double x = GUTTER + PAGE_W / 2;
        return lying(left, x, 0, surface(x, stack(left)));
    }

    /** A point of the book in its frame: {@code x} from the spine along {@link #a}, {@code v} along {@link #s}, {@code h} along {@link #n}. */
    Vec3 at(double x, double v, double h) {
        return origin.add(a.scale(x)).add(s.scale(v)).add(n.scale(h));
    }

    /**
     * While a leaf turns, the hand that turns it holds its corner: {right, left} hands, the other one resting; null
     * when no leaf is being turned by hand (the leaves of a riffle fly by themselves).
     */
    @Nullable
    Vec3[] turningHands() {
        if (turns.isEmpty()) {
            return null;
        }
        Turn turn = turns.get(turns.size() - 1);
        if (turn.length() < TURN) {
            return null;
        }
        double phase = turn.phase();
        boolean forward = turn.dir() > 0;
        Leaf leaf = leaves().get(turns.size() - 1);
        Vec3 held = leafPoints(leaf)[0][COLS].add(n.normalize().scale(0.025));
        // reach the corner, carry it over, let go as it falls
        Vec3 home = rest(forward);
        double reach = Anim.smoother(Anim.window(phase, 0, 0.14));
        double letGo = Anim.smoother(Anim.window(phase, 0.72, 1));
        Vec3 working = Anim.lerp(Anim.lerp(corner(!forward), held, reach), home, letGo);
        return forward ? new Vec3[]{working, rest(false)} : new Vec3[]{rest(true), working};
    }

    /** The lower outer corner of the left half while it folds over. */
    Vec3 foldCorner() {
        return leafPoints(foldLeaf())[0][COLS].add(n.normalize().scale(0.025));
    }

    // ---- geometry -----------------------------------------------------------------------------------------

    /** Thickness of the pages lying on one side. */
    private double stack(boolean left) {
        double share = left ? leftShare : 1 - leftShare;
        return STACK * (0.12 + 0.76 * share);
    }

    /** Height of the top page of a stack over the cover, {@code x} from the spine: it rises out of the gutter. */
    private static double surface(double x, double stack) {
        double k = Math.min(1, x / (0.24 * PAGE_W));
        return stack * (0.42 + 0.58 * Math.sin(k * Math.PI / 2)) + 0.004 * Math.sin(Math.PI * Math.min(1, x / (GUTTER + PAGE_W)));
    }

    /** A point of a page lying on its side, {@code x} from the spine, {@code v} up the page from its middle. */
    private Vec3 lying(boolean left, double x, double v, double h) {
        return origin.add(a.scale(left ? -x : x)).add(s.scale(v)).add(n.scale(h));
    }

    /** The angle of a leaf {@code x} from the spine: its corner leads or trails by the bend, the lower edge more. */
    private static double angle(Leaf leaf, double x, double v) {
        double lead = 0.75 - 0.5 * (v / PAGE_H);
        double out = Math.pow(x / (GUTTER + PAGE_W), 1.5);
        return Math.max(0, Math.min(Math.PI, Math.PI * leaf.p() + leaf.curl() * lead * out));
    }

    /** The bent leaf, [row from the lower edge][column from the spine]. */
    private Vec3[][] leafPoints(Leaf leaf) {
        Vec3[][] points = new Vec3[ROWS + 1][COLS + 1];
        double stackRight = stack(false), stackLeft = stack(true);
        for (int r = 0; r <= ROWS; r++) {
            double v = -PAGE_H / 2 + PAGE_H * r / ROWS;
            double across = 0;
            double up = surface(0, stackRight) * (1 - leaf.p()) + surface(0, stackLeft) * leaf.p() + leaf.lift();
            double before = 0;
            for (int c = 0; c <= COLS; c++) {
                double x = GUTTER + PAGE_W * c / COLS;
                double th = angle(leaf, (before + x) / 2, v);
                across += Math.cos(th) * (x - before);
                up += Math.sin(th) * (x - before);
                before = x;
                // as far as it still lies on the pages under it, it follows their rise out of the gutter
                double flat = Math.cos(angle(leaf, x, v));
                double bump = flat >= 0 ? surface(x, stackRight) - surface(0, stackRight) : surface(x, stackLeft) - surface(0, stackLeft);
                points[r][c] = origin.add(a.scale(across)).add(s.scale(v)).add(n.scale(up + bump * flat * flat));
            }
        }
        return points;
    }

    private Leaf foldLeaf() {
        return new Leaf(1 - fold, 0.25 * Anim.hump(fold), -1000, 2 * spread - 1, LEAF);
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    void render(PoseStack pose, SceneBuffers buffers, Vec3 cam, int light) {
        if (fold >= 0.999) {
            drawShut(pose, buffers, cam, light);
            return;
        }
        drawCover(pose, buffers, cam, light);
        if (closing) {
            drawStack(pose, buffers, cam, false, 2 * spread, light);
            Leaf half = foldLeaf();
            Vec3[][] points = leafPoints(half);
            drawLeafFace(pose, buffers, cam, points, true, half.left(), light);
            drawCoverFace(pose, buffers, cam, points, light);
            return;
        }
        int[] still = still();
        drawStack(pose, buffers, cam, true, still[0], light);
        drawStack(pose, buffers, cam, false, still[1], light);
        for (Leaf leaf : leaves()) {
            Vec3[][] points = leafPoints(leaf);
            drawLeafFace(pose, buffers, cam, points, false, leaf.right(), light);
            drawLeafFace(pose, buffers, cam, points, true, leaf.left(), light);
        }
    }

    private float[] boardUv(TextureAtlasSprite sprite, boolean mirrored) {
        float u0 = sprite.getU(look.u0()), u1 = sprite.getU(look.u1()), v0 = sprite.getV(look.v0()), v1 = sprite.getV(look.v1());
        return mirrored ? new float[]{u1, v1, u0, v1, u0, v0, u1, v0} : new float[]{u0, v1, u1, v1, u1, v0, u0, v0};
    }

    /** The cover board under the pages. */
    private void drawCover(PoseStack pose, SceneBuffers buffers, Vec3 cam, int light) {
        TextureAtlasSprite sprite = Fx.sprite(look.texture());
        VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS));
        double halfW = DEEP, halfH = TALL / 2;
        float[] plain = boardUv(sprite, false);
        for (boolean left : new boolean[]{true, false}) {
            if (left && fold > 0) {
                continue;
            }
            Vec3 inner = origin.add(n.scale(-0.0005));
            Vec3 outer = inner.add(a.scale(left ? -halfW : halfW));
            Vec3 lo = s.scale(-halfH), hi = s.scale(halfH);
            Vec3[] top = {inner.add(lo), outer.add(lo), outer.add(hi), inner.add(hi)};
            Vec3 down = n.scale(-COVER);
            if (left) {
                quad(pose, vc, cam, new Vec3[]{top[1], top[0], top[3], top[2]}, boardUv(sprite, true), n, light, 0.9f);
            } else {
                quad(pose, vc, cam, top, plain, n, light, 0.9f);
            }
            // the board's edges
            Vec3 out = a.scale(left ? -1 : 1);
            Vec3[] side = {outer.add(lo).add(down), outer.add(hi).add(down), outer.add(hi), outer.add(lo)};
            quad(pose, vc, cam, left ? reverse(side) : side, plain, out, light, 0.7f);
            Vec3[] near = {inner.add(lo).add(down), outer.add(lo).add(down), outer.add(lo), inner.add(lo)};
            quad(pose, vc, cam, left ? reverse(near) : near, plain, s.scale(-1), light, 0.75f);
            Vec3[] far = {inner.add(hi), outer.add(hi), outer.add(hi).add(down), inner.add(hi).add(down)};
            quad(pose, vc, cam, left ? reverse(far) : far, plain, s, light, 0.75f);
        }
    }

    /** The pages lying on one side: the top page and the edges of the stack. */
    private void drawStack(PoseStack pose, SceneBuffers buffers, Vec3 cam, boolean left, int page, int light) {
        double stack = stack(left);
        Vec3[][] points = new Vec3[2][COLS + 1];
        for (int c = 0; c <= COLS; c++) {
            double x = GUTTER + PAGE_W * c / COLS;
            double h = surface(x, stack);
            points[0][c] = lying(left, x, -PAGE_H / 2, h);
            points[1][c] = lying(left, x, PAGE_H / 2, h);
        }
        drawGrid(pose, buffers, cam, points, 1, left, page, light, 0);
        // the edges of the pages: the lower one faces the reader, the outer one the side, the upper one away
        VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(BOOK_GUI));
        float[] uv = {PU0, PV1, PU1, PV1, PU1, PV0, PU0, PV0};
        for (int c = 0; c < COLS; c++) {
            Vec3 b0 = drop(points[0][c]), b1 = drop(points[0][c + 1]);
            Vec3[] edge = {b0, b1, points[0][c + 1], points[0][c]};
            quad(pose, vc, cam, left ? reverse(edge) : edge, uv, s.scale(-1), light, 0.82f);
            Vec3 t0 = drop(points[1][c]), t1 = drop(points[1][c + 1]);
            Vec3[] back = {points[1][c], points[1][c + 1], t1, t0};
            quad(pose, vc, cam, left ? reverse(back) : back, uv, s, light, 0.82f);
        }
        Vec3 o0 = points[0][COLS], o1 = points[1][COLS];
        Vec3[] outer = {drop(o0), drop(o1), o1, o0};
        quad(pose, vc, cam, left ? reverse(outer) : outer, uv, a.scale(left ? -1 : 1), light, 0.78f);
    }

    /** A point of the pages taken straight down onto the cover board. */
    private Vec3 drop(Vec3 point) {
        return point.subtract(n.scale(point.subtract(origin).dot(n) / n.lengthSqr()));
    }

    /**
     * One face of a leaf: the side that is up while it lies on the right ({@code leftFace} false) shows a right-hand
     * page, the other side a left-hand one.
     */
    private void drawLeafFace(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3[][] points, boolean leftFace, int page, int light) {
        drawGrid(pose, buffers, cam, points, ROWS, leftFace, page, light, leftFace ? -0.0004 : 0.0004);
    }

    /** The outside of the cover on the half that folds over when the book is closed. */
    private void drawCoverFace(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3[][] points, int light) {
        TextureAtlasSprite sprite = Fx.sprite(look.texture());
        VertexConsumer vc = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS));
        Vec3 c00 = points[0][0], c01 = points[0][COLS], c11 = points[ROWS][COLS], c10 = points[ROWS][0];
        // the side that is up once the half lies folded on the right: the pages of that half, then the board
        Vec3 normal = c01.subtract(c00).cross(c10.subtract(c00)).normalize();
        Vec3 off = normal.scale((stack(true) + COVER * 0.5) * n.length());
        Vec3[] face = {c00.add(off), c01.add(off), c11.add(off), c10.add(off)};
        quad(pose, vc, cam, face, boardUv(sprite, false), normal, light, 0.95f);
    }

    /** The book shut: both boards, the block of pages between them showing their three edges, and the spine. */
    private void drawShut(PoseStack pose, SceneBuffers buffers, Vec3 cam, int light) {
        TextureAtlasSprite sprite = Fx.sprite(look.texture());
        VertexConsumer board = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(TextureAtlas.LOCATION_BLOCKS));
        float[] plain = boardUv(sprite, false);
        double v = TALL / 2;
        slab(pose, board, cam, 0, DEEP, -v, v, -COVER, 0, plain, light);
        slab(pose, board, cam, 0, DEEP, -v, v, STACK, STACK + COVER, plain, light);
        float[] spine = {sprite.getU(look.su0()), sprite.getV(look.sv1()), sprite.getU(look.su1()), sprite.getV(look.sv1()),
                sprite.getU(look.su1()), sprite.getV(look.sv0()), sprite.getU(look.su0()), sprite.getV(look.sv0())};
        // the spine across the thickness (its texture's width) and the height of the book
        double x = -0.0006;
        face(pose, board, cam, at(x, -v, -COVER), at(x, -v, STACK + COVER), at(x, v, STACK + COVER), at(x, v, -COVER), spine, a.scale(-1), light, 1f);
        VertexConsumer paper = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(BOOK_GUI));
        float[] uv = {PU0, PV1, PU1, PV1, PU1, PV0, PU0, PV0};
        double x0 = GUTTER, x1 = GUTTER + PAGE_W, p = PAGE_H / 2;
        face(pose, paper, cam, at(x1, -p, 0), at(x1, p, 0), at(x1, p, STACK), at(x1, -p, STACK), uv, a, light, 0.86f);
        face(pose, paper, cam, at(x0, p, 0), at(x1, p, 0), at(x1, p, STACK), at(x0, p, STACK), uv, s, light, 0.86f);
        face(pose, paper, cam, at(x0, -p, 0), at(x1, -p, 0), at(x1, -p, STACK), at(x0, -p, STACK), uv, s.scale(-1), light, 0.8f);
    }

    /** A board of the shut book: a box from x0 to x1 along a, v0 to v1 along s, h0 to h1 along n. */
    private void slab(PoseStack pose, VertexConsumer vc, Vec3 cam, double x0, double x1, double v0, double v1, double h0, double h1, float[] uv,
            int light) {
        face(pose, vc, cam, at(x0, v0, h1), at(x1, v0, h1), at(x1, v1, h1), at(x0, v1, h1), uv, n, light, 0.95f);
        face(pose, vc, cam, at(x0, v0, h0), at(x1, v0, h0), at(x1, v1, h0), at(x0, v1, h0), uv, n.scale(-1), light, 0.95f);
        face(pose, vc, cam, at(x1, v0, h0), at(x1, v1, h0), at(x1, v1, h1), at(x1, v0, h1), uv, a, light, 0.7f);
        face(pose, vc, cam, at(x0, v1, h0), at(x1, v1, h0), at(x1, v1, h1), at(x0, v1, h1), uv, s, light, 0.75f);
        face(pose, vc, cam, at(x0, v0, h0), at(x1, v0, h0), at(x1, v0, h1), at(x0, v0, h1), uv, s.scale(-1), light, 0.75f);
    }

    /** A page drawn over a grid of points, [row][column from the spine], textured with the painted page. */
    private void drawGrid(PoseStack pose, SceneBuffers buffers, Vec3 cam, Vec3[][] points, int rows, boolean leftFace, int page, int light,
            double offset) {
        Identifier texture = BookPages.texture(bookId, page);
        VertexConsumer vc;
        float uMin, uMax, vMin, vMax;
        if (texture != null) {
            vc = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(texture));
            // a render target's texture is stored bottom row first
            uMin = 0;
            uMax = 1;
            vMin = 1;
            vMax = 0;
        } else {
            // not painted yet: bare paper for a frame
            vc = buffers.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entitySolid(BOOK_GUI));
            boolean mirrored = BookPages.leftHand(page);
            uMin = (BookPages.PAPER_U + (mirrored ? BookPages.W : 0)) / 256f;
            uMax = (BookPages.PAPER_U + (mirrored ? 0 : BookPages.W)) / 256f;
            vMin = BookPages.PAPER_V / 256f;
            vMax = (BookPages.PAPER_V + BookPages.H) / 256f;
        }
        double scale = n.length();
        int cols = points[0].length - 1;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Vec3 p00 = points[r][c], p01 = points[r][c + 1], p11 = points[r + 1][c + 1], p10 = points[r + 1][c];
                Vec3 normal = p01.subtract(p00).cross(p10.subtract(p00));
                if (normal.lengthSqr() < 1e-12) {
                    continue;
                }
                normal = normal.normalize();
                if (leftFace) {
                    normal = normal.scale(-1);
                }
                Vec3 lift = normal.scale(Math.abs(offset) * scale);
                // texture: the spine edge of a right-hand page is its left edge, of a left-hand page its right edge
                float x0 = (float) c / cols, x1 = (float) (c + 1) / cols;
                float y0 = 1 - (float) r / rows, y1 = 1 - (float) (r + 1) / rows;
                float tu0 = leftFace ? 1 - x0 : x0, tu1 = leftFace ? 1 - x1 : x1;
                tu0 = uMin + (uMax - uMin) * tu0;
                tu1 = uMin + (uMax - uMin) * tu1;
                float tv0 = vMin + (vMax - vMin) * y0, tv1 = vMin + (vMax - vMin) * y1;
                Vec3[] corners = {p00.add(lift), p01.add(lift), p11.add(lift), p10.add(lift)};
                float[] uv = {tu0, tv0, tu1, tv0, tu1, tv1, tu0, tv1};
                if (leftFace) {
                    // seen from the other side: wound the other way round (p10, p11, p01, p00)
                    corners = reverse(corners);
                    uv = new float[]{tu0, tv1, tu1, tv1, tu1, tv0, tu0, tv0};
                }
                quad(pose, vc, cam, corners, uv, normal, light, 1f);
            }
        }
    }

    private static Vec3[] reverse(Vec3[] q) {
        return new Vec3[]{q[3], q[2], q[1], q[0]};
    }

    /** A quad facing {@code out}, wound so it shows from that side whatever order its corners come in. */
    private static void face(PoseStack pose, VertexConsumer vc, Vec3 cam, Vec3 c0, Vec3 c1, Vec3 c2, Vec3 c3, float[] uv, Vec3 out, int light,
            float shade) {
        Vec3[] corners = {c0, c1, c2, c3};
        if (c1.subtract(c0).cross(c3.subtract(c0)).dot(out) < 0) {
            corners = reverse(corners);
            uv = new float[]{uv[6], uv[7], uv[4], uv[5], uv[2], uv[3], uv[0], uv[1]};
        }
        quad(pose, vc, cam, corners, uv, out, light, shade);
    }

    private static void quad(PoseStack pose, VertexConsumer vc, Vec3 cam, Vec3[] corners, float[] uv, Vec3 normal, int light, float shade) {
        int c = Math.round(255 * shade);
        Vec3 unit = normal.normalize();
        for (int i = 0; i < 4; i++) {
            Vec3 p = corners[i].subtract(cam);
            vc.addVertex(pose.last(), (float) p.x, (float) p.y, (float) p.z).setColor(c, c, c, 255).setUv(uv[i * 2], uv[i * 2 + 1])
                    .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose.last(), (float) unit.x, (float) unit.y, (float) unit.z);
        }
    }
}
