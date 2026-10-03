# Execution target persistence

zMyBatis restart target selection is deliberately fail-closed. The authoritative restart state is a project-scoped **v3 source -> execution-target descriptor**, not a live `JdbcConsole`. Startup migrates/prunes persistence only; it never recreates a console, switches schema, opens an editor, mutates a console document, or executes SQL.

## v3 identity

- **Project** — storage uses the project-scoped IntelliJ `PropertiesComponent`; project identity is storage scope and is not serialized into each record.
- **Source** — the association stores the canonical `SourceFileId` using the `vfs:<VirtualFile.url>` convention. Its record/index ID is a fixed lowercase SHA-256 of that canonical source identity.
- **Datasource** — authoritative identity is the IDE-assigned stable datasource UUID. The display name is presentation metadata only and may change without redirecting the selection.
- **Schema** — restart persistence requires an explicit non-blank schema name. The exact schema string is preserved as identity; zMyBatis does not case-fold or trim it into a different identifier.

A datasource without a stable UUID may still be selected for the current execution, but it cannot become restart target authority. **Use Default Schema** is likewise in-process only because an implicit default/search path may change. Choosing either an unproven datasource identity or default schema removes any older persisted v3 selection for that source rather than retaining a stale named target.

The v3 store keeps source association and target descriptor independently meaningful without a live console. Malformed/index-mismatched/orphan records fail closed and are pruned.

## Startup and v2 migration

The previous v2 format stores strings only: mapper VFS URL, stable datasource UUID, datasource display name, and explicit schema. It does not serialize a live `JdbcConsole`.

At startup:

1. structurally invalid v2/v3 state is pruned fail-closed;
2. a v3 `vfs:` source association is retained only while its exact VFS URL still resolves without refresh;
3. a v2 record migrates only when its exact mapper URL still resolves and therefore proves the canonical `SourceFileId("vfs:<url>")`;
4. the migration adapter converts only that proven source identity plus the v2 stable datasource/schema identity;
5. v3 is saved/validated before v2 cleanup, so an interrupted migration may leave duplicate v2+v3 state but cannot roll the source back to the older v2 target on the next startup;
6. if valid v3 state already exists for the source, v3 wins and the duplicate v2 record is removed.

Startup does **not** resolve datasource/schema resources or create a console. Exact datasource UUID + exact schema re-resolution occurs when an explicit execution later needs a Database Tools resource.

## Older application-global storage

Versions before persistence v2 stored application-global `zMyBatis.session.*` records using `project.basePath.hashCode()` for the project index and datasource display name for datasource identity.

Those records remain intentionally **unmigrated and unread**. A hash collision or duplicate/renamed datasource name makes original ownership impossible to prove safely. They are also left untouched rather than bulk-deleted because cleanup cannot prove which project's legacy record it owns.

## Action-time target resolution and console lifecycle

On an explicit zMyBatis execution action:

- an existing live console may be reused under the `REUSE` policy;
- otherwise the v3 descriptor is resolved to exactly one live datasource and exactly one named schema through the maintained Database Tools target resolver;
- missing or ambiguous datasource/schema resolution invalidates the stored selection and requires explicit user re-selection;
- only after exact resolution does zMyBatis create a console and switch it to the proven schema;
- `NEW_EACH` creates a fresh console for the action but uses the same v3 target authority;
- explicit named datasource/schema selection updates v3 independently of console registration.

Live console caching is therefore an ephemeral optimization, not persistence authority. Closing or disposing a REUSE console does **not** delete the v3 target selection; the next explicit action re-resolves the descriptor and acquires a fresh resource.

The old v2 reader/cleanup path remains only as bounded migration compatibility in `LegacyV2ConsoleSessionMigrationStore`. `ConsoleCacheService` now owns only live console/selection/shutdown lifecycle and contains no v2/v3 persistence store. The v2 writer is retired, and startup explicitly composes the lifecycle transition gate with the migration store so v2 cleanup cannot race past shutdown. New v3 target identity is never created or deleted merely because a console is registered or disposed.

Project shutdown gates new selection/migration work. If migration cleanup is interrupted by shutdown, retained persisted state is re-evaluated on the next startup rather than guessed or redirected.
