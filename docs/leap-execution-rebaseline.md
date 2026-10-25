# Leap execution rebaseline — 2026-10-13

> **Historical status note (2026-10-25):** this document records the decision and migration sequence as they stood on 2026-10-13. The single production cutover has since landed, and #454/PR #455 deleted the former action/evaluator/extractor/dialog/history island plus the never-distributed v2 persistence migration path. For current architecture and product claims, use `AGENTS.md`, `docs/product-contract.md`, `docs/leap-architecture.md`, and `docs/test-contracts.md`. The original observations and stop-gate language below are preserved as historical evidence.

Status: **target product/architecture decision for #391**, superseding the strict bound-execution-only *target requirement* of closed #258 after this document is merged. The #251 Database Tools 2026.2 API investigation and #258's original historical decision remain preserved. This decision **does not make any unproven SQL materializer executable**. Runtime code and the shipping action remain unchanged until a separate validated code cutover.

Ownership: Epic #60; product policy #61; source #62; inputs #63; MyBatis/evaluation #64; Database Tools target/execution #65; orchestration #66; quality #67.

## 1. Product objective and the mistake being corrected

zMyBatis was a shipping JetBrains mapper-SQL runner before Leap. Leap exists to **replace its architecture**, not to turn JDBC PreparedStatement use into an otherwise unrelated prerequisite for feature parity. The previous all-or-nothing requirement combined (a) ordered JDBC binding, (b) maintained Database Tools APIs and (c) native console result/history. #251 found no one public/maintained 2026.2 bridge for all three, so strict #258 blocked a full migration. Independent OGNL syntax/admission expansion after that decision did not shrink the deployment blocker.

**Selected target:** retain the user-facing IDE mapper → SQL → native Database Tools console workflow, while making source, input, stock-MyBatis evaluation, SQL materialization, execution target, approval, and lifecycle distinct responsibilities. The target is **compatibility SQL-text execution with an explicit supported-fidelity envelope**, not a claim of JDBC binding/TypeHandler/application-runtime equivalence. True bound execution may remain future optional research, not a gate on the architectural replacement.

## 2. Single execution authority

~~~text
explicit user action
  -> immutable current-editor source + canonical statement/dependencies
  -> independently validated caller input (#{ } and ${ } distinct)
  -> stock MyBatis parser, dynamic SQL and OGNL (no second expression interpreter)
  -> BoundSql(sql with ?, ordered ParameterMapping, additional values)
  -> validated immutable PreparedExecution
  -> one proven dialect/type SQL-text materializer
  -> ExecutableSqlArtifact(exact SQL, source/target identity, safety flags, fingerprint)
  -> explicit confirmation + immediate source/target/lifecycle revalidation
  -> existing maintained Database Tools console execution path
  -> existing native results/history
~~~

- **Preparation:** MyBatis owns meaning. zMyBatis proves caller/root provenance, picks one mapper source and supplies verified data. It must not emulate OGNL Boolean/operator/foreach semantics with growing exact-text admission tables. AST/source checks may prove *provenance, safe properties or declared unsupported runtime constructs*, not define a second grammar.
- **Materialization:** the *only* place bound values can become executable text. Do not treat `BoundSql.sql` with `?` as executable if bindings remain. No formatted/copy SQL becomes execution authority. The artifact is frozen before preview; the exact text (or byte-identical execution payload) is what the adapter supplies. Source and explicit target identity travel with it.
- **Delivery:** Database Tools owns datasource/session, native console request, result handling, history and disposal; do not recreate a result grid. Root plugin → core contracts + MyBatis adapter, root plugin → Database Tools adapter. The current root lacks a mybatis-engine dependency, so the proposed path is NOT currently packaged or wired.
- **One authority:** until the new whole-path acceptance passes, keep the existing MyBatisEvaluator/ParameterExtractor/action running with all shipped safety guards. Do not select old/new runtime implicitly by zero/nonzero binds, mapper flavor or feature flags. A test-only experimental harness may validate the new flow without exposing a second production action.

## 3. SQL-text fidelity envelope (mandatory gate, not a best-effort fallback)

Any rendered statement with at least one bound value must pass **every** applicable check or return a typed non-executable failure.

| Dimension | Acceptance / refusal rule |
| --- | --- |
| Source | Current Document is authoritative; complete mapper/includes/Java annotation dependencies and exact statement identity where claimed; reject stale, ambiguous, unsupported graphs |
| Input | Explicit user values and known MyBatis additional parameters; never invent `paramN`/foreach/bind callers; missing/unknown requirements block |
| MyBatis | Run maintained stock MyBatis/OGNL on unchanged supported mapper content; validate BoundSql SQL, mappings, order and resolved values |
| Placeholder location | Proven lexical correspondence between every **bind** marker and ordered mapping. SQL string/comment/dialect question-mark operators and `${ }` output must not shift positions. Ambiguous token topology blocks; never `replaceFirst` or count raw `?` as sufficient |
| Scalar codecs | Explicit DBMS family + scalar type + escaping/precision/NULL/boolean/date/time proof. No object.toString(), generic `NULL`, marker-bearing SQL, unproved binary or custom TypeHandler conversion |
| Dialect | Execution-target selection stays DBMS-agnostic. A target with no proven renderer is selectable **but the candidate statement is not executable** until supported. No assertion that PostgreSQL, Oracle, MySQL, SQL Server and others all share literal syntax |
| Raw `${ }` | Separate provenance, injection risk policy and explicit confirmation. Never reinterpret as `#{ }`. Unsupported/missing raw authority blocks |
| SQL artifact | Exact authoritative text is immutable, fingerprinted and bound to source revisions, chosen datasource/schema and statement kind; formatter is display-only |
| Safety | DML confirmation, raw confirmation, stale source/target detection, project cancellation/disposal, no query/input in logs. No preparation/preview side-effect execution |
| Native integration | The actual configured Database Tools console processes the same artifact and renders its normal results/history. Fake/custom result UI is not completion evidence |

Evidence must cover at least one **named** DBMS/type slice with real execution, expected values and negative controls. H2-only fixture cannot substantiate PostgreSQL/Oracle/MySQL claims. Additional families/types are opted in only with equivalent repeatable evidence, not via an unbounded vendor claim. If the current plugin supports a case the target cannot reproduce, record a deliberate product regression decision **before** cutover; do not silently drop shipped behavior.

## 4. Architecture simplification rules

- Preserve model concepts: SourceSnapshot/SourceRevision, canonical statement identities, explicit source graph, typed input codecs, ordered PreparedExecution, target descriptors, typed failures and immutable execution artifact.
- Keep adapter boundaries: editor/PSI, stock MyBatis, materializer, Database Tools execution. Merge/remove implementations with no real consumer once the replacement is proven.
- Inspect `XmlBooleanGuardSyntax`, Boolean/foreach admission lists, SQL-token topology proofs, duplicated XML/annotation preparation variants and hand-maintained OGNL accepted-node sets **as one cost center**. Prefer stock MyBatis parser/evaluator results plus minimal input-source proof. A fixed-width whitelist is not evidence of general OGNL compatibility.
- Review isolated URLClassLoader, reflection, TCCL switches and cross-loader snapshots against a concrete process-global OGNL state test. Retain required isolation; simplify only if tests establish the same process safety. No premature removal of protections.
- Stop new grammar-island, syntax-comparison and independent proof PRs unless a specific **shipping vertical path** would otherwise fail.
- Existing security/lifecycle/target-persistence hardening is protected. Delete legacy action/evaluator/input/history only **after** full cutover; delete the old v2 migration reader only after a supported upgrade-window decision.

## 5. Implementation sequence with stop gates

1. **Product/architecture reset** (this document and #391): update authoritative docs and agent contract, link prior proof without changing its observed API facts. No runtime behavior change.
2. **One end-to-end feasibility slice** under #64/#65/#66: captured XML SELECT with `#{id}` and ordinary stock-OGNL `<if>`, explicit single target, one named supported type/dialect. Build a guarded SQL-text materializer and integration harness; assert actual native Database Tools query/result/history on the exact artifact. Include '?' in quoted text/comments, dialect operators, raw `${ }`, null/unsupported types, stale editor, wrong target, cancellations and no sensitive logs as negative/refusal fixtures.
3. **Decision checkpoint:** if native end-to-end execution, exact approved SQL, dialect/type coverage, stability and nonzero-bind safety cannot be proven, **stop migration**. Keep the shipped action and reconsider the contract explicitly. Do not compensate with more OGNL grammar cases.
4. **Migration parity**: test representative currently shipped XML/Java Mapper cases, dynamic tags, input UI/history, source staleness, DML/raw prompts, schema/restart, disposal and real IU/DataGrip evidence where applicable. Only explicitly supported cases are shipped; publish any known degradation.
5. **Single production cutover**: replace root invocation/engine wiring as one execution authority once supported nonzero and zero cases pass. No silent old-path fallback. Retire old evaluator/action/regex input authority and unused standalone/new admission scaffolding in bounded follow-on PRs; keep the regression evidence in equivalent/new tests.

Pass/fail is determined by executable behavior and safety. More prepared-expression variants, green CI alone, or a pass on a standalone engine are **not** evidence that the full path works.

## 6. Historical decisions and current code status

- #251 remains a valid NO-GO for **typed JDBC binding + maintained native console result/history integration** on the investigated 2026.2 surface. We are not asserting that such an API now exists.
- #258 was a valid strict no-fallback decision under the previous product target. This rebaseline **supersedes that target policy** after merge, not the underlying API evidence; no unreviewed fallback is implicitly permitted. Runtime `BOUND_EXECUTION_REQUIRED` remains a correct failure while a supported renderer and cutover do not exist.
- All merged #385/#387 Boolean admission code remains in Git, but new implementation expansion is frozen. Previously merged legacy source/target/resource/privacy hardening remains authoritative.
- #61's original closed product-contract issue and #101 target architecture are historical checkpoints, not permanent reasons to continue the blocked specification. Epic #60 and this dated reset now own the changed decision.

## 7. Definition of done for Leap

A shipped unified execution action with full source/input/MyBatis/materialization/target provenance; explicit supported DBMS/type/source matrix; immutable exact execution text approved by the user; real native Database Tools results; source/target/cancellation/refusal evidence; no unsafe silent fallback; unused legacy plus orphaned new architectures deleted; README/Marketplace fidelity claims aligned. A true JDBC bound-execution adapter is an optional future capability and is **not** required to declare this architecture replacement complete.

