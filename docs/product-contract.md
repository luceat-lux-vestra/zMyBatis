# zMyBatis product capability and safety contract

This document is the product-policy authority for Leap Epic #60 and Track #61.

It separates **current evidence** from the **Leap product decision**. Current implementation behavior is not a preservation constraint. A behavior is treated as maintained support only when its source, parameter, evaluation, execution, target, and IDE/database boundaries have enough evidence to fail closed when they cannot be proven.

Implementation audit baseline for this revision: `main` at `15af5d19898d7f7dc35732e2f684e42e668ddb57`. Evidence added by this revision is identified below and becomes authoritative only when the exact-HEAD test/merge gate succeeds; the baseline SHA does not imply that those new test files already existed on the parent commit.

## Status vocabulary

These terms describe evidence and product policy; they must not be read as a claim that the current implementation already satisfies every Leap target invariant.

- **SUPPORTED** — part of the intended maintained product contract with a concrete evidence path.
- **UNSUPPORTED** — deliberately outside the maintained product contract; the target behavior is to stop visibly before execution.
- **COMPATIBILITY-ALTERED** — intentionally differs from stock MyBatis/application-runtime semantics and must be labeled as such.
- **DEGRADED** — useful behavior exists, but evidence or fidelity is insufficient for an unconditional supported claim.
- **UNKNOWN** — the product cannot currently prove the required semantics.

**Leap target invariant:** UNKNOWN, unresolved ambiguity, missing provenance, and unsupported dependencies must fail closed rather than being converted into plausible executable SQL.

## Capability / fidelity matrix

| Area | Current / revision evidence | Current classification | Leap product decision | Downstream owner |
| --- | --- | --- | --- | --- |
| XML `<select>/<insert>/<update>/<delete>` under caret | `MyBatisContextAnalyzer` recognizes these tags and the action extracts the containing statement tag | SUPPORTED for self-contained statement extraction, subject to evaluator/input limits below | SUPPORTED only when the complete statement/dependency source can be proven | #62 |
| Java `@Select/@Insert/@Update/@Delete` | `PsiJavaFile` + `PsiMethod`; extractor unit/project fixtures cover literal/array/reference shapes and cross-file constants; `JavaActionContextProjectFixtureTest` proves saved Java caret/extraction for distinct overloads and separately characterizes an uncommitted editor Document against production action-time PSI extraction; `JavaActionContextDiskFixtureTest` separately proves the local-file persistence boundary without claiming project/index authority | DEGRADED: parser/index and saved-Java caret/extraction are evidenced, and VFS/save-state persistence semantics are characterized, but an unsaved editor Document can remain uncommitted while production analysis/extraction reads last-committed PSI; the remembered-parameter key also collapses overloads to `file::Class#method` | SUPPORTED only after the current editor source is made authoritative before PSI analysis/extraction and canonical method identity is maintained | #62, #66, #67 |
| Kotlin statement annotations | execution-context detection is `PsiJavaFile`-based and has no Kotlin PSI/source adapter | UNSUPPORTED | UNSUPPORTED until a dedicated adapter and evidence are added; do not group this claim with Java | #61, #62 |
| Provider annotations | provider annotations are detected only to display an unsupported notice | UNSUPPORTED | UNSUPPORTED unless a future product decision defines a bounded provider/runtime model | #61, #62 |
| XML `<sql>/<include>` and namespace fragment dependencies | extraction passes only the selected statement tag; no fragment graph/resolution implementation exists; this revision characterizes the default/unit-test unresolved-`<include>` path as plugin-error text, while `Ignore Unknown Tags` remains a compatibility-altered path that may strip unrecognized tags | UNSUPPORTED by the maintained product contract; current runtime enforcement is still degraded/compatibility-altered | unresolved include/fragment dependencies MUST fail closed with typed failure; support requires explicit source/dependency modeling | #62, #64 |
| `databaseId`, custom language drivers, runtime-only mapper extensions | no authoritative selection/runtime model is present | UNKNOWN | UNSUPPORTED for the maintained baseline until separately specified and evidenced | #61, #62, #64 |
| Unsaved document / PSI / VFS authority | `JavaActionContextProjectFixtureTest` proves an active Java editor Document can contain `SELECT draft` while last-committed PSI and production analysis/extraction still expose `SELECT saved` until explicit `PsiDocumentManager.commitDocument`; `JavaActionContextDiskFixtureTest` uses a local `file://` mapper backed by a real OS file and proves editor edit -> unsaved/uncommitted Document, PSI commit -> draft PSI while the physical file remains saved/unsaved, and `FileDocumentManager.saveDocument` -> draft text persisted to the physical file | DEGRADED for production action-time source authority; Document↔PSI and PSI↔physical-file save transitions are now separately evidenced | the current editor Document is authoritative for an active editor; analysis/extraction must establish a synchronized PSI snapshot from that Document or fail closed on ambiguity; PSI commit and physical persistence remain distinct transitions and must not be conflated | #62, #66 |
| Standard dynamic tags | implementation routes MyBatis standard handlers through `XMLScriptBuilder`; existing tests cover `if`/`where`/`foreach`, and this revision adds representative `choose`/`when`/`otherwise`, `set`, `trim`, and `bind` paths | DEGRADED for the full tag set: direct tag-level baseline evidence exists after this revision's exact-HEAD tests pass, but boundary/failure permutations, nested combinations, and application-runtime parity remain insufficient for an unconditional supported claim | define and maintain the exact supported tag set with positive and negative evidence; zMyBatis-owned parameter/OGNL/literal transformations remain separately classified | #61, #64, #67 |
| Custom map/OGNL behavior | evaluator installs a process-global `LinkedHashMap` OGNL `PropertyAccessor` and sanitizes expressions before MyBatis parsing | COMPATIBILITY-ALTERED | no uncontrolled global evaluator mutation may participate in correctness; isolate or remove it | #64 |
| `Strict OGNL Mode` off behavior | recognized/unrecognized evaluator failures can become SQL-looking error comments instead of typed failure data | DEGRADED / unsafe for an execution boundary | executable evaluation is always fail-closed; diagnostics are typed data, never SQL text | #64, #67 |
| `Ignore Unknown Tags` | unrecognized tags may be regex-stripped while preserving inner text when enabled | COMPATIBILITY-ALTERED | unknown constructs cannot be silently stripped and automatically executed on the authoritative path; any retained escape hatch is explicit and non-authoritative | #64 |
| `#{}` bound values | MyBatis produces parameter mappings and zMyBatis replaces JDBC placeholders with its own SQL literals | COMPATIBILITY-ALTERED vs JDBC/TypeHandler execution | #64 selects the authoritative representation and defines supported type/fidelity boundaries; unsupported types fail closed | #64 |
| `${}` raw interpolation | MyBatis raw substitution is preserved, while current input discovery finds `${}` through the same heuristic path as `#{}` | DEGRADED safety posture | raw interpolation is a distinct input class with explicit provenance and mandatory warning/confirmation before execution | #63, #64, #66 |
| Source-only parameter discovery | `ParameterExtractor` uses regex/keyword/path heuristics, finds bind/foreach locals, and explicitly filters names such as `paramN` | DEGRADED | heuristics may assist UX but are not caller-input authority; unknown/ambiguous requirements block execution | #63 |
| `@Param`, `argN`/`paramN`, collection aliases, runtime method metadata | current extractor has no mapper-method provenance and explicitly drops `paramN` | UNKNOWN / partially unsupported | classify names from evidence rather than naming convention; do not invent application runtime state | #63 |
| Remembered parameter input | current UI can pre-fill last values using its current statement key; `JavaActionContextProjectFixtureTest` proves two distinct overloaded Java mapper methods currently receive the same `file::Class#method` key | DEGRADED: canonical identity is insufficient and can alias remembered inputs across overloads; sensitive-data policy is also unresolved | persistence requires canonical statement identity, explicit retention/clearing rules, and no cross-statement/project bleed | #63, #67 |
| Literal SQL text as execution representation | current evaluator returns a String consumed by format/preview/execution | COMPATIBILITY-ALTERED; not JDBC/TypeHandler parity | not frozen by #61; #64 must select one authoritative representation and define fidelity limits | #64 |
| SELECT execution | same action pipeline as mutating statements; SQL preview is optional | DEGRADED safety posture | optional preview is acceptable only for fully supported/non-raw execution after all other contracts are proven | #61, #66 |
| INSERT/UPDATE/DELETE execution | same action pipeline as SELECT; no mandatory mutation confirmation exists | DEGRADED safety posture | mutating mapper statement kinds require explicit confirmation of the final authoritative representation before execution | #61, #66 |
| DDL / semantically unexpected SQL | mapper declaration kind is not proof of actual side effects and no authoritative SQL semantic classifier exists | UNKNOWN | static classification is advisory UX, never authorization; unknown execution meaning blocks instead of being guessed | #61, #64, #66 |
| Datasource/session identity contract | #57 established project-scoped persistence, stable datasource UUID identity, explicit-schema restart persistence, stale-state cleanup, and fail-closed restoration rules | SUPPORTED hardened identity/persistence baseline, with real Database Tools runtime cases still listed as platform evidence gaps | preserve the wrong-target-is-worse-than-refusal invariant; #65 may redesign only with equal or stronger evidence | #65, #67 |
| Default-schema restart | hardening intentionally does not persist/restart `Use Default Schema` sessions | UNSUPPORTED across restart; supported only as in-process reuse | preserve this restriction unless a future design can prove a stable default/search-path identity | #65 |
| IntelliJ IDEA Ultimate host | deterministic Plugin Verifier target is IDEA Ultimate `2025.3.3`; declared minimum build is 253 | SUPPORTED automated compatibility baseline for that configured verifier target, not every later build | maintained host claims require verifier plus targeted Database API/runtime evidence where needed | #67 |
| DataGrip host | product uses `com.intellij.database` / `JdbcConsole`, but there is no separate maintained DataGrip verifier/runtime evidence line | DEGRADED evidence | DataGrip remains a product target, but a maintained compatibility claim requires explicit reproducible evidence | #61, #67 |
| Other JetBrains IDEs with Database tooling | no maintained host matrix exists | UNKNOWN | no generic host claim without an explicit maintained matrix | #61, #67 |

## Current unsafe/degraded behaviors that Leap must not preserve by inertia

The following are observations about the current implementation, not accepted target behavior:

- evaluator failures can be returned as SQL-looking comment text when strict OGNL handling does not rethrow them;
- direct unsupported List/Map literalization currently returns an error marker plus `NULL` inside SQL text rather than a typed blocking result;
- unknown tags can be stripped when `Ignore Unknown Tags` is enabled;
- the evaluator mutates process-global OGNL accessor state;
- `${}` input does not have a separate mandatory warning/confirmation boundary;
- SELECT and mutating mapper statements share the same optional-preview execution flow;
- raw parameter values and rendered SQL are logged at INFO level;
- action presentation remains always enabled/visible and relies on `actionPerformed` context analysis to return early;
- Java action analysis/extraction can read last-committed PSI while the active editor Document contains newer uncommitted annotation SQL; separately, PSI synchronization does not mean the underlying file has been saved.

These observations are precisely why the target rules below are stronger than current behavior.

## Product safety posture — Leap target

### 1. Correctness over plausibility

If zMyBatis cannot prove the selected source, required input, MyBatis evaluation, execution representation, or target, the target architecture stops before database execution. It must not fall back to guessed nulls, invented runtime objects, truncated mapper source, silently stripped dependencies, stale source snapshots, or SQL-looking error text.

### 2. `#{}` and `${}` are different product concepts

- `#{}` is a bound-value concept whose eventual execution fidelity is owned by #64.
- `${}` is source-level raw interpolation. It is never described as a normal bound parameter.
- `${}` input requires explicit provenance and an execution warning/confirmation.
- Raw interpolation values are not silently promoted into a remembered-default mechanism. Any future persistence requires an explicit #63 sensitive-data policy.

### 3. Mutation confirmation is UX safety, not authorization

Mapper declaration kind (`select`, `insert`, `update`, `delete`) can drive user warnings, but it is not an authorization system and is not proof of the SQL's side effects.

For the Leap target:

- mutating mapper statement kinds require explicit confirmation of the final authoritative representation;
- raw interpolation also requires explicit confirmation;
- unsupported or unknown execution meaning is blocked, not merely confirmed;
- SELECT may keep optional preview only after source/parameter/evaluation/representation/target contracts are supported.

### 4. Evaluation failures are not executable artifacts

A parser, OGNL, mapping, unsupported-type, compatibility, formatting, target, or preparation failure is typed failure data. It must never be returned as comment-shaped or marker-bearing SQL that can continue into the execution path.

### 5. Sensitive values are not normal diagnostics

Normal logs must not contain raw parameter values, remembered inputs, rendered SQL, credentials, or equivalent sensitive source-derived values. Current INFO logging of parameter values/rendered SQL is a defect, not a diagnostic contract.

### 6. Target identity fails closed

The #57 baseline remains authoritative until #65 supersedes it with stronger evidence: stable datasource identity, explicit named-schema restart identity, stale/missing/ambiguous restoration rejection, and no SQL execution as a restoration side effect. Default-schema sessions remain in-process only under the current hardened baseline.

## Intended user workflow

This is the policy flow that downstream architecture must implement; it does not freeze current classes or Swing mechanics.

1. **Capture source/context** — identify one supported mapper statement from the authoritative current editor Document/source state and establish the corresponding synchronized PSI/model before analysis.
2. **Resolve source dependencies** — prove required fragments/metadata or stop as unsupported/unknown.
3. **Establish input contract** — distinguish caller inputs, MyBatis/internal/additional variables, `#{}` bindings, `${}` raw text, and unknown requirements.
4. **Collect user input** — parse supported input types without inventing runtime objects; apply retention/redaction policy.
5. **Evaluate** — use MyBatis as semantic authority only for behavior actually claimed as MyBatis-compatible; isolate deliberate compatibility transformations.
6. **Construct one authoritative execution representation** — preview/copy/execution must refer to the same approved meaning.
7. **Resolve execution target** — bind to one stable datasource/schema/search-path identity; ambiguity blocks.
8. **Apply confirmation policy** — mandatory for mutation/raw interpolation and any future explicitly defined high-risk mode.
9. **Execute only on explicit user action** — preparation, preview, formatting, history, restoration, startup, diagnostics, and cancellation never execute SQL as a side effect.
10. **Report outcome** — expected unsupported/user/runtime failures are typed and user-visible without sensitive-value leakage.

## Compatibility modes

Current settings are not automatically target product guarantees.

- **Strict OGNL Mode:** target execution is fail-closed regardless of the current setting. A future setting may control diagnostic detail or a deliberately scoped compatibility mode, but not whether failure-shaped text is executable.
- **Ignore Unknown Tags:** current regex stripping is COMPATIBILITY-ALTERED and cannot silently authorize automatic execution. If retained at all, it must be explicitly labeled and separated from the authoritative supported path.
- **Auto-format SQL:** presentation only. Formatting cannot change the approved execution meaning.
- **SQL Preview:** optional presentation for fully supported read paths; mandatory confirmation rules override the user's optional-preview preference.

## Evidence architecture for closing #61

This document establishes policy, but #61 remains open until the policy has falsifiable baseline evidence. At minimum, evidence must cover:

- self-contained XML statements;
- the exact maintained dynamic-tag set; this revision adds at least one representative path for every standard tag named by `MyBatisEvaluator`, but positive tag presence alone is not enough to promote the full set to unconditional support without boundary/failure evidence;
- Java annotation literal/array/constant shapes with maintained project-backed parser/index evidence, saved-Java action caret/extraction evidence, current Document↔PSI unsaved-state characterization, and a separate disk-backed PSI↔physical-file persistence characterization; the stale-PSI behavior is still a defect to replace, while canonical overloaded-method identity remains a separate obligation;
- explicit Kotlin/provider unsupported outcomes;
- `<sql>/<include>` and other dependency cases proving they cannot silently truncate into plausible SQL across relevant modes; this revision's default-path unresolved-`<include>` characterization is only a baseline, not the target typed-failure proof;
- malformed/unknown-tag/OGNL/unsupported-value failure paths proving failure cannot become executable SQL;
- `#{}` versus `${}` provenance and confirmation policy;
- `@Param`, generated aliases, collection aliases, nested/foreach/bind cases, and explicit unknown requirements;
- SELECT versus mutating action policy without treating static classification as authorization;
- authoritative preview/execution representation identity;
- #57 target-identity invariants, including default-schema non-persistence, without pretending automated tests emulate every Database Tools runtime case;
- IDEA Ultimate maintained compatibility plus explicit DataGrip runtime/verifier evidence if DataGrip is claimed as maintained;
- large/hostile fixtures sufficient to falsify downstream architecture assumptions.

Automated evidence belongs in the required `Test` context where it can be credible. Database/IDE behaviors that cannot be meaningfully emulated remain explicit platform/manual obligations rather than fake unit coverage.

## Downstream decision constraints

- #62 must not silently truncate source dependencies, read stale source state as authoritative, or invent canonical identity from current ad-hoc keys.
- #63 must not treat regex discovery as caller-input authority.
- #64 must not preserve global evaluator mutation, error-as-SQL/error-marker SQL, or unsupported literalization by inertia.
- #65 must preserve or strengthen #57 wrong-target fail-closed behavior and the explicit default-schema restart restriction unless stronger identity evidence replaces it.
- #66 must establish current-editor source synchronization before execution and implement confirmation/cancellation/lifecycle policy without making the current action class the architecture.
- #67 must close the evidence and diagnostics gaps, including sensitive INFO logging and host/runtime compatibility.

Any downstream proposal that needs to weaken this contract must update #61/#60 explicitly with evidence before implementation is merged.
