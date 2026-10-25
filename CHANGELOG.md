<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# zMyBatis Changelog

## [Unreleased]

### Changed
- **Execute (zMyBatis)** now has one registered Leap execution authority: authoritative Java/XML source capture → typed/provenanced input contract → isolated stock-MyBatis preparation → explicitly supported SQL-text materialization → final-artifact approval → exact JetBrains Database Tools delivery. Unsupported or ambiguous stages fail closed; there is no legacy evaluator/action fallback.
- **Session persistence** now stores only current v3 project-scoped source→target descriptors using a stable datasource UUID plus an explicit named schema. Startup prunes stale v3 source associations without recreating consoles or executing SQL; live console resources remain ephemeral and independent from persisted target identity.
- **Legacy execution compatibility state removed** — the old proxy/evaluator/extractor/dialog/history path, development-only v2 persistence migration path, and obsolete compatibility settings were deleted after the Leap cutover.

### Added

#### Core Execution
- Execute supported XML mapper statements and direct Java `@Select` / `@Insert` / `@Update` / `@Delete` annotations through the registered contract-driven action.
- Capture canonical statement/source authority and supported `<sql>/<include>` dependencies, then delegate admitted dynamic-SQL meaning to isolated stock MyBatis semantics.
- Preserve `#{}` bound inputs and `${}` raw interpolation as distinct contract kinds through preparation and approval.
- Materialize only explicitly proven executable SQL-text shapes: zero-binding execution is DB-neutral, and the maintained nonzero slice is PostgreSQL BIGINT. Other nonzero dialect/type/placeholder shapes fail typed rather than claiming generic JDBC/TypeHandler parity.
- Require explicit final-artifact approval for raw interpolation and INSERT/UPDATE/DELETE declarations; cancellation stops before native execution.
- Kotlin direct statement annotations, Provider annotations, custom language drivers/runtime-only extensions, and unproven dialect/type materialization remain unsupported.

#### Parameter Input
- Derive fields from typed/provenanced `ParameterContract` requirements instead of regex/heuristic parameter discovery.
- Validate and decode submitted values through the contract input boundary independently from Swing; unsupported or ambiguous requirements block execution.
- Treat raw-interpolation values as explicit raw input requiring acknowledgement and never retain them as remembered input.
- Remember Last Inputs is opt-in, project-scoped, keyed by canonical statement identity, and stores only eligible values explicitly selected for retention.

#### Settings (`Settings → Tools → zMyBatis`)
- **Show SQL preview before execution** — optional for ordinary supported paths; mandatory raw/mutation approval overrides this setting.
- **Auto-format SQL preview** — formats presentation text only and never mutates the immutable execution artifact.
- **Console Session Policy** — `REUSE` reuses a matching live console resource; `NEW_EACH` acquires a fresh resource per execution. Neither policy is persisted target identity.
- **Remember last parameter inputs per Mapper statement** — enables opt-in contract-driven retention; raw-interpolation values are never retained.
