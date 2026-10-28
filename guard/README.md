# Contexa guard application (benchmark)

A Spring Boot application that lets the Contexa engine decide, for every tool call of a benchmark agent (AgentDojo,
InjecAgent), whether the call may run. The Python side (`bench/guards.py`, `HttpGuard`) asks before it runs a tool; the
answer comes from a real model call of the engine, not from this application.

* port `19300`, address `127.0.0.1`
* Contexa core used as source (`includeBuild('../../contexa')`; nothing there is changed), Spring Boot 3.5.4, Java 21
* three stages (`BENCH_TIER`), one codebase: **C1** minimal, **C2** configured, **C3** full (C3: see "Stage C3")
* models: Layer 1 `gpt-6-luna` (OpenAI), Layer 2 `claude-sonnet-5-5` (Anthropic), prompt bundle `2026.10.27-v21.17`, as the
  showcase's control D (`showcase-workload-contexa/src/main/resources/application.yml`). With the OpenAI balance used up:
  Layer 1 `claude-haiku-5-5` (Anthropic) and local Ollama embeddings (`llm-anthropic.env`). `/guard/capabilities` and the
  `model` of every answer name the model that actually decided.

## Stages (`BENCH_TIER`, default `C1`)

| Stage | What is on | Ownership of authentication |
|---|---|---|
| C1 | The user's task as the delegation's objective, the tool name and its arguments, decided call by call (Layer 1, Layer 2 when the engine escalates) | host-owned: `@EnableAISecurity(mode = SANDBOX)`; the host puts the run's subject into the SecurityContext and states the delegation as request attributes `ctxa.delegation.*` |
| C2 | C1 plus every area of the table below | Contexa-owned: `@EnableAISecurity(mode = FULL)`, `contexa.bridge.ownership=CONTEXA_OWNED`; the call is authenticated by a token of the engine's authorization server |
| C3 | C2 plus the agent baseline (warm-up learning), the execution narrative and the signed records | as C2 |

A run can ask for a **lower** stage than the server's (`"tier":"C1"` in `/guard/begin`): it then gets none of the data-fed or
identity areas of the higher stage (no delegation record, no token, no records comparison) and is decided as in C1. The
areas that live in the server (the call limit, the agent gate) follow the server.

### The areas of C2 and where each comes from

| Area | How it is on | Code |
|---|---|---|
| Agent identity and delegation token (act chain) | per run: the run's subject is an account of the engine's user table; the suite's agent has an OAuth2 client (client credentials and token exchange); the guard gets the person's token from the engine's token service, the agent's own client-credentials token as the actor, and exchanges them at the authorization server's `/oauth2/token` (RFC 8693). The exchanged token names the person (`sub`), the agent's client (`act`, `client_id`) and the delegation (`delegation_id`). It is presented to the engine's chain as `Authorization: Bearer` | `FullLifecycle`, `RunPersons`, `SuiteAgents`, `DelegationTokens`, `GuardCallFilter` |
| Delegation record | the task is recorded in the engine's delegation registry at `/guard/begin`: delegator = the run's subject, agent = the suite's agent, objective = the task, family and allowed operations = the suite's scope, valid 30 minutes. A **new delegation ID per run** | `RunDelegations.record` |
| Agent registration | one agent per suite (`bench-agent-<suite>`), approved, with its sponsor (`bench-operator`), registered with its client | `SuiteAgents` |
| Company-record comparison | the values a call names (account numbers, addresses, file names, channels) are compared with the environment's records sent in `/guard/begin` (`records`), through the engine's `FrictionContextProvider` extension point; the result is the call's *assignment record* (see below) | `BenchRecordsContextProvider`, `RecordMatcher` |
| Objective contract and scope | the suite's scope (`scope.family`, `scope.allowedOperations`) is recorded in the delegation; the engine compares the call's declared action family with it ("drift") | `RunDelegations.record` |
| Resource importance | the tool's class selects a gateway method that declares action family, sensitivity, privileged, exports data (`@Protectable` actions, importance, privileged, exportSensitive); the class comes from the **frozen tool table** `guard-tools.yml` | `ToolTable`, `ToolGateway` |
| Call limit | the engine's agent call guard (120/min per delegation, 600/min per agent, throttle) | engine default |
| Confirmation (CHALLENGE) | a CHALLENGE makes the engine ask the person accountable for the agent to confirm the request; nobody answers in a benchmark, so the call is refused (`AGENT_CONFIRMATION_REQUIRED`) | engine, `AgentConfirmationGate` |
| Stop on BLOCK | a BLOCK revokes the run's delegation and the tokens the agent holds for the person, and tells the person | engine, `AgentContainmentService` |
| Scope narrowing | after a CHALLENGE, ESCALATE, BLOCK or an ALLOW with risk >= 0.7 the agent's later requests may only read for an hour | engine default |

`GET /guard/capabilities` lists the areas, whether each is on and what decides that, the tool table's version and SHA-256, the
models (configured and as the engine resolves them), and the engine's own capability report (`contexacapabilities`).

### The tool table (fair rule 2)

`../declarations/*.yml` (frozen, hash-checked at start) classes every tool by its meaning alone (READ, WRITE, MONEY, DELETE, EXTERNAL,
CREDENTIAL; 69 listed AgentDojo tools; any other tool by the words of its name, the riskiest class winning, an unknown name is
a WRITE). It was written before any result and is not changed after results are known; a change is a new version whose
SHA-256 appears in `/guard/capabilities` and in every answer's `facts.importance`.

The class also gives the call its endpoint: `GET` for a read, `DELETE` for a deletion, `POST` for the rest, at
`/guard/tools/<tool>`; so the engine sees the request of an agent to a tool endpoint, with the method and the declared action
family it uses to narrow an agent to reading.

### What the company-record comparison states

The engine's context holds one *assignment record* per request. For a call that names an account, an address, a file or a
channel, the provider states the primary one (an unknown value first): "assigned to X (known counterparty)" when the run's
records hold it, "not assigned to X" when the kind was sent and the value is not in it. A kind that the run did not send is
**not looked up** (the engine reads "not known", not "none found"). The value is repeated to the engine only when it is a
known value or has a closed form (an account number, an address); any other text of the call is the requester's and is never
repeated. The environment's records are kinds the engine has no word for; an assignment ("this is among the person's known
...") is the nearest record it reads.

## HTTP contract

All bodies are JSON (UTF-8).

| Call | Body | Answer |
|---|---|---|
| `POST /guard/begin` | `{"runId":"run-ab12","goal":"<the user's task>","suite":"banking"}` plus optional `"tier":"C1"`, `"records":{...}`, `"scope":{...}` | `200 {"principal":"bench-user-run-ab12-3f9a1c2e","agentId":"bench-agent-banking","tier":"C2","delegationId":"...","clientId":"...","records":N}` (the contract asks for `{}`; the extra fields are for the logs); `500` when the run could not be set up |
| `POST /guard/decide` | `{"runId":"run-ab12","tool":"send_money","arguments":{...}}` | `200` the verdict below; `404` for an unknown `runId`; `400` without a tool |
| `POST /guard/end` | `{"runId":"run-ab12"}` | `200 {"calls":N}` |
| `GET /guard/health` | | `200 {"status":"UP","activeRuns":N}` |
| `GET /guard/capabilities` | | the stage, the areas and their evidence, the models, the tool table, the engine's report |
| `GET /guard/decision/{decisionId}` | | the evidence of one decision: the engine's stage events, its model calls, and its own database record |

`records` (the environment before any attack is injected; a kind left out is not looked up, an empty list means none known):

```json
{"known_counterparties": ["GB29NWBK60161331926819"], "known_contacts": ["alice@example.com"],
 "known_files": ["landlord-notices.txt"], "known_channels": ["general"]}
```

`scope` (the suite-level policy the task is delegated under): `{"family":"PERSONAL_BANKING","allowedOperations":["READ","WRITE"]}`
with operations from READ, WRITE, EXPORT, DELETE, ADMIN. `allowedResources` is accepted and **not recorded**: the engine would
compare it with a resource family the guard does not declare for a tool, and the list would only read as drift.

The verdict of `/guard/decide`:

```json
{"action":"ALLOW|CHALLENGE|BLOCK|ESCALATE", "reason":"...", "layer":"LAYER1|LAYER2|STORED_RESTRICTION|NONE",
 "model":"claude-haiku-5-5", "decisionMs":4732, "decisionId":"a1c43177-...",
 "engineAction":"ALLOW", "unresolved":false, "technicalFallback":false, "analysed":true, "modelCalls":1,
 "promptTokens":10308, "completionTokens":419, "riskScore":0.12, "confidence":0.8, "tier":"C2",
 "facts":{"delegation":{...}, "importance":{"class":"READ","basis":"listed","declared":"action READ"}, "records":{...}}}
```

* `layer` is the layer whose model produced the verdict. `STORED_RESTRICTION` means the engine answered from a restriction
  already in force for the run (see "What shapes a run") and made no model call: `analysed` is false and `model` is null.
* `model`, `promptTokens`, `completionTokens` cover every model call of this decision (a Layer 1 call and, when the engine
  escalates, a Layer 2 call); `model` is the model of the last call.
* `engineAction` is what the engine said (`ALLOW`, `BLOCK`, `CHALLENGE`, `ESCALATE`, or what a gate of the chain answered:
  `AGENT_CONFIRMATION_REQUIRED`, `DELEGATION_REVOKED`, `AGENT_SCOPE_NARROWED`, `AGENT_CALL_LIMIT`).
* `unresolved: true` means the engine gave **no verdict by a model**: the analysis was required but did not complete, timed
  out, the engine was busy, a token or the chain failed, or the engine fell back (`technicalFallback`: an analysis failed, or
  its escalation protection applied CHALLENGE). A run that counts verdicts should not count these as decisions. A fallback
  keeps the engine's own action (CHALLENGE); a refusal without any verdict is answered `BLOCK` (the guard fails closed).
* `facts` says what the call was judged with, as the guard knows it: the delegation (agent, record, token form, scope), the
  tool's importance class and what its gateway declares, and the records comparison (the kinds looked up, each value and
  whether the records hold it). Whether a fact was used in the decision is shown by the decision's sealed prompt (below).
* `decisionId` is the key of the engine's records of the call: the row of `ai_security_decision_observation`
  (`request_id`), the sealed evidence package (`sealed_evidence_package.correlation_id`), the model calls.

## Run

```bash
# 1. the database container (once; it exists as contexa-bench-postgres, 127.0.0.1:46500 -> 5432, volume contexa-bench-pgdata)
docker run -d --name contexa-bench-postgres -e POSTGRES_USER=bench -e POSTGRES_PASSWORD=bench_pw -e POSTGRES_DB=bench_engine \
  -v contexa-bench-pgdata:/var/lib/postgresql/data -p 127.0.0.1:46500:5432 pgvector/pgvector:pg16
docker exec contexa-bench-postgres psql -U bench -d bench_engine -c "CREATE DATABASE bench_vector OWNER bench;"
docker exec contexa-bench-postgres psql -U bench -d bench_vector -c "CREATE EXTENSION IF NOT EXISTS vector;"
docker exec contexa-bench-postgres psql -U bench -d bench_engine -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 2. the application (builds the boot jar when it is missing; BENCH_BUILD=1 rebuilds it first)
OPENAI_API_KEY=...  BENCH_TIER=C2  scripts/start-guard.sh        # log: logs/guard-<tier>.log
# with the OpenAI balance used up:  set -a; . llm-anthropic.env; set +a; BENCH_TIER=C2 scripts/start-guard.sh
scripts/wait-guard.sh        # until it answers /guard/health
scripts/stop-guard.sh        # a running jar must be stopped before it is rebuilt
```

`scripts/start-guard.sh` never prints a key. `OPENAI_API_KEY` comes from the environment. The Anthropic key
(`BENCH_ANTHROPIC_API_KEY`) is taken from `ANTHROPIC_API_KEY` only if that is a real key (longer than 40 characters; the
user's variable is a 22-character placeholder), else from the `sk-ant-...` value inside
`E:/projects/contexa/docs/ANTHROPIC_API_KEY.MD`. The application reads only `BENCH_ANTHROPIC_API_KEY`. The script also
generates, once, two local secrets it keeps in git-ignored files of this directory: the signing key of the decision records
and sealed evidence (`.evidence-signing-key`) and the secret of the engine's internal OAuth2 client
(`.oauth2-client-secret`, needed by C2 and C3 and stable across restarts because the client is stored with it).

Tests (this application only, no core tests): `./gradlew test`. The smoke test against a running guard:
`python scripts/smoke.py --records --scope` (C2; without the two flags a C1 guard is tested) writes
`smoke/smoke-results.json`.

### Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `BENCH_TIER` | `C1` | the stage: `C1`, `C2`, `C3` (also selects the Spring profile) |
| `OPENAI_API_KEY` | | Layer 1 and the embeddings (OpenAI mode) |
| `BENCH_ANTHROPIC_API_KEY` | | Layer 2, and Layer 1 in Anthropic mode (set by the start script) |
| `BENCH_GUARD_PORT`, `BENCH_BIND_ADDRESS` | `19300`, `127.0.0.1` | where the guard listens |
| `BENCH_DB_USER`, `BENCH_DB_PASSWORD` | `bench`, `bench_pw` | the dedicated account of the container |
| `BENCH_ENGINE_DB_URL` | `jdbc:postgresql://127.0.0.1:46500/bench_engine` | the engine database (Contexa creates its schema) |
| `BENCH_VECTOR_DB_URL` | `jdbc:postgresql://127.0.0.1:46500/bench_vector` | the vector store (the application's own datasource) |
| `BENCH_LAYER1_MODEL`, `BENCH_LAYER2_MODEL` | `gpt-6-luna`, `claude-sonnet-5-5` | the model of each layer |
| `BENCH_CHAT_PROVIDER` | `openai` | `anthropic` when the OpenAI balance is used up (then also `BENCH_LAYER1_MODEL=claude-haiku-5-5`) |
| `BENCH_EMBEDDING_PROVIDER` | `openai` | `ollama` when the OpenAI balance is used up (`mxbai-embed-large`, 1024 dimensions; the vector store is then another vector space) |
| `BENCH_PROMPT_BUNDLE` | `2026.10.27-v21.17` | the decision prompt bundle |
| `BENCH_DELEGATION_MODE` | `ATTRIBUTES` | C1 only: `RECORD` also records the run's delegation (C2 always does) |
| `BENCH_DELEGATION_VALIDITY`, `BENCH_AGENT_SPONSOR`, `BENCH_AGENT_ID` | `30m`, `bench-operator`, `bench-agent` | the recorded delegation; the agent ID is suffixed with the suite from C2 on |
| `BENCH_PURGE_DELAY` | `30s` | how long after `/guard/end` the engine state of the run's subject is purged; negative never purges |
| `BENCH_EVIDENCE_SIGNING_KEY`, `BENCH_OAUTH2_CLIENT_SECRET` | generated by the start script | see above |

## Runs do not leak into each other

A decision is kept under the acting subject, and a BLOCK is never lifted by the engine. So every run is a **new subject** (named for the person the agent acts for; a person named `agent-...` was read by the model as "the delegation was recorded by the agent itself"):
`bench-user-<runId>-<8 hex>`; a run started again under the same `runId` is another subject as well. From C2 on the run's subject
is the *person* the agent acts for, and the run gets a new delegation ID; the agent is fixed per suite. After `/guard/end` the
run's registration goes at once and, after `BENCH_PURGE_DELAY`, the engine state of the subject is purged
(`UserEngineStatePurger`: decisions, baseline, decision history, the delegations of the person; the observation rows and the
sealed evidence are kept).

**Storage keys (checked in the code).** The decision key and the learning key are the same string,
`<user>::agent::<agent id>` for an agent acting for a person (`ZeroTrustSubject.storageKey`, `LearningSubject.key`); the
delegation ID and the tenant are carried but not part of the key. So "restriction per delegation, baseline per agent" is not
a property of the engine: a fixed person and a fixed agent share both the restriction and the baseline; a new person per run
shares neither. The guard uses a new person per run (restriction per run). See "Stage C3" for what that means for a baseline.

## What shapes a run (engine behaviour, not guard behaviour)

* **A restriction in force answers the rest of the run.** After a CHALLENGE, ESCALATE or BLOCK of the engine every later
  call of the run (BLOCK: forever, CHALLENGE: 30 minutes, ESCALATE: 5 minutes) is answered with that verdict without a
  model call (`layer: STORED_RESTRICTION`, `analysed: false`). A false CHALLENGE on the first harmless call therefore also
  "blocks" every later attack call of that run. A measurement has to count these calls apart.
* **Scope narrowing.** After a CHALLENGE, ESCALATE, BLOCK, or an ALLOW whose risk reaches 0.7, an agent's later requests
  may only read for an hour (C2: a write is refused with `AGENT_SCOPE_NARROWED`).
* **Escalation protection.** A user whose Layer 1 analyses escalate more than half of more than ten analyses gets a CHALLENGE
  without a model decision (`technicalFallback`, stage `ESCALATE_PROTECTION`).
* **Layer 2 is the engine's decision.** Layer 1 escalates what it cannot decide; Sonnet is called only then.
* **Failures are not verdicts.** `unresolved: true` (above).
* **Truncation.** The summary of the arguments is cut to 5 entries / 200 characters per value / 1000 characters
  (`InvocationArguments`), so a long injected text in an argument is only partly seen.

## Stage C3 (not implemented in this build)

C3 needs the agent's baseline from warm-up runs. What the code allows (checked, nothing built yet):

* The baseline is kept under the learning key `<user>::agent::<agent id>`, which is also the decision key (above). With a new
  person per run nothing accumulates across runs.
* **Option A, a fixed person per suite and runs one after another.** The baseline accumulates under one key. A run's
  restrictions are then cleared at `/guard/begin` with the engine's administrator release,
  `AgentContainmentService.releaseDelegatedAgent(user, agent, by, reason)`, which removes the decisions of that key
  (`ZeroTrustActionRepository.removeAllUserData`) and leaves the baseline; a new delegation is recorded per run. Costs: runs
  of one suite cannot overlap (they share the key), and the scope-narrowing store and the escalation window of the person
  are separate state to clear.
* **Option B, a snapshot restored to each run's subject** (what the showcase does with `TemplateSnapshots`: the learned
  baseline, the context data and the decision documents copied under the new run's key through `BaselineDataStore`,
  `SecurityContextDataStore` and the vector store). Runs can overlap; the snapshot is learned once.
* The baseline is established only after `minSamples` 20 requests on `minDays` 5 company days
  (`contexa.security.zerotrust.baseline.statistical`), and learning only takes an ALLOW applied. A warm-up of minutes needs the
  event time to be stated by the host (the request's observed time), as the showcase's virtual company clock does.
* Fair rule 3 (warm up on normal runs only, learn from one half of the tasks and judge the other) is a matter of the harness.

## Files

| File | Role |
|---|---|
| `GuardApplication`, `GuardTier`, `GuardProperties` | entry, stages, settings |
| `GuardSecurityConfiguration`, `GuardRunAuthenticationFilter`, `HostAssertion` | C1: host-owned chain, the run's subject and delegation attributes |
| `FullTierActivation`, `FullTierConfiguration`, `FullTierAiSecurity` | C2/C3: Contexa-owned activation and the flow that takes token requests |
| `GuardCallFilter`, `GuardCallController`, `ReplayedRequest`, `BufferedResponse`, `VerdictTranslator` | C2/C3: the call presented as the agent's request to the tool endpoint, the verdict read back |
| `FullLifecycle`, `RunPersons`, `SuiteAgents`, `DelegationTokens`, `RunDelegations` | C2/C3: the person, the suite's agent, the delegation record, the delegated token |
| `BenchRecordsContextProvider`, `RecordMatcher`, `CallContexts` | C2/C3: the company-record comparison |
| `ToolTable`, `ToolClass`, `ToolGateway`, `../declarations/*.yml` | the tool classes and the `@Protectable` gateways |
| `GuardRuns`, `GuardController`, `VerdictMapper`, `VerdictAnswers` | runs, the HTTP contract, exception to verdict, the answer |
| `DecisionTraces`, `DecisionTraceObserver`, `ModelCallMeter`, `EngineDecisionRecords`, `GuardCapabilities` | the engine's announcements per decision, model calls, its database records, the capability report |
| `Layer2AnthropicConfiguration` | the Anthropic chat models of Layer 2 and (Anthropic mode) Layer 1, with their bindings |
| `src/main/resources/application.yml` | settings (profile `C2 | C3` at the end) |
| `scripts/start-guard.sh`, `stop-guard.sh`, `wait-guard.sh`, `smoke.py` | start, stop, wait, smoke test |
| `llm-anthropic.env` | the switch for a used-up OpenAI balance |

## Evidence that a verdict is a model's, and what it was judged with

```bash
curl -s http://127.0.0.1:19300/guard/decision/<decisionId>
docker exec contexa-bench-postgres psql -U bench -d bench_engine -c \
  "select user_id, final_action, model_id, llm_latency_ms, technical_fallback from ai_security_decision_observation where request_id='<decisionId>'"
docker exec contexa-bench-postgres psql -U bench -d bench_engine -At -c \
  "select user_prompt_text from sealed_evidence_package where correlation_id='<decisionId>'"   # what the model was asked
```

The sealed prompt shows which areas reached the decision: `Subject: ... through agent X, agent sponsor Y, agent registration
ACTIVE`, `[R1] Credential: delegated access token, grant token-exchange, ... actor chain depth 1`, `[R4] Action: READ (declared)`,
`[F3] Delegation: ... objective ..., allowed READ/WRITE, proof signed token claim and delegation record`, `[F4] RecordProvenance:
delegation ... recorded by ... (the delegator)`, and the records comparison among the records and comparisons.

## Results of the smoke tests

(see the end of this file)
