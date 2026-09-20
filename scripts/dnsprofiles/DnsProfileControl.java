// SPDX-License-Identifier: GPL-3.0-only
// Narrow control helper for the supervised single-profile prototype.
import android.net.IDnsResolver;
import android.net.Network;
import android.net.ResolverInfoReader;
import android.net.ResolverParamsParcel;
import android.os.IBinder;
import android.system.Os;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.Proxy;
import javax.net.ssl.HttpsURLConnection;
import java.util.Arrays;

public final class DnsProfileControl {
    static final int NET_ID = 28200;
    static final String DNS = "10.253.241.2";
    static final String MARKER = "zdtd-profile.invalid";
    static final String ANSWER = "192.0.2.123";

    static IBinder binder() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("checkService", String.class).invoke(null, "dnsresolver");
        if (binder == null || !"android.net.IDnsResolver".equals(binder.getInterfaceDescriptor())) {
            throw new IllegalStateException("DNS Resolver unavailable");
        }
        return binder;
    }

    static void query(Network network, boolean profile) throws Exception {
        boolean marker = false;
        try {
            InetAddress[] addresses = network == null ? InetAddress.getAllByName(MARKER) : network.getAllByName(MARKER);
            for (InetAddress address : addresses) marker |= ANSWER.equals(address.getHostAddress());
            System.out.println("marker_answers=" + Arrays.toString(addresses));
        } catch (UnknownHostException expectedOnSystem) {
            System.out.println("marker_answers=not_resolved");
        }
        if (marker != profile) throw new IllegalStateException("DNS profile marker mismatch");
        String hostname = profile ? "gemini.google.com" : "example.com";
        InetAddress[] real = network == null ? InetAddress.getAllByName(hostname) : network.getAllByName(hostname);
        if (real.length == 0) throw new IllegalStateException("No public DNS answer");
        System.out.println("public_answers=" + hostname + " " + Arrays.toString(real));
        if (network == null) {
            HttpsURLConnection connection = (HttpsURLConnection) new URL("https://example.com/").openConnection(Proxy.NO_PROXY);
            try {
                connection.setConnectTimeout(7000); connection.setReadTimeout(7000);
                connection.setRequestMethod("HEAD"); connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                if (status < 200 || status >= 400) throw new IllegalStateException("HTTPS status " + status);
                System.out.println("https_direct_status=" + status);
            } finally { connection.disconnect(); }
        }
        System.out.println("QUERY_PASS=" + (profile ? "profile" : "system") + "; uid=" + Os.getuid());
    }

    public static void main(String[] args) {
        try {
            if (Os.getuid() != 0 || args.length == 0) throw new IllegalArgumentException("Root and action required");
            String action = args[0];
            if (action.equals("query-uid")) {
                if (args.length != 3) throw new IllegalArgumentException("query-uid UID profile|system");
                int uid = Integer.parseInt(args[1]);
                if (uid < 10000 || uid > 19999) throw new IllegalArgumentException("Unsupported app UID");
                if (!args[2].equals("profile") && !args[2].equals("system")) throw new IllegalArgumentException("Unknown mode");
                // Fresh process, real calling UID for netd; never impersonate the app's SELinux domain.
                Os.setgid(uid);
                Os.setuid(uid);
                query(null, args[2].equals("profile"));
                return;
            }
            if (action.equals("query-network")) {
                // Constructor comes from the platform; no guessed network-handle bit layout.
                Network network = Network.class.getConstructor(int.class).newInstance(NET_ID);
                query(network, true);
                return;
            }
            IBinder binder = binder();
            IDnsResolver resolver = IDnsResolver.Stub.asInterface(binder);
            if (resolver.getInterfaceVersion() < 1) throw new IllegalStateException("Stable resolver required");
            switch (action) {
                case "create":
                    resolver.createNetworkCache(NET_ID);
                    break;
                case "destroy":
                    resolver.destroyNetworkCache(NET_ID);
                    break;
                case "configure": {
                    ResolverParamsParcel p = new ResolverParamsParcel();
                    p.netId = NET_ID;
                    p.sampleValiditySeconds = 1800; p.successThreshold = 25;
                    p.minSamples = 8; p.maxSamples = 64; p.baseTimeoutMsec = 2000; p.retryCount = 2;
                    p.servers = new String[] {DNS}; p.domains = new String[0];
                    p.tlsName = ""; p.tlsServers = new String[0]; p.tlsFingerprints = new String[0];
                    resolver.setResolverConfiguration(p);
                    ResolverInfoReader.Info info = ResolverInfoReader.read(binder, NET_ID);
                    if (!Arrays.equals(info.servers, p.servers)
                            || !Arrays.equals(info.params, new int[] {1800, 25, 8, 64, 2000, 2})
                            || info.tlsServers == null || info.tlsServers.length != 0) {
                        throw new IllegalStateException("Resolver configuration readback mismatch");
                    }
                    break;
                }
                case "verify-empty": {
                    ResolverInfoReader.Info info = ResolverInfoReader.read(binder, NET_ID);
                    if (info.servers == null || info.servers.length != 0
                            || info.tlsServers == null || info.tlsServers.length != 0) {
                        throw new IllegalStateException("Resolver configuration remains");
                    }
                    break;
                }
                default: throw new IllegalArgumentException("Unknown action");
            }
            System.out.println("CONTROL_OK=" + action);
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
