// SPDX-License-Identifier: GPL-3.0-only
// Uses generated AOSP stable AIDL v1, not private platform Java classes.
import android.net.IDnsResolver;
import android.net.ResolverParamsParcel;
import android.net.ResolverInfoReader;
import android.os.IBinder;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

public final class DnsResolverCacheProbe {
    // Outside netd's valid network ID range (up to 65535). AOSP's resolver
    // cache uses unsigned 32-bit keys, so this cannot be a live netd network.
    // An OEM may reject this cache ID; that is reported, never worked around.
    // createNetworkCache must succeed before we may configure or destroy this ID.
    static final int TEST_NET_ID = 1000000;

    interface InfoSource {
        ResolverInfoReader.Info read(int netId) throws Exception;
    }

    static void checkCache(IDnsResolver resolver, InfoSource reader) throws Exception {
        boolean owned = false;
        Throwable primary = null;
        try {
            resolver.createNetworkCache(TEST_NET_ID);
            owned = true;
            System.out.println("cache_created=" + TEST_NET_ID);

            ResolverParamsParcel params = new ResolverParamsParcel();
            params.netId = TEST_NET_ID;
            params.sampleValiditySeconds = 1800;
            params.successThreshold = 25;
            params.minSamples = 8;
            params.maxSamples = 64;
            params.baseTimeoutMsec = 1000;
            params.retryCount = 1;
            // No DNS query is sent. This loopback address is only a readback marker.
            params.servers = new String[] {"127.0.0.1"};
            params.domains = new String[0];
            params.tlsName = "";
            params.tlsServers = new String[0];
            params.tlsFingerprints = new String[0];
            resolver.setResolverConfiguration(params);
            System.out.println("setResolverConfiguration=OK");

            ResolverInfoReader.Info info = reader.read(TEST_NET_ID);
            int[] expectedParams = {1800, 25, 8, 64, 1000, 1};
            System.out.println("readback.servers=" + Arrays.toString(info.servers));
            System.out.println("readback.params=" + Arrays.toString(info.params));
            System.out.println("readback.stats=" + Arrays.toString(info.stats));
            System.out.println("readback.timeout_counts=" + Arrays.toString(info.wait_for_pending_req_timeout_count));
            if (!Arrays.equals(params.servers, info.servers)
                    || !Arrays.equals(expectedParams, info.params)
                    || info.domains == null || info.domains.length != 0
                    || info.tlsServers == null || info.tlsServers.length != 0) {
                throw new IllegalStateException("Resolver readback differs from the test configuration");
            }
            System.out.println("readback=OK");
        } catch (Exception | Error error) {
            primary = error;
            throw error;
        } finally {
            if (owned) {
                try {
                    resolver.destroyNetworkCache(TEST_NET_ID);
                    System.out.println("cache_destroyed=" + TEST_NET_ID);
                } catch (Exception | Error error) {
                    System.out.println("cleanup=FAILED; temporary cache netId=" + TEST_NET_ID);
                    if (primary != null) primary.addSuppressed(error);
                    else throw error;
                }
            } else {
                // Includes EEXIST, permission errors and ambiguous Binder failures.
                // Never remove a cache we did not successfully create.
                System.out.println("cleanup=SKIPPED; no cache ownership acquired");
            }
        }
        // An absent cache yields no servers/stats, rather than an ENOENT exception.
        ResolverInfoReader.Info removed = reader.read(TEST_NET_ID);
        if (removed.servers == null || removed.servers.length != 0
                || removed.tlsServers == null || removed.tlsServers.length != 0) {
            throw new IllegalStateException("DNS servers remain after cache removal");
        }
        System.out.println("cleanup_readback=OK; no DNS servers remain at test netId");
    }

    public static void main(String[] args) {
        System.out.println("probe=isolated_cache_v3_1; dynamic native output arrays");
        System.out.println("scope=temporary resolver cache; no netd network, UID binding or DNS queries");
        try {
            if (args.length != 1 || !args[0].equals("--cache-selftest")) {
                throw new IllegalArgumentException("Expected --cache-selftest");
            }
            if (android.os.Process.myUid() != 0) {
                throw new SecurityException("Root is required");
            }
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("checkService", String.class).invoke(null, "dnsresolver");
            if (binder == null || !"android.net.IDnsResolver".equals(binder.getInterfaceDescriptor())) {
                throw new IllegalStateException("DNS Resolver Binder is missing or has an unexpected descriptor");
            }
            IDnsResolver resolver = IDnsResolver.Stub.asInterface(binder);
            int version = resolver.getInterfaceVersion();
            System.out.println("dnsresolver.version=" + version);
            System.out.println("dnsresolver.hash=" + resolver.getInterfaceHash());
            if (version < 1 || !resolver.isAlive()) {
                throw new IllegalStateException("Stable DNS Resolver interface is not available");
            }
            checkCache(resolver, netId -> ResolverInfoReader.read(binder, netId));
            System.out.println("RESULT=PASS; Binder configuration verified; per-app DNS is not enabled");
        } catch (Throwable error) {
            while (error instanceof InvocationTargetException && error.getCause() != null) {
                error = error.getCause();
            }
            System.out.println("RESULT=FAIL");
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
