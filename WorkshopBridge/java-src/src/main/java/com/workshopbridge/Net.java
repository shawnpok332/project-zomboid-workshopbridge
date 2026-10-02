package com.workshopbridge;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;

/**
 * Turns raw network failures into messages a player can act on.
 *
 * There is deliberately no pre-flight "is the internet up" probe - we attempt
 * the operation and translate the failure instead. A probe would add latency
 * to every operation and can lie (captive portals, flaky DNS); the attempt
 * itself is the honest check. Failures surface to the Lua UI through the
 * job's {@code error} field ("Update failed: ...").
 */
public final class Net {
    private Net() {}

    public static String friendlyMessage(IOException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof UnknownHostException) {
                return "Couldn't reach Steam's servers - check your internet connection.";
            }
            if (t instanceof ConnectException) {
                return "Couldn't connect to Steam's servers - check your internet connection.";
            }
            if (t instanceof HttpConnectTimeoutException || t instanceof SocketTimeoutException) {
                return "Timed out reaching Steam's servers - check your internet connection.";
            }
            t = t.getCause();
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Network error: " + e : m;
    }
}
