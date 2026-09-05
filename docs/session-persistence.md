# Console session persistence

zMyBatis restart persistence is deliberately fail-closed. A saved console is restored only when the project, mapper, datasource and explicitly selected schema can be resolved without guessing.

## v2 identity

- **Project** — storage uses the project-scoped IntelliJ `PropertiesComponent`; there is no application-global project hash namespace.
- **Mapper** — the session records the mapper's VFS URL. The URL must still resolve to a valid file at startup.
- **Datasource** — the authoritative identity is the IDE-assigned datasource UUID obtained from the datasource configuration. The display name is stored only for diagnostics and may change without redirecting the session.
- **Schema** — restart persistence requires a non-empty, explicitly selected schema name. Restoration succeeds only when exactly one matching schema exists on the resolved datasource and switching to it succeeds.

A datasource for which zMyBatis cannot obtain a stable ID may still be used during the current IDE process, but that console is not persisted for restart. It is never restored by display-name fallback.

**Use Default Schema** is also in-process only. An empty schema selection does not identify which schema will be effective after restart, and an IDE/database default may change. Persisting that choice could silently redirect a later query, so zMyBatis deliberately requires the user to choose the target again after restart.

## Legacy storage

Versions before persistence v2 stored application-global `zMyBatis.session.*` records using `project.basePath.hashCode()` for the project index and datasource display name for datasource identity.

Those records are intentionally **not migrated or read by v2**. A hash collision or duplicate/renamed datasource name makes the original identity impossible to prove safely. The first query after upgrading therefore asks the user to select datasource/schema again and creates a new v2 record only when the target has a stable datasource ID and an explicit schema.

Legacy application-level records are also left untouched rather than bulk-deleted: the same collision problem means a cleanup routine could not prove which project's legacy record it was deleting.

## Failure and cleanup behavior

At startup zMyBatis removes a v2 session when its mapper no longer exists, its datasource UUID is missing/ambiguous, or its named schema is missing/ambiguous. A malformed/stale index record is pruned. Transient console construction failures leave the valid session un-restored so a later startup can retry without redirecting it to a different identity.

Closing a live console removes its v2 session. Project/IDE shutdown marks the project-scoped cache as shutting down under the lifecycle lock before re-persisting live sessions. After that marker, new datasource selections and console registrations are rejected; a queued startup restoration therefore cannot recreate a session while the project is closing.

Startup restoration only reconstructs console state. It never injects SQL into the console and never invokes query execution.
