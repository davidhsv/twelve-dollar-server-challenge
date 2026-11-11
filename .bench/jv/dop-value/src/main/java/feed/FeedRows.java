package feed;

/** The feed being read: up to 20 rows, as columns. Texts are copied into `text` as they are read
 * (a record is dead once its cursor moves, and the posts are read in id order but answered in
 * feed order), so the rows hold offsets into it: it may move while it grows. Reset per feed. */
final class FeedRows {
    private FeedRows() {}

    static final int CAPACITY = 20;

    static final NativeBuffer text = new NativeBuffer(65536);
    static int count;
    static final long[] postId = new long[CAPACITY];
    static final long[] userId = new long[CAPACITY];
    /** In `text`: the body from bodyStart to createdStart, created_at to createdEnd, the author's
     * username from authorStart to authorEnd. */
    static final long[] bodyStart = new long[CAPACITY], createdStart = new long[CAPACITY], createdEnd = new long[CAPACITY],
            authorStart = new long[CAPACITY], authorEnd = new long[CAPACITY];
    static final long[] likes = new long[CAPACITY];
    /** Row indexes in ascending post id (the order the posts are read in). */
    static final int[] byId = new int[CAPACITY];

    static void clear() {
        text.clear();
        count = 0;
    }

    /** Row k as the API shows it; its texts are views into `text`, valid until the next feed. */
    static PostRow row(int k) {
        long t = text.address;
        return new PostRow(postId[k], Bytes.between(t + bodyStart[k], t + createdStart[k]),
                Bytes.between(t + createdStart[k], t + createdEnd[k]), Bytes.between(t + authorStart[k], t + authorEnd[k]),
                likes[k]);
    }
}
