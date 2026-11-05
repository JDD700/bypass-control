package com.pooh.passcontrol;

import android.content.pm.PackageManager;

import java.io.InputStream;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Optional Shizuku executor (v10) - the fallback write path for devices
 * where the direct Settings.System write is rejected by the
 * SettingsProvider.
 *
 * Built on the Shizuku API 12.2.0: the last API version that still offers
 * Shizuku.newProcess() (removed in API 13, which only offers the much
 * heavier UserService route). The Shizuku server stays backward compatible
 * with API-12 clients.
 *
 * EVERY Shizuku API touch lives in this class and is wrapped in a
 * Throwable catch: if Shizuku is not installed, not running, or the API
 * misbehaves, each method degrades quietly (false / -1) and the direct
 * executor is never affected. Nothing in here runs unless the user has
 * explicitly switched the executor to Shizuku (Pc.useShizuku).
 */
public final class ShizukuExec {
    private ShizukuExec() {}

    /** Is the Shizuku server binder alive (Shizuku installed AND running)? */
    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Has the user granted this app Shizuku permission? */
    public static boolean granted() {
        try {
            return binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Ask Shizuku to show its permission dialog for this app. The result
     *  is not listened for here - callers re-check granted() when the UI
     *  resumes, which keeps this class free of Activity lifecycle hooks. */
    public static void requestPermission(int requestCode) {
        try {
            if (binderAlive() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode);
            }
        } catch (Throwable ignored) {}
    }

    /** Run `settings put system pass_through <v>` with shell identity.
     *  @return the command's exit code (0 = the settings command ran), or
     *          -1 if it could not run at all (no binder, no permission,
     *          or the process failed to start). Callers must still verify
     *          by reading the setting back - Pc.write does exactly that. */
    public static int putPassThrough(int v) {
        try {
            if (!granted()) return -1;
            ShizukuRemoteProcess p = Shizuku.newProcess(
                    new String[]{"settings", "put", "system", Pc.KEY, String.valueOf(v)},
                    null, null);
            drainQuietly(p);
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Consume the process output on a throwaway thread so a chatty
     *  command can never block on a full pipe while we waitFor() it. */
    private static void drainQuietly(final Process p) {
        Thread t = new Thread(() -> {
            try {
                InputStream in = p.getInputStream();
                byte[] buf = new byte[1024];
                while (in.read(buf) != -1) { /* discard */ }
            } catch (Throwable ignored) {}
            try {
                InputStream err = p.getErrorStream();
                byte[] buf = new byte[1024];
                while (err.read(buf) != -1) { /* discard */ }
            } catch (Throwable ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }
}
