# Scripted real-socket walkthrough

Validated through scripted `UserhostInNamesTest` and `UserhostInNamesLifecycleTest` runs using TestServer, ephemeral loopback sockets and test-generated YAML, rather than an installed distribution. Quickstart step 5 is covered at the catalog/loader level. Commands and results from before the review edits are summarized in [build.md](build.md); the coordinator will validate the new peer-cloak and explicit post-SAJOIN NAMES assertions.

- Pre-registration CAP REQ/ACK, post-registration REQ/LIST and removal: expanded `@alice!ident@127.0.0.1` versus ordinary `@alice`; negotiation removal affects only the requester.
- Cloak disabled and enabled: NAMES exactly matches the display host seen by a separate WHOIS requester; enabled cloak host contains `user-` and excludes `127.0.0.1`. Self-WHOIS/WHO intentionally retain their pre-existing real-host presentation, so an independent observer supplies the privacy reference. Ordinary WHO fields remain separate and unchanged.
- Four status/identity combinations: `@owner`, `@+owner`, `@owner!界界@127.0.0.1`, `@+owner!界界@127.0.0.1`. No implicit dependency between capabilities.
- Explicit/bare NAMES and automatic JOIN/SAJOIN: same expansion; private and secret channels hidden from a non-member, missing channel returns only 366, SAJOIN after OPER yields full visible list. Existing channel visibility is preserved; no new invisible-user policy is introduced.
- Two-line JOIN listing with three nine-character nicks, 27-byte UTF-8 idents and a 349-byte UTF-8 channel: exact reconstructed members, no duplicates/truncation, every serialized reply <=512 bytes including CRLF, one 366 with `End of /NAMES list`.
- EXTENSION and REHASH withdrawal: eligible explicit/implicit cap-notify clients receive DEL/NEW, legacy clients receive none, new captures use bare nicknames after withdrawal, and re-enable restores expansion only after REQ. The WHO-driven oversized reload emits `Rehash failed`, sends no capability changes, and preserves expanded output and CAP LIST.

