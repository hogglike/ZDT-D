package com.android.zdtd.service.dns;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

/**
 * Root/app_process bridge for Android's stable IDnsResolver AIDL.
 *
 * The hidden framework Java stubs (android.net.IDnsResolver$Stub and
 * ResolverParamsParcel) are not present in the app_process boot class path on
 * current OxygenOS/Android builds.  Talk to the stable Binder interface
 * directly instead.  Stable AIDL keeps transaction order and parcel layout
 * compatible; newly appended parcel fields are skipped by older readers using
 * the size prefix.
 *
 * Usage:
 *   app_process /system/bin com.android.zdtd.service.dns.DnsResolverBridge set <netId> <dnsIp> <ifName>
 *   app_process /system/bin com.android.zdtd.service.dns.DnsResolverBridge clear <netId>
 */
public final class DnsResolverBridge {
    private DnsResolverBridge() {}

    private static final String DESCRIPTOR = "android.net.IDnsResolver";

    // Stable AIDL order (FIRST_CALL_TRANSACTION == 1):
    // isAlive=1, registerEventListener=2, setResolverConfiguration=3,
    // getResolverInfo=4, startPrefix64Discovery=5, stopPrefix64Discovery=6,
    // getPrefix64=7, createNetworkCache=8, destroyNetworkCache=9,
    // setLogSeverity=10, flushNetworkCache=11, ...
    private static final int TRANSACTION_SET_RESOLVER_CONFIGURATION = 3;
    private static final int TRANSACTION_CREATE_NETWORK_CACHE = 8;
    private static final int TRANSACTION_DESTROY_NETWORK_CACHE = 9;
    private static final int TRANSACTION_FLUSH_NETWORK_CACHE = 11;

    private static IBinder resolverBinder() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method getService = sm.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object value = getService.invoke(null, "dnsresolver");
        if (!(value instanceof IBinder)) {
            throw new IllegalStateException("dnsresolver service not found");
        }
        return (IBinder) value;
    }

    private static void transactInt(IBinder binder, int code, int value) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(value);
            if (!binder.transact(code, data, reply, 0)) {
                throw new IllegalStateException("binder transaction " + code + " returned false");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Serialize ResolverParamsParcel using stable-AIDL parcelable framing.
     * Current AOSP field order is append-only.  The size prefix lets an older
     * dnsresolver skip fields it does not know.
     */
    private static void writeResolverParams(Parcel p, int netId, String dnsIp, String ifName) {
        final int start = p.dataPosition();
        p.writeInt(0); // parcelable byte size, patched below

        p.writeInt(netId);
        p.writeInt(1800); // sampleValiditySeconds
        p.writeInt(25);   // successThreshold
        p.writeInt(8);    // minSamples
        p.writeInt(64);   // maxSamples
        p.writeInt(0);    // baseTimeoutMsec
        p.writeInt(0);    // retryCount
        p.writeStringArray(new String[]{dnsIp}); // servers
        p.writeStringArray(new String[0]);        // domains
        p.writeString("");                        // tlsName
        p.writeStringArray(new String[0]);        // tlsServers
        p.writeStringArray(new String[0]);        // tlsFingerprints
        p.writeString("");                        // caCertificate
        p.writeInt(0);                            // tlsConnectTimeoutMs
        p.writeInt(0);                            // nullable ResolverOptionsParcel
        p.writeIntArray(new int[]{4});            // transportTypes: TRANSPORT_VPN
        p.writeBoolean(false);                    // meteredNetwork
        p.writeInt(0);                            // nullable DohParamsParcel
        p.writeStringArray(new String[]{ifName}); // interfaceNames

        final int end = p.dataPosition();
        p.setDataPosition(start);
        p.writeInt(end - start);
        p.setDataPosition(end);
    }

    private static void setResolverConfiguration(
            IBinder binder, int netId, String dnsIp, String ifName) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);

            // Parcel.writeTypedObject(non-null, 0) starts with a presence marker.
            data.writeInt(1);
            writeResolverParams(data, netId, dnsIp, ifName);

            if (!binder.transact(TRANSACTION_SET_RESOLVER_CONFIGURATION, data, reply, 0)) {
                throw new IllegalStateException("setResolverConfiguration transaction returned false");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void setResolver(int netId, String dnsIp, String ifName) throws Exception {
        IBinder binder = resolverBinder();

        // Recreate the named cache so a retry after a partial previous run is deterministic.
        try {
            transactInt(binder, TRANSACTION_DESTROY_NETWORK_CACHE, netId);
        } catch (Throwable ignored) {
            // Cache may not exist.
        }
        transactInt(binder, TRANSACTION_CREATE_NETWORK_CACHE, netId);
        setResolverConfiguration(binder, netId, dnsIp, ifName);

        try {
            transactInt(binder, TRANSACTION_FLUSH_NETWORK_CACHE, netId);
        } catch (Throwable ignored) {
            // Older frozen versions may not expose flushNetworkCache.
        }

        System.out.println("OK set netId=" + netId + " dns=" + dnsIp + " if=" + ifName);
    }

    private static void clearResolver(int netId) throws Exception {
        IBinder binder = resolverBinder();
        try {
            transactInt(binder, TRANSACTION_DESTROY_NETWORK_CACHE, netId);
        } catch (Throwable e) {
            System.out.println("WARN clear netId=" + netId + " " + e);
            return;
        }
        System.out.println("OK clear netId=" + netId);
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                throw new IllegalArgumentException(
                        "usage: set <netId> <dnsIp> <ifName> | clear <netId>");
            }
            String op = args[0];
            int netId = Integer.parseInt(args[1]);
            if ("set".equals(op)) {
                if (args.length != 4) {
                    throw new IllegalArgumentException("set requires netId dnsIp ifName");
                }
                setResolver(netId, args[2], args[3]);
            } else if ("clear".equals(op)) {
                clearResolver(netId);
            } else {
                throw new IllegalArgumentException("unknown operation: " + op);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.err.println(
                    "ERR " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
            System.exit(2);
        }
    }
}
