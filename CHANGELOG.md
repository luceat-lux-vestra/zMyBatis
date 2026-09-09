<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# zMyBatis Changelog

## [Unreleased]

### Changed
- **Execute (zMyBatis)** is now a standalone action (right-click menu / `Run` context group) instead of overriding DataGrip's built-in Execute action — DataGrip's own Execute, Explain Plan, and all other actions are no longer affected
- **Session persistence hardened** — restart-persisted sessions are project-scoped and require a stable datasource UUID plus an explicit named schema; `Use Default Schema` and datasources without a stable UUID remain in-process only, and stale/missing/ambiguous restoration is rejected instead of redirected

### Added

#### Core Execution
- Execute self-contained MyBatis mapper statements from XML mapper files and supported Java annotation-based mappers through the JetBrains Database Tools console path, subject to the documented evaluator/input/target evidence boundaries
- Dynamic SQL evaluation routes `<if>`, `<choose>/<when>/<otherwise>`, `<foreach>`, `<where>`, `<set>`, `<trim>`, and `<bind>` through MyBatis `XMLScriptBuilder`; maintained tests exercise representative paths for each tag, but full application-runtime/JDBC/TypeHandler parity is not claimed
- Java annotation extraction covers literal values, ordered multi-line string arrays, and constant-field-reference shapes; maintained project-fixture evidence exercises real Java PSI/project-index resolution of cross-file constants, without claiming every Java source shape or production action cutover
- `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider` methods show a clear unsupported notice instead of failing silently

#### Parameter Input
- Parameter input dialog heuristically detects `#{param}`, `${param}`, and OGNL-driven inputs and prompts for values before execution; generated/runtime parameter naming is not treated as proven caller metadata
- Object/array parameters: multi-line JSON editor for supported dot/index navigation (e.g. `#{user.name}`) and collection/`foreach` shapes
- Supports `null`, numbers, strings, booleans, and structured/list inputs needed by the maintained navigation/`foreach` paths; direct collection/object `#{}` literal binding is not claimed as supported semantics
- Remember Last Inputs: parameter dialog pre-fills last-used values using the current statement key; canonical statement identity and sensitive-value retention remain Leap design obligations
- Empty Input Handling: configurable policy for blank fields — treat as `NULL` or empty string `""`
- OGNL expression parameters are heuristically extracted while known loop variables (`item`, `index`) and `<bind>` variables are excluded from prompting

#### Settings (`Settings → Tools → zMyBatis`)
- SQL Preview: optional dialog to review resolved SQL before sending it to the database; current mutation/raw-interpolation confirmation policy is still a documented Leap safety gap
- Auto-format SQL: reformat resolved SQL using IntelliJ's built-in SQL code-style settings before execution or preview
- Copy to Clipboard: auto-copy the final resolved SQL to clipboard after execution (enabled by default)
- Console Session Policy: choose between reusing an existing DB console or opening a new one per execution
- Strict OGNL Mode: optional strict mode that surfaces recognized OGNL evaluation errors immediately (disabled by default)
- Ignore Unknown Tags: optional pre-stripping of unrecognised/custom XML tags to allow parsing to continue (disabled by default); this is compatibility-altered behavior, not stock MyBatis semantics
