package server;

import java.lang.invoke.MethodHandle;

import static server.NativeLibrary.Call.BLOCKING;
import static server.NativeLibrary.LONG;

/** Environment variables as the process got them: bytes, with no charset decoding (a path or a
 * secret need not be valid in the platform's charset). */
public final class Environment {
    private Environment() {}

    private static final MethodHandle GETENV = NativeLibrary.libc("getenv", BLOCKING, LONG, LONG);

    /** The variable's bytes, or null when it is not set. */
    static byte[] get(String name) {
        try (var arena = java.lang.foreign.Arena.ofConfined()) {
            long value = (long) GETENV.invokeExact(arena.allocateFrom(name).address());
            return value == 0 ? null : Memory.toArray(value, Memory.cStringLength(value));
        } catch (Throwable t) {
            throw NativeLibrary.failure(t);
        }
    }

    public static byte[] get(String name, String otherwise) {
        byte[] value = get(name);
        return value != null ? value : Ascii.bytes(otherwise);
    }

    /** An optionally signed decimal integer, the whole value, within [min, max]; otherwise (unset,
     * not a number, out of range) `otherwise`. */
    static long integer(String name, long min, long max, long otherwise) {
        byte[] value = get(name);
        if (value == null) return otherwise;
        try {
            long n = Long.parseLong(new String(value, java.nio.charset.StandardCharsets.ISO_8859_1));
            return n >= min && n <= max ? n : otherwise;
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }
}
