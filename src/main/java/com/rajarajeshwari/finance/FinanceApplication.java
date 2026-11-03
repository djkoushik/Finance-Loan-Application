package com.rajarajeshwari.finance;

import com.rajarajeshwari.finance.desktop.DesktopIntegration;
import com.rajarajeshwari.finance.desktop.Splash;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

@SpringBootApplication
public class FinanceApplication {

    /** Held for the life of the process: a second launch (even while the first is still starting) cannot take it. */
    private static final int INSTANCE_LOCK_PORT = 54328;
    private static final int START_TIMEOUT_SECONDS = 180;

    @SuppressWarnings("unused")
    private static ServerSocket instanceLock; // kept referenced so it is never closed early

    public static void main(String[] args) {
        boolean desktop = DesktopIntegration.desktopMode(args);
        Splash splash = desktop ? Splash.show() : null; // feedback in the first second, before anything slow

        if (desktop) {
            instanceLock = acquireInstanceLock();
            if (instanceLock == null) {
                // The first copy is still starting up (or already running): don't start a second database, just wait for it.
                int port = DesktopIntegration.portOf(args);
                if (DesktopIntegration.waitForServer(port, START_TIMEOUT_SECONDS)) {
                    DesktopIntegration.openWindow(port);
                }
                if (splash != null) {
                    splash.close();
                }
                return;
            }
        }
        // Second launch while the app is already running: just bring up a window and leave.
        if (DesktopIntegration.alreadyRunning(args)) {
            if (splash != null) {
                splash.close();
            }
            return;
        }
        try {
            // not headless: the system tray icon needs AWT
            new SpringApplicationBuilder(FinanceApplication.class).headless(false).run(args);
        } catch (Throwable t) {
            if (splash != null) {
                splash.close();
            }
            if (desktop) {
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                JOptionPane.showMessageDialog(null,
                        "The application could not start.\n\n" + root + "\n\nPlease restart the computer and try again. "
                                + "If it still fails, send the file app.log from the data folder to support.",
                        "Sri Raja Rajeshwari Finance", JOptionPane.ERROR_MESSAGE);
            }
            t.printStackTrace();
            System.exit(1);
        }
        if (splash != null) {
            splash.close();
        }
    }

    private static ServerSocket acquireInstanceLock() {
        try {
            return new ServerSocket(INSTANCE_LOCK_PORT, 1, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            return null;
        }
    }
}
