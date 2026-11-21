# zMyBatis manual release validation

This project is the repository-owned acceptance fixture for an exact packaged zMyBatis 1.0 candidate.
Its detailed M/J/R/U/T matrix is primarily an **automated evidence inventory**, not a requirement for a
maintainer to replay every case by hand. Human release validation is deliberately limited to the
small visual/UX smoke in section 11. The fixture remains intentionally small: no Spring Boot
application and no application runtime is started.

The P5 target execution authority is:

```text
PreparedExecution
  -> immutable PreparedExecutionPlan
  -> NativeExecutionPlanMaterializer
       - zero binding: exact SQL text
       - supported PostgreSQL bindings: ordered/type-aware executable SQL
  -> exact execution-SQL approval when preview/mandatory confirmation applies
  -> source/target/lifecycle revalidation
  -> NativeConsoleSqlTextBackend
  -> Database Tools native result/history
```

This restores the pre-Leap native Database Tools result workflow without restoring the deleted legacy
evaluator. Stock MyBatis still owns dynamic SQL shape and ordered binding metadata; supported
bindings are materialized only after preparation, with explicit escaping/type rules. Unsupported
value/type/dialect/custom-TypeHandler shapes fail closed and do not fall back to a zMyBatis result
modal.

This fixture is **not** a replacement for automated tests. Detailed MyBatis semantics, routing,
approval, target identity, cancellation/lifecycle, and packaged host behavior stay in the lowest
reliable automated layer. Manual validation exists only for residual human-visible UX that the
Driver/fixture layers do not judge well.

## Release stop condition

Do not sign off a release while an automated acceptance owner is red, a human smoke item fails, the
wrong datasource/schema is observed, or the exact candidate record is incomplete. A semantic case
that already has deterministic automated ownership is not re-run manually merely to duplicate that
evidence.

#509 and #510 are no longer treated here as pending semantic contradictions. P1/P2 established the
production preparation/invocation contracts for standard dynamic tags and ordinary result/parameter
metadata. Their final issue disposition is completed only after P5 packaged/automated evidence agrees
with those contracts.

## 1. Record the exact candidate

Use the **canonical candidate artifact produced by the PR `Build` workflow**. Do not locally rebuild
the plugin for human sign-off: a local rebuild would break byte-for-byte continuity with the ZIP
already exercised by IDEA/DataGrip Starter E2E.

The Build job must provide exactly one artifact named:

```text
zmybatis-validation-candidate-<exact-40-char-PR-HEAD>
```

That artifact contains:

```text
zMyBatis-0.0.0-validation.sha<first-12-HEAD>.zip
validation-candidate-manifest.txt
```

The manifest records the exact candidate SHA, visible validation version, plugin ZIP filename and
plugin SHA-256. IDEA and DataGrip E2E must both download this same artifact and verify the same
SHA-256 before installing it. Extract the inner plugin ZIP from this artifact and install **that exact
file** for H01-H03. The outer GitHub artifact archive is transport only and is not the plugin ZIP.

If any source change lands after the artifact is built, discard the artifact and require a new Build
run. A standalone E2E `workflow_dispatch` or locally rebuilt ZIP is diagnostic evidence only and is
not accepted as the P5 human candidate.

Record:

```text
candidate version: ____________________
candidate commit:  ____________________
plugin ZIP SHA-256: ____________________
IDEA version:       ____________________
validator/date:     ____________________
```

`git status --short` must be empty. Install that exact ZIP through
**Settings/Preferences → Plugins → ⚙ → Install Plugin from Disk…**.

## 2. Open the sample and PostgreSQL fixture

Open `samples/manual-release-validation` as a Maven project and wait for indexing.

Start PostgreSQL:

```bash
cd samples/manual-release-validation
docker compose up -d
docker compose ps
```

Disposable fixture connection:

```text
Host:     127.0.0.1
Port:     5432 (or $ZMYBATIS_POSTGRES_PORT)
Database: zmybatis
User:     zmybatis
Password: zmybatis
Schema:   public
```

Create a PostgreSQL Database Tools datasource and select the explicit named `public` schema. A
stable datasource identity + explicit schema is part of execution authority.

Reset with:

```bash
docker compose down -v
docker compose up -d
```

## 3. Invoke zMyBatis

Put the caret inside the target XML CRUD statement or a supported direct Java MyBatis annotation and
invoke **Execute (zMyBatis)**. It is correct for the action to be hidden outside supported contexts.

For bound execution, stock-MyBatis placeholder SQL plus ordered binding metadata remains the semantic
source, but the execution preview shows the exact materialized SQL text that will be sent to Database
Tools. Binding metadata remains visible below it. Bound values must not appear in normal diagnostics
or logs.

## 4. Core XML acceptance inventory

These cases remain checked in so product behavior, lower-layer parity, packaged evidence and manual
reproduction all refer to the same fixture. **They are not a mandatory maintainer-run checklist.**
Run one manually only when investigating a failure or when its automated owner is unavailable.

Use `src/main/resources/mappers/XmlReleaseValidationMapper.xml` unless another mapper is named.

| ID | Manual case | Input / action | Expected result | Automated owner |
| --- | --- | --- | --- | --- |
| M01 | XML zero binding | `zeroBinding` | optional preview is `SELECT 7 AS result_value`; Database Tools native result contains `7` | packaged zero-binding E2E |
| M02 | qualified include | `ProductMapper.findAllProducts` | native result contains products `1..3`; qualified fragment resolves | source graph + projection parity |
| M03 | BIGINT bound execution | `bigintEcho`, `42` | preview shows exact materialized `42::BIGINT` + BIGINT metadata; native Database Tools result contains `42` | P5 native materialization + packaged native-result E2E |
| M04 | BIGINT + `<if>` | `bigintWithIf`: `7`, then `-7` | stock MyBatis selects the branch; remaining BIGINT binding materializes in order; native positive result includes marker `1` | P2 registered parity + native materialization envelope |
| M05 | `<where><if>` | `enabled=true,id=1`, then `enabled=false,id=1` | stock MyBatis selects each SQL shape; surviving bindings materialize when present; both cases use native Database Tools results | P2 registered parity + native delivery |
| M06 | `<foreach>` additional bindings | `foreachIds`: `[1]`, then `[1,2]` | ordered MyBatis additional-parameter bindings materialize in exact order; native results return `1`, then `1,2` | P2 foreach parity + P5 multi-binding materialization |
| M07 | XML raw `${}` | `rawEcho`, cancel `45001`, then approve `45002` with SQL Preview OFF | raw acknowledgement + forced final preview; cancelled value does not execute/retain; approved exact text returns `45002` through native route | packaged XML-raw E2E |
| M08 | target-derived `databaseId` | `databaseIdEcho` | PostgreSQL variant returns `2620`, not default `2600` | databaseId oracle/source/preparation + packaged databaseId E2E |
| M09 | VARCHAR bound execution | `unsupportedVarchar`, `abc` | despite historical method name, String/VARCHAR is materialized with PostgreSQL-safe escaping; native result contains `abc` | P5 String/VARCHAR materialization |
| M10 | two BIGINT bindings | `unsupportedTwoBigints`, `1`, `2` | despite historical method name, two ordered bindings materialize in exact order; native result is `3` | packaged multi-binding native-result envelope |
| M11 | XML INSERT approval | `insertNoop`, SQL Preview OFF | mutation approval mandatory; cancel does not execute; approved zero-binding statement changes 0 rows | mutation policy + packaged mutation-approval envelope |
| M12 | XML UPDATE approval | `updateNoop`, SQL Preview OFF | mutation approval mandatory; approved statement changes 0 rows | mutation policy + packaged mutation-approval E2E |
| M13 | XML DELETE approval | `deleteNoop`, SQL Preview OFF | mutation approval mandatory; approved statement changes 0 rows | mutation policy + packaged mutation-approval envelope |
| M14 | realistic bound Product lookup | `ProductMapper.findProductById`, `2` | BIGINT input materializes after stock-MyBatis preparation; native result row contains `2 / pro / true` | projection/include + native materialization |
| M15 | `<set>` + `<trim>` safe UPDATE | `setTrimNoop`, `name=ignored`, `id=1` | stock MyBatis emits SET/WHERE; VARCHAR+BIGINT bindings materialize in order; mutation approval is mandatory; `AND FALSE` keeps update count at 0 | P2 set/trim parity + P5 native materialization |
| M16 | nested Map property | `nestedMapEcho`, JSON Map `{"tenantId":2}` | input uses JSON Map editor; `filter.tenantId` resolves to one BIGINT binding which materializes after preparation; native result is `2` | P2 caller-authority nested Map parity + native materialization |
| M17 | same-namespace include | `ProductMapper.findAllProductsLocalInclude` | local `<sql id="localProductColumns">` resolves through `<include refid="localProductColumns"/>`; zero-binding native result contains products `1..3` | source graph + P1 projection parity |
| M18 | XML-only Fields + JSON input | open `XmlOnlyInputValidationMapper.findByIdJSON` (no Java mapper method) | input dialog defaults to Fields for `user.id`, `cust.name`, `test`; JSON tab contains the same generated nested object template; either mode submits one XML parameter object and `test=42` returns `42` | XML caller-provenance + dual-input presentation tests |

M02 and M17 jointly cover cross-namespace and same-namespace include authority. M10 is the manual representative for mapper-method multi-`@Param`; M06 is the manual representative for
List/foreach additional bindings. P2 lower-layer parity separately owns equivalent Collection and
array alias/wrapper behavior, so the maintainer checklist does not duplicate those shapes merely to
make the manual matrix combinatorial.

The historical method names `unsupportedVarchar` and `unsupportedTwoBigints` are retained only to
make regression from the old materializer envelope obvious. A refusal solely because the value is
VARCHAR or because there is more than one binding is a **BLOCKER**.

## 5. Promoted 1.0 parity inventory

These were the historical R01-R05 reconciliation fixtures. They are now normal supported acceptance
cases and are named `Parity*Mapper`.

| ID | Mapper / case | Expected result | Evidence owner |
| --- | --- | --- | --- |
| R01 | `ParityChooseMapper.chooseParity` with `enabled=true/false` | stock `choose/when/otherwise` produces `1` / `0`; resulting zero-binding SQL executes native | P2 registered-path parity |
| R02 | `ParityBindMapper.bindParity`, value `abc` | stock `<bind>` computes `%abc%`; raw acknowledgement + final preview required; native result is `%abc%` | P2 bind parity + raw policy |
| R03 | `ParityResultMapMapper.findAllWithResultMap` | `resultMap` metadata does not block SQL preparation; rows `1..3` are visible | P1 projection parity |
| R04 | `ParityResultTypeMapper.findAllWithResultType` | `resultType` metadata does not block SQL preparation; rows `1..3` are visible | P1 projection parity |
| R05 | `ParityParameterTypeMapper.findByIdWithParameterType`, `2` | built-in `parameterType="long"` remains valid; BIGINT materializes and native result returns product `2` | P1 projection + P5 native materialization |

R03/R04 do not assert MyBatis application result-object mapping. zMyBatis presents database rows; the
acceptance claim is that SQL-generation-irrelevant result metadata does not block preparation.

## 6. Direct Java annotation inventory

Open `src/main/java/fixture/AnnotationReleaseValidationMapper.java`.

| ID | Manual case | Expected result | Automated owner |
| --- | --- | --- | --- |
| J01 | `@Select` zero binding | native result `7` | Java source + zero-binding E2E envelope |
| J02 | bound `@Select` BIGINT | input `42` → exact materialized preview + native result `42` | Java caller authority + native materialization |
| J03 | raw `@Select` | acknowledgement + forced preview; cancelled raw input is not retained; approved exact text executes native | packaged direct-Java raw E2E |
| J04 | `@Insert` safe no-op | mutation approval mandatory; 0 rows | Java statement kind + approval |
| J05 | `@Update` safe no-op | mutation approval mandatory; 0 rows | Java statement kind + approval |
| J06 | `@Delete` safe no-op | mutation approval mandatory; 0 rows | Java statement kind + approval |
| J07 | `@SelectProvider` | typed provider-not-supported refusal; no execution | source/action boundary + packaged Provider refusal E2E |

Kotlin direct annotations and Provider execution remain outside the current 1.0 capability boundary.
DataGrip acceptance remains XML-focused.

## 7. Settings / retention / console inventory

| ID | Manual case | Procedure | Expected |
| --- | --- | --- | --- |
| U01 | SQL Preview ON | enable preview; M01/M03 | preview appears before execution |
| U02 | SQL Preview OFF | disable preview; M01 | safe read may execute without optional preview |
| U03 | Auto-format | compare M01/M03 formatting ON/OFF | presentation may change; plan/result semantics do not |
| U04 | Remember Last Inputs | execute M03 twice | eligible BIGINT input may be restored for the same statement |
| U05 | Raw non-retention | execute/cancel M07 twice | previous raw value remains blank |
| U06 | Statement isolation | enter M03 value, then M14 | values do not bleed across canonical statements |
| U07 | existing console reuse | keep one eligible Database Tools console; execute M01 twice from the mapper | the same console is reused and the remembered source+target association wins on the second execution |
| U08 | no-console creation | close/remove eligible consoles for the selected target; execute M01 | one mapper-derived console is created automatically; no extra editor tab steals focus |
| U09 | multiple-console chooser | keep two eligible consoles for the target; execute M01 | Choose Database Console popup appears with both candidates plus Create New Console; the chosen console is remembered |
| U10 | mapper focus | execute M01 from the XML mapper while its console editor is closed | execution must not open or focus another console editor tab; mapper remains the active editor |

Prepared bound-result presentation remains a separate P5 UX gap until maintained Database Tools APIs
cannot expose a maintained true-prepared-to-native-grid bridge. P5 therefore restores the established native result UX through explicit post-stock-MyBatis SQL materialization, with unsupported shapes failing closed.

## 8. Target / lifecycle inventory

| ID | Manual case | Procedure | Expected |
| --- | --- | --- | --- |
| T01 | explicit schema | first invocation | chooser requires datasource + named `public` schema |
| T02 | persisted target | invoke same source again | exact valid target is reused |
| T03 | restart restore | close/reopen IDE/project | stable UUID + `public` target restores |
| T04 | datasource display rename | rename without recreation | stable UUID keeps the same target valid |
| T05 | stale datasource identity | delete/recreate datasource | old UUID never silently redirects; re-selection required |
| T06 | no datasource | fresh state, invoke M01 | typed **Execution Target Unavailable** refusal; no execution |
| T07 | cancel target chooser | cancel first selection | invocation ends before input/approval/execution |

Wrong-datasource or wrong-schema execution is a **release blocker**.

## 9. Packaged host acceptance ownership

### IntelliJ IDEA Ultimate

The exact packaged IDEA Starter/Driver suite is the primary executable host authority. It already
installs the built plugin archive and covers bound/unbound native-result routing,
raw/mutation approval, databaseId/target behavior, Provider refusal and no-datasource failure.
Lower-level fixtures own detailed lifecycle/cancellation and semantic matrices.

### DataGrip

The exact packaged DataGrip Starter/Driver suite is the host authority. It covers plugin/service
loading, XML action refusal, one XML-only bound PostgreSQL native-result route and clean shutdown.

No separate DataGrip maintainer-run checklist is required for P5. Do not infer Java annotation
support, native Database Tools result/history parity, every DBMS/dialect, or IDEA-only UX from the
bounded automated DataGrip evidence.

## 10. Explicit unsupported boundaries

The following are not converted into approximate execution:

- Provider annotations;
- Kotlin direct annotations;
- custom LanguageDriver;
- application/custom TypeHandler semantics requiring arbitrary application code execution;
- `selectKey` / application runtime plugins where runtime authority cannot be safely reconstructed;
- arbitrary external-entity/runtime loading.

Refusal must be typed and must not fall back to another execution backend.

## 11. Minimal human UX smoke / GO-NO-GO record

Run this only against the **exact final P5 candidate ZIP**. The automated M/J/R/U/T inventory above is
not replayed manually.

### Human smoke

| ID | Human-only check | Expected |
| --- | --- | --- |
| H01 | Install the exact ZIP in IDEA Ultimate 2026.3; open zMyBatis settings and a supported XML mapper; open M18 once | plugin is enabled, settings render normally, Execute (zMyBatis) is visible; XML-only input shows source-proven Fields plus the generated JSON tab instead of a generic `{"key":"value"}` object |
| H02 | Run M01 once and M03 once | both successful queries land in the native Database Tools result surface; values `7` and `42` are readable; no `zMyBatis: Query Result` modal appears |
| H03 | Restart IDEA/project after H02 and invoke the same source again | persisted exact datasource UUID + `public` target restores/reuses without silently switching target; visible flow remains usable |

These three checks deliberately cover only human-visible installation/presentation/restart UX.
Semantic correctness remains owned by automated tests. DataGrip requires no separate manual smoke
because its exact packaged host path is already exercised by the independent Starter/Driver job.

```text
Candidate version: ____________________
Candidate SHA:     ____________________
ZIP SHA-256:       ____________________
IDEA version:      ____________________
Validator/date:    ____________________

H01 install/settings/action: PASS / FAIL   notes: ____________________
H02 native bound/unbound visual: PASS / FAIL   notes: ____________________
H03 restart/target UX:      PASS / FAIL   notes: ____________________

#509 disposition:  RESOLVED / BLOCKED  notes: ____________________
#510 disposition:  RESOLVED / BLOCKED  notes: ____________________

Human blockers:    NONE / ______________________________________
Human smoke:       GO / NO-GO
```

A human-smoke GO requires H01-H03 PASS and no unresolved human blocker. It does **not** replace the
exact-head automated IDEA/DataGrip evidence or Merge Gate, which remain separate P5 exit conditions.
