package feed;

import server.Bytes;

/** A post as the API shows it: its id, body, created_at, its author's username and its like
 * count. The texts are views into wherever the row was read (see Bytes). */
value record PostRow(long id, Bytes body, Bytes createdAt, Bytes author, long likes) {}
