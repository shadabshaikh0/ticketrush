# 🎟️ TicketRush — concurrency & distributed systems, made visible

A seat-booking backend built to **demonstrate concurrency control**, driven by a single
interactive page. You spawn hundreds of *real* concurrent booking requests against a real
Postgres database, watch the classic **race condition oversell seats live**, then flip on a
fix and watch the invariant hold. **Every number on screen is an actual server outcome — not a
browser animation.**

![TicketRush demo — naive strategy oversells (red), atomic strategy holds (green)](docs/demo.gif)

> Kotlin · Spring Boot 3 · PostgreSQL · Redis · Java 21 virtual threads · Docker

---

## Table of contents
- [What it demonstrates](#what-it-demonstrates)
- [Quick start](#quick-start)
- [The strategies](#the-strategies)
- [How it works](#how-it-works)
- [API reference](#api-reference)
- [Test cases](#test-cases) — copy-paste commands with expected results
- [Project layout](#project-layout)

---

## What it demonstrates

| Concept | Where you see it |
|---|---|
| **Lost-update race condition** | the `naive` strategy — measured, not hand-waved |
| **Pessimistic locking** | `SELECT … FOR UPDATE` inside a transaction |
| **Optimistic concurrency** | a `version` column + compare-and-set + retry |
| **Atomic conditional write** | one-statement correctness, no explicit lock |
| **In-process vs distributed lock** | a JVM lock that works on 1 node but **oversells across 3** |
| **Distributed lock + fencing** | Redis `SET NX PX` coordinating all nodes |
| **Leases / TTL** | seat holds that auto-release when they expire |
| **Idempotency (effectively-once)** | a keyed confirm that books at most once under retries |
| **Leader election** | only one node runs the hold-expiry sweeper |
| **Invariant checking** | after every run: `bookings == distinct seats ≤ total seats` |

---

## Quick start

**Prerequisites:** Docker. (No local JDK/Gradle needed — it builds inside the image.)

### Single node — the fastest way in
```bash
docker compose up --build
```
Open **http://localhost:8080**, pick a strategy, hit **Run stampede**.

### The cluster — 3 nodes behind nginx (for the distributed demo)
```bash
docker compose -f docker-compose.cluster.yml up --build --scale app=3
```
Open **http://localhost:8080**, set **App nodes = 3**, then compare `synchronized` (oversells)
with `redis lock` (holds).

### Local dev (app in your IDE)
```bash
docker compose up -d db redis      # just the datastores
gradle bootRun                     # or run TicketRushApplication in IntelliJ
```

Stop everything with `docker compose down` (add `-f docker-compose.cluster.yml` for the cluster).

---

## The strategies

Default run = **500 concurrent requests for 100 seats**. Round-robin assignment means ~5
requests fight for each seat. The **invariant** is: no seat is booked more than once, and total
bookings ≤ total seats.

| Strategy | Mechanism | 1 node | 3 nodes |
|---|---|:---:|:---:|
| `naive` | read status, then write — no coordination | ❌ oversells | ❌ oversells |
| `pessimistic` | `SELECT … FOR UPDATE` (row lock) | ✅ | ✅ |
| `optimistic` | `UPDATE … WHERE version = ?` + retry | ✅ | ✅ |
| `atomic` | `UPDATE … WHERE status = 'AVAILABLE'` | ✅ | ✅ |
| `synchronized` | JVM lock around a naive body | ✅ | ❌ **oversells** |
| `redis lock` | Redis `SET NX PX` + fencing token | ✅ | ✅ |

The headline lesson is the last two rows: a `synchronized` block is correct on one node but
**each JVM has its own lock**, so across a cluster it oversells — only the Redis (distributed)
lock composes.

Beyond the stampede, two more demos on the page:
- **Hold seats (TTL):** reserve seats as *leases*; they turn yellow, then auto-release to grey
  when the lease expires (a leader-elected sweeper runs every second).
- **Idempotency test:** fire several identical confirmations (same key) at once → exactly one
  booking is created.

---

## How it works

```mermaid
flowchart LR
  P[Interactive page] -->|POST /demo/stampede| S[StampedeService]
  S -->|N virtual threads released together| B[BookingService]
  B --> DB[(PostgreSQL)]
  S -->|nodes above 1, fan out via nginx| LB{{nginx}}
  LB --> A1[app node 1]
  LB --> A2[app node 2]
  LB --> A3[app node 3]
  A1 --> DB
  A2 --> DB
  A3 --> DB
  A1 -.->|Redis lock / leader election| R[(Redis)]
  A2 -.-> R
  A3 -.-> R
  S -->|SSE booked and oversell events| P
```

**Honest concurrency:** browsers cap ~6 connections per host, so the real contention is
generated **server-side** — `StampedeService` launches N virtual threads that all block on a
start latch and are released at once. In cluster mode each request is sent through nginx so it
lands on a different node, which is what exposes the `synchronized` strategy.

**Key files:**
| File | Role |
|---|---|
| [`SeatRepository.kt`](src/main/kotlin/com/ticketrush/SeatRepository.kt) | the DB strategies + holds + metrics (the heart) |
| [`LockingBookings.kt`](src/main/kotlin/com/ticketrush/LockingBookings.kt) | JVM-lock and Redis-lock strategies |
| [`RedisLock.kt`](src/main/kotlin/com/ticketrush/RedisLock.kt) | `SET NX PX` acquire + safe release + fencing |
| [`StampedeService.kt`](src/main/kotlin/com/ticketrush/StampedeService.kt) | server-side load generator + cluster fan-out |
| [`HoldSweeper.kt`](src/main/kotlin/com/ticketrush/HoldSweeper.kt) | leader-elected TTL sweeper |
| [`SeatEventPublisher.kt`](src/main/kotlin/com/ticketrush/SeatEventPublisher.kt) | SSE fan-out for the live grid |
| [`static/`](src/main/resources/static/) | the interactive page |

---

## API reference

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | the interactive page |
| `POST` | `/demo/stampede` | run the rush `{concurrency, strategy, gapMs, seatCount, nodes}` |
| `GET` | `/demo/stream` | SSE live events (`reset`, `booked`, `held`, `released`, `summary`) |
| `GET` | `/demo/state` | seats + booking counts (authoritative grid repaint) |
| `POST` | `/demo/reset` | clear bookings, mark all seats available |
| `POST` | `/demo/hold` | hold N seats for a TTL `{count, ttlSeconds}` |
| `POST` | `/demo/idempotency-test` | fire identical confirms; exactly one booking results |
| `POST` | `/internal/book` | book one seat on this node `{seatId, strategy}` (cluster fan-out) |

---

## Test cases

Everything below is runnable against `http://localhost:8080`. The **invariant** that must hold
for every correct strategy: `bookingRows == distinctSeatsBooked` **and** `≤ totalSeats`.

### 1 · The race condition and its fixes (single node)
```bash
for s in NAIVE PESSIMISTIC OPTIMISTIC ATOMIC SYNCHRONIZED REDIS_LOCK; do
  curl -s -X POST http://localhost:8080/demo/stampede -H 'Content-Type: application/json' \
    -d "{\"concurrency\":500,\"strategy\":\"$s\",\"gapMs\":5,\"seatCount\":100,\"nodes\":1}" \
  | python3 -c "import sys,json;r=json.load(sys.stdin);print(f\"{r['strategy']:<13} oversold={r['oversoldSeats']:<3} invariant={r['invariantHeld']}\")"
done
```
Expected:

| Strategy | oversold | invariant |
|---|:---:|:---:|
| NAIVE | > 0 | **false** ❌ |
| PESSIMISTIC | 0 | true ✅ |
| OPTIMISTIC | 0 | true ✅ |
| ATOMIC | 0 | true ✅ |
| SYNCHRONIZED | 0 | true ✅ |
| REDIS_LOCK | 0 | true ✅ |

### 2 · In-process lock fails across nodes (needs the cluster)
```bash
# start: docker compose -f docker-compose.cluster.yml up --build --scale app=3
for s in SYNCHRONIZED REDIS_LOCK; do
  curl -s -X POST http://localhost:8080/demo/stampede -H 'Content-Type: application/json' \
    -d "{\"concurrency\":300,\"strategy\":\"$s\",\"gapMs\":50,\"seatCount\":100,\"nodes\":3}" \
  | python3 -c "import sys,json;r=json.load(sys.stdin);print(f\"{r['strategy']:<13} oversold={r['oversoldSeats']:<3} invariant={r['invariantHeld']} nodesSeen={r['nodesSeen']}\")"
done
```
Expected: `SYNCHRONIZED` → **oversold > 0, invariant false** (each node has its own JVM lock);
`REDIS_LOCK` → **oversold 0, invariant true**. Both show `nodesSeen=3`.

### 3 · Idempotency — identical confirms create one booking
```bash
curl -s -X POST http://localhost:8080/demo/idempotency-test | python3 -m json.tool
```
Expected: `"bookings": 1`, `"correct": true`, outcomes like `["BOOKED","REJECTED",...]`.

### 4 · Hold lease auto-releases on TTL
```bash
curl -s -X POST http://localhost:8080/demo/hold -H 'Content-Type: application/json' -d '{"count":5,"ttlSeconds":3}'
curl -s http://localhost:8080/demo/seats | python3 -c "import sys,json;d=json.load(sys.stdin);print('HELD now:', sum(1 for s in d if s['status']=='HELD'))"
sleep 5
curl -s http://localhost:8080/demo/seats | python3 -c "import sys,json;d=json.load(sys.stdin);print('HELD after expiry:', sum(1 for s in d if s['status']=='HELD'))"
```
Expected: `HELD now: 5` → `HELD after expiry: 0` (the sweeper released them).

---

## Project layout
```
src/main/kotlin/com/ticketrush/
  TicketRushApplication.kt   app entrypoint (@EnableScheduling)
  SeatRepository.kt          DB strategies, holds, metrics
  LockingBookings.kt         JVM-lock + Redis-lock strategies
  RedisLock.kt               distributed lock (SET NX PX + fencing)
  BookingService.kt          maps Strategy -> implementation
  StampedeService.kt         server-side load generator + cluster fan-out
  HoldService.kt             hold + idempotency demos
  HoldSweeper.kt             leader-elected TTL sweeper
  SeatEventPublisher.kt      SSE fan-out
  DemoController.kt          /demo/*  ·  InternalController.kt  /internal/book
src/main/resources/
  static/                    the interactive page (index.html, app.js)
  schema.sql                 seat + booking tables
docker-compose.yml           single node (app + Postgres + Redis)
docker-compose.cluster.yml   3 app nodes + nginx + Postgres + Redis
```
