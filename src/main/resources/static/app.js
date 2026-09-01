// TicketRush interactive page.
// - renders the seat grid from the server
// - opens an SSE stream so the grid animates live during a stampede
// - fires POST /demo/stampede and shows the resulting metrics + comparison row

const grid = document.getElementById("grid");
const cellById = new Map();
let liveBooked = 0;
let liveOversold = 0;

const NARRATION = {
  NAIVE: "Read-then-write with no coordination. Many requests read the seat as AVAILABLE before any write lands, so they all 'succeed' and insert a booking — the same seat is sold multiple times (lost update). Watch cells flash red.",
  PESSIMISTIC: "SELECT … FOR UPDATE locks the seat row; contenders queue and only the first sees it AVAILABLE. Correct, but it serializes access and holds locks for the transaction.",
  OPTIMISTIC: "No locks. Each writer bumps a version and commits only if the version is unchanged (UPDATE … WHERE version = ?). Losers detect the conflict via rowcount 0 and retry or give up.",
  ATOMIC: "A single conditional UPDATE … WHERE status='AVAILABLE'. The database guarantees exactly one row-update wins the predicate. Simplest correct fix — no explicit lock, no retry loop.",
  SYNCHRONIZED: "A JVM lock (synchronized) around a naive read-then-write. On ONE node this serializes and is correct — but each node has its OWN lock, so set App nodes = 3 and the oversell returns: in-process locks don't compose across a cluster. (Uses a ~50ms gap so the cross-node race shows clearly through the network.)",
  REDIS_LOCK: "A distributed lock in Redis (SET NX PX + fencing token) around the same naive body. All nodes coordinate through Redis, so exactly one holder books each seat — correct even with App nodes = 3. This is how you lock across a cluster.",
};

function setNarration() {
  document.getElementById("narration").textContent =
    NARRATION[document.getElementById("strategy").value];
}
document.getElementById("strategy").addEventListener("change", () => {
  setNarration();
  // The cross-node race needs a wider read->write window to show through the network.
  const s = document.getElementById("strategy").value;
  const gap = document.getElementById("gap");
  if (s === "SYNCHRONIZED") gap.value = 50;
  else if (s === "NAIVE") gap.value = 5;
});
setNarration();

function renderGrid(seats) {
  grid.innerHTML = "";
  cellById.clear();
  for (const s of seats) {
    const el = document.createElement("div");
    el.className = "seat";
    el.textContent = s.label;
    el.dataset.id = s.id;
    grid.appendChild(el);
    cellById.set(String(s.id), el);
  }
}

async function loadSeats() {
  const res = await fetch("/demo/seats");
  renderGrid(await res.json());
}

function resetStatsUI() {
  liveBooked = 0;
  liveOversold = 0;
  for (const id of ["s-booked", "s-oversold", "s-rejected", "s-retries", "s-p99", "s-tput"]) {
    document.getElementById(id).textContent = "–";
  }
  const v = document.getElementById("verdict");
  v.className = "verdict idle";
  v.textContent = "running…";
}

// ---- SSE live stream ----
const es = new EventSource("/demo/stream");
es.addEventListener("msg", (e) => {
  const ev = JSON.parse(e.data);
  if (ev.type === "reset") {
    renderGrid(ev.seats);
  } else if (ev.type === "held") {
    const cell = cellById.get(String(ev.seatId));
    if (cell) {
      cell.classList.remove("booked", "oversold");
      cell.classList.add("held");
    }
  } else if (ev.type === "released") {
    const cell = cellById.get(String(ev.seatId));
    if (cell) cell.classList.remove("booked", "held", "oversold");
  } else if (ev.type === "booked") {
    const cell = cellById.get(String(ev.seatId));
    if (cell) {
      if (ev.oversell) {
        cell.classList.remove("booked", "held");
        cell.classList.add("oversold");
        liveOversold++;
        document.getElementById("s-oversold").textContent = liveOversold;
      } else if (!cell.classList.contains("oversold")) {
        cell.classList.remove("held");
        cell.classList.add("booked");
      }
    }
    liveBooked++;
    document.getElementById("s-booked").textContent = liveBooked;
  } else if (ev.type === "summary") {
    applySummary(ev.result);
  }
});

function applySummary(r) {
  document.getElementById("s-booked").textContent = r.distinctSeatsBooked;
  document.getElementById("s-oversold").textContent = r.oversoldSeats;
  document.getElementById("s-rejected").textContent = r.rejected;
  document.getElementById("s-retries").textContent = r.retries;
  document.getElementById("s-p99").textContent = r.p99Ms + " ms";
  document.getElementById("s-tput").textContent = Math.round(r.throughputPerSec) + "/s";

  const v = document.getElementById("verdict");
  const nodeNote = r.nodes > 1 ? ` · handled by ${r.nodesSeen} node${r.nodesSeen > 1 ? "s" : ""}` : "";
  if (r.invariantHeld) {
    v.className = "verdict pass";
    v.textContent = `✅ INVARIANT HELD — ${r.distinctSeatsBooked}/${r.totalSeats} booked, 0 double-booked${nodeNote}`;
  } else {
    v.className = "verdict fail";
    v.textContent = `❌ INVARIANT VIOLATED — ${r.bookingRows} bookings for ${r.totalSeats} seats, ${r.oversoldSeats} oversold${nodeNote}`;
  }

  const tbody = document.querySelector("#cmp tbody");
  const row = document.createElement("tr");
  row.innerHTML = `
    <td>${r.strategy.toLowerCase()}</td>
    <td>${r.distinctSeatsBooked}</td>
    <td class="${r.oversoldSeats > 0 ? "bad" : "ok"}">${r.oversoldSeats}</td>
    <td>${r.rejected}</td>
    <td class="${r.invariantHeld ? "ok" : "bad"}">${r.invariantHeld ? "PASS" : "FAIL"}</td>
    <td>${r.p99Ms}ms</td>
    <td>${Math.round(r.throughputPerSec)}</td>`;
  tbody.prepend(row);
}

// ---- actions ----
const runBtn = document.getElementById("run");
const resetBtn = document.getElementById("reset");

runBtn.addEventListener("click", async () => {
  runBtn.disabled = true;
  resetBtn.disabled = true;
  resetStatsUI();
  const body = {
    concurrency: parseInt(document.getElementById("concurrency").value, 10),
    strategy: document.getElementById("strategy").value,
    gapMs: parseInt(document.getElementById("gap").value, 10),
    seatCount: 100,
    nodes: parseInt(document.getElementById("nodes").value, 10),
  };
  try {
    await fetch("/demo/stampede", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    // final metrics also arrive via the SSE "summary" event;
    // repaint the grid from authoritative DB state (correct even in cluster mode).
    await repaintFromState();
  } catch (err) {
    document.getElementById("verdict").textContent = "request failed: " + err;
  } finally {
    runBtn.disabled = false;
    resetBtn.disabled = false;
  }
});

async function repaintFromState() {
  try {
    const res = await fetch("/demo/state");
    const seats = await res.json();
    for (const s of seats) {
      const cell = cellById.get(String(s.id));
      if (!cell) continue;
      cell.classList.remove("booked", "held", "oversold");
      if (s.bookings > 1) cell.classList.add("oversold");
      else if (s.bookings === 1 || s.status === "BOOKED") cell.classList.add("booked");
      else if (s.status === "HELD") cell.classList.add("held");
    }
  } catch (e) {
    /* non-fatal: the live SSE grid still reflects the run */
  }
}

resetBtn.addEventListener("click", async () => {
  await fetch("/demo/reset", { method: "POST" });
  resetStatsUI();
  document.getElementById("verdict").className = "verdict idle";
  document.getElementById("verdict").textContent = "run a stampede to test the invariant";
  document.getElementById("m2result").style.display = "none";
});

// ---- M2: holds (TTL) + idempotency ----
const holdBtn = document.getElementById("holdBtn");
const idemBtn = document.getElementById("idemBtn");
const m2result = document.getElementById("m2result");

holdBtn.addEventListener("click", async () => {
  holdBtn.disabled = true;
  const body = {
    count: parseInt(document.getElementById("holdCount").value, 10),
    ttlSeconds: parseInt(document.getElementById("ttl").value, 10),
  };
  m2result.style.display = "block";
  m2result.textContent = `Holding ${body.count} seats for ${body.ttlSeconds}s — watch them turn yellow, then auto-release to grey when each lease expires (the sweeper runs every second).`;
  try {
    const res = await fetch("/demo/hold", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const r = await res.json();
    m2result.textContent = `Held ${r.held} seats for ${r.ttlSeconds}s (lease / TTL). They'll flip back to available automatically on expiry — no client action needed.`;
  } catch (e) {
    m2result.textContent = "hold failed: " + e;
  } finally {
    holdBtn.disabled = false;
  }
});

idemBtn.addEventListener("click", async () => {
  idemBtn.disabled = true;
  m2result.style.display = "block";
  m2result.textContent = "Firing 5 identical confirms (same idempotency key) concurrently…";
  try {
    const res = await fetch("/demo/idempotency-test", { method: "POST" });
    const r = await res.json();
    const mark = r.correct ? "✅" : "❌";
    m2result.textContent =
      `${mark} Seat ${r.label}: ${r.attempts} identical confirms (same key) → ${r.bookings} booking(s). ` +
      `Outcomes: [${r.outcomes.join(", ")}]. ` +
      (r.correct ? "Idempotency held — exactly one booking." : "Idempotency FAILED.");
  } catch (e) {
    m2result.textContent = "idempotency test failed: " + e;
  } finally {
    idemBtn.disabled = false;
  }
});

loadSeats();
