package dev.dyslexic.cinematic.client;

/** Input shared by the local station editors; server actions must never receive editor clicks. */
interface LayoutEditor {
    boolean editing();
    boolean visible();
    boolean over(double x, double y);
    boolean click(double x, double y, int button);
    boolean drag(double x, double y, int button);
    boolean scroll(double x, double y, double amount);
    boolean keyPressed(int key);
    void release();
}
