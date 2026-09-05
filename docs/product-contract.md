# zMyBatis product capability and safety contract

This document is the product-policy authority for Leap Epic #60 and Track #61.

It separates **current evidence** from the **Leap product decision**. Current implementation behavior is not a preservation constraint. A behavior is supported only when its source, parameter, evaluation, execution, target, and IDE/database boundaries are explicit enough to fail closed when they cannot be proven.

Audit baseline for this contract: `main` at `babb971b885b1538c70883994701ef3c900eea44`.

## Status vocabulary

- **SUPPORTED** — part of the intended product contract, with a concrete evidence path.
- **UNSUPPORTED** — deliberately outside the maintained product contract; invocation must stop visibly before execution.
- **COMPATIBILITY-ALTERED** — intentionally differs from stock MyBatis/application-runtime semantics and must be labeled as such.
- **DEGRADED** — some useful behavior exists, but evidence or fidelity is insufficient for an unconditional supported claim.
- **UNKNOWN** — the product cannot currently prove the required semantics. UNKNOWN is fail-closed, not a best-effort execution mode.

`UNKNOWN`, unresolved ambiguity, missing provenance, and unsupported dependencies are never converted into plausible executable SQL.

## Capability / fidelity matrix

| Area | Current evidence at audit base | Current classification | Leap product decision | Downstream owner |
| --- | --- | --- | --- | --- |
| XML `<select>/<insert>/<update>/<delete>` under caret | `MyBatisContextAnalyzer` recognizes these tags and action extraction returns the containing statement tag text | SUPPORTED for self-contained statements | SUPPORTED when the complete statement/dependency source can be proven | #62 |
| Java `@Select/@Insert/@Update/@Delete` | `PsiJavaFile` + `PsiMethod`; extractor handles literal, array, and PSI-field-reference shapes | DEGRADED: extractor contract is tested, real parser/index-backed resolution is platform-dependent | SUPPORTED only with maintained Java parser/index evidence and canonical method identity | #62, #67 |
| Kotlin statement annotations | no Kotlin PSI/source adapter is present in the execution context path | UNSUPPORTED | UNSUPPORTED until a dedicated adapter and evidence are added; do not group this claim with Java | #61, #62 |
| Provider annotations | provider annotations are detected only to display an unsupported notice | UNSUPPORTED | UNSUPPORTED unless a future product decision defines a bounded runtime/provider model | #61, #62 |
| XML `<sql>/<include>` and namespace fragment dependencies | extraction passes only the selected statement tag; no fragment graph/resolution evidence exists | UNSUPPORTED | unresolved include/fragment dependencies MUST fail closed; support requires explicit source/dependency modeling | #62 |
| `databaseId`, custom language drivers, runtime-only mapper extensions | no authoritative selection/runtime model is present | UNKNOWN | UNSUPPORTED for the maintained baseline until separately specified and evidenced | #61, #62, #64 |
| Unsaved document / PSI / VFS authority | action reads PSI/editor state but there is no explicit synchronization/authority contract | UNKNOWN | one explicit current-document authority and validity model is required before support is claimed | #62, #66 |
| Standard dynamic tags (`if`, `choose`, `foreach`, `where`, `set`, `trim`, `bind`) | evaluation delegates script parsing to MyBatis `XMLScriptBuilder`; contract tests cover representative cases | SUPPORTED within the current self-contained evaluator boundary | SUPPORTED only where source/parameter prerequisites are proven and zMyBatis transformations are separately identified | #64 |
| Custom map/OGNL behavior | evaluator installs a process-global `LinkedHashMap` OGNL `PropertyAccessor` and sanitizes expressions | COMPATIBILITY-ALTERED | no uncontrolled global evaluator mutation may participate in correctness; behavior must be isolated or removed | #64 |
| `Strict OGNL Mode` off behavior | evaluation failures can become SQL-looking error comments | DEGRADED / unsafe for an execution boundary | executable evaluation is always fail-closed; diagnostics must be typed data, never SQL text | #64, #67 |
| `Ignore Unknown Tags` | unrecognized tags may be regex-stripped while preserving inner text | COMPATIBILITY-ALTERED | unknown constructs cannot be silently stripped and automatically executed; any retained escape hatch is explicit and non-authoritative | #64 |
| `#{}` bound values | MyBatis mappings are resolved and then zMyBatis literalizes values | COMPATIBILITY-ALTERED vs JDBC/TypeHandler execution | binding semantics and the authoritative execution representation are decided by #64; unsupported types fail closed | #64 |
| `${}` raw interpolation | MyBatis raw substitution is preserved, but current UI discovers it through the same input path as `#{}` | DEGRADED | raw interpolation is a distinct input class with explicit provenance and mandatory warning/confirmation before execution | #63, #64, #66 |
| Source-only parameter discovery | regex/keyword/path heuristics find roots, bind/foreach locals, and structured-input roots | DEGRADED | heuristics may assist UX but are not caller-input authority; unknown/ambiguous requirements block execution | #63 |
| `@Param`, `argN`/`paramN`, collection aliases, runtime method metadata | current extractor has no mapper-method provenance and explicitly drops `paramN` | UNKNOWN / partially UNSUPPORTED | classify names by evidence, not naming convention; do not invent application runtime state | #63 |
| Remembered parameter input | current UI can persist last values per statement key | DEGRADED pending canonical identity/sensitive-data policy | persistence requires canonical statement identity, explicit retention/clearing rules, and no cross-statement/project bleed | #63, #67 |
| Literal SQL text as execution representation | current evaluator returns a string that preview/format/execution consume | COMPATIBILITY-ALTERED; not JDBC/TypeHandler parity | not frozen by #61; #64 must select one authoritative representation and define fidelity limits | #64 |
| SELECT execution | same action path as mutating statements; preview is optional | DEGRADED safety posture | preview may remain optional only for fully supported/non-raw execution; unsupported/unknown semantics block | #61, #66 |
| INSERT/UPDATE/DELETE execution | same action path as SELECT; no mandatory mutation confirmation exists | DEGRADED safety posture | mutating mapper statement kinds require explicit final-representation confirmation before execution | #61, #66 |
| DDL / semantically unexpected SQL | source declaration alone cannot be treated as authorization or a complete SQL classifier | UNKNOWN | static classification is advisory UX, never authorization; unknown execution meaning blocks rather than guesses | #61, #64, #66 |
| Datasource/schema/session target | project-scoped stable datasource identity and fail-closed restoration were hardened under #57 | SUPPORTED baseline with documented platform gaps | preserve the wrong-target-is-worse-than-refusal invariant; #65 may redesign without weakening it | #65, #67 |
| IntelliJ IDEA Ultimate host | deterministic Plugin Verifier target is IDEA Ultimate `2025.3.3`; build baseline is 253+ | SUPPORTED automated compatibility baseline | maintained host claim requires verifier plus targeted runtime evidence for Database APIs where needed | #67 |
| DataGrip host | product uses `com.intellij.database` / `JdbcConsole`, but there is no separate maintained DataGrip verifier target today | DEGRADED evidence | DataGrip remains a product target, but a maintained compatibility claim requires explicit reproducible evidence | #61, #67 |
| Other JetBrains IDEs with Database tooling | README historically used a generic compatibility phrase | UNKNOWN | no generic host claim without an explicit maintained matrix | #61, #67 |

## Product safety posture

### 1. Correctness over plausibility

If zMyBatis cannot prove the selected source, required input, MyBatis evaluation, execution representation, or target, it stops before database execution. There is no fallback from UNKNOWN to guessed nulls, guessed runtime objects, truncated mapper source, stripped dependencies, or SQL-looking error text.

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
- SELECT may keep optional preview only after all source/parameter/evaluation/representation/target contracts are supported.

### 4. Evaluation failures are not executable artifacts

A parser, OGNL, mapping, unsupported-type, compatibility, formatting, target, or preparation failure is typed failure data. It must never be returned as comment-shaped SQL that can continue into the execution path.

### 5. Sensitive values are not normal diagnostics

Normal logs must not contain raw parameter values, remembered inputs, rendered SQL, credentials, or equivalent sensitive source-derived values. Current INFO logging of parameter values/rendered SQL is a known defect, not accepted product behavior, and must be removed independently rather than preserved as a diagnostic contract.

### 6. Target identity fails closed

The #57 baseline remains authoritative until #65 supersedes it with stronger evidence: stable datasource identity, explicit schema/search-path identity, stale/missing/ambiguous restoration rejection, and no SQL execution as a restoration side effect.

## Intended user workflow

This is the policy flow that downstream architecture must implement; it does not freeze current classes or Swing mechanics.

1. **Capture source/context** — identify one supported mapper statement from the current editor state.
2. **Resolve source dependencies** — prove required fragments/metadata or stop as unsupported/unknown.
3. **Establish input contract** — distinguish caller inputs, MyBatis/internal/additional variables, `#{}` bindings, `${}` raw text, and unknown requirements.
4. **Collect user input** — parse supported input types without inventing runtime objects; apply retention/redaction policy.
5. **Evaluate** — use MyBatis as semantic authority for behavior claimed as MyBatis-compatible; isolate any deliberate compatibility transformation.
6. **Construct one authoritative execution representation** — preview/copy/execution must refer to the same approved meaning.
7. **Resolve execution target** — bind to one stable datasource/schema/search-path identity; ambiguity blocks.
8. **Apply confirmation policy** — mandatory for mutation/raw interpolation and any future explicitly defined high-risk mode.
9. **Execute only on explicit user action** — preparation, preview, formatting, history, restoration, startup, diagnostics, and cancellation never execute SQL as a side effect.
10. **Report outcome** — expected unsupported/user/runtime failures are typed and user-visible without sensitive-value leakage.

## Compatibility modes

Current settings are not automatically target product guarantees.

- **Strict OGNL Mode:** execution itself becomes fail-closed regardless of the current setting. A future setting may control diagnostic detail or compatibility behavior, but not whether an evaluation error becomes executable text.
- **Ignore Unknown Tags:** current regex stripping is COMPATIBILITY-ALTERED and cannot silently authorize automatic execution. If retained at all, it must be explicitly labeled and separated from the authoritative supported path.
- **Auto-format SQL:** presentation only. Formatting cannot change the approved execution meaning.
- **SQL Preview:** optional presentation for fully supported read paths; mandatory confirmation rules override the user's optional-preview preference.

## Evidence architecture for closing #61

This document establishes policy, but #61 remains open until the policy has falsifiable baseline evidence. At minimum, evidence must cover:

- self-contained XML statements and representative dynamic tags;
- Java annotation literal/array/constant shapes plus real parser/index integration evidence;
- explicit Kotlin/provider unsupported outcomes;
- `<sql>/<include>` and other dependency cases proving they cannot silently truncate into plausible SQL;
- malformed/unknown-tag/OGNL failure paths proving failure cannot become executable SQL;
- `#{}` versus `${}` provenance and confirmation policy;
- `@Param`, generated aliases, collection aliases, nested/foreach/bind cases, and explicit unknown requirements;
- SELECT versus mutating action policy without treating static classification as authorization;
- authoritative preview/execution representation identity;
- IDEA Ultimate maintained compatibility plus explicit DataGrip runtime/verifier evidence if DataGrip is claimed as maintained;
- large/hostile fixtures sufficient to falsify downstream architecture assumptions.

Automated evidence belongs in the required `Test` context where it can be credible. Database/IDE behaviors that cannot be meaningfully emulated remain explicit platform/manual obligations rather than fake unit coverage.

## Downstream decision constraints

- #62 must not silently truncate source dependencies or invent canonical identity from current ad-hoc keys.
- #63 must not treat regex discovery as caller-input authority.
- #64 must not preserve global evaluator mutation, error-as-SQL, or unsupported literalization by inertia.
- #65 must preserve or strengthen #57 wrong-target fail-closed behavior.
- #66 must implement confirmation/cancellation/lifecycle policy without making the action class the architecture.
- #67 must close the evidence and diagnostics gaps, including sensitive INFO logging and host/runtime compatibility.

Any downstream proposal that needs to weaken this contract must update #61/#60 explicitly with evidence before implementation is merged.
