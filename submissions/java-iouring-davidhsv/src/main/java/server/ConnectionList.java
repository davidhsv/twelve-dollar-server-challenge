package server;

import java.util.Arrays;

/** A list of connection ids, cleared every pass. It holds one entry per slot (a pass can name
 * every connection: all of them closing at once, a burst after a stall), so it never grows while
 * serving. */
final class ConnectionList {
    private int[] ids = new int[0];
    private int size;

    void resize(int total) { ids = Arrays.copyOf(ids, total); }

    void add(int id) { ids[size++] = id; }

    int size() { return size; }

    int get(int k) { return ids[k]; }

    void clear() { size = 0; }
}
