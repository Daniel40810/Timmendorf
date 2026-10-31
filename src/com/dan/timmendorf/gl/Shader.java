package com.dan.timmendorf.gl;

import com.jogamp.opengl.GL4;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Ein GLSL-Programm aus Textdateien in {@code com/dan/timmendorf/gl/shader} (aus Haithabu übernommen). Zeilen
 * {@code #include "datei.glsl"} werden eingesetzt; ein Pfad mit führendem / holt einen Baustein eines Packages aus dem
 * Klassenpfad (etwa {@code /com/dan/weathering/gl/weathering.glsl}). Fehler des Treibers kommen mit Dateiname und Zeile zurück,
 * damit man sie im Quelltext findet.
 * <p>
 * Steht {@code -Dtimmendorf.shaders=<ordner>} beim Start, liest das Programm die Dateien von dort statt aus dem
 * Jar; mit F5 lassen sich Shader dann zur Laufzeit neu laden.
 */
public final class Shader {
    public static final String RES = "/com/dan/timmendorf/gl/shader/";

    public final String name;
    private final String[] files;
    private final String[] defines;
    int program;
    private final Map<String, Integer> loc = new HashMap<>();

    /** files: Dateinamen, die Endung bestimmt die Stufe (.vert, .frag, .comp, .geom). */
    public Shader(String name, String[] defines, String... files) {
        this.name = name;
        this.files = files;
        this.defines = defines == null ? new String[0] : defines;
    }

    public int id() { return program; }

    /** Die Dateien dieses Programms (für die Prüfung). */
    public String[] files() { return files.clone(); }

    /** Übersetzt und bindet; bei Fehlern GLException mit Treibermeldung. */
    public void build(GL4 gl) {
        int[] stages = new int[files.length];
        int prog = gl.glCreateProgram();
        try {
            for (int i = 0; i < files.length; i++) {
                String f = files[i];
                int type = f.endsWith(".vert") ? GL4.GL_VERTEX_SHADER : f.endsWith(".frag") ? GL4.GL_FRAGMENT_SHADER
                        : f.endsWith(".comp") ? GL4.GL_COMPUTE_SHADER : GL4.GL_GEOMETRY_SHADER;
                Source src = source(f, defines);
                int sh = gl.glCreateShader(type);
                gl.glShaderSource(sh, 1, new String[]{src.text}, null, 0);
                gl.glCompileShader(sh);
                int[] ok = new int[1];
                gl.glGetShaderiv(sh, GL4.GL_COMPILE_STATUS, ok, 0);
                if (ok[0] == 0) {
                    String log = shaderLog(gl, sh);
                    gl.glDeleteShader(sh);
                    throw new com.jogamp.opengl.GLException("Shader " + f + " lässt sich nicht übersetzen:\n" + src.map(log));
                }
                gl.glAttachShader(prog, sh);
                stages[i] = sh;
            }
            gl.glLinkProgram(prog);
            int[] ok = new int[1];
            gl.glGetProgramiv(prog, GL4.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new com.jogamp.opengl.GLException("Programm " + name + " lässt sich nicht binden:\n" + programLog(gl, prog));
        } catch (RuntimeException e) {
            for (int s : stages) if (s != 0) gl.glDeleteShader(s);
            gl.glDeleteProgram(prog);
            throw e;
        }
        for (int s : stages) { if (s != 0) { gl.glDetachShader(prog, s); gl.glDeleteShader(s); } }
        if (program != 0) gl.glDeleteProgram(program);
        program = prog;
        loc.clear();
    }

    public void use(GL4 gl) { gl.glUseProgram(program); }

    public int loc(GL4 gl, String uniform) {
        Integer l = loc.get(uniform);
        if (l == null) {
            l = gl.glGetUniformLocation(program, uniform);
            loc.put(uniform, l);
        }
        return l;
    }

    public void set(GL4 gl, String u, float v) { gl.glUniform1f(loc(gl, u), v); }
    public void set(GL4 gl, String u, int v) { gl.glUniform1i(loc(gl, u), v); }
    public void set(GL4 gl, String u, float x, float y) { gl.glUniform2f(loc(gl, u), x, y); }
    public void set(GL4 gl, String u, float x, float y, float z) { gl.glUniform3f(loc(gl, u), x, y, z); }
    public void set(GL4 gl, String u, float x, float y, float z, float w) { gl.glUniform4f(loc(gl, u), x, y, z, w); }
    public void set(GL4 gl, String u, double[] v) { gl.glUniform3f(loc(gl, u), (float) v[0], (float) v[1], (float) v[2]); }
    /** Feld aus vec4; v.length / 4 Einträge. */
    public void vec4Array(GL4 gl, String u, float[] v) { gl.glUniform4fv(loc(gl, u + "[0]"), v.length / 4, v, 0); }
    /** Feld aus vec3. */
    public void vec3Array(GL4 gl, String u, float[] v) { gl.glUniform3fv(loc(gl, u + "[0]"), v.length / 3, v, 0); }
    public void mat(GL4 gl, String u, float[] m) { gl.glUniformMatrix4fv(loc(gl, u), 1, false, m, 0); }

    public void dispose(GL4 gl) { if (program != 0) gl.glDeleteProgram(program); program = 0; }

    // ---------------------------------------------------------------- Quelltext

    /** Quelltext mit eingesetzten Dateien und einer Zuordnung Zeile → Datei:Zeile für Fehlermeldungen. */
    public static final class Source {
        public final String text;
        final java.util.List<String> origin = new java.util.ArrayList<>();
        Source(String text) { this.text = text; }

        String map(String log) {
            StringBuilder sb = new StringBuilder();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?:\\d+:|\\d+\\()(\\d+)");
            for (String line : log.split("\n")) {
                java.util.regex.Matcher m = p.matcher(line);
                if (m.find()) {
                    int n = Integer.parseInt(m.group(1));
                    if (n >= 1 && n <= origin.size()) line = origin.get(n - 1) + "  " + line;
                }
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    public static Source source(String file, String[] defines) {
        StringBuilder sb = new StringBuilder();
        java.util.List<String> origin = new java.util.ArrayList<>();
        sb.append("#version 430 core\n");
        origin.add("(kopf)");
        if (defines != null) for (String d : defines) { sb.append("#define ").append(d).append('\n'); origin.add("(define)"); }
        expand(file, sb, origin, new HashSet<>());
        Source s = new Source(sb.toString());
        s.origin.addAll(origin);
        return s;
    }

    private static void expand(String file, StringBuilder sb, java.util.List<String> origin, Set<String> seen) {
        if (!seen.add(file)) return;
        String text = read(file);
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String t = l.trim();
            if (t.startsWith("#include")) {
                int a = t.indexOf('"'), b = t.lastIndexOf('"');
                expand(t.substring(a + 1, b), sb, origin, seen);
            } else {
                sb.append(l).append('\n');
                origin.add(file + ":" + (i + 1));
            }
        }
    }

    public static String read(String file) {
        String dir = System.getProperty("timmendorf.shaders");
        try {
            // Bausteine anderer Packages: absoluter Pfad im Klassenpfad (z. B. /com/dan/weathering/gl/weathering.glsl)
            if (file.startsWith("/")) {
                try (InputStream in = Shader.class.getResourceAsStream(file)) {
                    if (in == null) throw new IllegalStateException("Shader-Baustein fehlt im Klassenpfad: " + file);
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            if (dir != null) {
                Path p = Paths.get(dir, file);
                if (Files.exists(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            try (InputStream in = Shader.class.getResourceAsStream(RES + file)) {
                if (in == null) throw new IllegalStateException("Shader-Datei fehlt: " + file);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Shader-Datei " + file + ": " + e.getMessage(), e);
        }
    }

    private static String shaderLog(GL4 gl, int sh) {
        int[] len = new int[1];
        gl.glGetShaderiv(sh, GL4.GL_INFO_LOG_LENGTH, len, 0);
        byte[] b = new byte[Math.max(1, len[0])];
        gl.glGetShaderInfoLog(sh, b.length, len, 0, b, 0);
        return new String(b, 0, Math.max(0, len[0]), StandardCharsets.UTF_8);
    }

    private static String programLog(GL4 gl, int p) {
        int[] len = new int[1];
        gl.glGetProgramiv(p, GL4.GL_INFO_LOG_LENGTH, len, 0);
        byte[] b = new byte[Math.max(1, len[0])];
        gl.glGetProgramInfoLog(p, b.length, len, 0, b, 0);
        return new String(b, 0, Math.max(0, len[0]), StandardCharsets.UTF_8);
    }
}
