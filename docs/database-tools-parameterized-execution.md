# Database Tools parameterized execution proof — IntelliJ 2026.2

Issue: #251

Status: **NO-GO for the Leap cutover contract on the maintained IntelliJ IDEA Ultimate / Database Tools 2026.2 baseline.**

This result does **not** mean that Database Tools cannot execute prepared statements. It means that the maintained/public surface proven in 2026.2 does not provide one path that simultaneously:

- consumes MyBatis `sqlWithPlaceholders` plus ordered values programmatically;
- reuses the already configured Database Tools datasource/session;
- remains DBMS-family opaque;
- avoids a second user parameter prompt;
- preserves Database Tools cancellation/disposal semantics; and
- preserves the native console result/history UX without zMyBatis owning result rendering or relying on undocumented/internal contracts.

The consequence is fail-closed: non-zero bindings remain structured `PreparedExecution` state and are not executable through the Leap adapter yet. This proof does **not** authorize a PostgreSQL/Oracle/MySQL literalizer fallback.

## 1. Maintained baseline and evidence method

The repository baseline is IntelliJ IDEA Ultimate 2026.2 with the bundled `com.intellij.database` plugin.

The investigation used three evidence sources:

1. JetBrains 2026.2 user documentation for query consoles and user parameters.
2. JetBrains Plugin SDK extension-point metadata.
3. Bounded compile/runtime API probes on PR #253 against the repository's real 2026.2 test/plugin classpath.

Temporary probes were deliberately failing discovery code and direct compile references only. They are not production code and are removed from the final diff.

The direct compile probe referenced:

- `DataRequest.OwnerEx`;
- `DatabaseConnectionCore`;
- `SmartStatementFactoryService`;
- `StatementParameters`.

On the probe HEAD, normal plugin compilation and Plugin Verifier both passed. Therefore the NO-GO is not based on a claim that these classes are simply absent. It is based on the contracts they expose.

## 2. Candidate A — Database Tools console/user-parameter subsystem

JetBrains 2026.2 documents query consoles as datasource-attached execution surfaces with native cancellation, history, and result tabs:

- https://www.jetbrains.com/help/datagrip/run-a-query.html

The same documentation describes user parameters as values requested in the Parameters dialog and then **replaced** into the SQL text. A single `?` can, for example, be replaced by the SQL fragment `206, 'John', 'Smith'`. This is text-substitution semantics, not a JDBC positional bind contract.

The public extension-point list exposes:

- `com.intellij.database.queryParametersProvider` → `QueryParametersProvider`;
- `com.intellij.database.consoleRunContextParametersTuner` → `ConsoleRunContextParametersTuner`.

Reference:

- https://plugins.jetbrains.com/docs/intellij/data-grip-extension-point-list.html

The 2026.2 runtime probe established the relevant signatures:

```text
QueryParametersProvider.getParameters(T)
    -> Map<String, PsiElement>

ConsoleRunContextParametersTuner.tuneParams(
    Project,
    LocalDataSource,
    SimpleJavaParameters
)

ConsoleDataRequest.newConsoleRequest(
    JdbcConsoleBase,
    Editor,
    ScriptModel<?>,
    boolean
)
```

These contracts do not accept zMyBatis's already-collected ordered runtime values.

The standard request factories likewise expose SQL text and execution constraints:

```text
DataRequest.newRequest(OwnerEx, String, Dbms)
DataRequest.newRequest(OwnerEx, String, Constraints)
DataRequest.newRequest(OwnerEx, String, int, int, int, int, int)
```

`DataRequest.QueryRequest` has protected constructors whose final parameter is an opaque `Object`, but the maintained public surface exposes no typed value-supply contract for it. Treating that opaque slot as a binding API would require undocumented semantic inference and therefore fails the repository's proof obligation.

### Candidate A result

**Rejected for execution authority.**

Even if zMyBatis could pre-populate the console parameter storage, the documented subsystem is SQL text substitution. It would move literal-rendering semantics into an undocumented adapter interaction rather than preserve MyBatis's ordered typed bindings as JDBC parameters.

It also conflicts with the requirement that the user must not be prompted a second time unless zMyBatis depends on undocumented parameter-storage mutation.

## 3. Candidate B — session-bound RawRequest / JDBC prepared execution

Database Tools does expose a path that executes against the configured session connection:

```text
DataRequest.RawRequest.processRaw(
    DataRequest.Context,
    DatabaseConnectionCore
)

DatabaseConnectionCore.getRemoteConnection()
```

This proves that zMyBatis can reach the Database Tools-owned connection without duplicating datasource/credential configuration.

A direct JDBC prepared statement through `remoteConnection` can therefore bind values, but that by itself exits the normal query request/result/history pipeline. zMyBatis would have to consume and present results itself, which is explicitly outside #251's acceptance boundary.

### Candidate B result

**Useful evidence, but not sufficient for the Leap execution adapter.**

It proves "reuse the configured session + true prepared statement" but not "preserve native Database Tools result/history UX".

## 4. Candidate C — SmartStatementFactory parameterized execution

The 2026.2 probe also established:

```text
SmartStatementFactoryService.poweredBy(DatabaseConnectionCore)
    -> SmartStatementFactory

SmartStatementFactory.parameterized()
    -> ParameterizedSmartStatement

StatementParameters.placeholdersOffsets(int[])
StatementParameters.parameters(List<ColumnQueryData>)
StatementParameters.asData(String)
    -> ParameterizedStatementData
```

This is stronger than raw `remoteConnection.prepareStatement(...)`: it is a Database Tools statement abstraction and can execute on a Database Tools-owned connection.

However, its parameter contract is grid-oriented rather than a generic "ordered JDBC values" port:

```text
StatementParameters.parameters(List<ColumnQueryData>)
```

`ColumnQueryData` carries a `GridColumn`/JDBC-column descriptor together with the object value. zMyBatis's `PreparedExecution` contains MyBatis `ParameterMapping`/Java/JDBC/type-handler evidence, not a Database Tools result-grid column model. Manufacturing synthetic grid columns would create another semantic adapter that must guess Database Tools column metadata and still would not establish native console execution/history identity.

The result side is processor-oriented. The 2026.2 API exposes `ResultsProducer` / `StandardResultsProcessors` over `RemoteResultSet`; the proven surface does not provide a maintained bridge from a custom parameterized statement request back into the standard console's result tabs and query history.

### Candidate C result

**Rejected for the current cutover contract.**

It proves a DBMS-opaque Database Tools prepared-statement mechanism exists, but not one that consumes zMyBatis bindings directly **and** preserves the native console result/history path required by #251.

## 5. Cancellation and disposal

The standard console/request pipeline owns cancellation and request lifecycle. A `RawRequest` is still scheduled through a `DatabaseSession` producer and therefore can inherit part of the session lifecycle, but executing and consuming a custom prepared statement inside `processRaw` would make zMyBatis responsible for the exact statement/result/cancellation bridge.

Because the missing native-result bridge is already a blocking contract failure, #251 does not broaden scope into a custom cancellation/result implementation.

Project/datasource disposal remains fail-closed: no new execution path is introduced by this proof PR.

## 6. Security / logging result

No candidate requires or justifies logging raw parameter values or rendered executable SQL.

The final code path remains unchanged:

- bound values stay in structured `PreparedExecution`;
- the zero-binding DB-neutral text path remains the only maintained materialization path;
- no vendor literalization is reintroduced.

## 7. Why two-DB runtime fixtures are not run

The acceptance criteria require two DB-family runtime evidence when a viable adapter candidate exists, or proof that the adapter is DBMS-opaque.

The investigation stops before database fixtures because no candidate reaches the API-contract gate:

- console parameter substitution is the wrong execution semantic;
- raw/smart prepared execution lacks the required native console result/history integration.

Running PostgreSQL + Oracle/MySQL fixtures against a contract that is already rejected would not prove the missing integration and would create false confidence.

## 8. Decision

For IntelliJ Database Tools 2026.2, #251 is **NO-GO** for the intended Leap cutover:

```text
PreparedExecution(sqlWithPlaceholders, orderedBindings)
        │
        ├─ console/user parameters
        │    -> native console UX
        │    -> text substitution / second-prompt model
        │    -> FAIL
        │
        └─ RawRequest / SmartStatementFactory.parameterized()
             -> configured session + true prepared execution
             -> no proven maintained bridge to native console result/history
             -> FAIL
```

This decision is deliberately narrow.

It does **not** establish that future Database Tools versions cannot expose the needed bridge.
It does **not** authorize internal/reflection-based integration.
It does **not** authorize per-DB literalizers automatically.

#64 and #65 must make the fallback/product decision separately after this proof. Until then, non-zero bindings remain fail-closed with `BOUND_EXECUTION_REQUIRED`.
