<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# zMyBatis Changelog

## [Unreleased]

### Added

#### Core Execution
- Execute MyBatis mapper queries directly from XML mapper files and Java annotation-based mappers via the DataGrip execution pipeline
- Dynamic SQL evaluation: `<if>`, `<choose>/<when>/<otherwise>`, `<foreach>`, `<where>`, `<set>`, `<trim>`, `<bind>` tags fully supported
- Supports `@Select`, `@Insert`, `@Update`, `@Delete` annotation-based mappers including multi-line string arrays and constant field references
- `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider` methods show a clear unsupported notice instead of failing silently

#### Parameter Input
- Parameter input dialog: automatically detects `#{param}` and OGNL expression parameters and prompts for values before execution
- Object/array parameters: multi-line JSON editor for dot-notation params (e.g. `#{user.name}`)
- Supports `null`, numbers, strings, booleans, and list inputs (e.g. `[1, 2, 3]`) in the parameter dialog
- Remember Last Inputs: parameter dialog pre-fills with last-used values per Mapper statement
- Empty Input Handling: configurable policy for blank fields — treat as `NULL` or empty string `""`
- OGNL expression parameters properly extracted: loop variables (`item`, `index`) and `<bind>` variables are correctly excluded

#### Settings (`Settings → Tools → zMyBatis`)
- SQL Preview: optional dialog to review resolved Native SQL before sending it to the database
- Auto-format SQL: reformat resolved SQL using IntelliJ's built-in SQL code-style settings before execution or preview
- Copy to Clipboard: auto-copy the final resolved SQL to clipboard after execution (enabled by default)
- Console Session Policy: choose between reusing an existing DB console or opening a new one per execution
- Strict OGNL Mode: optional strict mode that surfaces OGNL evaluation errors immediately (disabled by default)
- Ignore Unknown Tags: optional pre-stripping of unrecognised/custom XML tags to allow parsing to continue (disabled by default)
