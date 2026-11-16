# zMyBatis manual release validation

This project is the maintainer-run acceptance fixture for a packaged zMyBatis release candidate.
It is intentionally small: there is no Spring Boot application and no application runtime to start.
The Java/MyBatis dependency exists only so IntelliJ IDEA can resolve mapper methods and direct MyBatis annotations while the plugin executes statements through JetBrains Database Tools.

The fixture combines:

- realistic `Product` mapper shapes retained from the historical external `mybatisTest` project;
- the same zero-binding, PostgreSQL BIGINT + `<if>`, XML raw-interpolation and direct-Java raw shapes used by the maintained Starter/Driver E2E suite;
- safe no-op INSERT/UPDATE/DELETE declarations;
- explicit fail-closed examples outside the maintained nonzero materializer envelope;
- target/session/settings checks that remain valuable as maintainer-observed UX acceptance.

This is **not** a replacement for automated tests. Detailed MyBatis semantic matrices stay in the lowest reliable automated layer. Manual validation checks the installed ZIP, visible IDE flow, Database Tools integration, and the release candidate as a user experiences it.

## Release stop condition

Do not sign off a release while a case marked **BLOCKER** is failing or while a known contract-reconciliation item is unresolved for the release claim being made.

At the baseline that introduced this fixture, two contract/product reconciliations remain explicit release holds:

- [#509](https://github.com/luceat-lux-vestra/zMyBatis/issues/509) — broad `<choose>/<when>/<otherwise>` / `<bind>` support wording versus bounded runtime admission. `PendingDynamicContractMapper.xml` owns R01/R02.
- [#510](https://github.com/luceat-lux-vestra/zMyBatis/issues/510) — common `resultMap` / `resultType` / `parameterType` mapper declarations versus XML runtime-class-loading preflight. `PendingCommonMapperDeclarations.xml` owns R03-R05.

These pending cases are deliberately separated from the PASS fixtures so one discrepancy cannot mask another.

## 1. Record the exact candidate

From the zMyBatis repository root:

```bash
git switch main
git pull --ff-only
git status --short
git rev-parse HEAD
./gradlew clean buildPlugin -PpluginVersion=<candidate-version>
shasum -a 256 build/distributions/*.zip
```

Record:

```text
candidate version: ____________________
candidate commit:  ____________________
plugin ZIP SHA-256: ____________________
IDEA version:       ____________________
DataGrip version:   ____________________
validator/date:     ____________________
```

`git status --short` must be empty. Install that exact ZIP through **Settings/Preferences → Plugins → ⚙ → Install Plugin from Disk…**. Do not validate a Marketplace copy when the candidate under review is a local release ZIP.

## 2. Open this sample project

Open this directory itself as a Maven project:

```text
samples/manual-release-validation
```

Wait for Maven import/indexing to complete. The project pins MyBatis `3.6.0`, matching the plugin's maintained semantic runtime.

## 3. Start the local PostgreSQL fixture

```bash
cd samples/manual-release-validation
docker compose up -d
docker compose ps
```

The credentials below are disposable local fixture credentials committed intentionally for this sample:

```text
Host:     127.0.0.1
Port:     5432 (or $ZMYBATIS_POSTGRES_PORT)
Database: zmybatis
User:     zmybatis
Password: zmybatis
Schema:   public
```

In JetBrains Database Tools, create a **PostgreSQL** datasource with those values. zMyBatis requires a stable datasource identity and an **explicit named schema**. Select `public`; do not use a default-schema shortcut.

To reset the fixture:

```bash
docker compose down -v
docker compose up -d
```

## 4. How to invoke zMyBatis

The action is context-gated. Put the caret **inside** the target XML `<select>/<insert>/<update>/<delete>` or inside a supported direct Java MyBatis annotation/method, then invoke:

```text
Execute (zMyBatis)
```

It is correct for the action to be hidden in unrelated XML/Java locations.

## 5. Core execution checklist

Use `src/main/resources/mappers/XmlReleaseValidationMapper.xml` unless a case names another file.

| ID | Manual case | Input / action | Expected result | Automated owner |
| --- | --- | --- | --- | --- |
| M01 | XML zero binding | `zeroBinding` | preview/execution SQL is `SELECT 7 AS result_value`; native result contains `7` | exact packaged E2E |
| M02 | qualified `<sql>/<include>` | `ProductMapper.findAllProducts` | native result contains products `1..3`; no missing-fragment/refid failure | XML source-graph + preparation tests; manual registered-action observation |
| M03 | PostgreSQL BIGINT | `bigintEcho`, value `42` | preview materializes one BIGINT value and native result is `42` | materializer tests + exact packaged E2E shape |
| M04 | BIGINT + `<if>` | `bigintWithIf`, run `7` then `-7` | `7` path includes `positive_marker=1`; `-7` path omits it | exact packaged E2E |
| M05 | bounded `<where><if>` | `whereIf`; `enabled=true`, `id=1`, then `enabled=false`, `id=1` | true path returns only `1`; false path omits WHERE and returns `1,2,3` | Boolean-if/wrapper preparation tests |
| M06 | source-proven `<foreach>` | `foreachIds`; first `[1]`, then `[1,2]` | both must **Execution Refused** before native execution: foreach item bindings are MyBatis additional parameters, and multiple items also exceed the one-binding envelope | foreach preparation + BIGINT materializer refusal tests |
| M07 | XML raw `${}` | `rawEcho`, value `45001`; SQL Preview setting OFF | raw acknowledgement is required; final SQL preview is forced; Cancel creates no result/history. Re-run with `45002`: prior raw value is absent; approve and native result is `45002` | exact packaged XML-raw E2E |
| M08 | target-derived `databaseId` | `databaseIdEcho` on PostgreSQL | PostgreSQL variant wins; native result is `2620`, not default `2600` | databaseId/source-graph/preparation tests |
| M09 | unsupported VARCHAR binding | `unsupportedVarchar`, value `abc` | **Execution Refused**; no SQL reaches native Database Tools | input/preparation/materializer refusal tests |
| M10 | unsupported two-BIGINT materialization | `unsupportedTwoBigints`, values `1`, `2` | **Execution Refused**; no native execution | `PostgreSqlBigintExecutionMaterializerTest` binding-count refusal |
| M11 | XML INSERT confirmation | `insertNoop`, SQL Preview OFF | final-artifact confirmation is mandatory; Cancel must not execute; approved run changes zero rows | mutation approval policy/adapter/delivery tests |
| M12 | XML UPDATE confirmation | `updateNoop`, SQL Preview OFF | final-artifact confirmation is mandatory; approved run changes zero rows | mutation approval policy/adapter/delivery tests |
| M13 | XML DELETE confirmation | `deleteNoop`, SQL Preview OFF | final-artifact confirmation is mandatory; approved run changes zero rows | mutation approval policy/adapter/delivery tests |
| M14 | realistic BIGINT mapper | `ProductMapper.findProductById`, value `2` | native result row is `2 / pro / true`; passing fixture intentionally omits XML result mapping while #510 is unresolved | XML mapper-method/source + BIGINT materializer tests |

### M06 note

The list editor accepts JSON. Use exactly:

```json
[1]
```

and then:

```json
[1,2]
```

Both cases are intentionally expected to fail at final materialization. Even a one-item foreach produces a MyBatis additional-parameter binding, which the current PostgreSQL BIGINT materializer explicitly refuses. The two-item case also exceeds the one-binding cardinality envelope. This manual probe checks that the admitted foreach preparation path never falls back into native execution; detailed foreach semantics remain lower-layer automated evidence. Any native execution here is a release blocker.

## 6. Direct Java annotation checklist

Open `src/main/java/fixture/AnnotationReleaseValidationMapper.java` and put the caret inside the annotation/method being tested.

| ID | Manual case | Expected result | Automated owner |
| --- | --- | --- | --- |
| J01 | `@Select` zero binding | native result `7` | Java source/registered-action tests + zero-binding E2E envelope |
| J02 | `@Select` PostgreSQL BIGINT | input `42` → native result `42` | Java parameter/preparation + candidate/materializer tests; manual packaged-path observation |
| J03 | `@Select` raw `${}` | raw acknowledgement + forced preview; cancel one value; re-run must not retain raw value; approved value executes exactly | exact packaged direct-Java raw E2E |
| J04 | `@Insert` safe no-op | mutation confirmation mandatory; zero rows changed | Java statement-kind capture + mutation approval tests |
| J05 | `@Update` safe no-op | mutation confirmation mandatory; zero rows changed | Java statement-kind capture + mutation approval tests |
| J06 | `@Delete` safe no-op | mutation confirmation mandatory; zero rows changed | Java statement-kind capture + mutation approval tests |
| J07 | `@SelectProvider` | explicit **zMyBatis: Provider Not Supported** message; no execution | Java adversarial source capture + action presentation tests |

Kotlin direct annotations are deliberately unsupported in Leap v1 and are owned by automated Kotlin boundary tests. Do not treat the absence of Java-annotation behavior in DataGrip as a failure; the maintained DataGrip claim is XML-only smoke.

## 7. Settings / retention / console checklist

Open **Settings/Preferences → Tools → zMyBatis**.

| ID | Manual case | Procedure | Expected |
| --- | --- | --- | --- |
| U01 | SQL Preview ON | enable preview; execute M01 | preview shown before execution |
| U02 | SQL Preview OFF | disable preview; execute M01 | safe read may execute without optional preview |
| U03 | Auto-format | compare M01 with formatting ON/OFF | presentation may change; semantic/result payload must not |
| U04 | Remember Last Inputs | enable retention; execute M03 twice | eligible BIGINT value may be restored for the same canonical statement |
| U05 | Raw non-retention | with retention enabled, execute/cancel M07 twice | previous `${}` raw value must remain blank |
| U06 | Statement isolation | enter value in M03, then invoke M14 | one statement's remembered value must not bleed into another canonical statement |
| U07 | REUSE | set Console Session Policy=REUSE; execute M01 twice | matching live console may be reused; result remains correct |
| U08 | NEW_EACH | set Console Session Policy=NEW_EACH; execute M01 twice | executions use distinct live console resources; result remains correct |

## 8. Target persistence / lifecycle checklist

| ID | Manual case | Procedure | Expected |
| --- | --- | --- | --- |
| T01 | explicit schema | first invocation | chooser requires a concrete datasource + named `public` schema |
| T02 | persisted target | run M01 again from the same source file | exact persisted target is reused while still valid |
| T03 | restart restore | close/reopen IDE/project and run M01 | same stable datasource UUID + `public` target is restored; no silent redirection |
| T04 | stale target | remove/rename the configured datasource, then invoke | stale target is rejected and explicit re-selection is required |
| T05 | no datasource | open a fresh project state with no eligible datasource and invoke M01 | **zMyBatis: Execution Target Unavailable**; no preparation/execution | exact packaged fail-closed E2E |
| T06 | cancel target chooser | cancel first target selection | invocation ends; no parameter/preview/native execution |

Wrong-datasource or wrong-schema execution is a **release blocker**, even if the SQL itself succeeds.

## 9. IDEA / DataGrip host checklist

### IntelliJ IDEA Ultimate

Run the full M/J/U/T checklist in the maintained IDEA release line. This is the host with the maintained PostgreSQL native result/history proof.

### DataGrip

Install the same candidate ZIP and use the same sample project only for the maintained DataGrip claim:

- plugin loads without fatal IDE error;
- zMyBatis settings service/configurable is reachable;
- `Execute (zMyBatis)` appears inside a supported XML statement;
- with no datasource, invocation reaches the typed target-unavailable refusal;
- IDE closes cleanly.

Do **not** infer Java-annotation support or PostgreSQL native result/history parity in DataGrip from IDEA results. Those are outside the current maintained DataGrip evidence boundary.

## 10. Pending contract reconciliation cases

`src/main/resources/mappers/PendingDynamicContractMapper.xml` contains:

- **R01** — generic `<choose>/<when>/<otherwise>`;
- **R02** — generic `<bind>`.

At the fixture introduction baseline, authoritative documentation broadly calls these standard dynamic tags supported, while the shipping bounded XML admission path does not expose matching generic registered-path authority. This is tracked by #509.

`src/main/resources/mappers/PendingCommonMapperDeclarations.xml` contains realistic forms taken from the historical manual project:

- **R03** — `<resultMap>` plus `resultMap="..."` on a select;
- **R04** — `resultType="fixture.Product"` on a select;
- **R05** — `parameterType="long"` on a bound select.

The current XML preparation preflight deliberately rejects runtime class-loading surfaces, including these common declarations, with `xml-preparation-runtime-class-loading-unsupported`. This is tracked by #510. Passing fixtures intentionally avoid those declarations so the underlying source/preparation/materialization path remains observable.

Until #509/#510 are resolved:

- do not count R01-R05 as PASS criteria;
- do not widen the manual acceptance claim beyond the actually admitted bounded shapes;
- do not “fix” the reproduction files by stripping the disputed declarations/tags;
- a 1.0.0 release claim must reconcile both issues rather than hiding the discrepancies.

## 11. GO / NO-GO record

```text
Candidate version: ____________________
Candidate SHA:     ____________________
ZIP SHA-256:       ____________________

Core XML M01-M14:  PASS / FAIL / BLOCKED
Java J01-J07:      PASS / FAIL / BLOCKED
UX U01-U08:        PASS / FAIL / BLOCKED
Target T01-T06:    PASS / FAIL / BLOCKED
IDEA host:         PASS / FAIL / BLOCKED
DataGrip smoke:    PASS / FAIL / BLOCKED
#509 disposition:  RESOLVED / BLOCKED
#510 disposition:  RESOLVED / BLOCKED

Release decision:  GO / NO-GO
Notes:
- 
```

A manual PASS never overrides failing or missing required CI evidence. Conversely, green CI does not override a reproducible release-blocking manual observation against the exact candidate ZIP.

## 12. Cleanup

```bash
docker compose down -v
```
