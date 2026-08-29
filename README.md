# 🎟️ TicketRush — concurrency & distributed systems, made visible

A seat-booking backend built to **demonstrate concurrency control**, driven by a single
interactive page. You spawn hundreds of *real* concurrent booking requests against a real
Postgres database, watch the classic **race condition** oversell seats live, then flip on a
fix and watch the invariant hold — every number on screen is an actual server outcome, not a
browser animation.

> Built with Kotlin + Spring Boot 3, PostgreSQL, and Java 21 virtual threads.

---

## The demo

Open the page, pick a strategy, and hit **Run stampede** (default: 500 concurrent requests for
100 seats). Round-robin assignment means ~5 requests fight for each seat.

| Strategy | What happens | Invariant |
|---|---|---|
| **naive** (read-then-write) | seats get sold multiple times — cells flash 🔴 | ❌ violated |
| **pessimistic** (`SELECT … FOR UPDATE`) | contenders queue on a row lock | ✅ holds |
| **optimistic** (`version` + retry) | losers detect the conflict and retry | ✅ holds |
| **atomic** (`UPDATE … WHERE status='AVAILABLE'`) | one conditional update wins | ✅ holds |

Each run appends a row to the comparison table so you can see naive break and the three fixes
hold, side by side, with p99 latency and throughput.

### Why the concurrency is honest
Browsers cap ~6 connections per host, so the real contention is generated **server-side**:
`POST /demo/stampede` launches N **virtual threads** that all block on a start latch and are
released at once (`StampedeService`). Progress streams back to the page over **SSE**
(`/demo/stream`) to animate the grid.

---

## Concepts demonstrated
- **Lost update / race condition** — the naive path, measured (not hand-waved).
- **Pessimistic locking** — `SELECT … FOR UPDATE` inside a transaction.
- **Optimistic concurrency** — version column + compare-and-set + retry.
- **Atomic conditional write** — single-statement correctness with no explicit lock.
- **Invariant checking** — after every run: `bookings == distinct seats booked ≤ total seats`.

Roadmap (see `PLAN`): seat-hold **TTL** + **idempotency keys** (M2); **multi-node** behind nginx
with a **Redis distributed lock + fencing tokens** and a **leader-elected** TTL sweeper (M3).

---

## Run it

### Option A — Docker (everything, one command)
```bash
docker compose up --build
```
Then open <http://localhost:8080>.

### Option B — local (Postgres in Docker, app in your IDE / Gradle)
```bash
docker compose up -d db          # just Postgres
gradle bootRun                   # or run TicketRushApplication from IntelliJ
```
(If you don't have Gradle installed, open the folder in IntelliJ — it provisions the wrapper —
or run `gradle wrapper` once.)

---

## How it works

```
Browser page ──POST /demo/stampede {concurrency, strategy}──▶ StampedeService
                                                              │ N virtual threads,
                                                              │ released together
                                                              ▼
                                            SeatRepository.book{Naive|Pessimistic|
                                            Optimistic|Atomic}  ──▶ PostgreSQL
                                                              │
                          SSE /demo/stream  ◀──── SeatEventPublisher (booked / oversell / summary)
                                                              │
Browser grid animates live ◀──────────────────────────────────┘
```

Key files:
- `SeatRepository.kt` — the four strategies, one method each (the heart of the project).
- `StampedeService.kt` — server-side concurrency generator + invariant/metrics.
- `SeatEventPublisher.kt` — SSE fan-out for the live grid.
- `src/main/resources/static/` — the interactive page.

---

## API
| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | the interactive page |
| `POST` | `/demo/stampede` | run the rush `{concurrency, strategy, gapMs, seatCount}` |
| `GET` | `/demo/stream` | SSE live events |
| `POST` | `/demo/reset` | clear bookings, mark all seats available |
| `GET` | `/demo/seats` | current seat map |
