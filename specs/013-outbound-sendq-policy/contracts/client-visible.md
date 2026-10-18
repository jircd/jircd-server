# Contract: client-visible behavior on overflow

To the overflowing client (best effort, only if its socket can take the write):

```
ERROR :Closing Link: <host> (SendQ exceeded)
```

then the connection is closed. `<host>` is the connection's remote address, the same value used for
other `Closing Link` lines.

To the other members of channels it shared:

```
:<nick>!<user>@<host> QUIT :SendQ exceeded
```

- No other message format changes. Lines queued before the overflow may be discarded rather than
  delivered ahead of the `ERROR`.
- A client that reads continuously and stays under the limit sees no change.
