package com.rajarajeshwari.finance.desktop;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

/**
 * Desktop behaviour: opens the application in its own window (Edge/Chrome "app mode", falling back to the
 * default browser) and puts an icon in the system tray with Open / Exit.
 * Disabled with --finance.open-window=false (server-only use).
 */
@Component
public class DesktopIntegration {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DesktopIntegration.class);

    private final ConfigurableApplicationContext context;
    private final boolean openWindow;
    private final int port;

    public DesktopIntegration(ConfigurableApplicationContext context,
                              @Value("${finance.open-window:true}") boolean openWindow,
                              @Value("${server.port:8080}") int port) {
        this.context = context;
        this.openWindow = openWindow;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        long sinceJvmStart = System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        LOG.info("STARTUP TIMING: server ready {} ms after the program was launched", sinceJvmStart);
        if (!openWindow) {
            return;
        }
        openWindow(port);
        addTrayIcon();
        LOG.info("STARTUP TIMING: window opened {} ms after the program was launched",
                System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    // ------------------------------------------------------------------ static helpers

    /** True when another copy is already serving on the configured port (and a window has been opened for it). */
    public static boolean alreadyRunning(String[] args) {
        int port = 8080;
        boolean open = true;
        for (String a : args) {
            if (a.startsWith("--server.port=")) {
                port = Integer.parseInt(a.substring("--server.port=".length()));
            }
            if (a.startsWith("--finance.open-window=")) {
                open = Boolean.parseBoolean(a.substring("--finance.open-window=".length()));
            }
            if (a.startsWith("--import-legacy") || a.equals("--stats")) {
                return false;
            }
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
        } catch (IOException e) {
            return false;
        }
        if (open) {
            openWindow(port);
        }
        return true;
    }

    /** True for a normal interactive launch (not --finance.open-window=false, not a maintenance command). */
    public static boolean desktopMode(String[] args) {
        for (String a : args) {
            if (a.equals("--finance.open-window=false") || a.startsWith("--import-legacy") || a.equals("--stats")) {
                return false;
            }
        }
        return !GraphicsEnvironment.isHeadless();
    }

    public static int portOf(String[] args) {
        for (String a : args) {
            if (a.startsWith("--server.port=")) {
                return Integer.parseInt(a.substring("--server.port=".length()));
            }
        }
        return 8080;
    }

    /** Waits until something accepts connections on the port; false when it did not within the given seconds. */
    public static boolean waitForServer(int port, int seconds) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return true;
            } catch (IOException e) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    return false;
                }
            }
        }
        return false;
    }

    public static void openWindow(int port) {
        String url = "http://127.0.0.1:" + port + "/";
        String[][] attempts = {
                {"cmd", "/c", "start", "", "msedge", "--app=" + url},
                {"cmd", "/c", "start", "", "chrome", "--app=" + url}
        };
        for (String[] cmd : attempts) {
            try {
                if (new ProcessBuilder(cmd).start().waitFor() == 0) {
                    return;
                }
            } catch (Exception ignored) {
                // try next browser
            }
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            System.out.println("Open " + url + " in your browser.");
        }
    }

    private void addTrayIcon() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Open Finance");
            open.addActionListener(e -> openWindow(port));
            MenuItem exit = new MenuItem("Exit");
            exit.addActionListener(e -> {
                int code = SpringApplication.exit(context, () -> 0);
                System.exit(code);
            });
            menu.add(open);
            menu.addSeparator();
            menu.add(exit);
            TrayIcon icon = new TrayIcon(iconImage(), "Sri Raja Rajeshwari Finance", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openWindow(port));
            SystemTray.getSystemTray().add(icon);
        } catch (Exception e) {
            System.out.println("Tray icon not available: " + e.getMessage());
        }
    }

    private static java.awt.Image iconImage() {
        try (java.io.InputStream in = DesktopIntegration.class.getResourceAsStream("/static/img/app-icon.png")) {
            if (in != null) {
                return javax.imageio.ImageIO.read(in);
            }
        } catch (IOException ignored) {
            // fall back to the drawn icon below
        }
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1f3a5f));
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.setColor(new Color(0xc9a227));
        g.setFont(new Font("Serif", Font.BOLD, 24));
        g.drawString("R", 8, 25);
        g.dispose();
        return img;
    }
}
