/* What the server's half of the native library exports to Java (net.c and db.c). Everything
 * else in the library, SQLite included, is built with hidden visibility. */
#ifndef SERVER_H
#define SERVER_H
#include <stdint.h>

#define API __attribute__((visibility("default")))

/* Results that do not fit a return value, read by Java at the address db_io() gives: the length
 * of the record db_rec() returned. */
typedef struct {
  int64_t len;        /*  0: db_rec's record length */
} db_io_t;

#endif
