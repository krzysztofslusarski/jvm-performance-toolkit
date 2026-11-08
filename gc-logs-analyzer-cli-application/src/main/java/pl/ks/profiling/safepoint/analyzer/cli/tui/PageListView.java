package pl.ks.profiling.safepoint.analyzer.cli.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractInteractableComponent;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.InteractableRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import pl.ks.profiling.gui.commons.Page;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The pages of the report - the sidebar of the web report. Moving the selection shows the page at once.
 */
class PageListView extends AbstractInteractableComponent<PageListView> {
    private static final int MIN_WIDTH = 16;
    private static final int MAX_WIDTH = 38;

    private final List<Page> pages;
    private final IntConsumer onSelect;
    private final IntSupplier screenColumns;
    private final int longestName;
    private int selectedIndex;
    private int scrollOffset;

    /**
     * @param screenColumns width of the terminal - the list takes at most a third of it
     */
    PageListView(List<Page> pages, IntConsumer onSelect, IntSupplier screenColumns) {
        this.pages = pages;
        this.onSelect = onSelect;
        this.screenColumns = screenColumns;
        this.longestName = pages.stream().mapToInt(page -> page.getMenuName().length()).max().orElse(0);
    }

    private int width() {
        int max = Math.min(MAX_WIDTH, screenColumns.getAsInt() / 3);
        return Math.max(MIN_WIDTH, Math.min(max, longestName + 3));
    }

    int getSelectedIndex() {
        return selectedIndex;
    }

    @Override
    protected InteractableRenderer<PageListView> createDefaultRenderer() {
        return new InteractableRenderer<>() {
            @Override
            public TerminalPosition getCursorLocation(PageListView component) {
                return null;
            }

            @Override
            public TerminalSize getPreferredSize(PageListView component) {
                return new TerminalSize(width(), pages.size());
            }

            @Override
            public void drawComponent(TextGUIGraphics graphics, PageListView component) {
                component.paint(graphics);
            }
        };
    }

    @Override
    protected Interactable.Result handleKeyStroke(KeyStroke keyStroke) {
        int previous = selectedIndex;
        int lastIndex = pages.size() - 1;
        switch (keyStroke.getKeyType()) {
            case ArrowDown -> selectedIndex = Math.min(lastIndex, selectedIndex + 1);
            case ArrowUp -> selectedIndex = Math.max(0, selectedIndex - 1);
            case PageDown -> selectedIndex = Math.min(lastIndex, selectedIndex + 10);
            case PageUp -> selectedIndex = Math.max(0, selectedIndex - 10);
            case Home -> selectedIndex = 0;
            case End -> selectedIndex = lastIndex;
            case Enter, ArrowRight -> {
                return Interactable.Result.MOVE_FOCUS_RIGHT;
            }
            default -> {
                return Interactable.Result.UNHANDLED;
            }
        }
        if (selectedIndex != previous) {
            onSelect.accept(selectedIndex);
        }
        invalidate();
        return Interactable.Result.HANDLED;
    }

    private void paint(TextGUIGraphics graphics) {
        TerminalSize size = graphics.getSize();
        graphics.setBackgroundColor(Colors.SIDEBAR);
        graphics.setForegroundColor(Colors.TEXT);
        graphics.fill(' ');
        int rows = size.getRows();
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + rows) {
            scrollOffset = selectedIndex - rows + 1;
        }
        for (int row = 0; row < rows && scrollOffset + row < pages.size(); row++) {
            int index = scrollOffset + row;
            graphics.clearModifiers();
            if (index == selectedIndex) {
                graphics.setBackgroundColor(isFocused() ? Colors.SELECTED : Colors.SELECTED_UNFOCUSED);
                graphics.setForegroundColor(Colors.TITLE);
                graphics.enableModifiers(SGR.BOLD);
            } else {
                graphics.setBackgroundColor(Colors.SIDEBAR);
                graphics.setForegroundColor(Colors.TEXT);
            }
            graphics.putString(0, row, " " + Formats.pad(pages.get(index).getMenuName(), size.getColumns() - 1));
        }
    }
}
