<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# zMyBatis Changelog

## [Unreleased]

### Added

- **Settings UI** (`Settings → Tools → zMyBatis`) with full configuration panel
- **SQL Preview**: optional dialog to review resolved Native SQL before execution
- **Auto-format SQL**: reformats resolved SQL using IntelliJ's built-in SQL code-style formatter
- **Console Session Policy**: choose between reusing an existing DB console or opening a new one per execution
- **Copy to Clipboard**: optional auto-copy of resolved SQL to clipboard after execution (enabled by default)
- **Remember Last Inputs**: parameter dialog pre-fills with the last-used values per Mapper statement
- **Empty Input Handling**: configurable policy for blank parameter fields — NULL or empty string
- **Strict OGNL Mode**: optional strict propagation of OGNL evaluation errors (disabled by default)
- **Ignore Unknown Tags**: optional pre-stripping of unrecognised XML tags to allow parsing to continue (disabled by default)

### Fixed

- Fixed "Editor is already disposed" crash (`LogicalPositionCache.checkDisposed`) that occurred when `RunQueryInConsoleIntentionAction`'s async coroutine tried to show an error hint on the headless dummy editor. `HOST_EDITOR` and `CONTEXT_COMPONENT` in the spoofed `DataContext` are now set to the original visible editor so that hint display has a real Swing window parent.
- Fixed unit test failures caused by `ApplicationManager.getApplication()` returning null in non-IDE test environments — `MyBatisEvaluator` now gracefully falls back to safe defaults

### Removed

- Removed stale backup source files (`_bak`, `_newfile`) from the build

### Added

- Execute MyBatis mapper queries directly from XML mapper files and Java annotation-based mappers via DataGrip execution pipeline
- Dynamic SQL evaluation: `<if>`, `<choose>`, `<when>`, `<otherwise>`, `<foreach>`, `<where>`, `<set>`, `<trim>`, `<bind>` tags fully supported
- Parameter input dialog: automatically detects `#{param}` and OGNL parameters and prompts for values before execution
- Supports `null`, numbers, strings, booleans, and list inputs (`[1,2,3]`) in parameter dialog
- Supports `@Select`, `@Insert`, `@Update`, `@Delete` annotation-based mappers including multi-line string arrays and constant field references
- `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider` methods show an informative unsupported notice instead of failing silently
- OGNL expression parameters properly extracted: `<if test="...">` variables are included in the input dialog, loop variables (`item`, `index`) and `<bind>` variables are correctly excluded
- Automatic version numbering in `yy.MM.dd.HHmmss` format

- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)

- Older IntelliJ Platform Plugin Template-specific history (up to and including 2.4.0 — 2025-11-25) has been removed from this file. If you need the original template history, it is preserved in the repository or upstream template; contact the maintainer to restore specific entries.

<!-- NOTE: Template comparison links and long template history removed to keep changelog focused on zMyBatis releases -->