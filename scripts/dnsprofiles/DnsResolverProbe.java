// SPDX-License-Identifier: GPL-3.0-only
// Read-only capability probe for app_process. No DNS/network setters are called.
import android.os.IBinder;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

public final class DnsResolverProbe {
    private static void failure(String operation, Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) {
            error = error.getCause();
        }
        System.out.println(operation + "=ERROR " + error.getClass().getName() + ": "
                + String.valueOf(error.getMessage()).replace('\n', ' '));
    }

    public static void main(String[] args) {
        System.out.println("probe=read_only_v2");
        IBinder binder = null;
        try {
            Class<?> manager = Class.forName("android.os.ServiceManager");
            binder = (IBinder) manager.getMethod("checkService", String.class)
                    .invoke(null, "dnsresolver");
            System.out.println("dnsresolver.present=" + (binder != null));
            if (binder != null) {
                System.out.println("dnsresolver.descriptor=" + binder.getInterfaceDescriptor());
                System.out.println("dnsresolver.binder_alive=" + binder.isBinderAlive());
            }
        } catch (Throwable error) {
            failure("service_lookup", error);
        }

        try {
            Class<?> contract = Class.forName("android.net.IDnsResolver");
            Class<?> stub = Class.forName("android.net.IDnsResolver$Stub");
            System.out.println("platform_resolver_stub=available");
            Method[] methods = contract.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method method : methods) {
                String name = method.getName();
                if (name.equals("setResolverConfiguration") || name.equals("createNetworkCache")
                        || name.equals("destroyNetworkCache") || name.equals("getResolverInfo")) {
                    // Print signatures only. In particular, NEVER invoke setters.
                    System.out.println("platform_method=" + method.toGenericString());
                }
            }
            if (binder != null) {
                Object proxy = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
                for (String name : new String[] {"getInterfaceVersion", "getInterfaceHash"}) {
                    try {
                        // Stable AIDL metadata getters are the only proxy calls.
                        System.out.println("dnsresolver." + name + "="
                                + contract.getMethod(name).invoke(proxy));
                    } catch (Throwable error) {
                        failure(name, error);
                    }
                }
            }
        } catch (Throwable error) {
            failure("platform_resolver_stub", error);
        }

        try {
            Class<?> parcel = Class.forName("android.net.ResolverParamsParcel");
            Field[] fields = parcel.getFields();
            Arrays.sort(fields, Comparator.comparing(Field::getName));
            for (Field field : fields) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    // Inspect types, never retrieve resolver state or query history.
                    System.out.println("resolver_parcel_field=" + field.getName()
                            + ":" + field.getType().getName());
                }
            }
        } catch (Throwable error) {
            failure("resolver_parcel", error);
        }
        System.out.println("probe_complete=true; no configuration changed");
    }
}
