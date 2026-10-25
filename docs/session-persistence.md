# Execution target persistence

zMyBatis restart target selection is deliberately fail-closed. The only supported persisted format is the project-scoped **v3 source → execution-target descriptor**. Live `JdbcConsole` objects are never persistence authority.

Development-only v2 persistence was never distributed to users and has no supported upgrade obligation. Its reader/converter/cleanup path is intentionally absent from production.

## v3 identity

- **Project** — storage uses the project-scoped IntelliJ `PropertiesComponent`.
- **Source** — the association stores canonical `SourceFileId` using the `vfs:<VirtualFile.url>` convention. Record/index IDs are fixed lowercase SHA-256 values of that canonical source identity.
- **Datasource** — authoritative identity is the IDE-assigned stable datasource UUID. Display name is presentation metadata only.
- **Schema** — restart persistence requires an explicit non-blank schema. The exact schema string is identity; zMyBatis does not case-fold or redirect it.

A datasource without a stable UUID may still be used for the current invocation, but it cannot become restart authority. **Use Default Schema** is also in-process only because implicit search-path meaning may change. Choosing either removes an older persisted v3 selection for that source instead of preserving stale authority.

Malformed, index-mismatched, orphaned, or source-stale records fail closed and are pruned.

## Startup

Startup may only maintain current v3 descriptors:

1. load/prune structurally invalid v3 state;
2. retain a `vfs:` source association only while its exact VFS URL still resolves without refresh;
3. remove a selection whose source identity can no longer be proven;
4. stop maintenance when project shutdown wins.

Startup does **not** resolve datasource/schema resources, create a console, switch schema, open an editor, mutate a console document, or execute SQL.

## Action-time target resolution

On an explicit zMyBatis execution action:

- persisted v3 target authority is resolved before console reuse;
- a REUSE cache entry carries its immutable `ExecutionTargetId` and is returned only when it matches the current exact target;
- otherwise the v3 descriptor resolves to exactly one live datasource and one named schema through `DatabaseToolsTargetResolver`;
- missing/ambiguous datasource or schema invalidates the stored selection and requires explicit re-selection;
- target validity is rechecked after modal/background boundaries and before native query dispatch;
- only after exact resolution does zMyBatis acquire a console and apply the proven schema;
- `NEW_EACH` acquires a fresh console but uses the same target authority;
- explicit named datasource/schema selection updates v3 independently of live-console registration.

Live console caching is an ephemeral optimization. Closing/disposal does not delete persisted v3 target identity, and service disposal releases owned console resources without mutating persistence.

## Unsupported historical state

Older application-global `zMyBatis.session.*` records used ownership/target identifiers that cannot be proven safely. They remain unread and are not migrated or bulk-deleted by production code. No supported release requires those records as execution authority.
