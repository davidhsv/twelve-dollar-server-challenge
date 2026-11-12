/* What the feed's half of the native library (feed_db.c) exports to Java, beside server.h. */
#ifndef FEED_H
#define FEED_H
#include "server.h"

/* Results that do not fit a return value, read by Java at the address feed_io() gives: a new
 * post's id and created_at, a row of the reference feed query. The pointers stay valid until the
 * next call on the same statement. Field offsets are fixed: Java reads them by offset. */
typedef struct {
  int64_t id;          /*  0: feed_add_post's id, feed_step's post id */
  int64_t likes;       /*  8: feed_step's like count */
  uint64_t text[3];    /* 16: feed_add_post's created_at in text[0]; feed_step's body, created_at, author */
  int64_t text_len[3]; /* 40 */
} feed_io_t;

#endif
