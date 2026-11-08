package pl.ks.profiling.safepoint.analyzer.cli.tui;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * Images drawn by the terminal itself, with the escape sequences of the inline image protocols. The terminals of the
 * iTerm2 protocol: iTerm2, WezTerm, VS Code (with terminal.integrated.enableImages); of the kitty graphics protocol:
 * kitty, Ghostty. Inside tmux the sequences are passed through to the outer terminal (tmux needs
 * {@code set -g allow-passthrough on}).
 */
public final class TerminalImages {
    private static final String ESC = "\u001b";
    private static final int KITTY_CHUNK = 4096;

    public enum Protocol {
        ITERM2,
        KITTY,
        NONE
    }

    private final Protocol protocol;
    private final boolean tmux;

    public TerminalImages(Protocol protocol, boolean tmux) {
        this.protocol = protocol;
        this.tmux = tmux;
    }

    /**
     * The protocol of the terminal the application runs in, recognized by the environment variables it sets.
     */
    public static TerminalImages detect(Map<String, String> environment) {
        return new TerminalImages(detectProtocol(environment), environment.containsKey("TMUX"));
    }

    public static TerminalImages of(Protocol protocol, Map<String, String> environment) {
        return new TerminalImages(protocol, environment.containsKey("TMUX"));
    }

    static Protocol detectProtocol(Map<String, String> environment) {
        String term = environment.getOrDefault("TERM", "");
        String termProgram = environment.getOrDefault("TERM_PROGRAM", "").toLowerCase(Locale.ROOT);
        if (term.equals("xterm-kitty") || environment.containsKey("KITTY_WINDOW_ID") || termProgram.equals("ghostty")
                || term.equals("xterm-ghostty")) {
            return Protocol.KITTY;
        }
        // LC_TERMINAL is set by iTerm2 and passed by ssh, TERM_PROGRAM inside tmux is "tmux"
        if (termProgram.equals("iterm.app") || termProgram.equals("wezterm") || termProgram.equals("vscode")
                || "iTerm2".equals(environment.get("LC_TERMINAL"))) {
            return Protocol.ITERM2;
        }
        return Protocol.NONE;
    }

    public Protocol getProtocol() {
        return protocol;
    }

    public boolean isSupported() {
        return protocol != Protocol.NONE;
    }

    /**
     * Sequence drawing the PNG image at the cursor, scaled to fit the given number of terminal cells.
     */
    byte[] drawImage(byte[] png, int columns, int rows) {
        String base64 = Base64.getEncoder().encodeToString(png);
        return switch (protocol) {
            case ITERM2 -> wrap(ESC + "]1337;File=inline=1;size=" + png.length + ";width=" + columns + ";height=" + rows
                    + ";preserveAspectRatio=1:" + base64 + "\u0007");
            case KITTY -> kitty(base64, columns, rows);
            case NONE -> new byte[0];
        };
    }

    /**
     * Sequence removing the images drawn before - kitty keeps them above the text until they are deleted, iTerm2
     * replaces them with the text written over them.
     */
    byte[] removeImages() {
        if (protocol == Protocol.KITTY) {
            return wrap(ESC + "_Ga=d,q=2" + ESC + "\\");
        }
        return new byte[0];
    }

    private byte[] kitty(String base64, int columns, int rows) {
        ByteArrayOutputStream sequence = new ByteArrayOutputStream();
        for (int offset = 0; offset < base64.length(); offset += KITTY_CHUNK) {
            String chunk = base64.substring(offset, Math.min(base64.length(), offset + KITTY_CHUNK));
            boolean last = offset + KITTY_CHUNK >= base64.length();
            // The first chunk has the parameters: transmit and display (a=T) a PNG (f=100) scaled to the cells,
            // without moving the cursor (C=1) and without the responses (q=2), which would be read as keys
            String control = offset == 0
                    ? "a=T,f=100,c=" + columns + ",r=" + rows + ",C=1,q=2,m=" + (last ? 0 : 1)
                    : "m=" + (last ? 0 : 1);
            sequence.writeBytes(wrap(ESC + "_G" + control + ";" + chunk + ESC + "\\"));
        }
        return sequence.toByteArray();
    }

    private byte[] wrap(String sequence) {
        if (tmux) {
            // tmux passthrough: the escape characters of the passed sequence are doubled
            sequence = ESC + "Ptmux;" + sequence.replace(ESC, ESC + ESC) + ESC + "\\";
        }
        return sequence.getBytes(StandardCharsets.US_ASCII);
    }
}
