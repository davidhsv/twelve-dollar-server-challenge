package server;

import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/** JsonEscape's copy and check a vector register at a time, with the Vector API (an incubating
 * JDK module): 256-bit vectors where the machine has them (not 512: on the box's Cascade Lake a
 * 512-bit instruction lowers the core's clock for the code that follows), else the widest it
 * has. */
final class VectorEscapeScan {
    private VectorEscapeScan() {}

    private static final VectorSpecies<Byte> SPECIES =
            ByteVector.SPECIES_PREFERRED.length() > 32 ? ByteVector.SPECIES_256 : ByteVector.SPECIES_PREFERRED;
    private static final int WIDTH = SPECIES.length();
    private static final ByteOrder ORDER = ByteOrder.nativeOrder();

    static int width() { return WIDTH; }

    /** Copies the n (at least width()) bytes at from to to; true when one needs escaping. The
     * last vector overlaps the one before it. The loop body stays in one method so that C2
     * keeps the vectors and masks in registers. */
    static boolean copyAndCheck(long from, long to, long n) {
        long last = n - WIDTH;
        boolean found = false;
        for (long i = 0; ; i += WIDTH) {
            if (i > last) i = last;
            ByteVector v = ByteVector.fromMemorySegment(SPECIES, Memory.all(), from + i, ORDER);
            v.intoMemorySegment(Memory.all(), to + i, ORDER);
            VectorMask<Byte> special = v.compare(VectorOperators.ULT, (byte) 0x20)
                    .or(v.compare(VectorOperators.EQ, (byte) '"'))
                    .or(v.compare(VectorOperators.EQ, (byte) '\\'));
            found |= special.anyTrue();
            if (i == last) return found;
        }
    }
}
