/* A native replay of bench/load.js's user model, for local comparisons (not part of a submission).
 *
 * One virtual user = one keep-alive connection running:
 *   GET /feed -> think 3-7 s -> GET /posts/:id (one from the feed) -> think 3-8 s
 *   -> like it (15 %) -> write a post (2 %) -> idle 5-15 s -> repeat.
 * -s divides every think time, so fewer connections can offer the request rate of more users.
 *
 *   loadgen -H 127.0.0.1 -p 3000 -u 50000 -r 30 -d 60 -s 4 -t 4 -T seed/tokens.json
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

enum { ST_IDLE, ST_CONNECTING, ST_FEED, ST_POST_GET, ST_LIKE, ST_CREATE };
enum { W_NONE, W_START, W_FEED, W_POST_GET, W_WRITES };
enum { K_FEED, K_GET, K_LIKE, K_CREATE, K_N };

#define NB 1024 /* histogram buckets: 16 per power of two, in microseconds */
#define RBUF 16384

typedef struct {
  int fd;
  uint8_t st, wake;
  uint8_t want_create;
  uint32_t id;       /* user index */
  uint32_t post_id;
  uint32_t iter;
  uint32_t rlen;
  uint64_t t_send;
  char *rbuf;
  int heap_pos;
} vu_t;

typedef struct {
  uint64_t at;
  vu_t *v;
} tm_t;

typedef struct {
  int id, ep;
  vu_t *vus;
  int nvu;
  tm_t *heap;
  int nheap;
  uint64_t rng;
  char **pool;
  int npool;
  uint64_t hist[K_N][NB];
  uint64_t count[K_N], errs[K_N];
  uint64_t conn_errs, timeouts;
  _Atomic uint64_t done, bad; /* running totals for the progress line */
  _Atomic uint64_t slow;      /* responses over 1 s */
} th_t;

static const char *host = "127.0.0.1";
static int port = 3000, users = 1000, ramp = 10, hold = 30, nthreads = 2;
static double scale = 1.0;
static char **tokens, **names;
static int ntokens;
static uint64_t t_begin, t_hold, t_end;
static _Atomic int measuring;
static int srv_pid;

/* read and write system calls of the server process so far */
static void io_calls(unsigned long long *r, unsigned long long *w) {
  char path[64], line[128];
  *r = *w = 0;
  if (!srv_pid) return;
  snprintf(path, sizeof path, "/proc/%d/io", srv_pid);
  FILE *f = fopen(path, "r");
  if (!f) return;
  while (fgets(line, sizeof line, f)) {
    sscanf(line, "syscr: %llu", r);
    sscanf(line, "syscw: %llu", w);
  }
  fclose(f);
}

/* utime and stime of the server process, in clock ticks */
static void cpu_ticks(unsigned long long *ut, unsigned long long *st) {
  char path[64], buf[2048];
  *ut = *st = 0;
  if (!srv_pid) return;
  snprintf(path, sizeof path, "/proc/%d/stat", srv_pid);
  FILE *f = fopen(path, "r");
  if (!f) return;
  size_t n = fread(buf, 1, sizeof buf - 1, f);
  fclose(f);
  buf[n] = 0;
  char *p = strrchr(buf, ')');
  if (!p) return;
  int field = 2;
  for (p++; *p; p++) {
    if (*p == ' ') {
      field++;
      if (field == 14) { sscanf(p + 1, "%llu %llu", ut, st); return; }
    }
  }
}

static uint64_t now_ns(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return (uint64_t)t.tv_sec * 1000000000ull + t.tv_nsec;
}

static uint64_t rnd(th_t *t) {
  uint64_t x = t->rng;
  x ^= x << 13; x ^= x >> 7; x ^= x << 17;
  return t->rng = x;
}
static double frand(th_t *t) { return (rnd(t) >> 11) * (1.0 / 9007199254740992.0); }
static uint64_t between(th_t *t, double lo, double hi) {
  return (uint64_t)((lo + frand(t) * (hi - lo)) / scale * 1e9);
}

static int bucket(uint64_t us) {
  if (us < 16) return (int)us;
  int e = 63 - __builtin_clzll(us);
  int b = (e - 3) * 16 + (int)((us >> (e - 4)) & 15);
  return b >= NB ? NB - 1 : b;
}
static double bucket_us(int b) {
  if (b < 16) return b;
  int e = b / 16 + 3, m = b % 16;
  return (double)((16ull + m) << (e - 4));
}

/* ---- timers: a binary heap ---- */
static void heap_swap(th_t *t, int a, int b) {
  tm_t x = t->heap[a]; t->heap[a] = t->heap[b]; t->heap[b] = x;
  t->heap[a].v->heap_pos = a; t->heap[b].v->heap_pos = b;
}
static void heap_push(th_t *t, vu_t *v, uint64_t at) {
  int i = t->nheap++;
  t->heap[i] = (tm_t){at, v};
  v->heap_pos = i;
  while (i > 0 && t->heap[(i - 1) / 2].at > t->heap[i].at) { heap_swap(t, i, (i - 1) / 2); i = (i - 1) / 2; }
}
static vu_t *heap_pop(th_t *t) {
  vu_t *v = t->heap[0].v;
  t->nheap--;
  if (t->nheap > 0) {
    t->heap[0] = t->heap[t->nheap];
    t->heap[0].v->heap_pos = 0;
    int i = 0;
    for (;;) {
      int l = 2 * i + 1, r = l + 1, m = i;
      if (l < t->nheap && t->heap[l].at < t->heap[m].at) m = l;
      if (r < t->nheap && t->heap[r].at < t->heap[m].at) m = r;
      if (m == i) break;
      heap_swap(t, i, m); i = m;
    }
  }
  v->heap_pos = -1;
  return v;
}

static char *buf_get(th_t *t) { return t->npool ? t->pool[--t->npool] : malloc(RBUF); }
static void buf_put(th_t *t, char *b) { t->pool[t->npool++] = b; }

static void sleep_then(th_t *t, vu_t *v, int wake, uint64_t ns) {
  v->st = ST_IDLE;
  v->wake = (uint8_t)wake;
  heap_push(t, v, now_ns() + ns);
}

static void drop(th_t *t, vu_t *v) {
  if (v->fd >= 0) { close(v->fd); v->fd = -1; }
  if (v->rbuf) { buf_put(t, v->rbuf); v->rbuf = 0; }
}

static void fail(th_t *t, vu_t *v, int kind) {
  if (atomic_load(&measuring)) { if (kind >= 0) t->errs[kind]++; else t->conn_errs++; }
  atomic_fetch_add(&t->bad, 1);
  drop(t, v);
  /* load.js: a failed feed sleeps 3-7 s and starts over */
  sleep_then(t, v, W_FEED, between(t, 3, 7));
}

static int start_connect(th_t *t, vu_t *v) {
  int fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
  if (fd < 0) return -1;
  int one = 1;
  setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
  setsockopt(fd, IPPROTO_IP, IP_BIND_ADDRESS_NO_PORT, &one, sizeof one);
  struct sockaddr_in src = {.sin_family = AF_INET};
  /* many source addresses: one address has too few ports for every user */
  if (!strncmp(host, "127.", 4)) {
    src.sin_addr.s_addr = htonl(0x7f000100u + 1 + v->id / 20000);
    if (bind(fd, (struct sockaddr *)&src, sizeof src) < 0) { close(fd); return -1; }
  }
  struct sockaddr_in dst = {.sin_family = AF_INET, .sin_port = htons(port)};
  inet_pton(AF_INET, host, &dst.sin_addr);
  int rc = connect(fd, (struct sockaddr *)&dst, sizeof dst);
  if (rc < 0 && errno != EINPROGRESS) { close(fd); return -1; }
  v->fd = fd;
  v->st = ST_CONNECTING;
  struct epoll_event ev = {.events = EPOLLOUT | EPOLLIN | EPOLLRDHUP, .data.ptr = v};
  epoll_ctl(t->ep, EPOLL_CTL_ADD, fd, &ev);
  return 0;
}

static void send_req(th_t *t, vu_t *v, int st) {
  char req[2048];
  int n;
  const char *tok = tokens[v->id % ntokens];
  switch (st) {
  case ST_FEED:
    n = snprintf(req, sizeof req, "GET /feed HTTP/1.1\r\nHost: %s\r\nUser-Agent: Grafana k6/1.3.0\r\n\r\n", host);
    break;
  case ST_POST_GET:
    n = snprintf(req, sizeof req, "GET /posts/%u HTTP/1.1\r\nHost: %s\r\nUser-Agent: Grafana k6/1.3.0\r\n\r\n", v->post_id, host);
    break;
  case ST_LIKE:
    n = snprintf(req, sizeof req,
                 "POST /posts/%u/like HTTP/1.1\r\nHost: %s\r\nUser-Agent: Grafana k6/1.3.0\r\nContent-Length: 0\r\n"
                 "Authorization: Bearer %s\r\nContent-Type: application/json\r\n\r\n",
                 v->post_id, host, tok);
    break;
  default: {
    char body[512];
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    gmtime_r(&ts.tv_sec, &tm);
    int bn = snprintf(body, sizeof body,
                      "{\"body\":\"%s says hi at %04d-%02d-%02dT%02d:%02d:%02d.%03ldZ (VU %u, iter %u)\"}",
                      names[v->id % ntokens], tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour, tm.tm_min,
                      tm.tm_sec, ts.tv_nsec / 1000000, v->id + 1, v->iter);
    n = snprintf(req, sizeof req,
                 "POST /posts HTTP/1.1\r\nHost: %s\r\nUser-Agent: Grafana k6/1.3.0\r\nContent-Length: %d\r\n"
                 "Authorization: Bearer %s\r\nContent-Type: application/json\r\n\r\n%s",
                 host, bn, tok, body);
  }
  }
  v->st = (uint8_t)st;
  v->rlen = 0;
  if (!v->rbuf) v->rbuf = buf_get(t);
  v->t_send = now_ns();
  ssize_t w = send(v->fd, req, n, MSG_NOSIGNAL);
  if (w != n) fail(t, v, st - ST_FEED);
}

/* After the post was read and the 3-8 s think: like (15 %), then create (2 %), then idle. */
static void after_writes(th_t *t, vu_t *v) {
  v->iter++;
  sleep_then(t, v, W_FEED, between(t, 5, 15));
}

static void on_wake(th_t *t, vu_t *v) {
  switch (v->wake) {
  case W_START:
  case W_FEED:
    if (v->fd < 0) {
      if (start_connect(t, v) < 0) fail(t, v, -1);
    } else {
      send_req(t, v, ST_FEED);
    }
    break;
  case W_POST_GET:
    send_req(t, v, ST_POST_GET);
    break;
  case W_WRITES: {
    int like = frand(t) < 0.15;
    v->want_create = frand(t) < 0.02;
    if (like) send_req(t, v, ST_LIKE);
    else if (v->want_create) { v->want_create = 0; send_req(t, v, ST_CREATE); }
    else after_writes(t, v);
    break;
  }
  }
}

/* A whole response is in rbuf: status and body. */
static void on_response(th_t *t, vu_t *v, int status, const char *body, int blen) {
  uint64_t us = (now_ns() - v->t_send) / 1000;
  int kind = v->st - ST_FEED;
  int ok = kind == K_FEED || kind == K_GET ? status == 200 : kind == K_LIKE ? (status == 200 || status == 201) : status == 201;
  if (atomic_load(&measuring)) {
    t->hist[kind][bucket(us)]++;
    t->count[kind]++;
    if (!ok) t->errs[kind]++;
  }
  atomic_fetch_add(&t->done, 1);
  if (!ok) atomic_fetch_add(&t->bad, 1);
  if (us > 1000000) atomic_fetch_add(&t->slow, 1);
  int st = v->st;
  buf_put(t, v->rbuf);
  v->rbuf = 0;
  switch (st) {
  case ST_FEED: {
    if (!ok) { sleep_then(t, v, W_FEED, between(t, 3, 7)); return; }
    uint32_t ids[32];
    int n = 0;
    for (const char *p = body; n < 32 && (p = memmem(p, blen - (p - body), "{\"id\":", 6)); p += 6)
      ids[n++] = (uint32_t)strtoul(p + 6, 0, 10);
    if (n == 0) { sleep_then(t, v, W_FEED, between(t, 3, 7)); return; }
    v->post_id = ids[rnd(t) % n];
    sleep_then(t, v, W_POST_GET, between(t, 3, 7));
    break;
  }
  case ST_POST_GET:
    sleep_then(t, v, W_WRITES, between(t, 3, 8));
    break;
  case ST_LIKE:
    if (v->want_create) { v->want_create = 0; send_req(t, v, ST_CREATE); }
    else after_writes(t, v);
    break;
  case ST_CREATE:
    after_writes(t, v);
    break;
  }
}

static void on_readable(th_t *t, vu_t *v) {
  for (;;) {
    if (v->st < ST_FEED || !v->rbuf) {
      /* nothing expected: EOF or stray bytes */
      char tmp[256];
      ssize_t r = recv(v->fd, tmp, sizeof tmp, 0);
      if (r == 0 || (r < 0 && errno != EAGAIN)) {
        /* the server closed an idle connection: reconnect at the next request */
        epoll_ctl(t->ep, EPOLL_CTL_DEL, v->fd, 0);
        close(v->fd);
        v->fd = -1;
      }
      return;
    }
    ssize_t r = recv(v->fd, v->rbuf + v->rlen, RBUF - 1 - v->rlen, 0);
    if (r < 0) {
      if (errno == EAGAIN) return;
      if (v->heap_pos < 0) fail(t, v, v->st - ST_FEED);
      return;
    }
    if (r == 0) { fail(t, v, v->st - ST_FEED); return; }
    v->rlen += (uint32_t)r;
    v->rbuf[v->rlen] = 0;
    char *he = memmem(v->rbuf, v->rlen, "\r\n\r\n", 4);
    if (!he) { if (v->rlen >= RBUF - 1) { fail(t, v, v->st - ST_FEED); return; } continue; }
    int clen = 0;
    char *cl = strcasestr(v->rbuf, "content-length:");
    if (cl && cl < he) clen = atoi(cl + 15);
    int hlen = (int)(he + 4 - v->rbuf);
    if ((int)v->rlen < hlen + clen) { if (hlen + clen >= RBUF - 1) { fail(t, v, v->st - ST_FEED); return; } continue; }
    int status = atoi(v->rbuf + 9);
    on_response(t, v, status, v->rbuf + hlen, clen);
    return;
  }
}

static void *run(void *arg) {
  th_t *t = arg;
  struct epoll_event evs[1024];
  for (int i = 0; i < t->nvu; i++) {
    vu_t *v = &t->vus[i];
    /* ramping-vus starts users linearly; each then sleeps 0-5 s before its first request */
    uint64_t at = t_begin + (uint64_t)((double)v->id / users * ramp * 1e9) + between(t, 0, 5);
    v->st = ST_IDLE;
    v->wake = W_START;
    heap_push(t, v, at);
  }
  for (;;) {
    uint64_t now = now_ns();
    if (now >= t_end) break;
    while (t->nheap > 0 && t->heap[0].at <= now) on_wake(t, heap_pop(t));
    int to = 100;
    if (t->nheap > 0) {
      uint64_t d = t->heap[0].at > now ? t->heap[0].at - now : 0;
      to = (int)(d / 1000000);
      if (to > 100) to = 100;
    }
    int n = epoll_wait(t->ep, evs, 1024, to);
    for (int i = 0; i < n; i++) {
      vu_t *v = evs[i].data.ptr;
      if (v->fd < 0) continue;
      if (v->st == ST_CONNECTING) {
        int err = 0;
        socklen_t l = sizeof err;
        getsockopt(v->fd, SOL_SOCKET, SO_ERROR, &err, &l);
        if (err || (evs[i].events & (EPOLLERR | EPOLLHUP))) { fail(t, v, -1); continue; }
        if (!(evs[i].events & EPOLLOUT)) continue;
        struct epoll_event ev = {.events = EPOLLIN | EPOLLRDHUP, .data.ptr = v};
        epoll_ctl(t->ep, EPOLL_CTL_MOD, v->fd, &ev);
        send_req(t, v, ST_FEED);
        continue;
      }
      if (evs[i].events & (EPOLLIN | EPOLLRDHUP | EPOLLERR | EPOLLHUP)) on_readable(t, v);
    }
  }
  return 0;
}

static void load_tokens(const char *path) {
  FILE *f = fopen(path, "rb");
  if (!f) { perror(path); exit(1); }
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  char *s = malloc(n + 1);
  if (fread(s, 1, n, f) != (size_t)n) { perror("read"); exit(1); }
  s[n] = 0;
  fclose(f);
  int cap = 32768;
  tokens = malloc(cap * sizeof *tokens);
  names = malloc(cap * sizeof *names);
  char *p = s;
  while (ntokens < cap) {
    char *u = strstr(p, "\"username\":\"");
    char *k = strstr(p, "\"token\":\"");
    if (!u || !k) break;
    u += 12; k += 9;
    char *ue = strchr(u, '"'), *ke = strchr(k, '"');
    names[ntokens] = strndup(u, ue - u);
    tokens[ntokens] = strndup(k, ke - k);
    ntokens++;
    p = (ue > ke ? ue : ke) + 1;
  }
  free(s);
  if (!ntokens) { fprintf(stderr, "no tokens in %s\n", path); exit(1); }
}

static double pct(uint64_t *h, uint64_t total, double p) {
  if (!total) return 0;
  uint64_t want = (uint64_t)(total * p), acc = 0;
  for (int b = 0; b < NB; b++) {
    acc += h[b];
    if (acc > want) return bucket_us(b) / 1000.0;
  }
  return bucket_us(NB - 1) / 1000.0;
}

int main(int argc, char **argv) {
  const char *tok = "seed/tokens.json";
  int c;
  while ((c = getopt(argc, argv, "H:p:u:r:d:s:t:T:P:")) != -1) {
    switch (c) {
    case 'H': host = optarg; break;
    case 'p': port = atoi(optarg); break;
    case 'u': users = atoi(optarg); break;
    case 'r': ramp = atoi(optarg); break;
    case 'd': hold = atoi(optarg); break;
    case 's': scale = atof(optarg); break;
    case 't': nthreads = atoi(optarg); break;
    case 'T': tok = optarg; break;
    case 'P': srv_pid = atoi(optarg); break;
    default: fprintf(stderr, "usage: loadgen -H host -p port -u users -r ramp -d hold -s scale -t threads -T tokens.json\n"); return 2;
    }
  }
  load_tokens(tok);
  th_t *th = calloc(nthreads, sizeof *th);
  pthread_t *tid = calloc(nthreads, sizeof *tid);
  t_begin = now_ns() + 200000000ull;
  /* the first requests come up to 5 s (scaled) after the ramp ends */
  t_hold = t_begin + (uint64_t)ramp * 1000000000ull + (uint64_t)(5e9 / scale);
  t_end = t_hold + (uint64_t)hold * 1000000000ull;
  for (int i = 0; i < nthreads; i++) {
    th_t *t = &th[i];
    t->id = i;
    t->ep = epoll_create1(EPOLL_CLOEXEC);
    t->nvu = users / nthreads + (i < users % nthreads);
    t->vus = calloc(t->nvu, sizeof(vu_t));
    t->heap = calloc(t->nvu + 1, sizeof(tm_t));
    t->pool = calloc(t->nvu + 1, sizeof(char *));
    t->rng = 0x9e3779b97f4a7c15ull * (i + 1) ^ now_ns();
    for (int k = 0; k < t->nvu; k++) {
      t->vus[k].fd = -1;
      t->vus[k].id = (uint32_t)(k * nthreads + i);
      t->vus[k].heap_pos = -1;
    }
    pthread_create(&tid[i], 0, run, t);
  }
  uint64_t last_done = 0;
  unsigned long long ut0 = 0, st0 = 0, ut1 = 0, st1 = 0, r0 = 0, w0 = 0, r1 = 0, w1 = 0;
  for (;;) {
    sleep(1);
    uint64_t now = now_ns();
    if (now >= t_end) break;
    if (!atomic_load(&measuring) && now >= t_hold) { cpu_ticks(&ut0, &st0); io_calls(&r0, &w0); atomic_store(&measuring, 1); }
    uint64_t done = 0, bad = 0, slow = 0;
    for (int i = 0; i < nthreads; i++) { done += th[i].done; bad += th[i].bad; slow += th[i].slow; }
    if (getenv("LG_QUIET") == 0)
      fprintf(stderr, "%4.0fs %s %7llu req/s  bad %llu  >1s %llu\n", (now - t_begin) / 1e9, now >= t_hold ? "hold" : "ramp",
              (unsigned long long)(done - last_done), (unsigned long long)bad, (unsigned long long)slow);
    last_done = done;
  }
  cpu_ticks(&ut1, &st1);
  io_calls(&r1, &w1);
  for (int i = 0; i < nthreads; i++) pthread_join(tid[i], 0);
  static const char *kn[K_N] = {"GET /feed", "GET /posts/:id", "POST like", "POST /posts"};
  uint64_t all[NB] = {0}, total = 0, errs = 0, conn = 0;
  for (int k = 0; k < K_N; k++) {
    uint64_t h[NB] = {0}, n = 0, e = 0;
    for (int i = 0; i < nthreads; i++) {
      for (int b = 0; b < NB; b++) { h[b] += th[i].hist[k][b]; all[b] += th[i].hist[k][b]; }
      n += th[i].count[k];
      e += th[i].errs[k];
    }
    total += n;
    errs += e;
    printf("%-16s n=%-9llu err=%-6llu p50=%.2f p95=%.2f p99=%.2f p99.9=%.2f ms\n", kn[k], (unsigned long long)n,
           (unsigned long long)e, pct(h, n, 0.50), pct(h, n, 0.95), pct(h, n, 0.99), pct(h, n, 0.999));
  }
  for (int i = 0; i < nthreads; i++) conn += th[i].conn_errs;
  printf("TOTAL users=%d scale=%.2f hold=%ds reqs=%llu rps=%.0f err=%llu (%.3f%%) conn_err=%llu p50=%.2f p95=%.2f p99=%.2f p99.9=%.2f max~%.0f ms\n",
         users, scale, hold, (unsigned long long)total, (double)total / hold, (unsigned long long)errs,
         total ? 100.0 * errs / total : 0.0, (unsigned long long)conn, pct(all, total, 0.50), pct(all, total, 0.95),
         pct(all, total, 0.99), pct(all, total, 0.999), pct(all, total, 0.999999));
  if (srv_pid && total) {
    double tick = 1e6 / sysconf(_SC_CLK_TCK);
    double u = (ut1 - ut0) * tick, k = (st1 - st0) * tick;
    printf("SERVER cpu=%.1f%% user=%.2f us/req sys=%.2f us/req total=%.2f us/req file-writes=%.3f/req file-reads=%.3f/req\n",
           (u + k) / (hold * 1e4), u / total, k / total, (u + k) / total, (double)(w1 - w0) / total, (double)(r1 - r0) / total);
  }
  return 0;
}
