package com.android.zdtd.service.dns;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Root/app_process bridge for modern Android DnsResolver AIDL.
 *
 * Usage:
 *   app_process /system/bin com.android.zdtd.service.dns.DnsResolverBridge set <netId> <dnsIp> <ifName>
 *   app_process /system/bin com.android.zdtd.service.dns.DnsResolverBridge clear <netId>
 */
public final class DnsResolverBridge {
    private DnsResolverBridge() {}

    private static void setFieldIfPresent(Object obj, String name, Object value) throws Exception {
        try {
            Field f = obj.getClass().getField(name);
            f.set(obj, value);
        } catch (NoSuchFieldException ignored) {
            // Older frozen AIDL versions may not have newer optional fields.
        }
    }

    private static Object resolver() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method getService = sm.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        Object binder = getService.invoke(null, "dnsresolver");
        if (binder == null) throw new IllegalStateException("dnsresolver service not found");

        Class<?> stub = Class.forName("android.net.IDnsResolver$Stub");
        Method asInterface = null;
        for (Method m : stub.getDeclaredMethods()) {
            if (m.getName().equals("asInterface") && m.getParameterTypes().length == 1) {
                asInterface = m;
                break;
            }
        }
        if (asInterface == null) throw new NoSuchMethodException("IDnsResolver.Stub.asInterface");
        asInterface.setAccessible(true);
        Object r = asInterface.invoke(null, binder);
        if (r == null) throw new IllegalStateException("dnsresolver binder interface unavailable");
        return r;
    }

    private static void invokeNoResult(Object target, String method, Class<?>[] sig, Object... args)
            throws Exception {
        Method m = target.getClass().getMethod(method, sig);
        try {
            m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }

    private static void setResolver(int netId, String dnsIp, String ifName) throws Exception {
        Object r = resolver();

        // Resolver configuration requires a named cache on modern Android.
        try {
            invokeNoResult(r, "destroyNetworkCache", new Class<?>[]{int.class}, netId);
        } catch (Throwable ignored) {
            // Best effort; the cache may not exist yet.
        }
        invokeNoResult(r, "createNetworkCache", new Class<?>[]{int.class}, netId);

        Class<?> pClass = Class.forName("android.net.ResolverParamsParcel");
        Object p = pClass.getDeclaredConstructor().newInstance();

        setFieldIfPresent(p, "netId", netId);
        setFieldIfPresent(p, "sampleValiditySeconds", 1800);
        setFieldIfPresent(p, "successThreshold", 25);
        setFieldIfPresent(p, "minSamples", 8);
        setFieldIfPresent(p, "maxSamples", 64);
        setFieldIfPresent(p, "baseTimeoutMsec", 0);
        setFieldIfPresent(p, "retryCount", 0);
        setFieldIfPresent(p, "servers", new String[]{dnsIp});
        setFieldIfPresent(p, "domains", new String[0]);
        setFieldIfPresent(p, "tlsName", "");
        setFieldIfPresent(p, "tlsServers", new String[0]);
        setFieldIfPresent(p, "tlsFingerprints", new String[0]);
        setFieldIfPresent(p, "caCertificate", "");
        setFieldIfPresent(p, "tlsConnectTimeoutMs", 0);
        setFieldIfPresent(p, "resolverOptions", null);
        setFieldIfPresent(p, "transportTypes", new int[]{4}); // TRANSPORT_VPN
        setFieldIfPresent(p, "meteredNetwork", false);
        setFieldIfPresent(p, "dohParams", null);
        setFieldIfPresent(p, "interfaceNames", new String[]{ifName});

        invokeNoResult(r, "setResolverConfiguration", new Class<?>[]{pClass}, p);

        try {
            invokeNoResult(r, "flushNetworkCache", new Class<?>[]{int.class}, netId);
        } catch (Throwable ignored) {
            // Available on modern versions; not required on old frozen AIDL.
        }

        System.out.println("OK set netId=" + netId + " dns=" + dnsIp + " if=" + ifName);
    }

    private static void clearResolver(int netId) throws Exception {
        Object r = resolver();
        try {
            invokeNoResult(r, "destroyNetworkCache", new Class<?>[]{int.class}, netId);
        } catch (Throwable e) {
            // Destroy is idempotent enough for cleanup; report but do not turn cleanup into a crash.
            System.out.println("WARN clear netId=" + netId + " " + e);
            return;
        }
        System.out.println("OK clear netId=" + netId);
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                throw new IllegalArgumentException("usage: set <netId> <dnsIp> <ifName> | clear <netId>");
            }
            String op = args[0];
            int netId = Integer.parseInt(args[1]);
            if ("set".equals(op)) {
                if (args.length != 4) throw new IllegalArgumentException("set requires netId dnsIp ifName");
                setResolver(netId, args[2], args[3]);
            } else if ("clear".equals(op)) {
                clearResolver(netId);
            } else {
                throw new IllegalArgumentException("unknown operation: " + op);
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.err.println("ERR " + t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
            System.exit(2);
        }
    }
}
