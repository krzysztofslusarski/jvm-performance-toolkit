package pl.ks.profiling.safepoint.analyzer.standalone;

import com.formdev.flatlaf.FlatLaf;
import lombok.extern.slf4j.Slf4j;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Current theme of the application, remembered between the runs (Java preferences: node of this package, key
 * darkMode). Used only on the event dispatch thread.
 */
@Slf4j
final class ThemeManager {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(ThemeManager.class);
    private static final String DARK_MODE_KEY = "darkMode";
    private static final String MACOS_APPEARANCE_PROPERTY = "apple.awt.application.appearance";
    private static final boolean MACOS = System.getProperty("os.name", "").startsWith("Mac");

    /**
     * Shown next to the theme switches - the title bars do not follow the switch until the application is restarted.
     */
    static final String TITLE_BARS_HINT = MACOS ? "The window title bars change after a restart of the application" : null;
    // Listeners of the open windows, removed when a window is closed
    private static final Map<Window, List<Runnable>> LISTENERS = new LinkedHashMap<>();

    private static Theme current = Theme.LIGHT;

    private ThemeManager() {
    }

    /**
     * On macOS the title bars of the windows are drawn by the system, in the appearance of the whole application. It
     * can be chosen only before the first window is created, so the title bars follow the theme remembered on start
     * (unless the appearance is given with -Dapple.awt.application.appearance).
     */
    static void configureMacOsTitleBars() {
        if (MACOS && System.getProperty(MACOS_APPEARANCE_PROPERTY) == null) {
            System.setProperty(MACOS_APPEARANCE_PROPERTY, isDarkRemembered() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        }
    }

    /**
     * Sets the look and feel of the remembered theme - before any component is created.
     */
    static void install() {
        current = isDarkRemembered() ? Theme.DARK : Theme.LIGHT;
        FlatLaf.setup(current.createLookAndFeel());
    }

    private static boolean isDarkRemembered() {
        return PREFERENCES.getBoolean(DARK_MODE_KEY, false);
    }

    static Theme current() {
        return current;
    }

    static void setDark(boolean dark) {
        Theme theme = dark ? Theme.DARK : Theme.LIGHT;
        if (theme == current) {
            return;
        }
        current = theme;
        PREFERENCES.putBoolean(DARK_MODE_KEY, dark);
        try {
            // Java writes the preferences only from time to time and at a normal exit, so they would be lost when
            // the application is killed
            PREFERENCES.flush();
        } catch (BackingStoreException e) {
            log.warn("The theme cannot be remembered", e);
        }

        // The standard components of every window get the colors of the new look and feel, the components styled
        // by the application are styled again by the listeners
        FlatLaf.setup(theme.createLookAndFeel());
        FlatLaf.updateUI();
        new ArrayList<>(LISTENERS.values()).forEach(listeners -> listeners.forEach(Runnable::run));
    }

    /**
     * Runs the listener after every change of the theme, as long as the window is not closed.
     */
    static void onChange(Window window, Runnable listener) {
        LISTENERS.computeIfAbsent(window, newWindow -> {
            newWindow.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    LISTENERS.remove(newWindow);
                }
            });
            return new ArrayList<>();
        }).add(listener);
    }
}
