package feed;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** libfeed.so (net.c and db.c), loaded once, and the one way a function of it is declared.
 *
 * Every pointer crosses as a 64-bit integer: on the 64-bit ABIs this runs on a pointer argument
 * and a long are passed the same way, so no MemorySegment is made per call. Each function is a
 * downcall handle in a static final field next to the Java method that calls it with invokeExact
 * and the exact signature, which C2 compiles to a direct call. */
final class NativeLibrary {
    private NativeLibrary() {}

    /** How a call may run. */
    enum Call {
        /** Short, never waits: the thread stays in Java state for it (Linker.Option.critical),
         * which saves an ordinary downcall's state transitions. Only native memory is passed. */
        CRITICAL,
        /** May wait (io_uring_enter with a timeout, COMMIT, a write, opening): an ordinary
         * downcall, during which the JVM can bring the thread to a safepoint. */
        BLOCKING
    }

    static final ValueLayout INT = ValueLayout.JAVA_INT;
    static final ValueLayout LONG = ValueLayout.JAVA_LONG;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBRARY = SymbolLookup.libraryLookup(
            Path.of(System.getProperty("feed.lib", "bin/libfeed.so")), Arena.global());
    private static final Linker.Option[] CRITICAL = { Linker.Option.critical(false) };
    private static final Linker.Option[] BLOCKING = {};

    /** A function of libfeed.so returning ret (null for void). */
    static MethodHandle function(String symbol, Call call, MemoryLayout ret, MemoryLayout... args) {
        return downcall(LIBRARY, symbol, call, ret, args);
    }

    /** A function of the C library. */
    static MethodHandle libc(String symbol, Call call, MemoryLayout ret, MemoryLayout... args) {
        return downcall(LINKER.defaultLookup(), symbol, call, ret, args);
    }

    private static MethodHandle downcall(SymbolLookup lookup, String symbol, Call call, MemoryLayout ret,
                                         MemoryLayout... args) {
        var address = lookup.find(symbol).orElseThrow(() -> new IllegalStateException("no native function " + symbol));
        var descriptor = ret == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(ret, args);
        return LINKER.downcallHandle(address, descriptor, call == Call.CRITICAL ? CRITICAL : BLOCKING);
    }

    /** What a failed invokeExact throws; with exact signatures it does not happen. */
    static Error failure(Throwable t) { return new AssertionError("native call failed", t); }
}
