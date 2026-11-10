/* SQLite: the unmodified amalgamation plus the calls server.goose makes into it.
 *
 * Reads (GET /feed, GET /posts/:id) move SQLite's own B-tree cursors inside the pass's read
 * transaction, so only the SQL bytecode engine is skipped: pages come through SQLite's pager, WAL
 * and page cache. This file moves a cursor and copies the current row's record bytes into a
 * Goose builder; server.goose decodes the record and decides what to read next. Only functions
 * of SQLite's B-tree interface (btree.h) are called here, and no SQLite structure is written.
 * Writes, BEGIN/COMMIT and /health are ordinary prepared statements.
 *
 * Everything runs on the event-loop thread except WAL checkpoints, which a second thread runs
 * on its own connection when server.goose asks (design from rust-iouring-kensac #264), so
 * SQLite is built SQLITE_THREADSAFE=2: each connection is used by one thread. */
#include "sqlite3.c"
#include <pthread.h>
#include <stdbool.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include "ffi.h"

/* The trees a pass keeps a cursor on (the same numbers as in server.goose). */
enum { T_POSTS, T_USERS, T_FEED, T_LIKES, NT };

static sqlite3 *db;
static Btree *bt;
static BtCursor *cur[NT];
static Pgno root[NT];
static KeyInfo *keys[NT];
static sqlite3_stmt *q_begin, *q_commit, *q_rollback, *q_post, *q_like, *q_ping, *q_feed;
static int in_pass;      /* the pass's transaction is open, with its cursors */
static int pass_lost;    /* SQLite rolled it back by itself: nothing more may run in this pass */
static u8 *spill;        /* a record with overflow pages is assembled here (none with this schema) */
static u32 spill_cap;
static int wal_size;     /* frames in the log after the last commit that wrote */

/* ---- the checkpoint thread ---- */

/* On one vCPU a checkpoint at the loop's priority would take the CPU from it for as long as it
 * runs; at nice 19 it runs in the loop's idle time. */
#define CK_NICE 19
enum { CK_IDLE, CK_ASKED, CK_DONE_ALL, CK_DONE_SOME };
static sqlite3 *ck_db;
static int ck_state;
static pthread_mutex_t ck_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ck_cv = PTHREAD_COND_INITIALIZER;

static void *ck_main(void *arg) {
  (void)arg;
  setpriority(PRIO_PROCESS, (id_t)syscall(SYS_gettid), CK_NICE);
  for (;;) {
    pthread_mutex_lock(&ck_mu);
    while (ck_state != CK_ASKED) pthread_cond_wait(&ck_cv, &ck_mu);
    pthread_mutex_unlock(&ck_mu);
    /* PASSIVE never waits on the loop's locks, and the loop never waits for this thread. */
    int in_log = -1, copied = -2;
    int rc = sqlite3_wal_checkpoint_v2(ck_db, "main", SQLITE_CHECKPOINT_PASSIVE, &in_log, &copied);
    __atomic_store_n(&ck_state, rc == SQLITE_OK && in_log == copied ? CK_DONE_ALL : CK_DONE_SOME, __ATOMIC_RELEASE);
  }
  return 0;
}

/* Asks the thread for one PASSIVE checkpoint. */
void db_ck_ask(void) {
  pthread_mutex_lock(&ck_mu);
  __atomic_store_n(&ck_state, CK_ASKED, __ATOMIC_RELEASE);
  pthread_cond_signal(&ck_cv);
  pthread_mutex_unlock(&ck_mu);
}

/* 0 idle, 1 copying, 2 done with the whole log copied, 3 done with frames left. A finished
 * state is reported once. */
int32_t db_ck_poll(void) {
  int s = __atomic_load_n(&ck_state, __ATOMIC_ACQUIRE);
  if (s == CK_DONE_ALL || s == CK_DONE_SOME) __atomic_store_n(&ck_state, CK_IDLE, __ATOMIC_RELEASE);
  return s;
}

int64_t db_wal_frames(void) { return wal_size; }

/* The whole log is copied back: the next write starts it over, and the hook reports its size. */
void db_wal_restarts(void) { wal_size = 0; }

static int on_commit_frames(void *arg, sqlite3 *d, const char *name, int frames) {
  (void)arg; (void)d; (void)name;
  wal_size = frames;
  return SQLITE_OK;
}

/* ---- the log's writes: one per commit ---- */

/* SQLite appends each frame to the log with two writes (a 24-byte header, then the page), so a
 * commit of forty frames is eighty system calls. "feed" is a VFS over unix-excl, made with
 * SQLite's public VFS interface, that changes one thing: on the log file, the headers and pages
 * of frames appended at the log's end are gathered in memory, and all of it is written out,
 * inside SQLite's own write call, the moment the page of a commit frame arrives (the idea of
 * goose-iouring-ahmadsh2007 #291). That is before the frames enter the WAL index and before
 * COMMIT returns, the point by which SQLite itself would have written them, so a commit is
 * exactly as durable as without it. Gathered frames of a transaction that has not committed
 * are not on disk yet, and need not be.
 *
 * Only an append is ever held back. SQLite also rewrites frames in place (a page written twice
 * in a transaction that spilled, and then the checksums in the headers after it): those writes
 * are not at the log's end, so they go straight through, like every write that is not a frame.
 * And every read, sync, truncate, size query, file control and close first writes out what is
 * gathered. The database file is opened by unix-excl itself and never passes through here. */
#define GATHER_MAX (1 << 20)
/* unix-excl writes at most 0x1ffff bytes per call. */
#define GATHER_STEP 0x1f000

typedef struct {
  sqlite3_file base;
  sqlite3_file *real; /* unix-excl's file, in the memory after this struct */
  u8 *buf;
  i64 at;     /* where the gathered bytes start in the file */
  i64 tail;   /* where the log ends, as far as this file's writes show it; -1 not known yet */
  i64 lone;   /* the previous operation wrote a frame header through on its own: where, else -1 */
  int n;      /* bytes gathered */
  int header; /* the last gathered piece is a frame header: only its page may follow */
  int commit; /* ... of a commit frame */
} log_file;

static sqlite3_vfs feed_vfs;
static sqlite3_vfs *excl_vfs;

/* Writes out what is gathered. Every operation but a write that is held back comes through
 * here first. */
static int log_flush(log_file *f) {
  int rc = SQLITE_OK;
  f->lone = -1;
  for (int done = 0; done < f->n && rc == SQLITE_OK;) {
    int step = f->n - done < GATHER_STEP ? f->n - done : GATHER_STEP;
    rc = f->real->pMethods->xWrite(f->real, f->buf + done, step, f->at + done);
    done += step;
  }
  f->n = 0;
  f->header = 0;
  f->commit = 0;
  return rc;
}

static int log_write(sqlite3_file *file, const void *data, int amt, sqlite3_int64 off) {
  log_file *f = (log_file *)file;
  if (f->header) {
    if (off == f->at + f->n && amt >= 512 && amt <= 65536) {
      /* the page of the header that is held: the frame is whole */
      memcpy(f->buf + f->n, data, (size_t)amt);
      f->n += amt;
      f->header = 0;
      f->lone = -1;
      f->tail = off + amt;
      return f->commit ? log_flush(f) : SQLITE_OK;
    }
    int rc = log_flush(f);
    if (rc != SQLITE_OK) return rc;
  }
  if (amt == WAL_FRAME_HDRSIZE && off == f->tail && (f->n == 0 || off == f->at + f->n)) {
    /* a new frame at the log's end: hold its header until its page comes */
    if (f->n + WAL_FRAME_HDRSIZE + 65536 > GATHER_MAX) {
      int rc = log_flush(f);
      if (rc != SQLITE_OK) return rc;
    }
    if (f->n == 0) f->at = off;
    memcpy(f->buf + f->n, data, WAL_FRAME_HDRSIZE);
    f->n += WAL_FRAME_HDRSIZE;
    f->header = 1;
    f->lone = -1;
    /* bytes 4-7: the database size after the commit, zero for a frame that does not commit */
    f->commit = sqlite3Get4byte((const u8 *)data + 4) != 0;
    return SQLITE_OK;
  }
  /* Anything else is written now, after what was gathered before it. */
  i64 lone = f->lone;
  int rc = log_flush(f);
  if (rc != SQLITE_OK) return rc;
  rc = f->real->pMethods->xWrite(f->real, data, amt, off);
  /* Where the log ends: after its own header when the log starts over, and after a frame that
   * was just written through whole, its header and then, as the very next operation, its page.
   * That is an append, or the last frame being rewritten; either way the log ends behind it. A
   * header on its own (a checksum rewrite) says nothing, so `tail` is never short of the end,
   * and a header held back above is always a new frame whose page comes next. */
  if (off == 0) f->tail = amt;
  else if (amt == WAL_FRAME_HDRSIZE) f->lone = off;
  else if (lone >= 0 && off == lone + WAL_FRAME_HDRSIZE) f->tail = off + amt;
  return rc;
}

static int log_read(sqlite3_file *file, void *data, int amt, sqlite3_int64 off) {
  log_file *f = (log_file *)file;
  int rc = log_flush(f);
  return rc != SQLITE_OK ? rc : f->real->pMethods->xRead(f->real, data, amt, off);
}

static int log_truncate(sqlite3_file *file, sqlite3_int64 size) {
  log_file *f = (log_file *)file;
  int rc = log_flush(f);
  return rc != SQLITE_OK ? rc : f->real->pMethods->xTruncate(f->real, size);
}

static int log_sync(sqlite3_file *file, int flags) {
  log_file *f = (log_file *)file;
  int rc = log_flush(f);
  return rc != SQLITE_OK ? rc : f->real->pMethods->xSync(f->real, flags);
}

static int log_size(sqlite3_file *file, sqlite3_int64 *size) {
  log_file *f = (log_file *)file;
  int rc = log_flush(f);
  return rc != SQLITE_OK ? rc : f->real->pMethods->xFileSize(f->real, size);
}

static int log_control(sqlite3_file *file, int op, void *arg) {
  log_file *f = (log_file *)file;
  int rc = log_flush(f);
  return rc != SQLITE_OK ? rc : f->real->pMethods->xFileControl(f->real, op, arg);
}

static int log_close(sqlite3_file *file) {
  log_file *f = (log_file *)file;
  int rc = log_flush(f);
  int rc2 = f->real->pMethods->xClose(f->real);
  sqlite3_free(f->buf);
  f->buf = 0;
  return rc != SQLITE_OK ? rc : rc2;
}

static int log_lock(sqlite3_file *file, int level) {
  log_file *f = (log_file *)file;
  return f->real->pMethods->xLock(f->real, level);
}

static int log_unlock(sqlite3_file *file, int level) {
  log_file *f = (log_file *)file;
  return f->real->pMethods->xUnlock(f->real, level);
}

static int log_reserved(sqlite3_file *file, int *out) {
  log_file *f = (log_file *)file;
  return f->real->pMethods->xCheckReservedLock(f->real, out);
}

static int log_sector(sqlite3_file *file) {
  log_file *f = (log_file *)file;
  return f->real->pMethods->xSectorSize(f->real);
}

static int log_traits(sqlite3_file *file) {
  log_file *f = (log_file *)file;
  return f->real->pMethods->xDeviceCharacteristics(f->real);
}

static const sqlite3_io_methods log_methods = {
  1, log_close, log_read, log_write, log_truncate, log_sync, log_size, log_lock, log_unlock, log_reserved,
  log_control, log_sector, log_traits,
};

static int feed_open(sqlite3_vfs *vfs, sqlite3_filename name, sqlite3_file *file, int flags, int *out_flags) {
  (void)vfs;
  if (!(flags & SQLITE_OPEN_WAL)) return excl_vfs->xOpen(excl_vfs, name, file, flags, out_flags);
  log_file *f = (log_file *)file;
  memset(f, 0, sizeof *f);
  f->tail = -1;
  f->lone = -1;
  f->real = (sqlite3_file *)(f + 1);
  f->buf = sqlite3_malloc(GATHER_MAX);
  if (!f->buf) return SQLITE_NOMEM;
  int rc = excl_vfs->xOpen(excl_vfs, name, f->real, flags, out_flags);
  if (rc != SQLITE_OK) {
    sqlite3_free(f->buf);
    f->buf = 0;
    return rc; /* base.pMethods stays null, so SQLite does not call xClose */
  }
  f->base.pMethods = &log_methods;
  return SQLITE_OK;
}

/* Registers "feed". With FEED_PLAIN_LOG set (a test knob), connections use unix-excl as it is. */
static const char *vfs_name(void) {
  if (getenv("FEED_PLAIN_LOG")) return "unix-excl";
  if (!excl_vfs) {
    excl_vfs = sqlite3_vfs_find("unix-excl");
    if (!excl_vfs) return "unix-excl";
    feed_vfs = *excl_vfs;
    feed_vfs.zName = "feed";
    feed_vfs.pNext = 0;
    feed_vfs.szOsFile = (int)sizeof(log_file) + excl_vfs->szOsFile;
    feed_vfs.xOpen = feed_open;
    if (sqlite3_vfs_register(&feed_vfs, 0) != SQLITE_OK) return "unix-excl";
  }
  return "feed";
}

/* ---- the page cache ---- */

/* SQLite's page cache behind its public plug-in interface (SQLITE_CONFIG_PCACHE2), in place of
 * the built-in one. A B-tree descent fetches and releases a page per level, and the built-in
 * cache answers each fetch with a hash chain walk and moves the page in and out of an LRU list.
 * Here a page is found at table[page number], and pinning or unpinning it sets a byte; a page
 * to evict is looked for (a clock hand over the table) only when the cache is full. Pages are
 * cut from 2 MB blocks that are returned when the cache is destroyed. What the cache holds and
 * when is still SQLite's decision: this only stores pages under the keys SQLite gives it. */
#include <sys/mman.h>

#define PC_DIRECT (1u << 22) /* page numbers below this are table slots; a larger one is on a list */
#define PC_BLOCK (2u << 20)

typedef struct pc_page {
  sqlite3_pcache_page page; /* first: SQLite's handle is this struct's address */
  struct pc_page *next;     /* the free list, or the far list */
  u32 key;
  u8 pinned;
  u8 far;
} pc_page;

typedef struct pc_block { struct pc_block *next; } pc_block;

typedef struct {
  int purgeable;
  u32 page_bytes, extra_bytes, slot_bytes;
  u32 max_pages, pages, pinned;
  u32 table_len, hand;
  u32 max_key;   /* no page in the table has a larger number */
  u32 below_max; /* ... and none but the one at max_key has a larger number than this */
  pc_page **table;
  pc_page *far;
  pc_page *free;
  pc_block *blocks;
  u8 *fresh; /* unused memory of the newest block */
  u32 fresh_left;
} pc_cache;

static int pc_huge; /* FEED_HUGE (a test knob): ask for transparent huge pages for the blocks */

static int pc_init(void *arg) { (void)arg; return SQLITE_OK; }
static void pc_shutdown(void *arg) { (void)arg; }

static sqlite3_pcache *pc_create(int page_bytes, int extra_bytes, int purgeable) {
  pc_cache *c = calloc(1, sizeof *c);
  if (!c) return 0;
  c->purgeable = purgeable;
  c->page_bytes = (u32)ROUND8(page_bytes);
  c->extra_bytes = (u32)ROUND8(extra_bytes);
  c->slot_bytes = (u32)ROUND8(sizeof(pc_page)) + c->page_bytes + c->extra_bytes;
  c->max_pages = 10;
  return (sqlite3_pcache *)c;
}

static void pc_cachesize(sqlite3_pcache *cache, int n) {
  pc_cache *c = (pc_cache *)cache;
  c->max_pages = n > 0 ? (u32)n : 1;
}

static int pc_pagecount(sqlite3_pcache *cache) { return (int)((pc_cache *)cache)->pages; }

/* Memory for one more page: from the free list, else cut from a block. */
static pc_page *pc_slot(pc_cache *c) {
  pc_page *e = c->free;
  if (e) {
    c->free = e->next;
    return e;
  }
  if (c->fresh_left < c->slot_bytes) {
    size_t want = c->slot_bytes + ROUND8(sizeof(pc_block)) > PC_BLOCK ? c->slot_bytes + ROUND8(sizeof(pc_block)) : PC_BLOCK;
    /* a block aligned to its own size, so that one huge page can back it */
    u8 *raw = mmap(0, want + PC_BLOCK, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (raw == MAP_FAILED) return 0;
    u8 *at = (u8 *)(((uintptr_t)raw + PC_BLOCK - 1) & ~(uintptr_t)(PC_BLOCK - 1));
    if (at > raw) munmap(raw, (size_t)(at - raw));
    if (at + want < raw + want + PC_BLOCK) munmap(at + want, (size_t)(raw + want + PC_BLOCK - (at + want)));
    if (pc_huge) madvise(at, want, MADV_HUGEPAGE);
    pc_block *b = (pc_block *)at;
    b->next = c->blocks;
    c->blocks = b;
    c->fresh = at + ROUND8(sizeof(pc_block));
    c->fresh_left = (u32)(want - ROUND8(sizeof(pc_block)));
  }
  e = (pc_page *)c->fresh;
  c->fresh += c->slot_bytes;
  c->fresh_left -= c->slot_bytes;
  e->page.pBuf = (u8 *)e + ROUND8(sizeof(pc_page));
  e->page.pExtra = (u8 *)e->page.pBuf + c->page_bytes;
  return e;
}

static pc_page *pc_find(pc_cache *c, u32 key) {
  pc_page *e = key < c->table_len ? c->table[key] : 0;
  if (e || !c->far) return e;
  for (e = c->far; e && e->key != key; e = e->next) {}
  return e;
}

/* Takes a page out of the cache's index (its memory is not freed here). */
static void pc_unlink(pc_cache *c, pc_page *e) {
  if (!e->far) {
    c->table[e->key] = 0;
    /* While rebalancing, SQLite parks a page at the number of the 1 GB mark and moves it back.
     * When the highest page leaves, the bound falls back to the highest of the others, so a
     * truncate does not walk the table up to a number nothing holds any more. */
    if (e->key == c->max_key) c->max_key = c->below_max;
  } else {
    pc_page **at = &c->far;
    while (*at != e) at = &(*at)->next;
    *at = e->next;
  }
  c->pages--;
  if (e->pinned) c->pinned--;
}

static void pc_drop(pc_cache *c, pc_page *e) {
  pc_unlink(c, e);
  e->next = c->free;
  c->free = e;
}

static int pc_link(pc_cache *c, pc_page *e, u32 key) {
  e->key = key;
  e->far = key >= PC_DIRECT;
  if (!e->far) {
    if (key >= c->table_len) {
      u32 len = c->table_len ? c->table_len : 1024;
      while (len <= key) len *= 2;
      pc_page **t = realloc(c->table, (size_t)len * sizeof *t);
      if (!t) return 0;
      memset(t + c->table_len, 0, (size_t)(len - c->table_len) * sizeof *t);
      c->table = t;
      c->table_len = len;
    }
    c->table[key] = e;
    if (key > c->max_key) {
      c->below_max = c->max_key;
      c->max_key = key;
    } else if (key > c->below_max && key < c->max_key) {
      c->below_max = key;
    }
  } else {
    e->next = c->far;
    c->far = e;
  }
  c->pages++;
  if (e->pinned) c->pinned++;
  return 1;
}

/* An unpinned page to reuse, taken out of the index; null if every page is pinned. */
static pc_page *pc_victim(pc_cache *c) {
  if (c->pinned >= c->pages) return 0;
  for (u32 n = 0; n <= c->max_key && c->table_len; n++) {
    u32 k = c->hand;
    c->hand = k >= c->max_key ? 0 : k + 1;
    pc_page *e = k < c->table_len ? c->table[k] : 0;
    if (e && !e->pinned) {
      pc_unlink(c, e);
      return e;
    }
  }
  for (pc_page *e = c->far; e; e = e->next) {
    if (!e->pinned) {
      pc_unlink(c, e);
      return e;
    }
  }
  return 0;
}

/* create: 0 only look; 1 make the page if that is easy (null tells SQLite to write dirty pages
 * out first); 2 make it if at all possible. The page comes back pinned. */
static sqlite3_pcache_page *pc_fetch(sqlite3_pcache *cache, unsigned key, int create) {
  pc_cache *c = (pc_cache *)cache;
  pc_page *e = pc_find(c, key);
  if (e) {
    if (!e->pinned) {
      e->pinned = 1;
      c->pinned++;
    }
    return &e->page;
  }
  if (!create) return 0;
  if (c->purgeable && c->pages >= c->max_pages) {
    e = pc_victim(c);
    if (!e && create == 1) return 0;
  }
  if (!e) e = pc_slot(c);
  if (!e) return 0;
  e->pinned = 1;
  if (!pc_link(c, e, key)) {
    e->next = c->free;
    c->free = e;
    return 0;
  }
  /* SQLite takes a null here as "this page is new" */
  *(void **)e->page.pExtra = 0;
  return &e->page;
}

static void pc_unpin(sqlite3_pcache *cache, sqlite3_pcache_page *page, int discard) {
  pc_cache *c = (pc_cache *)cache;
  pc_page *e = (pc_page *)page;
  if (discard || c->pages > c->max_pages) {
    pc_drop(c, e);
  } else if (e->pinned) {
    e->pinned = 0;
    c->pinned--;
  }
}

static void pc_rekey(sqlite3_pcache *cache, sqlite3_pcache_page *page, unsigned old_key, unsigned new_key) {
  pc_cache *c = (pc_cache *)cache;
  pc_page *e = (pc_page *)page;
  (void)old_key;
  pc_page *there = pc_find(c, new_key);
  if (there && there != e) pc_drop(c, there);
  pc_unlink(c, e);
  if (!pc_link(c, e, new_key)) {
    /* the table could not grow: the page is lost to the cache, which SQLite cannot be told */
    fprintf(stderr, "out of memory\n");
    abort();
  }
}

/* Drops every page whose number is `limit` or more, pinned or not. */
static void pc_truncate(sqlite3_pcache *cache, unsigned limit) {
  pc_cache *c = (pc_cache *)cache;
  for (u32 k = limit; k <= c->max_key && k < c->table_len; k++) {
    if (c->table[k]) pc_drop(c, c->table[k]);
  }
  if (limit <= c->max_key) c->max_key = limit ? limit - 1 : 0;
  if (c->below_max > c->max_key) c->below_max = c->max_key;
  for (pc_page *e = c->far, *next; e; e = next) {
    next = e->next;
    if (e->key >= limit) pc_drop(c, e);
  }
}

static void pc_destroy(sqlite3_pcache *cache) {
  pc_cache *c = (pc_cache *)cache;
  for (pc_block *b = c->blocks, *next; b; b = next) {
    next = b->next;
    size_t len = c->slot_bytes + ROUND8(sizeof(pc_block)) > PC_BLOCK ? c->slot_bytes + ROUND8(sizeof(pc_block)) : PC_BLOCK;
    munmap(b, len);
  }
  free(c->table);
  free(c);
}

static void pc_shrink(sqlite3_pcache *cache) { (void)cache; }

/* Before sqlite3_initialize: installs the page cache. With FEED_PLAIN_CACHE set (a test knob),
 * SQLite keeps its own. */
static void pc_install(void) {
  static const sqlite3_pcache_methods2 methods = {
    1, 0, pc_init, pc_shutdown, pc_create, pc_cachesize, pc_pagecount, pc_fetch, pc_unpin, pc_rekey,
    pc_truncate, pc_destroy, pc_shrink,
  };
  pc_huge = getenv("FEED_HUGE") != 0;
  if (!getenv("FEED_PLAIN_CACHE")) sqlite3_config(SQLITE_CONFIG_PCACHE2, &methods);
}

/* ---- opening ---- */

static void explain(gs_rref err, const char *what, sqlite3 *d) {
  const char *m = d ? sqlite3_errmsg(d) : "no connection";
  gs_bld_append(err, what, (int64_t)strlen(what));
  gs_bld_append(err, ": ", 2);
  gs_bld_append(err, m, (int64_t)strlen(m));
}

static int run(sqlite3_stmt *q) {
  int rc = sqlite3_step(q);
  sqlite3_reset(q);
  return rc;
}

static sqlite3_stmt *prepare(const char *sql) {
  sqlite3_stmt *q = 0;
  return sqlite3_prepare_v3(db, sql, -1, SQLITE_PREPARE_PERSISTENT, &q, 0) == SQLITE_OK ? q : 0;
}

/* Comma-joined first column of a query, for the schema checks. */
static int columns_are(const char *sql, const char *want) {
  sqlite3_stmt *q;
  char got[256];
  size_t n = 0;
  got[0] = 0;
  if (sqlite3_prepare_v2(db, sql, -1, &q, 0) != SQLITE_OK) return 0;
  while (sqlite3_step(q) == SQLITE_ROW && n < sizeof got) {
    const char *c = (const char *)sqlite3_column_text(q, 0);
    n += (size_t)snprintf(got + n, sizeof got - n, "%s%s", n ? "," : "", c ? c : "");
  }
  sqlite3_finalize(q);
  return strcmp(got, want) == 0;
}

static Pgno root_of(const char *name) {
  sqlite3_stmt *q;
  Pgno r = 0;
  if (sqlite3_prepare_v2(db, "SELECT rootpage FROM sqlite_schema WHERE name = ?1", -1, &q, 0) != SQLITE_OK) return 0;
  sqlite3_bind_text(q, 1, name, -1, SQLITE_STATIC);
  if (sqlite3_step(q) == SQLITE_ROW) r = (Pgno)sqlite3_column_int64(q, 0);
  sqlite3_finalize(q);
  return r;
}

/* How an index compares its keys: every column with the database's default collation. */
static KeyInfo *key_info(int nkey, int descending) {
  KeyInfo *k = sqlite3KeyInfoAlloc(db, nkey, 1);
  if (!k) return 0;
  for (int i = 0; i <= nkey; i++) k->aColl[i] = db->pDfltColl;
  for (int i = 0; i < nkey; i++) k->aSortFlags[i] = descending ? KEYINFO_ORDER_DESC : 0;
  return k;
}

/* 0, or -1 with the reason appended to err. */
int32_t db_open(bytes_t path_b, gs_rref err) {
  char path[4096];
  if (path_b.len <= 0 || path_b.len >= (int64_t)sizeof path) {
    gs_bld_append(err, "bad SQLITE_PATH", 15);
    return -1;
  }
  memcpy(path, path_b.data, (size_t)path_b.len);
  path[path_b.len] = 0;
  pc_install();
  if (sqlite3_initialize() != SQLITE_OK) { gs_bld_append(err, "sqlite3_initialize", 18); return -1; }
  /* unix-excl (under "feed", above): this process locks the file once and keeps it, so no other
   * process can open it, and the WAL index is heap memory shared by the process's two
   * connections (no -shm file, no file locks per transaction). */
  if (sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOMUTEX, vfs_name()) != SQLITE_OK) {
    explain(err, "open", db);
    return -1;
  }
  /* WAL with synchronous=NORMAL: a commit survives a process crash once COMMIT returns (rule 6).
   * No mmap: a page in SQLite's own cache keeps its parsed header and needs no WAL lookup. */
  if (sqlite3_exec(db,
                   "PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL; PRAGMA temp_store = MEMORY;"
                   "PRAGMA cache_size = -65536; PRAGMA mmap_size = 0; PRAGMA wal_autocheckpoint = 0;"
                   "SELECT count(*) FROM sqlite_schema",
                   0, 0, 0) != SQLITE_OK) {
    explain(err, "pragmas", db);
    return -1;
  }
  /* FEED_CACHE_PAGES (a test knob): a small page cache, so that a large transaction spills
   * frames to the log before it commits. */
  if (getenv("FEED_CACHE_PAGES")) {
    char sql[64];
    snprintf(sql, sizeof sql, "PRAGMA cache_size = %d", atoi(getenv("FEED_CACHE_PAGES")));
    sqlite3_exec(db, sql, 0, 0, 0);
  }
  sqlite3_wal_hook(db, on_commit_frames, 0);
  /* server.goose decodes records by position: refuse any other column order. */
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
      gs_bld_append(err, "unexpected schema: ", 19);
      gs_bld_append(err, checks[i][0], (int64_t)strlen(checks[i][0]));
      return -1;
    }
  }
  if (ENC(db) != SQLITE_UTF8) { gs_bld_append(err, "the database is not UTF-8", 25); return -1; }
  root[T_POSTS] = root_of("posts");
  root[T_USERS] = root_of("users");
  root[T_FEED] = root_of("posts_created_at_id_idx");
  root[T_LIKES] = root_of("likes_post_id_idx");
  keys[T_FEED] = key_info(2, 1);
  keys[T_LIKES] = key_info(1, 0);
  for (int t = 0; t < NT; t++) {
    cur[t] = sqlite3_malloc(sqlite3BtreeCursorSize());
    if (!root[t] || !cur[t] || (t >= T_FEED && !keys[t])) { explain(err, "tables", db); return -1; }
  }
  bt = db->aDb[0].pBt;
  q_begin = prepare("BEGIN");
  q_commit = prepare("COMMIT");
  q_rollback = prepare("ROLLBACK");
  q_post = prepare("INSERT INTO posts (user_id, body) VALUES (?1, ?2) RETURNING id, created_at");
  q_like = prepare("INSERT INTO likes (user_id, post_id) VALUES (?1, ?2) ON CONFLICT (user_id, post_id) DO NOTHING");
  q_ping = prepare("SELECT 1");
  /* The reference query, for a feed the cursor plan cannot answer (a post with no user row). */
  q_feed = prepare("SELECT p.id, p.body, p.created_at, u.username,"
                   " (SELECT count(*) FROM likes l WHERE l.post_id = p.id)"
                   " FROM posts p JOIN users u ON u.id = p.user_id"
                   " ORDER BY p.created_at DESC, p.id DESC LIMIT 20");
  if (!q_begin || !q_commit || !q_rollback || !q_post || !q_like || !q_ping || !q_feed) {
    explain(err, "prepare", db);
    return -1;
  }
  /* The checkpoint connection copies frames from the log; it reads no tables. */
  if (sqlite3_open_v2(path, &ck_db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOMUTEX, vfs_name()) != SQLITE_OK ||
      sqlite3_exec(ck_db, "PRAGMA synchronous = NORMAL; PRAGMA wal_autocheckpoint = 0; PRAGMA cache_size = 16", 0, 0, 0) !=
        SQLITE_OK) {
    explain(err, "checkpoint connection", ck_db);
    return -1;
  }
  pthread_t t;
  pthread_attr_t a;
  pthread_attr_init(&a);
  pthread_attr_setstacksize(&a, 512 << 10);
  if (pthread_create(&t, &a, ck_main, 0) != 0) { gs_bld_append(err, "checkpoint thread", 17); return -1; }
  pthread_attr_destroy(&a);
  return 0;
}

/* ---- the pass's transaction ---- */

static void close_cursors(void) {
  for (int t = 0; t < NT; t++) sqlite3BtreeCloseCursor(cur[t]);
}

/* Joins the pass's transaction, opening it (and a cursor on each tree) for the first request
 * that needs the database: its reads share one snapshot and its writes one COMMIT. 0, or -1. */
int32_t db_begin(void) {
  if (pass_lost) return -1;
  if (in_pass) {
    if (!sqlite3_get_autocommit(db)) return 0;
    /* SQLite ended the transaction itself (an I/O error, a full disk): the pass is lost. */
    close_cursors();
    in_pass = 0;
    pass_lost = 1;
    return -1;
  }
  if (run(q_begin) != SQLITE_DONE) return -1;
  if (sqlite3BtreeBeginTrans(bt, 0, 0) != SQLITE_OK) { run(q_rollback); return -1; }
  for (int t = 0; t < NT; t++) {
    sqlite3BtreeCursorZero(cur[t]);
    if (sqlite3BtreeCursor(bt, root[t], 0, keys[t], cur[t]) != SQLITE_OK) {
      for (int k = 0; k < t; k++) sqlite3BtreeCloseCursor(cur[k]);
      run(q_rollback);
      return -1;
    }
  }
  in_pass = 1;
  return 0;
}

/* Ends the pass's transaction: true once it is committed (or there was none), false if it was
 * rolled back. No response of the pass is sent before this returns. */
bool db_commit(void) {
  if (pass_lost) {
    pass_lost = 0;
    if (!sqlite3_get_autocommit(db)) run(q_rollback);
    return false;
  }
  if (!in_pass) return true;
  in_pass = 0;
  close_cursors();
  if (run(q_commit) != SQLITE_DONE) {
    fprintf(stderr, "commit failed: %s\n", sqlite3_errmsg(db));
    if (!sqlite3_get_autocommit(db)) run(q_rollback);
    return false;
  }
  return true;
}

/* ---- cursors ---- */

/* Moves a table's cursor to rowid `key`: 1 there, 0 no such row, -1 error. */
int32_t db_seek(int32_t t, int64_t key) {
  int res = 0;
  if (sqlite3BtreeTableMoveto(cur[t], key, 0, &res) != SQLITE_OK) return -1;
  return res == 0 && !sqlite3BtreeEof(cur[t]);
}

/* Moves an index's cursor to its first entry whose leading integer column is >= key: 1 on an
 * entry, 0 none, -1 error. */
int32_t db_seek_ge(int32_t t, int64_t key) {
  BtCursor *c = cur[t];
  Mem m;
  UnpackedRecord r;
  int res = 0;
  memset(&m, 0, sizeof m);
  m.flags = MEM_Int;
  m.u.i = key;
  memset(&r, 0, sizeof r);
  r.pKeyInfo = keys[t];
  r.aMem = &m;
  r.nField = 1;
  r.default_rc = 1; /* entries equal on the key sort after it, so this lands before the first */
  if (sqlite3BtreeIndexMoveto(c, &r, &res) != SQLITE_OK) return -1;
  if (sqlite3BtreeEof(c)) return 0;
  if (res < 0) {
    int rc = sqlite3BtreeNext(c, 0);
    if (rc == SQLITE_DONE) return 0;
    if (rc != SQLITE_OK) return -1;
  }
  return 1;
}

/* 1 on the first entry, 0 empty, -1 error. */
int32_t db_first(int32_t t) {
  int empty = 1;
  if (sqlite3BtreeFirst(cur[t], &empty) != SQLITE_OK) return -1;
  return !empty;
}

/* 1 on the next entry, 0 at the end, -1 error. */
int32_t db_next(int32_t t) {
  int rc = sqlite3BtreeNext(cur[t], 0);
  return rc == SQLITE_OK ? 1 : rc == SQLITE_DONE ? 0 : -1;
}

/* Appends the current entry's record bytes to a Goose builder: their count, or -1. */
int64_t db_row(int32_t t, gs_rref out) {
  BtCursor *c = cur[t];
  u32 total = sqlite3BtreePayloadSize(c);
  u32 local = 0;
  const void *p = sqlite3BtreePayloadFetch(c, &local);
  if (local >= total) {
    gs_bld_append(out, p, total);
    return total;
  }
  if (total > spill_cap) {
    sqlite3_free(spill);
    spill_cap = total + 4096;
    spill = sqlite3_malloc(spill_cap);
    if (!spill) { spill_cap = 0; return -1; }
  }
  if (sqlite3BtreePayload(c, 0, total, spill) != SQLITE_OK) return -1;
  gs_bld_append(out, spill, total);
  return total;
}

/* Copies the first bytes of the current entry's record (at most 24) into a Goose array and
 * returns the record's whole length, or -1: enough to read an index entry's leading key. */
int64_t db_row_start(int32_t t, uint8_t *into) {
  BtCursor *c = cur[t];
  u32 total = sqlite3BtreePayloadSize(c);
  u32 local = 0;
  const void *p = sqlite3BtreePayloadFetch(c, &local);
  u32 n = total < 24 ? total : 24;
  if (local < n) return -1;
  memcpy(into, p, n);
  return total;
}

/* ---- writes and /health ---- */

/* A write failed: if SQLite ended the transaction with it, the pass is lost. */
static void after_failed_write(void) {
  if (in_pass && sqlite3_get_autocommit(db)) {
    close_cursors();
    in_pass = 0;
    pass_lost = 1;
  }
}

/* INSERT ... RETURNING id, created_at: 0 (id set, created_at appended), -1 error. */
int32_t db_add_post(int64_t user_id, bytes_t body, int64_t *id, gs_rref created) {
  sqlite3_bind_int64(q_post, 1, user_id);
  sqlite3_bind_text(q_post, 2, (const char *)body.data, (int)body.len, SQLITE_STATIC);
  int ok = sqlite3_step(q_post) == SQLITE_ROW;
  if (ok) {
    *id = sqlite3_column_int64(q_post, 0);
    const unsigned char *text = sqlite3_column_text(q_post, 1);
    int n = sqlite3_column_bytes(q_post, 1);
    ok = text && n > 0;
    if (ok) gs_bld_append(created, text, n);
    ok = ok && sqlite3_step(q_post) == SQLITE_DONE;
  }
  sqlite3_reset(q_post);
  sqlite3_clear_bindings(q_post);
  if (!ok) after_failed_write();
  return ok ? 0 : -1;
}

/* 1 a new like, 0 already liked, -1 error. */
int32_t db_add_like(int64_t user_id, int64_t post_id) {
  sqlite3_bind_int64(q_like, 1, user_id);
  sqlite3_bind_int64(q_like, 2, post_id);
  if (run(q_like) != SQLITE_DONE) { after_failed_write(); return -1; }
  return sqlite3_changes(db) > 0;
}

/* SELECT 1: 0 ok, -1 with SQLite's message appended to err. */
int32_t db_ping(gs_rref err) {
  int ok = sqlite3_step(q_ping) == SQLITE_ROW && sqlite3_column_int(q_ping, 0) == 1;
  sqlite3_reset(q_ping);
  if (ok) return 0;
  const char *m = sqlite3_errmsg(db);
  gs_bld_append(err, m, (int64_t)strlen(m));
  return -1;
}

/* One row of the reference feed query: 1 (numbers in *row, the three strings appended to text
 * in the order body, created_at, author), 0 done, -1 error. */
int32_t db_feed_step(feed_row_t *row, gs_rref text) {
  int rc = sqlite3_step(q_feed);
  if (rc != SQLITE_ROW) {
    sqlite3_reset(q_feed);
    return rc == SQLITE_DONE ? 0 : -1;
  }
  row->id = sqlite3_column_int64(q_feed, 0);
  row->likes = sqlite3_column_int64(q_feed, 4);
  int64_t *lens[3] = {&row->body_len, &row->created_len, &row->author_len};
  for (int k = 0; k < 3; k++) {
    const unsigned char *s = sqlite3_column_text(q_feed, k + 1);
    *lens[k] = s ? sqlite3_column_bytes(q_feed, k + 1) : 0;
    if (s) gs_bld_append(text, s, *lens[k]);
  }
  return 1;
}

/* Abandons the reference query before its last row. */
void db_feed_reset(void) { sqlite3_reset(q_feed); }
