/* The feed's database: its schema check, the four trees its reads walk (with db.c's cursors,
 * from feed.ReadPlans), the two INSERTs, and the reference feed query. db.c, included here, is the
 * server's part; this is the only C that knows the schema.
 *
 * Tree numbers are FeedDatabase's: posts 0, users 1, posts_created_at_id_idx 2,
 * likes_post_id_idx 3. */
#define DB_TREES 4
#include "db.c"
#include "feed.h"

static sqlite3_stmt *q_post, *q_like, *q_feed;
static feed_io_t fio;
static char created[64]; /* a new post's created_at */

API feed_io_t *feed_io(void) { return &fio; }

/* feed.ReadPlans decodes records by position: refuse any other column order. */
static int app_check_schema(void) {
  static const char *const checks[][2] = {
    {"SELECT name FROM pragma_table_info('posts')", "id,user_id,body,created_at"},
    {"SELECT name FROM pragma_table_info('users')", "id,username,created_at"},
    {"SELECT name FROM pragma_index_info('posts_created_at_id_idx')", "created_at,id"},
    {"SELECT \"desc\" FROM pragma_index_xinfo('posts_created_at_id_idx') WHERE key", "1,1"},
    {"SELECT name FROM pragma_index_info('likes_post_id_idx')", "post_id"},
    {"SELECT \"desc\" FROM pragma_index_xinfo('likes_post_id_idx') WHERE key", "0"},
  };
  for (size_t i = 0; i < sizeof checks / sizeof checks[0]; i++) {
    if (!columns_are(checks[i][0], checks[i][1])) {
      snprintf(errbuf, sizeof errbuf, "unexpected schema: %s", checks[i][0]);
      return -1;
    }
  }
  return 0;
}

static void app_trees(void) {
  db_tree(0, "posts", 0, 0);
  db_tree(1, "users", 0, 0);
  db_tree(2, "posts_created_at_id_idx", 2, 1);
  db_tree(3, "likes_post_id_idx", 1, 0);
}

static int app_prepare(void) {
  q_post = prepare("INSERT INTO posts (user_id, body) VALUES (?1, ?2) RETURNING id, created_at");
  q_like = prepare("INSERT INTO likes (user_id, post_id) VALUES (?1, ?2) ON CONFLICT (user_id, post_id) DO NOTHING");
  /* The reference query, for a feed the cursor plan cannot answer (a post with no user row). */
  q_feed = prepare("SELECT p.id, p.body, p.created_at, u.username,"
                   " (SELECT count(*) FROM likes l WHERE l.post_id = p.id)"
                   " FROM posts p JOIN users u ON u.id = p.user_id"
                   " ORDER BY p.created_at DESC, p.id DESC LIMIT 20");
  return q_post && q_like && q_feed ? 0 : -1;
}

/* ---- writes ---- */

/* INSERT ... RETURNING id, created_at: 0 (feed_io: id, and created_at in text[0], valid until the
 * next post), -1 error. */
API int32_t feed_add_post(int64_t user_id, const uint8_t *body, int64_t body_len) {
  sqlite3_bind_int64(q_post, 1, user_id);
  sqlite3_bind_text(q_post, 2, (const char *)body, (int)body_len, SQLITE_STATIC);
  int ok = sqlite3_step(q_post) == SQLITE_ROW;
  if (ok) {
    fio.id = sqlite3_column_int64(q_post, 0);
    const unsigned char *text = sqlite3_column_text(q_post, 1);
    int n = sqlite3_column_bytes(q_post, 1);
    ok = text && n > 0 && n < (int)sizeof created;
    if (ok) {
      memcpy(created, text, (size_t)n);
      fio.text[0] = (uint64_t)(uintptr_t)created;
      fio.text_len[0] = n;
    }
    ok = ok && sqlite3_step(q_post) == SQLITE_DONE;
  }
  sqlite3_reset(q_post);
  sqlite3_clear_bindings(q_post);
  if (!ok) after_failed_write();
  return ok ? 0 : -1;
}

/* 1 a new like, 0 already liked, -1 error. */
API int32_t feed_add_like(int64_t user_id, int64_t post_id) {
  sqlite3_bind_int64(q_like, 1, user_id);
  sqlite3_bind_int64(q_like, 2, post_id);
  if (run(q_like) != SQLITE_DONE) { after_failed_write(); return -1; }
  return sqlite3_changes(db) > 0;
}

/* ---- the reference feed query ---- */

/* One row of the reference feed query: 1 (feed_io: id, likes, and text[] the body, created_at
 * and author, valid until the next step), 0 done, -1 error. */
API int32_t feed_step(void) {
  int rc = sqlite3_step(q_feed);
  if (rc != SQLITE_ROW) {
    sqlite3_reset(q_feed);
    return rc == SQLITE_DONE ? 0 : -1;
  }
  fio.id = sqlite3_column_int64(q_feed, 0);
  fio.likes = sqlite3_column_int64(q_feed, 4);
  for (int k = 0; k < 3; k++) {
    const unsigned char *s = sqlite3_column_text(q_feed, k + 1);
    fio.text[k] = (uint64_t)(uintptr_t)s;
    fio.text_len[k] = s ? sqlite3_column_bytes(q_feed, k + 1) : 0;
  }
  return 1;
}

/* Abandons the reference query before its last row. */
API void feed_reset(void) { sqlite3_reset(q_feed); }
