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
};

function setNarration() {
  document.getElementById("narration").textContent =
    NARRATION[document.getElementById("strategy").value];
}
document.getElementById("strategy").addEventListener("change", setNarration);
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
  } else if (ev.type === "booked") {
    const cell = cellById.get(String(ev.seatId));
    if (cell) {
      if (ev.oversell) {
        cell.classList.remove("booked");
        cell.classList.add("oversold");
        liveOversold++;
        document.getElementById("s-oversold").textContent = liveOversold;
      } else if (!cell.classList.contains("oversold")) {
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
  if (r.invariantHeld) {
    v.className = "verdict pass";
    v.textContent = `✅ INVARIANT HELD — ${r.distinctSeatsBooked}/${r.totalSeats} booked, 0 double-booked`;
  } else {
    v.className = "verdict fail";
    v.textContent = `❌ INVARIANT VIOLATED — ${r.bookingRows} bookings for ${r.totalSeats} seats, ${r.oversoldSeats} oversold`;
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
  };
  try {
    await fetch("/demo/stampede", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    // final metrics also arrive via the SSE "summary" event
  } catch (err) {
    document.getElementById("verdict").textContent = "request failed: " + err;
  } finally {
    runBtn.disabled = false;
    resetBtn.disabled = false;
  }
});

resetBtn.addEventListener("click", async () => {
  await fetch("/demo/reset", { method: "POST" });
  resetStatsUI();
  document.getElementById("verdict").className = "verdict idle";
  document.getElementById("verdict").textContent = "run a stampede to test the invariant";
});

loadSeats();
