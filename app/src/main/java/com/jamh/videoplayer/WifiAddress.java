package com.jamh.videoplayer;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 1.7.0: the phone's address on the local Wi-Fi (or Ethernet / its own hotspot), which the cast
 * server binds to. Mobile data and VPN interfaces are never used.
 */
final class WifiAddress {

    private WifiAddress() {
    }

    @Nullable
    @SuppressWarnings("deprecation")
    static InetAddress find(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                    if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp == null) continue;
                    List<InetAddress> addrs = new ArrayList<>();
                    for (LinkAddress la : lp.getLinkAddresses()) addrs.add(la.getAddress());
                    InetAddress a = pick(addrs);
                    if (a != null) return a;
                }
            }
        } catch (RuntimeException ignored) {
        }
        // Hotspot (the phone is the access point) or OEMs that don't report the Wi-Fi network.
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !isWifiLikeName(ni.getName())) continue;
                InetAddress a = pick(Collections.list(ni.getInetAddresses()));
                if (a != null) return a;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** wlan0, swlan0 / ap0 (hotspot), eth0. Not rmnet / ccmnet (mobile) or tun (VPN). */
    static boolean isWifiLikeName(@Nullable String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("eth")
                || n.startsWith("softap");
    }

    /** Prefers a private (site-local) IPv4 address; never loopback / link-local. */
    @Nullable
    static InetAddress pick(List<InetAddress> addrs) {
        InetAddress fallback = null;
        for (InetAddress a : addrs) {
            if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()
                    || a.isAnyLocalAddress()) continue;
            if (a.isSiteLocalAddress()) return a;
            if (fallback == null) fallback = a;
        }
        return fallback;
    }
}
