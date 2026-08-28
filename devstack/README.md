# Local dev stack

Redis and MongoDB for testing the edge rollback path without touching any production machine.

    gradlew devStackUp      start redis (6380) + mongo (27018)
    gradlew devStackStatus  show container + port state
    gradlew devStackDown    stop and remove them
    gradlew devStackWipe    stop, remove, and drop all data

Non-default ports on purpose: 6379/27017 are commonly taken by other projects, and pointing
the edge at someone else's Redis would mean testing against the wrong data.

Both are ephemeral: redis persists nothing (no RDB, no AOF) and the mongo volume is
container-local, so `devStackDown` leaves nothing behind.

The edge plugin points at 127.0.0.1:6380 by default when `broker.enabled` is true, so no
extra configuration is needed for local testing.
