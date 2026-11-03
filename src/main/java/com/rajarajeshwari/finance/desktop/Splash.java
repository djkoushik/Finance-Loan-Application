package com.rajarajeshwari.finance.desktop;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

/** Small "please wait" window shown the moment the program is launched, until the application window opens. */
public final class Splash {

    private final JWindow window;

    private Splash(JWindow window) {
        this.window = window;
    }

    /** Shows the splash immediately; returns null when there is no display (server-only use). */
    public static Splash show() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        final JWindow[] holder = new JWindow[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    JWindow w = new JWindow();
                    JPanel p = new JPanel(new BorderLayout(0, 10));
                    p.setBackground(new Color(0x1f3a5f));
                    p.setBorder(BorderFactory.createCompoundBorder(
                            BorderFactory.createLineBorder(new Color(0xc9a227), 2),
                            BorderFactory.createEmptyBorder(22, 28, 22, 28)));
                    JLabel title = new JLabel("Sri Raja Rajeshwari Finance", SwingConstants.CENTER);
                    title.setForeground(Color.WHITE);
                    title.setFont(new Font("SansSerif", Font.BOLD, 20));
                    JLabel msg = new JLabel("Starting the application, please wait...", SwingConstants.CENTER);
                    msg.setForeground(new Color(0xdfe6ee));
                    msg.setFont(new Font("SansSerif", Font.PLAIN, 13));
                    JProgressBar bar = new JProgressBar();
                    bar.setIndeterminate(true);
                    bar.setPreferredSize(new Dimension(300, 8));
                    p.add(title, BorderLayout.NORTH);
                    p.add(msg, BorderLayout.CENTER);
                    p.add(bar, BorderLayout.SOUTH);
                    w.setContentPane(p);
                    w.pack();
                    Dimension s = Toolkit.getDefaultToolkit().getScreenSize();
                    w.setLocation((s.width - w.getWidth()) / 2, (s.height - w.getHeight()) / 2);
                    w.setAlwaysOnTop(true);
                    w.setVisible(true);
                    holder[0] = w;
                }
            });
        } catch (Exception e) {
            return null;
        }
        return new Splash(holder[0]);
    }

    public void close() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                window.dispose();
            }
        });
    }
}
