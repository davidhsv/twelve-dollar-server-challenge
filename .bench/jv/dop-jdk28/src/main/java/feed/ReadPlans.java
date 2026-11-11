package feed;

import feed.SqliteRecord.Field;
import feed.Sqlite.Position;
import feed.Sqlite.Tree;

/** GET /feed, GET /posts/:id and /health. Each moves SQLite's B-tree cursors inside the pass's
 * transaction and answers from the rows it reads there; nothing is remembered between requests.
 * Only SQLite's B-tree interface is used, and no SQLite structure is written. */
final class ReadPlans {
    private ReadPlans() {}

    /** Like counts come from one walk of the likes index when the feed's ids span at most this. */
    static final int LIKE_SPAN = 512;
    private static final int[] likeCounts = new int[LIKE_SPAN + 1];
    private static long startedNanos;

    /** The server is up: /health counts its uptime from now. */
    static void started() { startedNanos = System.nanoTime(); }

    /** A user row looked up for its username. */
    private sealed interface Author {
        /** On the users cursor's page: valid until that cursor moves. */
        record Found(Bytes username) implements Author {}
        /** No such user: the reference JOIN drops the post. */
        enum Missing implements Author { MISSING }
        enum Failed implements Author { FAILED }
    }

    private static Author author(long userId) {
        return switch (Sqlite.seek(Tree.USERS, userId)) {
            case NONE -> Author.Missing.MISSING;
            case FAILED -> Author.Failed.FAILED;
            // users: id (the rowid, stored as NULL), username, created_at
            case ON_ENTRY -> SqliteRecord.field(Sqlite.record(Tree.USERS), 1) instanceof Field.Value(long type, Bytes username)
                    && SqliteRecord.isText(type) ? new Author.Found(username) : Author.Failed.FAILED;
        };
    }

    /** The post id the likes cursor is on (likes_post_id_idx: post_id, rowid), or -1. */
    private static long likedPost() {
        if (!(SqliteRecord.field(Sqlite.record(Tree.LIKES), 0) instanceof Field.Value value) || !SqliteRecord.isInteger(value.serialType())) return -1;
        return SqliteRecord.integer(value);
    }

    /** The likes of one post: its entries in likes_post_id_idx. -1 on an error. */
    private static long countLikes(long postId) {
        Position at = Sqlite.seekAtLeast(Tree.LIKES, postId);
        long n = 0;
        while (at == Position.ON_ENTRY) {
            long liked = likedPost();
            if (liked < 0) return -1;
            if (liked != postId) break;
            n++;
            at = Sqlite.next(Tree.LIKES);
        }
        return at == Position.FAILED ? -1 : n;
    }

    static Reply post(long id) {
        if (!Sqlite.begin()) return ErrorReply.INTERNAL;
        switch (Sqlite.seek(Tree.POSTS, id)) {
            case NONE: return ErrorReply.POST_NOT_FOUND;
            case FAILED: return ErrorReply.INTERNAL;
            case ON_ENTRY: break;
        }
        // posts: id (the rowid, stored as NULL), user_id, body, created_at. The record stays on its
        // page while the other cursors move.
        Bytes record = Sqlite.record(Tree.POSTS);
        if (!(SqliteRecord.field(record, 1) instanceof Field.Value userField)
                || !(SqliteRecord.field(record, 2) instanceof Field.Value(long bodyType, Bytes body))
                || !(SqliteRecord.field(record, 3) instanceof Field.Value(long createdType, Bytes created))
                || !SqliteRecord.isText(bodyType) || !SqliteRecord.isText(createdType) || !SqliteRecord.isInteger(userField.serialType())) {
            return ErrorReply.INTERNAL;
        }
        return switch (author(SqliteRecord.integer(userField))) {
            case Author.Missing _ -> ErrorReply.POST_NOT_FOUND;  // the JOIN finds no row
            case Author.Failed _ -> ErrorReply.INTERNAL;
            case Author.Found(Bytes username) -> {
                long likes = countLikes(id);
                yield likes < 0 ? ErrorReply.INTERNAL : new Reply.Found(new PostRow(id, body, created, username, likes));
            }
        };
    }

    /** How the cursor plan for the feed went. */
    private enum FeedPlan { READ, NEEDS_QUERY, FAILED }

    /** The 20 newest posts by (created_at DESC, id DESC), which is the order of
     * posts_created_at_id_idx. */
    static Reply feed() {
        if (!Sqlite.begin()) return ErrorReply.INTERNAL;
        return switch (readFeed()) {
            case READ -> new Reply.Feed(FeedRows.count);
            case NEEDS_QUERY -> feedByQuery();
            case FAILED -> ErrorReply.INTERNAL;
        };
    }

    /** The plan: the index's first 20 entries, their posts by rowid in ascending order
     * (neighbours on a leaf are one step apart), their authors, their like counts. Nothing about
     * the data is assumed: a post the index names but the table lacks, or one without a user,
     * sends the feed to the reference query. */
    private static FeedPlan readFeed() {
        FeedRows.clear();
        Position at = Sqlite.first(Tree.FEED);
        while (at == Position.ON_ENTRY && FeedRows.count < FeedRows.CAPACITY) {
            // the index: created_at, id, rowid
            if (!(SqliteRecord.field(Sqlite.record(Tree.FEED), 1) instanceof Field.Value id) || !SqliteRecord.isInteger(id.serialType())) {
                return FeedPlan.FAILED;
            }
            FeedRows.postId[FeedRows.count++] = SqliteRecord.integer(id);
            at = Sqlite.next(Tree.FEED);
        }
        if (at == Position.FAILED) return FeedPlan.FAILED;
        int n = FeedRows.count;
        sortById(n);
        for (int k = 0; k < n; k++) {
            FeedPlan read = readPost(FeedRows.byId[k]);
            if (read != FeedPlan.READ) return read;
        }
        for (int k = 0; k < n; k++) {
            FeedPlan read = readAuthor(k);
            if (read != FeedPlan.READ) return read;
        }
        return n == 0 || countFeedLikes(n) ? FeedPlan.READ : FeedPlan.FAILED;
    }

    /** Row `row`'s post: its user id, and its body and created_at copied into FeedRows.text.
     * (Each row is its own small method so that C2 replaces the records it decodes by numbers.) */
    private static FeedPlan readPost(int row) {
        switch (Sqlite.seek(Tree.POSTS, FeedRows.postId[row])) {
            case FAILED: return FeedPlan.FAILED;
            case NONE: return FeedPlan.NEEDS_QUERY;  // the index and the table disagree
            case ON_ENTRY: break;
        }
        Bytes record = Sqlite.record(Tree.POSTS);
        if (!(SqliteRecord.field(record, 1) instanceof Field.Value user)
                || !(SqliteRecord.field(record, 2) instanceof Field.Value(long bodyType, Bytes body))
                || !(SqliteRecord.field(record, 3) instanceof Field.Value(long createdType, Bytes created))
                || !SqliteRecord.isText(bodyType) || !SqliteRecord.isText(createdType) || !SqliteRecord.isInteger(user.serialType())) {
            return FeedPlan.FAILED;
        }
        FeedRows.userId[row] = SqliteRecord.integer(user);
        // body and created_at lie side by side in the record: one copy takes both
        long start = FeedRows.text.length;
        FeedRows.text.append(body.address(), created.end() - body.address());
        FeedRows.bodyStart[row] = start;
        FeedRows.createdStart[row] = start + body.length();
        FeedRows.createdEnd[row] = FeedRows.text.length;
        return FeedPlan.READ;
    }

    /** Row k's author, copied into FeedRows.text. */
    private static FeedPlan readAuthor(int k) {
        return switch (author(FeedRows.userId[k])) {
            case Author.Failed _ -> FeedPlan.FAILED;
            case Author.Missing _ -> FeedPlan.NEEDS_QUERY;  // a post without a user: the JOIN drops it
            case Author.Found(Bytes username) -> {
                FeedRows.authorStart[k] = FeedRows.text.length;
                FeedRows.text.append(username);
                FeedRows.authorEnd[k] = FeedRows.text.length;
                yield FeedPlan.READ;
            }
        };
    }

    /** byId: the rows in ascending post id. Newest first is normally descending ids, so the
     * reverse is the starting guess for an insertion sort. */
    private static void sortById(int n) {
        int[] order = FeedRows.byId;
        long[] id = FeedRows.postId;
        for (int k = 0; k < n; k++) order[k] = n - 1 - k;
        for (int k = 1; k < n; k++) {
            int o = order[k];
            int j = k;
            while (j > 0 && id[order[j - 1]] > id[o]) { order[j] = order[j - 1]; j--; }
            order[j] = o;
        }
    }

    /** The like counts: one walk of the likes index from the lowest id to the highest when they
     * span at most LIKE_SPAN, a seek per post otherwise. False on an error. */
    private static boolean countFeedLikes(int n) {
        long lo = FeedRows.postId[FeedRows.byId[0]], hi = FeedRows.postId[FeedRows.byId[n - 1]];
        if (hi - lo > LIKE_SPAN) {
            for (int k = 0; k < n; k++) {
                long likes = countLikes(FeedRows.postId[k]);
                if (likes < 0) return false;
                FeedRows.likes[k] = likes;
            }
            return true;
        }
        for (int k = 0; k <= hi - lo; k++) likeCounts[k] = 0;
        Position at = Sqlite.seekAtLeast(Tree.LIKES, lo);
        while (at == Position.ON_ENTRY) {
            long liked = likedPost();
            if (liked < 0) return false;
            if (liked > hi) break;
            likeCounts[(int) (liked - lo)]++;
            at = Sqlite.next(Tree.LIKES);
        }
        if (at == Position.FAILED) return false;
        for (int k = 0; k < n; k++) FeedRows.likes[k] = likeCounts[(int) (FeedRows.postId[k] - lo)];
        return true;
    }

    /** The reference SQL query, for a feed the cursor plan does not answer. Its texts live only
     * until the next step, so they are copied. */
    private static Reply feedByQuery() {
        FeedRows.clear();
        for (;;) {
            switch (Sqlite.stepReferenceFeed()) {
                case Sqlite.FeedStep.End.DONE -> { return new Reply.Feed(FeedRows.count); }
                case Sqlite.FeedStep.End.FAILED -> { return ErrorReply.INTERNAL; }
                case Sqlite.FeedStep.Row(PostRow post) -> {
                    if (FeedRows.count == FeedRows.CAPACITY) {
                        Sqlite.resetReferenceFeed();
                        return ErrorReply.INTERNAL;
                    }
                    int k = FeedRows.count++;
                    FeedRows.postId[k] = post.id();
                    FeedRows.likes[k] = post.likes();
                    FeedRows.bodyStart[k] = FeedRows.text.length;
                    FeedRows.text.append(post.body());
                    FeedRows.createdStart[k] = FeedRows.text.length;
                    FeedRows.text.append(post.createdAt());
                    FeedRows.createdEnd[k] = FeedRows.text.length;
                    FeedRows.authorStart[k] = FeedRows.text.length;
                    FeedRows.text.append(post.author());
                    FeedRows.authorEnd[k] = FeedRows.text.length;
                }
            }
        }
    }

    private static final Bytes NO_TRANSACTION = nativeText("cannot begin a transaction");

    private static Bytes nativeText(String s) {
        byte[] bytes = Ascii.bytes(s);
        long at = java.lang.foreign.Arena.global().allocate(bytes.length).address();
        Memory.copy(bytes, at);
        return new Bytes(at, bytes.length);
    }

    static Reply health() {
        if (!Sqlite.begin()) return new Reply.Degraded(NO_TRANSACTION);
        if (!Sqlite.ping()) return new Reply.Degraded(Sqlite.error());
        return new Reply.Healthy((System.nanoTime() - startedNanos) / 1_000_000_000);
    }
}
