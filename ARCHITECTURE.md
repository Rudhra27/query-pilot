
# QueryPilot — What I Built & Why (interview prep notes)

> Read this cold, a month from now, with no other context. Goal: in 2 minutes you
> should be able to explain what it does, and in 10 minutes defend every design
> decision in it.

## 1. The elevator pitch

QueryPilot takes a SQL `SELECT` query, runs `EXPLAIN ANALYZE` on it, figures out
*why* it's slow, and — instead of just guessing a fix — **actually creates the
proposed index in a disposable clone of the database and benchmarks before vs.
after**, so every optimization it suggests comes with real proof, not a guess.
An LLM (Groq) also looks at the evidence and adds a second, independent
recommendation in plain English — but the LLM never gets to just be trusted;
its suggestion goes through the exact same "prove it in a sandbox" pipeline as
everything else.

**One-line version for a resume/interview:** "A query optimization tool that
validates its own suggestions empirically (via a disposable sandbox DB +
before/after benchmarking) instead of trusting static analysis or an LLM."

**Live at:** https://querypilot-92f7.onrender.com (Render free tier — spins
down after ~5 min idle, first request after that eats a ~50s cold start).

## 2. The problem it solves

Most "AI query optimizer" demos just ask an LLM "how do I speed this up?" and
print whatever it says. That's unreliable — LLMs hallucinate columns, indexes,
and performance claims. QueryPilot's whole design is built around **never
trusting a claim it hasn't verified against the real database**, whether that
claim comes from a hard-coded rule or from an LLM.

## 3. Request flow — what happens after what

```
POST /api/v1/query-analysis  { sql }
        │
        ▼
1. SqlSafetyValidator.validate(sql)
     - only SELECT / WITH allowed, single statement, blocklist of
       INSERT/UPDATE/DELETE/DROP/ALTER/... keywords
     - reject → 400 UnsafeSqlException
        │
        ▼
2. EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) <sql>
     - runs on the PRIMARY database, as the querypilot_readonly role
        │
        ▼
3. Parse the plan JSON
     - plan summary (cost, rows, root node type)
     - issues: e.g. a Seq Scan with a highly selective filter and no index
       → flagged "INEFFICIENT_SEQUENTIAL_SCAN"
     - joins: nested loop / hash / merge join stats
     - operations: expensive sorts / aggregates
        │
        ▼
4. findCandidates(issues)              <-- deterministic, rule-based
     - for each seq-scan issue, check if the filtered column is already
       indexed (DatabaseMetadataService reads pg_indexes)
     - if not: propose  CREATE INDEX idx_<table>_<col> ON <table>(<col>)
        │
        ▼
5. validateCandidates(candidates)      <-- THE CORE IDEA OF THE PROJECT
   For each candidate:
     a. benchmark the ORIGINAL sql on the primary DB (5 runs, take median)
     b. SandboxDatabaseService.createFreshSandbox()
          DROP + CREATE DATABASE querypilot_sandbox TEMPLATE querypilot
          (full physical clone of the real data, every single time)
     c. run the candidate's CREATE INDEX statement — ONLY in the sandbox
     d. benchmark the SAME original sql again, now against the sandbox
     e. compare before vs. after → % improvement, "improved" if ≥5% faster
        │
        ▼
6. Ask the LLM for a second opinion
     - build a JSON context: sql, plan, issues, joins, operations,
       existing indexes, and the step-5 validation results
     - GroqOptimizationAdvisor calls Groq with response_format=json_schema,
       strict:true — the model can ONLY return
       { summary, recommendations: [{type: CREATE_INDEX|NO_CHANGE, table,
         columns, reasoning}] }
     - the LLM is explicitly told never to write SQL itself
        │
        ▼
7. Validate the LLM's recommendation (never trust it blindly)
     - OptimizationRecommendationValidator checks:
         • type is CREATE_INDEX or NO_CHANGE (nothing else is supported)
         • every column it named actually exists on that table
           (queries information_schema.columns for real)
         • that index doesn't already exist
     - OptimizationSqlGenerator — NOT the LLM — builds the real SQL string,
       and validates every identifier against [a-zA-Z_][a-zA-Z0-9_]* first
        │
        ▼
8. Run the LLM's (validated) candidate through the SAME sandbox pipeline
   as step 5 — UNLESS it's literally the same table+columns as something
   already validated in step 5, in which case reuse that result instead
   of re-benchmarking (added later, see §6.2)
        │
        ▼
9. If ANYTHING in steps 6-8 fails (Groq down, bad JSON, rejected
   recommendation) — catch it, return aiRecommendation = null. The
   deterministic result from steps 1-5 is unaffected and still returned.
        │
        ▼
200 OK  { plan, issues, joins, operations, candidates, validations,
          aiRecommendation?, aiValidations }
```

## 4. The database trust model (the part I'm proudest of)

Three Postgres roles, each scoped to exactly what it's allowed to do:

| Role | Used for | Can do | Can't do |
|---|---|---|---|
| `querypilot` | Liquibase migrations, drop/recreate the sandbox DB | superuser | (never touches user-submitted SQL) |
| `querypilot_readonly` | running the user's arbitrary `EXPLAIN ANALYZE` SQL | `SELECT` only, `default_transaction_read_only=on` | can't write anything, even by mistake |
| `querypilot_sandbox` | `CREATE INDEX` / `DROP INDEX` during validation | owns the cloned tables in the disposable DB only | **can't even `CONNECT`** to the real `querypilot` database (`REVOKE CONNECT ... FROM PUBLIC`, not granted back) |

Why this matters: the app runs *arbitrary user-submitted SQL* — that's
inherently risky. Rather than trusting app-level SQL string validation alone,
the blast radius is contained at the database permission level:
- Even if `SqlSafetyValidator`'s blocklist had a bypass, `querypilot_readonly`
  physically cannot write.
- Even if a malicious/hallucinated `CREATE INDEX` tried to do something
  clever, `querypilot_sandbox` runs inside a database that gets wiped and
  recreated from the primary's template **before every single validation
  call**, and has no network path to the real data at all.
- The superuser role is never in the request path for user input — only for
  two fixed, hard-coded admin operations (clone, transfer ownership).

**If asked "why not just give the sandbox role superuser / grant it CREATE
INDEX directly":** Postgres < 17 has no standalone grantable "create index"
privilege — it's tied to table ownership until the `MAINTAIN` privilege
arrives in PG17. Making the role inherit the superuser role was tried and
rejected, because inherited superuser bypasses every privilege check
*including* the `CONNECT` revoke — so it could still reach the primary DB.
Solution: transfer ownership of the freshly cloned tables to the sandbox role
every time, right after cloning.

## 5. Why the LLM is treated as untrusted input

This is the single idea to lead with if asked "what's interesting about the
AI part":

- **Schema-constrained output** (`response_format: json_schema, strict:true`)
  — the model literally cannot return free text or an unexpected shape.
- **It can't write SQL.** It can only name a `table` + `columns`;
  `OptimizationSqlGenerator` builds the actual string and validates every
  identifier.
- **Its factual claims are checked against the live database** — does the
  table have this column? Does this index already exist? — before anything
  else happens with it.
- **Its performance claim is checked empirically.** Its `CREATE_INDEX`
  recommendation goes through the identical sandbox benchmark pipeline as a
  deterministically-found candidate. It gets zero special trust for being an
  LLM. "AI proposes, sandbox disposes."
- **A failure in the AI path can't take down the request.** Steps 1-5 (the
  deterministic result) are computed and returned regardless of whether Groq
  is up, times out, or returns something the validator rejects.

## 6. Two things I found and fixed after the fact (good interview material —
shows you can review your own work critically)

### 6.1 Prompt/schema promised more than the code could do
The Groq JSON schema originally had a third recommendation type,
`QUERY_REWRITE`, in its `enum`. But `OptimizationRecommendationValidator`
only ever accepted `CREATE_INDEX` or `NO_CHANGE` and threw
`IllegalArgumentException` for anything else — and there was no
`@ExceptionHandler` for that, so the model picking the very option its own
schema offered turned into an opaque 500. **Fix:** removed `QUERY_REWRITE`
from the schema enum, since nothing downstream could act on it anyway — the
model should never be offered a capability the system can't fulfill.

### 6.2 One bad AI response used to fail the whole request
`GroqOptimizationAdvisor.advise()` wrapped everything in a generic
try/catch → `RuntimeException`, uncaught anywhere else. So *any* AI hiccup —
rate limit, timeout, malformed JSON despite strict mode — took down the
entire `/query-analysis` response, even though the deterministic plan
analysis, issues, and sandbox-validated candidates had already been computed
successfully. **Fix:** the AI section (steps 6-8 above) is now wrapped in a
try/catch at the service level; on any failure it degrades to
`aiRecommendation: null` and the deterministic response is returned intact.

### 6.3 Minor efficiency: dedupe before re-benchmarking
The LLM is handed the deterministic candidates as evidence and often
proposes the identical index. Before the fix, that meant tearing down and
rebuilding the sandbox and re-running the full benchmark cycle a second time
for the same one-line index. **Fix:** before validating an AI candidate,
check if a deterministic candidate with the same type+table+columns was
already validated this request; if so, reuse that result instead of
re-running the sandbox.

**Considered but explicitly skipped (documented reasoning, not laziness):**
recomputing the "before" benchmark once per request instead of once per
candidate (it's the same original SQL against an unchanged primary DB every
time) — decided the added complexity wasn't worth it for how rarely a single
request has more than one or two candidates.

## 7. Deploying to Render: five bugs only deployment could find

Everything in §6 was found by re-reading the code. This section is different:
these five only showed up because the app was actually run somewhere other
than local `docker-compose`, against a database where the admin role is
**not** a real Postgres superuser. That single difference — Render's admin
role is a scoped, privileged-but-not-superuser owner rather than an actual
`rolsuper=true` account — is the common thread behind every one of them.
Good material if asked "what's different about running this for real vs. on
your laptop."

### 7.1 The template database name was hardcoded to local dev's name

`querypilot.sandbox.baseline-database-name` was a literal `querypilot` in
`application.yml` — the name of the primary database in local
`docker-compose`. Render names the primary database whatever you called it
when you created the instance (`querypilot_probe` in this deployment), so
`SandboxDatabaseService`'s `CREATE DATABASE ... TEMPLATE querypilot` failed
immediately with `database "querypilot" does not exist`.

**Fix:** parameterized it — `${SANDBOX_BASELINE_DATABASE_NAME:querypilot}` —
same pattern already used for `admin-url` and `jdbc-url`, just missed the
first time.

### 7.2 `pg_terminate_backend` needs a privilege a non-superuser doesn't have

`terminateConnections()` calls `pg_terminate_backend(pid)` to clear sessions
before dropping/cloning the sandbox. Postgres only allows that when the
caller is superuser, is the *same role* as the target session, or holds the
`pg_signal_backend` predefined role. Locally, the admin role (`querypilot`)
is a real superuser, so this always worked. On Render, the admin role could
terminate its own sessions but not `querypilot_readonly`'s — a genuinely
different role — and the call failed with `permission denied to terminate
process`, aborting the whole request.

Confirmed the ceiling directly rather than assuming it: tried to
self-grant `pg_signal_backend` to the admin role and Postgres refused —
`Only roles with the ADMIN option on role "pg_signal_backend" may grant
this role.` Not fixable by granting more privilege from inside the app's
own role; had to stop relying on cross-role termination at all.

**Fix:** made `terminateConnections()` best-effort — catch the permission
error, log it, and keep going — since the case that actually mattered (the
primary pool's own connection) gets handled deterministically below, not by
this call.

### 7.3 Evicting the pool doesn't mean the pool stays empty

`CREATE DATABASE ... TEMPLATE` requires *zero* other connections to the
template database at the moment it runs. Since `terminateConnections` could
no longer force this from outside, `OptimizationValidationService` was
changed to evict the primary pool's own idle connections
(`HikariPoolMXBean.softEvictConnections()`) right before the clone — the
pool releasing its own connection rather than being killed externally.

That fix alone wasn't enough — the exact same error kept reproducing. The
reason: Spring Boot's default `minimum-idle` equals the pool's maximum size
(10). The instant eviction closed an idle connection, Hikari's own
housekeeper reopened a fresh one to satisfy that minimum, and the clone lost
the race every time, retry loop included.

**Fix:** `spring.datasource.hikari.minimum-idle: 0`. This app's traffic is
one bursty analysis request at a time, not sustained load — a warm pool
buys nothing here and was actively fighting the sandbox clone. Also added a
bounded poll (`getTotalConnections() == 0`, up to 20×100ms) after eviction
instead of trusting the timing blindly, plus a short retry-with-backoff
around the `CREATE DATABASE` call itself for the same reason: eviction is
inherently a race against Hikari's own housekeeper thread, not something to
assume wins on the first try.

### 7.4 An external, out-of-band connection was still racing the clone

Even with the pool provably empty — logged proof: `active=0 idle=2 total=2`
→ `Primary pool empty after 1 attempt(s)` — the clone still failed with the
identical error. That ruled out anything in the request's own code path, so
the next step was looking at the database directly instead of guessing
again: queried `pg_stat_activity` for `querypilot_probe` straight from a
throwaway script and found a `querypilot_readonly` connection alive with no
request in flight to explain it.

The cause: Render's own uptime monitor polls `/actuator/health` on its own
schedule, independent of app traffic, and Spring Boot Actuator's default
database health indicator borrows a connection from that same primary pool
to answer it — an external trigger with its own timer that no amount of
in-request eviction or retrying can reliably out-race.

**Fix:** `management.health.db.enabled: false`. Render only needs a 200 from
the health check, not a database-verified status, so the indicator that was
causing the race isn't needed at all.

### 7.5 Reassigning table ownership needs membership in the target role

The very last step of a clone, `transferSandboxTableOwnership()`, runs
`ALTER TABLE ... OWNER TO querypilot_sandbox` using the admin connection.
Postgres only allows reassigning ownership *to* a role if the caller is a
member of it (or superuser). A real superuser gets this for free; Render's
admin role does not, and the very first successful clone got past every
prior bug only to fail here with `must be able to SET ROLE
"querypilot_sandbox"`.

**Fix:** not a code change — a one-time
`GRANT querypilot_sandbox TO <admin role>` against the live database,
now called out explicitly in `render.yaml`'s setup comment so it isn't a
surprise on the next fresh deploy.

### 7.6 Explicit type casts leaked into the generated SQL

Not a privilege issue — a plain parsing gap, found by testing (§10) rather
than by any of the deployment work above. Filtering a `VARCHAR` column
against a string literal makes Postgres render the plan's `Filter` with an
explicit cast — `((email)::text = 'x'::text)` — rather than the plain
`(email = 'x')` shape a `BIGINT` column like `orders.customer_id` produces.
`extractFilterColumn()`'s paren-stripping didn't account for that suffix,
so it leaked straight into the generated identifier and SQL:
`CREATE INDEX idx_customers_email::text ON customers (email::text)` —
invalid syntax, 500 on every request that filtered on `customers.email`.

**Fix:** strip a trailing `::\w+` cast off the extracted column name. This
one would have hit local dev too, just never exercised there — every
locally-tested query up to this point happened to filter on a numeric
column.

### The pattern across all five (plus one from testing)

None of 7.1–7.5 were reachable by reading the code more carefully — they
only exist at the boundary between "designed against a superuser" and
"running against a scoped admin role." That's worth having a crisp answer
for: the fix in every case was either (a) stop depending on a privilege the
role doesn't have and solve the same problem a different way (7.2, 7.3,
7.4), or (b) grant the one specific privilege actually needed instead of
assuming superuser-equivalent rights (7.5). Bug 7.1 is the odd one out
among those five — a plain environment-parity gap, not a privilege one.
7.6 is a different lesson again: it was always there, deployment just
wasn't what surfaced it — *testing* did, once queries other than the one
used throughout development got tried. Both are the same underlying
point from a different angle: "worked locally" only covers what was
actually exercised, whether that's a role's privileges or a query shape.

## 8. Tech stack, for quick recall

- **Java / Spring Boot** — `@Service`/`@Component` layering, `JdbcTemplate`
  for raw SQL (not JPA, because this app *is* raw SQL analysis).
- **PostgreSQL** — `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`, `pg_indexes`,
  `information_schema.columns`, role-based privilege separation.
- **Liquibase** — schema migrations, run with its own admin credentials
  separate from the runtime datasource.
- **Groq** (OpenAI-compatible `/chat/completions` API) — `openai/gpt-oss-20b`,
  structured output via `json_schema` + `strict: true`.
- **`mock-ai` Spring profile** — `MockOptimizationAdvisor` swaps in a
  hard-coded response so the pipeline is testable/demoable without hitting a
  real Groq API key.

## 9. Response structure — what each section tells you

### Request

```json
POST /api/v1/query-analysis
{ "sql": "SELECT * FROM orders WHERE customer_id = 50000" }
```

Just the raw SQL string. Everything else is derived server-side.

### Response — top level (`QueryAnalysisResponse`)

| Field | Type | Answers the question |
|---|---|---|
| `executionTimeMs` | double | How long did the query *actually* take (Postgres-reported, real run)? |
| `planningTimeMs` | double | How long did the planner spend choosing a plan? |
| `plan` | `PlanSummary` | What plan did Postgres pick, and how far off was its row estimate? |
| `issues` | `Issue[]` | What specific problems did we detect in the plan? |
| `joins` | `Join[]` | What joins ran, and how expensive/large was each? |
| `operations` | `PlanOperation[]` | What sorts/aggregates ran, and were any of them costly? |
| `candidates` | `OptimizationCandidate[]` | What fixes did the *rule-based* detector propose? |
| `validations` | `OptimizationValidationResult[]` | Sandbox-proven before/after numbers for each item in `candidates` |
| `aiRecommendation` | `AiOptimizationAdvice` or **absent** | What did the LLM independently conclude? Field is omitted from the JSON entirely (not `null`) when AI is disabled or the AI step failed/degraded — `@JsonInclude(NON_NULL)` on the DTO does this automatically |
| `aiValidations` | `OptimizationValidationResult[]` | Sandbox-proven before/after numbers for the LLM's own `CREATE_INDEX` recommendation (empty list if it recommended `NO_CHANGE`, or if AI degraded) |

### `plan` (`PlanSummary`)

| Field | Meaning |
|---|---|
| `totalCost` | Planner's *estimated* cost — an internal arbitrary unit (roughly "disk page fetches"), **not milliseconds**. Only meaningful compared to another cost. |
| `rootNodeType` | Top-level plan node, e.g. `"Gather"`, `"Seq Scan"`, `"Index Scan"` |
| `estimatedRows` / `actualRows` | Planner's guess vs. what actually came back. A big gap between these is the classic sign of stale table statistics. |

### `issues[]` (`Issue`)

| Field | Meaning |
|---|---|
| `type` | Currently only `"INEFFICIENT_SEQUENTIAL_SCAN"` — a `Seq Scan` where >10,000 rows were filtered out and selectivity was <5% (see `isInefficientSequentialScan`) |
| `severity` | `"HIGH"` for the case above |
| `relation` | The table this happened on |
| `rowsRemovedByFilter` | Rows read off disk, then discarded because they didn't match the filter — the "wasted work" number |
| `actualRows` | Rows that *did* match |
| `actualLoops` | How many times this plan node executed (>1 under parallel workers or inside a nested loop) |
| `totalActualRowsProcessed` | `actualRows × actualLoops` — real total work done, accounting for repetition |
| `filter` | The raw filter clause text from the plan, e.g. `"(customer_id = 50000)"` — this is what gets parsed to find the candidate index column |

### `joins[]` (`Join`)

| Field | Meaning |
|---|---|
| `type` | `"Nested Loop"`, `"Hash Join"`, or `"Merge Join"` |
| `estimatedRows` / `actualRows` / `actualLoops` / `totalCost` | Same meaning as in `PlanSummary`/`Issue`, scoped to this join node |
| `relations` | The table(s) feeding into this specific join node |

### `operations[]` (`PlanOperation`)

| Field | Meaning |
|---|---|
| `type` | Plan node type containing `"Sort"` or `"Aggregate"` |
| `severity` | `"HIGH"` if a Sort's cost > 10,000, or an Aggregate produced > 100,000 rows; `"MEDIUM"` otherwise |
| `relation`, `estimatedRows`, `actualRows`, `actualLoops`, `totalCost` | Same meaning as elsewhere, scoped to this node |

### `candidates[]` / the AI's proposal (`OptimizationCandidate`)

| Field | Meaning |
|---|---|
| `type` | `"CREATE_INDEX"` (the only type either path currently produces) |
| `table`, `columns` | What the index would cover |
| `proposedSql` | The exact SQL that was run in the sandbox to test this — e.g. `CREATE INDEX idx_orders_customer_id ON orders (customer_id)` |
| `reason` | Why this was proposed — rule-based text for `candidates`, the LLM's own `reasoning` for the AI path |

### `validations[]` / `aiValidations[]` — the proof (`OptimizationValidationResult`)

This is the payoff of the whole architecture — every other section is *analysis*, this section is *evidence*.

| Field | Meaning |
|---|---|
| `candidate` | Which `OptimizationCandidate` this result belongs to |
| `beforeBenchmark` | Median execution time (ms) of the original SQL, measured over 5 runs, **before** the index existed |
| `afterBenchmark` | Median execution time (ms) of the *same* SQL, measured the same way, **after** the index was created — in the sandbox |
| `beforeTotalCost` / `afterTotalCost` | Planner's estimated cost before/after — can disagree with the timing numbers, because cost is an estimate and execution time is real; worth having both to explain when they diverge |
| `executionTimeImprovementPercent` | `(before − after) / before × 100` — the real, measured speedup |
| `costImprovementPercent` | Same formula, but on the planner's cost estimate instead of real time |
| `improved` | `true` only if `executionTimeImprovementPercent ≥ 5.0` — the threshold that decides whether this candidate is worth recommending |

### `aiRecommendation` (`AiOptimizationAdvice`)

| Field | Meaning |
|---|---|
| `summary` | Plain-English explanation from the LLM of what it looked at and concluded |
| `recommendations[].type` | `"CREATE_INDEX"` or `"NO_CHANGE"` only (see §6.1 for why) |
| `recommendations[].table` / `.columns` | What the LLM wants to change — verified against real schema metadata before being trusted (§5) |
| `recommendations[].reasoning` | The LLM's own explanation for *why* — this is commentary, not proof; the proof is `aiValidations` |

### Worked example (seq-scan-on-orders case)

```json
{
  "executionTimeMs": 44.72,
  "planningTimeMs": 0.39,
  "plan": { "totalCost": 15059.33, "rootNodeType": "Gather", "estimatedRows": 10, "actualRows": 10 },
  "issues": [
    {
      "type": "INEFFICIENT_SEQUENTIAL_SCAN", "severity": "HIGH", "relation": "orders",
      "rowsRemovedByFilter": 333330, "actualRows": 10, "actualLoops": 3,
      "totalActualRowsProcessed": 30, "filter": "(customer_id = 50000)"
    }
  ],
  "joins": [],
  "operations": [],
  "candidates": [
    {
      "type": "CREATE_INDEX", "table": "orders", "columns": ["customer_id"],
      "proposedSql": "CREATE INDEX idx_orders_customer_id ON orders (customer_id)",
      "reason": "Highly selective filter is causing an inefficient sequential scan and no existing index covers the filter column."
    }
  ],
  "validations": [
    {
      "candidate": { "table": "orders", "columns": ["customer_id"], "...": "..." },
      "beforeBenchmark": 44.7, "afterBenchmark": 0.9,
      "beforeTotalCost": 15059.33, "afterTotalCost": 8.3,
      "executionTimeImprovementPercent": 97.9, "costImprovementPercent": 99.9,
      "improved": true
    }
  ],
  "aiRecommendation": {
    "summary": "The query filters orders by customer_id but must scan the entire table because no index covers that column.",
    "recommendations": [
      { "type": "CREATE_INDEX", "table": "orders", "columns": ["customer_id"],
        "reasoning": "customer_id is used in an equality filter and is not indexed, forcing a full sequential scan." }
    ]
  },
  "aiValidations": [
    { "...": "same shape as validations[0] above — reused, not re-benchmarked, because it's the same table+columns" }
  ]
}
```

Reading this end to end: `issues` says *what's wrong*, `candidates`/`aiRecommendation` say *what to do about it*, and `validations`/`aiValidations` say *does it actually work* — with real numbers, not an opinion.

## 10. Try it yourself — example queries

Five queries against the seeded demo data (`customers`: 100,000 rows, 5
countries; `orders`: 1,000,000 rows, `customer_id` 1–100,000, 4 statuses),
each exercising a different part of the response documented in §9. Run
against the live deployment (§1) or locally — the structural result
(which `issues`/`joins`/`operations` populate, whether an index gets
recommended) is stable; exact benchmark numbers vary run to run.

| # | Query | What it exercises | What to expect |
|---|---|---|---|
| 1 | `SELECT * FROM orders WHERE customer_id = 50000;` | The core pipeline, seq scan on a highly selective column | `issues` flags `INEFFICIENT_SEQUENTIAL_SCAN`, an index on `customer_id` is proposed and validated (~99% faster in testing), AI recommendation agrees |
| 2 | `SELECT * FROM orders WHERE status = 'COMPLETED';` | The detector's precision, not just its sensitivity | `status` has only 4 values (~25% selectivity) — still a `Seq Scan`, but selectivity is above the 5% threshold, so `issues`/`candidates` stay empty and the AI correctly says `NO_CHANGE` instead of proposing an index that wouldn't help |
| 3 | `SELECT c.name, o.amount FROM customers c JOIN orders o ON o.customer_id = c.id WHERE c.country = 'India';` | `joins[]` | Populates a `Hash Join` entry (`relations: ["orders","customers"]`) — no candidate, since join-column indexing isn't something the rule-based detector looks for (see §11 Known limitations) |
| 4 | `SELECT customer_id, SUM(amount) AS total FROM orders GROUP BY customer_id ORDER BY total DESC;` | `operations[]` | Populates both a `Sort` (`HIGH`, cost > 10,000) and an `Aggregate` (`MEDIUM` at exactly 100,000 actual rows — one row over the threshold flips it to `HIGH`) |
| 5 | `SELECT * FROM customers WHERE email = 'customer12345@example.com';` | A second index case, on a `VARCHAR` column | Same shape as #1 but on `customers.email` (~98% faster in testing). This is the query that caught the real bug in §7.6 — worth running specifically to confirm that fix still holds after any future change to `extractFilterColumn` |

## 11. Known limitations (be upfront about these if asked)

- Deterministic candidate detection only recognizes single-column equality
  filters on a `Seq Scan` — no composite indexes, no join-column indexes,
  no partial/expression indexes from the rule-based side (the LLM is the
  only path that could surface those, and even then only if it reasons its
  way there from the evidence).
- Benchmarking runs `EXPLAIN ANALYZE` 5 times and takes the median — good
  enough to smooth out noise for a demo, not a substitute for a proper load
  test.
- No caching/pooling between sandbox clones — every validation fully drops
  and recreates the sandbox database from a template, which is correctness-
  first but not fast.
- Read-only enforcement is defense-in-depth (blocklist keyword check +
  read-only role + read-only transaction setting) rather than a single
  mechanism — worth knowing all three layers if asked "how do you know a user
  can't sneak in a write."
