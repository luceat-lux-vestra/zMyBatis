# zMyBatis manual release validation

This project is the maintainer-run acceptance fixture for an exact packaged zMyBatis 1.0 candidate.
It is intentionally small: no Spring Boot application and no application runtime is started. The
Java/MyBatis dependency exists so IntelliJ IDEA can resolve mapper methods and direct MyBatis
annotations while the plugin executes through JetBrains Database Tools.

The current shipping execution authority is:

```text
PreparedExecution
  -> immutable PreparedExecutionPlan
  -> exact-plan approval + source/target/lifecycle revalidation

ordered bindings > 0
  -> DatabaseSessionPreparedBackend
  -> zMyBatis prepared-result presentation

ordered bindings == 0
  -> NativeConsoleSqlTextBackend
  -> Database Tools native result/history
```

There is no PostgreSQL-BIGINT literalization fallback in the registered path. Standard dynamic SQL is
evaluated by stock MyBatis; ordinary result metadata must not block SQL preparation; common stock
TypeHandlers own prepared binding. Application-runtime-dependent constructs remain explicit typed
boundaries.

This fixture is **not** a replacement for automated tests. Detailed MyBatis semantic matrices stay in
the lowest reliable automated layer. Manual validation checks the installed ZIP, visible IDE flow,
Database Tools integration, settings/lifecycle behavior, and the release candidate as a user
experiences it.

## Release stop condition

Do not sign off a release while a case marked **BLOCKER** fails, a case reaches the wrong backend, the
wrong datasource/schema is used, or the exact candidate record is incomplete.

#509 and #510 are no longer treated here as pending semantic contradictions. P1/P2 established the
production preparation/invocation contracts for standard dynamic tags and ordinary result/parameter
metadata. Their final issue disposition is completed only after P5 packaged/manual evidence agrees
with those contracts.

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

For prepared execution, preview identity is SQL with placeholders plus ordered typed binding metadata.
Do not expect a literalized final SQL string. Bound values must not appear in normal diagnostics or
the binding metadata preview.

## 4. Core XML execution checklist

Use `src/main/resources/mappers/XmlReleaseValidationMapper.xml` unless another mapper is named.

| ID | Manual case | Input / action | Expected result | Automated owner |
| --- | --- | --- | --- | --- |
| M01 | XML zero binding | `zeroBinding` | optional preview is `SELECT 7 AS result_value`; Database Tools native result contains `7` | packaged zero-binding E2E |
| M02 | qualified include | `ProductMapper.findAllProducts` | native result contains products `1..3`; qualified fragment resolves | source graph + projection parity |
| M03 | BIGINT prepared binding | `bigintEcho`, `42` | preview keeps `?` + BIGINT metadata; zMyBatis prepared result contains `42` | P4 prepared backend + packaged prepared E2E |
| M04 | BIGINT + `<if>` | `bigintWithIf`: `7`, then `-7` | stock MyBatis selects the branch; remaining BIGINT binding executes prepared; positive result includes marker `1` | P2 registered parity + prepared E2E envelope |
| M05 | `<where><if>` | `enabled=true,id=1`, then `enabled=false,id=1` | true branch keeps one prepared binding and returns `1`; false branch emits no binding and may route native, returning `1,2,3` | P2 registered parity + explicit backend routing |
| M06 | `<foreach>` additional bindings | `foreachIds`: `[1]`, then `[1,2]` | both execute prepared; ordered additional-parameter bindings return `1`, then `1,2` | P2 foreach parity + P3/P4 multi-binding backend |
| M07 | XML raw `${}` | `rawEcho`, cancel `45001`, then approve `45002` with SQL Preview OFF | raw acknowledgement + forced final preview; cancelled value does not execute/retain; approved exact text returns `45002` through native route | packaged XML-raw E2E |
| M08 | target-derived `databaseId` | `databaseIdEcho` | PostgreSQL variant returns `2620`, not default `2600` | databaseId oracle/source/preparation + packaged databaseId E2E |
| M09 | VARCHAR prepared binding | `unsupportedVarchar`, `abc` | despite historical method name, this is now supported: placeholder + String/VARCHAR metadata; prepared result contains `abc` | stock TypeHandler bridge |
| M10 | two BIGINT bindings | `unsupportedTwoBigints`, `1`, `2` | despite historical method name, two ordered bindings execute prepared; result is `3` | packaged multi-binding E2E envelope |
| M11 | XML INSERT approval | `insertNoop`, SQL Preview OFF | mutation approval mandatory; cancel does not execute; approved zero-binding statement changes 0 rows | mutation policy + packaged mutation-approval envelope |
| M12 | XML UPDATE approval | `updateNoop`, SQL Preview OFF | mutation approval mandatory; approved statement changes 0 rows | mutation policy + packaged mutation-approval E2E |
| M13 | XML DELETE approval | `deleteNoop`, SQL Preview OFF | mutation approval mandatory; approved statement changes 0 rows | mutation policy + packaged mutation-approval envelope |
| M14 | realistic bound Product lookup | `ProductMapper.findProductById`, `2` | prepared result row contains `2 / pro / true` | projection/include + prepared backend |
| M15 | `<set>` + `<trim>` safe UPDATE | `setTrimNoop`, `name=ignored`, `id=1` | stock MyBatis emits SET/WHERE from dynamic tags; two prepared bindings are shown; mutation approval is mandatory; `AND FALSE` keeps update count at 0 | P2 set/trim registered parity + P4 prepared mutation route |
| M16 | nested Map property | `nestedMapEcho`, JSON Map `{"tenantId":2}` | input uses JSON Map editor; `filter.tenantId` resolves to one BIGINT prepared binding; result is `2` | P2 caller-authority nested Map parity + prepared backend |
| M17 | same-namespace include | `ProductMapper.findAllProductsLocalInclude` | local `<sql id="localProductColumns">` resolves through `<include refid="localProductColumns"/>`; zero-binding native result contains products `1..3` | source graph + P1 projection parity |

M02 and M17 jointly cover cross-namespace and same-namespace include authority. M10 is the manual representative for mapper-method multi-`@Param`; M06 is the manual representative for
List/foreach additional bindings. P2 lower-layer parity separately owns equivalent Collection and
array alias/wrapper behavior, so the maintainer checklist does not duplicate those shapes merely to
make the manual matrix combinatorial.

The historical method names `unsupportedVarchar` and `unsupportedTwoBigints` are retained only to
make regression from the old materializer envelope obvious. A refusal solely because the value is
VARCHAR or because there is more than one binding is a **BLOCKER**.

## 5. Promoted 1.0 parity cases

These were the historical R01-R05 reconciliation fixtures. They are now normal supported acceptance
cases and are named `Parity*Mapper`.

| ID | Mapper / case | Expected result | Evidence owner |
| --- | --- | --- | --- |
| R01 | `ParityChooseMapper.chooseParity` with `enabled=true/false` | stock `choose/when/otherwise` produces `1` / `0`; resulting zero-binding SQL executes native | P2 registered-path parity |
| R02 | `ParityBindMapper.bindParity`, value `abc` | stock `<bind>` computes `%abc%`; raw acknowledgement + final preview required; native result is `%abc%` | P2 bind parity + raw policy |
| R03 | `ParityResultMapMapper.findAllWithResultMap` | `resultMap` metadata does not block SQL preparation; rows `1..3` are visible | P1 projection parity |
| R04 | `ParityResultTypeMapper.findAllWithResultType` | `resultType` metadata does not block SQL preparation; rows `1..3` are visible | P1 projection parity |
| R05 | `ParityParameterTypeMapper.findByIdWithParameterType`, `2` | built-in `parameterType="long"` remains valid; BIGINT executes prepared and returns product `2` | P1 projection + P3/P4 prepared binding |

R03/R04 do not assert MyBatis application result-object mapping. zMyBatis presents database rows; the
acceptance claim is that SQL-generation-irrelevant result metadata does not block preparation.

## 6. Direct Java annotation checklist

Open `src/main/java/fixture/AnnotationReleaseValidationMapper.java`.

| ID | Manual case | Expected result | Automated owner |
| --- | --- | --- | --- |
| J01 | `@Select` zero binding | native result `7` | Java source + zero-binding E2E envelope |
| J02 | bound `@Select` BIGINT | input `42` → prepared preview/result `42` | Java caller authority + prepared backend |
| J03 | raw `@Select` | acknowledgement + forced preview; cancelled raw input is not retained; approved exact text executes native | packaged direct-Java raw E2E |
| J04 | `@Insert` safe no-op | mutation approval mandatory; 0 rows | Java statement kind + approval |
| J05 | `@Update` safe no-op | mutation approval mandatory; 0 rows | Java statement kind + approval |
| J06 | `@Delete` safe no-op | mutation approval mandatory; 0 rows | Java statement kind + approval |
| J07 | `@SelectProvider` | typed provider-not-supported refusal; no execution | source/action boundary + packaged Provider refusal E2E |

Kotlin direct annotations and Provider execution remain outside the current 1.0 capability boundary.
DataGrip acceptance remains XML-focused.

## 7. Settings / retention / console checklist

| ID | Manual case | Procedure | Expected |
| --- | --- | --- | --- |
| U01 | SQL Preview ON | enable preview; M01/M03 | preview appears before execution |
| U02 | SQL Preview OFF | disable preview; M01 | safe read may execute without optional preview |
| U03 | Auto-format | compare M01/M03 formatting ON/OFF | presentation may change; plan/result semantics do not |
| U04 | Remember Last Inputs | execute M03 twice | eligible BIGINT input may be restored for the same statement |
| U05 | Raw non-retention | execute/cancel M07 twice | previous raw value remains blank |
| U06 | Statement isolation | enter M03 value, then M14 | values do not bleed across canonical statements |
| U07 | REUSE | execute M01 twice | matching live native console may be reused; result stays correct |
| U08 | NEW_EACH | execute M01 twice | separate live console resources; result stays correct |

Prepared result presentation is zMyBatis-owned and is not required to recreate native Database Tools
query history.

## 8. Target / lifecycle checklist

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

## 9. Host acceptance

### IntelliJ IDEA Ultimate

Run the full M/J/R/U/T checklist against the exact candidate ZIP. IDEA is the full packaged acceptance
host for the prepared backend, zMyBatis prepared-result presentation, and native Database Tools
zero-binding/raw routes.

### DataGrip

Install the same ZIP and run only the maintained bounded host claim:

- plugin loads without fatal IDE error;
- settings/configurable is reachable;
- Execute (zMyBatis) appears inside supported XML;
- the maintained XML execution/refusal smoke succeeds;
- IDE closes cleanly.

Do not infer Java annotation support or IDEA-only UX from the DataGrip result. If P5 automation proves
a stable prepared XML case on the maintained DataGrip host, record that case explicitly rather than
broadening the claim implicitly.

## 10. Explicit unsupported boundaries

The following are not converted into approximate execution:

- Provider annotations;
- Kotlin direct annotations;
- custom LanguageDriver;
- application/custom TypeHandler semantics requiring arbitrary application code execution;
- `selectKey` / application runtime plugins where runtime authority cannot be safely reconstructed;
- arbitrary external-entity/runtime loading.

Refusal must be typed and must not fall back to another execution backend.

## 11. GO / NO-GO record

Fill this only after installing and running the **exact final P5 candidate ZIP**.

```text
Candidate version: ____________________
Candidate SHA:     ____________________
ZIP SHA-256:       ____________________
IDEA version:      ____________________
DataGrip version:  ____________________
Validator/date:    ____________________

M01-M17:           PASS / FAIL / NA   notes: ____________________
J01-J07:           PASS / FAIL / NA   notes: ____________________
R01-R05:           PASS / FAIL / NA   notes: ____________________
U01-U08:           PASS / FAIL / NA   notes: ____________________
T01-T07:           PASS / FAIL / NA   notes: ____________________

#509 disposition:  RESOLVED / BLOCKED  notes: ____________________
#510 disposition:  RESOLVED / BLOCKED  notes: ____________________

Blockers:          NONE / ______________________________________
Release decision:  GO / NO-GO
```

A GO requires no unresolved blocker. Automated CI is necessary but does not itself fill this manual
record.
