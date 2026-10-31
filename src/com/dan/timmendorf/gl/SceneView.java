package com.dan.timmendorf.gl;

import com.dan.timmendorf.camera.Camera;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.util.Animator;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Die 3D-Fläche (aus Geiranger): ein {@link GLCanvas} mit Kernprofil OpenGL 4, der Animator im Takt des Bildschirms
 * und die Bedienung mit Maus und Tasten.
 * <ul>
 * <li>Links ziehen: kreisen bzw. umschauen · rechts ziehen: verschieben · Rad: zoomen</li>
 * <li>W A S D: gleiten bzw. fliegen (Umschalt schneller) · Q/E: ab/auf (frei) · Pfeiltasten: drehen · Bild auf/ab: zoomen</li>
 * <li>F: frei fliegen an/aus · R: von selbst kreisen · Pos1: Grundansicht · H: HUD · F12: Foto nach docs/status · F5: Shader neu laden</li>
 * <li>1 bis 9: die Ansichten nach Namen (vom Bedienfeld gesetzt) · F1: alle Tasten · F11: nur die Szene</li>
 * </ul>
 * Mit {@code -Dtimmendorf.gljpanel=true} zeichnet statt des GLCanvas ein leichtes GLJPanel (Rückfall, falls ein
 * Treiber den Canvas im FFrame nicht zeigt).
 */
public final class SceneView extends JPanel {
    private final Renderer renderer;
    private final java.awt.Component canvas;
    private final GLAutoDrawable drawable;
    public final boolean lightweight = Boolean.getBoolean("timmendorf.gljpanel");
    private Animator animator;
    private Consumer<Camera.Mode> modeListener = m -> { };
    private Consumer<Boolean> orbitListener = b -> { };
    private final Map<Integer, Runnable> keyActions = new ConcurrentHashMap<>();

    public SceneView(Renderer renderer) {
        super(new BorderLayout());
        this.renderer = renderer;
        setBackground(new Color(8, 12, 18));
        GLProfile profile;
        try {
            profile = GLProfile.get(GLProfile.GL4);
        } catch (GLException e) {
            throw new GLException("Kein OpenGL-4-Kernprofil verfügbar: " + e.getMessage(), e);
        }
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDoubleBuffered(true);
        caps.setDepthBits(24);
        caps.setHardwareAccelerated(true);
        if (lightweight) {
            GLJPanel p = new GLJPanel(caps);
            p.addGLEventListener(renderer);
            canvas = p;
            drawable = p;
        } else {
            GLCanvas c = new GLCanvas(caps);
            c.addGLEventListener(renderer);
            canvas = c;
            drawable = c;
        }
        canvas.setFocusable(true);
        add(canvas, BorderLayout.CENTER);
        installInput();
    }

    public Renderer renderer() { return renderer; }
    public void setModeListener(Consumer<Camera.Mode> l) { modeListener = l; }
    public void setOrbitListener(Consumer<Boolean> l) { orbitListener = l; }

    /** Ein Klick in die Szene (ohne Ziehen) an (x, y) in einem Bild von w × h Punkten. */
    public interface Klick { void click(int x, int y, int w, int h); }
    private volatile Klick clickListener = (x, y, w, h) -> { };
    public void setClickListener(Klick k) { clickListener = k; }
    /** Umschalt + Klick (ohne Ziehen); seit Phase 17 ein Merkpunkt (bis Phase 16 der Rauchfaden, jetzt Strg + Klick). */
    private volatile Klick shiftClickListener = (x, y, w, h) -> { };
    public void setShiftClickListener(Klick k) { shiftClickListener = k; }
    /** Strg + Klick (ohne Ziehen); seit Phase 17 der Rauchfaden. */
    private volatile Klick ctrlClickListener = (x, y, w, h) -> { };
    public void setCtrlClickListener(Klick k) { ctrlClickListener = k; }

    /** Seit Phase 17: Strg+S (Sitzung sichern), vom Bedienfeld gesetzt. */
    public volatile Runnable speichern;

    /** Weitere Tasten, vom Bedienfeld gesetzt (null entfernt). */
    public void setKeyAction(int keyCode, Runnable action) {
        if (action == null) keyActions.remove(keyCode); else keyActions.put(keyCode, action);
    }

    public void start() {
        animator = new Animator(drawable);
        animator.setUpdateFPSFrames(60, null);
        animator.start();
        canvas.requestFocusInWindow();
    }

    public void stop() { if (animator != null) animator.stop(); }

    /** Den Mauszeiger über der Szene verbergen (Kino ab Phase 16) oder zeigen. */
    public void zeigerVerbergen(boolean weg) {
        if (weg) {
            java.awt.image.BufferedImage leer = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            canvas.setCursor(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(leer, new java.awt.Point(0, 0), "kino"));
        } else canvas.setCursor(java.awt.Cursor.getDefaultCursor());
    }

    @Override
    public boolean requestFocusInWindow() { return canvas.requestFocusInWindow(); }

    public void goHome() {
        renderer.camera.stopFahrt();
        renderer.camera.home();
        modeListener.accept(Camera.Mode.KREISEN);
        orbitListener.accept(false);
    }

    public void setMode(Camera.Mode m) {
        renderer.camera.setMode(m);
        modeListener.accept(m);
        orbitListener.accept(renderer.camera.autoOrbit);
    }

    public void toggleMode() {
        setMode(renderer.camera.mode == Camera.Mode.FREI ? Camera.Mode.KREISEN : Camera.Mode.FREI);
    }

    public void setAutoOrbit(boolean on) {
        if (on && renderer.camera.mode == Camera.Mode.FREI) setMode(Camera.Mode.KREISEN);
        renderer.camera.autoOrbit = on;
        orbitListener.accept(on);
    }

    private void installInput() {
        MouseAdapter m = new MouseAdapter() {
            int lx, ly, px, py;
            @Override public void mousePressed(MouseEvent e) {
                lx = e.getX(); ly = e.getY();
                px = lx; py = ly;
                canvas.requestFocusInWindow();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (renderer.kino.laeuft()) return;   // Phase 16: im Kino keine Klicks
                // ein Klick ohne Ziehen (für Karten und Auswahl in späteren Phasen)
                if (SwingUtilities.isLeftMouseButton(e) && Math.abs(e.getX() - px) + Math.abs(e.getY() - py) <= 4)
                    (e.isShiftDown() ? shiftClickListener : e.isControlDown() ? ctrlClickListener : clickListener).click(e.getX(), e.getY(), canvas.getWidth(), canvas.getHeight());
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (renderer.kino.laeuft()) return;   // Phase 16: im Kino führt die Regie die Kamera
                renderer.camera.stopFahrt();
                int dx = e.getX() - lx, dy = e.getY() - ly;
                lx = e.getX(); ly = e.getY();
                double hgt = Math.max(1, canvas.getHeight());
                Camera c = renderer.camera;
                if (SwingUtilities.isLeftMouseButton(e) && !e.isShiftDown()) {
                    if (c.mode != Camera.Mode.KREISEN) c.orbit(dx * 0.22, dy * 0.22);
                    else {
                        c.orbit(-dx * 0.32, dy * 0.25);
                        if (c.autoOrbit) setAutoOrbit(false);
                    }
                } else {
                    c.pan(dx / hgt, dy / hgt);
                }
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { if (!renderer.kino.laeuft()) renderer.camera.zoom(e.getPreciseWheelRotation()); }
        };
        canvas.addMouseListener(m);
        canvas.addMouseMotionListener(m);
        canvas.addMouseWheelListener(m);
        final KeyAdapter keys = new KeyAdapter() {
            private boolean moveKey(KeyEvent e, boolean down) {
                Camera c = renderer.camera;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W: c.kFwd = down; return true;
                    case KeyEvent.VK_S: c.kBack = down; return true;
                    case KeyEvent.VK_A: c.kLeft = down; return true;
                    case KeyEvent.VK_D: c.kRight = down; return true;
                    case KeyEvent.VK_E: c.kUp = down; return true;
                    case KeyEvent.VK_Q: c.kDown = down; return true;
                    case KeyEvent.VK_SHIFT: c.kRun = down; return true;
                    default: return false;
                }
            }
            @Override public void keyReleased(KeyEvent e) { moveKey(e, false); }
            @Override public void keyPressed(KeyEvent e) {
                // Phase 16: im Kino nur Esc (heraus), Leertaste (anhalten), F12 (Foto mit Balken), F5
                if (renderer.kino.laeuft()) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE -> renderer.kinoVerlassen();
                        case KeyEvent.VK_SPACE -> renderer.frozen = !renderer.frozen;
                        case KeyEvent.VK_F12 -> renderer.photoRequest = true;
                        case KeyEvent.VK_F5 -> renderer.requestShaderReload();
                        default -> { }
                    }
                    return;
                }
                // seit Phase 17: Strg+S sichert die Sitzung (F46 A)
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_S) { Runnable sv = speichern; if (sv != null) sv.run(); return; }
                if (moveKey(e, true)) { renderer.camera.stopFahrt(); renderer.camera.stopMitfahrt(); return; }
                Camera c = renderer.camera;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT: case KeyEvent.VK_RIGHT: case KeyEvent.VK_UP: case KeyEvent.VK_DOWN:
                    case KeyEvent.VK_F: case KeyEvent.VK_R: c.stopFahrt(); if (e.getKeyCode() != KeyEvent.VK_LEFT && e.getKeyCode() != KeyEvent.VK_RIGHT && e.getKeyCode() != KeyEvent.VK_UP && e.getKeyCode() != KeyEvent.VK_DOWN) c.stopMitfahrt(); break;
                    default: break;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT: c.orbit(-4, 0); break;
                    case KeyEvent.VK_RIGHT: c.orbit(4, 0); break;
                    case KeyEvent.VK_UP: c.orbit(0, -3); break;
                    case KeyEvent.VK_DOWN: c.orbit(0, 3); break;
                    case KeyEvent.VK_PAGE_UP: case KeyEvent.VK_PLUS: case KeyEvent.VK_ADD: c.zoom(-1); break;
                    case KeyEvent.VK_PAGE_DOWN: case KeyEvent.VK_MINUS: case KeyEvent.VK_SUBTRACT: c.zoom(1); break;
                    case KeyEvent.VK_HOME: goHome(); break;
                    case KeyEvent.VK_F: toggleMode(); break;
                    case KeyEvent.VK_R: setAutoOrbit(!c.autoOrbit); break;
                    case KeyEvent.VK_H: {
                        // kurz → voll → aus → kurz
                        if (renderer.showHud && !renderer.hudFull) renderer.hudFull = true;
                        else if (renderer.showHud) { renderer.showHud = false; renderer.hudFull = false; }
                        else renderer.showHud = true;
                        break;
                    }
                    case KeyEvent.VK_F5: renderer.requestShaderReload(); break;
                    case KeyEvent.VK_F12: renderer.photoRequest = true; break;
                    default: {
                        Runnable a = keyActions.get(e.getKeyCode());
                        if (a != null) a.run();
                        break;
                    }
                }
            }
        };
        canvas.addKeyListener(keys);
        canvas.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { releaseKeys(); }
        });
        // Tasten gelten im ganzen Fenster, nicht nur bei Fokus auf der Szene (außer in Textfeldern)
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(ev -> {
            if (ev.getSource() == canvas || !canvas.isShowing()) return false;
            if (ev.getID() != KeyEvent.KEY_PRESSED && ev.getID() != KeyEvent.KEY_RELEASED) return false;
            java.awt.Window w = SwingUtilities.getWindowAncestor(canvas);
            if (w == null || !w.isActive() || SwingUtilities.getWindowAncestor(ev.getComponent()) != w) return false;
            java.awt.Component fo = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (fo instanceof javax.swing.text.JTextComponent) return false;
            int kc = ev.getKeyCode();
            boolean ours = (renderer.kino.laeuft() && (kc == KeyEvent.VK_ESCAPE || kc == KeyEvent.VK_SPACE)) || kc == KeyEvent.VK_W || kc == KeyEvent.VK_A || kc == KeyEvent.VK_S || kc == KeyEvent.VK_D || kc == KeyEvent.VK_Q
                    || kc == KeyEvent.VK_E || kc == KeyEvent.VK_SHIFT || kc == KeyEvent.VK_F || kc == KeyEvent.VK_R || kc == KeyEvent.VK_H
                    || kc == KeyEvent.VK_F12 || kc == KeyEvent.VK_F5 || kc == KeyEvent.VK_HOME || keyActions.containsKey(kc);
            if (!ours) return false;
            if (ev.getID() == KeyEvent.KEY_PRESSED) keys.keyPressed(ev); else keys.keyReleased(ev);
            return kc != KeyEvent.VK_SHIFT;
        });
    }

    private void releaseKeys() {
        Camera c = renderer.camera;
        c.kFwd = c.kBack = c.kLeft = c.kRight = c.kRun = c.kUp = c.kDown = false;
    }
}
