/*
 * Copyright 2020 Krzysztof Slusarski, Artur Owczarek
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pl.ks.profiling.safepoint.analyzer.standalone;

import pl.ks.profiling.gui.commons.Page;
import pl.ks.profiling.safepoint.analyzer.commons.shared.report.JvmLogFile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

class MenuPanel extends JPanel {
    private static final Dimension BUTTON_SIZE = new Dimension(260, 32);

    private final Consumer<Page> renderPage;
    private final Consumer<JvmLogFile> openGcLogsViewer;
    private final JPanel buttonList = new JPanel();
    private final List<JLabel> sectionLabels = new ArrayList<>();
    private final List<JButton> buttons = new ArrayList<>();
    private final JButton themeButton;
    private JButton selectedButton = null;

    MenuPanel(JvmLogFile stats, Consumer<Page> renderPage, Consumer<JvmLogFile> openGcLogsViewer) {
        this.renderPage = renderPage;
        this.openGcLogsViewer = openGcLogsViewer;
        setLayout(new BorderLayout());

        buttonList.setLayout(new BoxLayout(buttonList, BoxLayout.Y_AXIS));
        buttonList.setBorder(new EmptyBorder(12, 10, 12, 10));

        buttonList.add(createSectionLabel("Navigation"));

        for (Page page : stats.getPages()) {
            buttonList.add(createPageButton(page));
            buttonList.add(Box.createRigidArea(new Dimension(0, 2)));
        }

        buttonList.add(Box.createRigidArea(new Dimension(0, 10)));
        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        buttonList.add(sep);
        buttonList.add(Box.createRigidArea(new Dimension(0, 10)));

        buttonList.add(createSectionLabel("Tools"));
        buttonList.add(createGcLogsViewerButton(stats));
        buttonList.add(Box.createRigidArea(new Dimension(0, 2)));
        themeButton = createStyledButton(themeButtonText(), false);
        themeButton.addActionListener(e -> ThemeManager.setDark(ThemeManager.current() == Theme.LIGHT));
        themeButton.setToolTipText(ThemeManager.TITLE_BARS_HINT);
        buttonList.add(themeButton);

        JScrollPane scrollPane = new JScrollPane(buttonList);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);
        applyTheme();
    }

    void applyTheme() {
        Theme theme = ThemeManager.current();
        setBackground(theme.sidebarBackground);
        setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, theme.border));
        buttonList.setBackground(theme.sidebarBackground);
        sectionLabels.forEach(label -> label.setForeground(theme.sidebarLabel));
        for (JButton button : buttons) {
            button.setBackground(button == selectedButton ? theme.selectedBackground : theme.sidebarBackground);
            button.setForeground(button == selectedButton ? theme.selectedForeground : UIManager.getColor("Button.foreground"));
        }
        themeButton.setText(themeButtonText());
    }

    private static String themeButtonText() {
        return ThemeManager.current() == Theme.LIGHT ? "Dark mode" : "Light mode";
    }

    private JLabel createSectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setBorder(new EmptyBorder(0, 8, 8, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        sectionLabels.add(label);
        return label;
    }

    private JButton createGcLogsViewerButton(JvmLogFile stats) {
        JButton button = createStyledButton("GC logs viewer", true);
        button.addActionListener(e -> openGcLogsViewer.accept(stats));
        return button;
    }

    private JButton createPageButton(Page page) {
        JButton button = createStyledButton(page.getMenuName(), true);
        button.addActionListener(renderPage(page));
        return button;
    }

    /**
     * @param selectable whether the button stays highlighted after it is clicked
     */
    private JButton createStyledButton(String text, boolean selectable) {
        JButton button = new JButton(text);
        buttons.add(button);
        button.setMaximumSize(BUTTON_SIZE);
        button.setPreferredSize(BUTTON_SIZE);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                if (button != selectedButton) {
                    button.setBackground(ThemeManager.current().sidebarHover);
                }
            }
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                if (button != selectedButton) {
                    button.setBackground(ThemeManager.current().sidebarBackground);
                }
            }
        });

        if (selectable) {
            button.addActionListener(e -> {
                Theme theme = ThemeManager.current();
                if (selectedButton != null) {
                    selectedButton.setBackground(theme.sidebarBackground);
                    selectedButton.setForeground(UIManager.getColor("Button.foreground"));
                }
                selectedButton = button;
                button.setBackground(theme.selectedBackground);
                button.setForeground(theme.selectedForeground);
            });
        }

        return button;
    }

    private ActionListener renderPage(Page page) {
        return e -> renderPage.accept(page);
    }
}
