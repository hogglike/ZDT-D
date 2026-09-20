// SPDX-License-Identifier: GPL-3.0-only
// Host tests of ownership and cleanup; these do not simulate Android Binder.
import android.net.IDnsResolver;
import android.net.ResolverParamsParcel;
import android.net.ResolverInfoReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DnsResolverCacheProbeTest {
    private static final class Fake extends IDnsResolver.Default {
        final List<String> calls = new ArrayList<>();
        String failure = "";
        boolean exists;
        int statsLength = 7;

        private void record(String call) {
            calls.add(call);
            if (failure.equals(call)) throw new IllegalStateException(call + " failed");
        }

        @Override public void createNetworkCache(int netId) {
            require(netId == 1000000, "Unexpected netId");
            record("create");
            if (exists) throw new IllegalStateException("EEXIST");
            exists = true;
        }

        @Override public void setResolverConfiguration(ResolverParamsParcel params) {
            record("set");
            require(exists && params.netId == 1000000, "Set without ownership");
            require(Arrays.equals(params.servers, new String[] {"127.0.0.1"}), "Unexpected server");
            require(params.tlsServers.length == 0 && params.tlsName.isEmpty(), "TLS must be disabled");
        }

        ResolverInfoReader.Info read(int netId) {
            record(exists ? "read" : "read_removed");
            require(netId == 1000000, "Unexpected read netId");
            ResolverInfoReader.Info info = new ResolverInfoReader.Info();
            info.servers = exists ? new String[] {failure.equals("mismatch") ? "192.0.2.1" : "127.0.0.1"} : new String[0];
            info.domains = new String[0];
            info.tlsServers = new String[0];
            info.params = exists ? new int[] {1800, 25, 8, 64, 1000, 1} : new int[6];
            info.stats = new int[exists ? statsLength : 0];
            info.wait_for_pending_req_timeout_count = new int[1];
            if (!exists && failure.equals("remaining")) info.servers = new String[] {"127.0.0.1"};
            return info;
        }

        @Override public void destroyNetworkCache(int netId) {
            require(netId == 1000000 && exists, "Destroy without ownership");
            record("destroy");
            exists = false;
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void run(String failure, boolean preexisting, String... expected) throws Exception {
        Fake fake = new Fake();
        fake.failure = failure;
        fake.exists = preexisting;
        boolean failed = false;
        try {
            DnsResolverCacheProbe.checkCache(fake, fake::read);
        } catch (IllegalStateException error) {
            failed = true;
        }
        require(failed == (!failure.isEmpty() || preexisting), "Wrong success/failure: " + failure);
        require(fake.calls.equals(Arrays.asList(expected)), "Wrong calls: " + fake.calls);
        require(fake.exists == (preexisting || failure.equals("destroy")), "Wrong final ownership");
    }

    public static void main(String[] args) throws Exception {
        run("", false, "create", "set", "read", "destroy", "read_removed");
        run("", true, "create");
        run("create", false, "create");
        run("set", false, "create", "set", "destroy");
        run("read", false, "create", "set", "read", "destroy");
        run("mismatch", false, "create", "set", "read", "destroy");
        run("destroy", false, "create", "set", "read", "destroy");
        run("read_removed", false, "create", "set", "read", "destroy", "read_removed");
        run("remaining", false, "create", "set", "read", "destroy", "read_removed");
        for (int length : new int[] {0, 9, 14}) {
            Fake fake = new Fake();
            fake.statsLength = length;
            DnsResolverCacheProbe.checkCache(fake, fake::read);
            require(!fake.exists, "Cleanup failed with stats length " + length);
        }
        System.out.println("PASS: 12 ownership/cleanup/variable-array scenarios; device Binder still requires verification");
    }
}
