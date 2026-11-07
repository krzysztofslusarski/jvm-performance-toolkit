/*
 * Copyright 2020 Krzysztof Slusarski
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

import net.miginfocom.swing.MigLayout;
import pl.ks.profiling.safepoint.analyzer.commons.shared.gc.parser.GCLogFile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

class GcLogsViewerFrame extends JFrame {
    private PresentationFontProviderStandalone presentationFontProvider;
    private Long collectionIdFrom = null;
    private Long collectionIdTo = null;
    private boolean showDecorators = false;
    private JScrollPane contentScroll;
    private GcLogsPanel gcLogsPanel;
    private GcLogsViewerPanel viewerPanel;
    private GcLogsViewerFrame uberFrame;
    private GCLogFile gcLogFile;

    public GcLogsViewerFrame(PresentationFontProviderStandalone presentationFontProvider, GCLogFile gcLogFile) throws HeadlessException {
        this.presentationFontProvider = presentationFontProvider;
        this.gcLogsPanel = new GcLogsPanel();
        this.gcLogFile = gcLogFile;
        this.uberFrame = this;
        setLayout(new BorderLayout());
        setTitle("GC Logs Viewer");
        viewerPanel = new GcLogsViewerPanel();
        add(viewerPanel, BorderLayout.NORTH);
        contentScroll = new JScrollPane(gcLogsPanel);
        contentScroll.getVerticalScrollBar().setUnitIncrement(32);
        add(contentScroll, BorderLayout.CENTER);
        ThemeManager.onChange(this, () -> {
            viewerPanel.applyTheme();
            gcLogsPanel.applyTheme();
        });
        setSize(1500, 1000);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLocationRelativeTo(null);
        setVisible(true);
    }

    class GcLogsViewerPanel extends JPanel {
        public GcLogsViewerPanel() {
            setOpaque(true);
            applyTheme();
            setLayout(new MigLayout("", "[]8[]16[]8[]16[]", ""));
            JLabel inputDescription = new JLabel("GC collection id:", JLabel.LEFT);
            inputDescription.setFont(presentationFontProvider.getDefaultFont());
            add(inputDescription);
            JTextField textField = new JTextField(20);
            add(textField);
            inputDescription = new JLabel("With decorators:", JLabel.LEFT);
            inputDescription.setFont(presentationFontProvider.getDefaultFont());
            add(inputDescription);
            JCheckBox checkBox = new JCheckBox();
            add(checkBox);
            JButton button = new JButton("Show");
            button.setPreferredSize(new Dimension(100, 32));
            button.addActionListener(e -> {
                String[] range = textField.getText().split("-");
                collectionIdFrom = Long.parseLong(range[0]);
                if (range.length > 1) {
                    collectionIdTo = Long.parseLong(range[1]);
                } else {
                    collectionIdTo = collectionIdFrom;
                }

                showDecorators = checkBox.isSelected();
                gcLogsPanel.recreate();
                contentScroll.getVerticalScrollBar().setValue(0);
                contentScroll.getHorizontalScrollBar().setValue(0);
                uberFrame.revalidate();
                uberFrame.repaint();
            });
            add(button, "span");
        }

        void applyTheme() {
            Theme theme = ThemeManager.current();
            setBackground(theme.panelBackground);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, theme.border),
                    new EmptyBorder(10, 16, 10, 16)
            ));
        }
    }

    class GcLogsPanel extends JPanel {
        private JLabel info;
        private JTextArea textArea;

        public GcLogsPanel() {
            setBackground(ThemeManager.current().contentBackground);
            setOpaque(true);
            setLayout(new MigLayout("insets 16"));
            showInfo();
        }

        private void showInfo() {
            info = new JLabel("Enter collection id to view log", JLabel.LEFT);
            info.setFont(presentationFontProvider.getDefaultFont());
            info.setForeground(ThemeManager.current().hint);
            add(info);
        }

        void applyTheme() {
            Theme theme = ThemeManager.current();
            setBackground(theme.contentBackground);
            if (info != null) {
                info.setForeground(theme.hint);
            }
            if (textArea != null) {
                textArea.setBackground(theme.panelBackground);
            }
        }

        void recreate() {
            removeAll();
            StringBuilder builder = new StringBuilder();
            for (long i = collectionIdFrom; i <= collectionIdTo; i++) {
                List<String> lines = gcLogFile.getRawLogLines().get(i);
                for (String line : lines) {
                    String toShow = showDecorators ? line : line.replaceFirst(".*GC\\(", "GC(");
                    builder.append(toShow).append("\n");
                }
            }

            if (builder.length() == 0) {
                showInfo();
                return;
            }

            info = null;
            textArea = new JTextArea();
            textArea.setFont(presentationFontProvider.getMonospaceFont());
            textArea.setBorder(new EmptyBorder(8, 8, 8, 8));
            textArea.setEditable(false);
            textArea.setBackground(ThemeManager.current().panelBackground);

            textArea.setText(builder.toString());
            add(textArea);
        }
    }
}
