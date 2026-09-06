# zMyBatis — Dynamic SQL Runner with Parameters

> **Distribution status:** zMyBatis is published on [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30447-zmybatis--mybatis-dynamic-sql-runner-with-parameters-mybatis). This GitHub repository is the canonical public source repository. No public GitHub Release artifact is currently published.

<!-- Plugin description -->
<p><b>zMyBatis</b> is a JetBrains IDE plugin that lets you execute MyBatis mapper queries directly from XML mapper files or supported Java annotation-based mappers — without leaving the IDE.</p>

<p>It evaluates supported MyBatis dynamic SQL, prompts you for parameter values, converts the result to executable SQL using zMyBatis-owned parameter/literalization rules, and sends the final SQL through the JetBrains Database Tools JDBC-console execution path.</p>

<h2>Features</h2>

<h3>Execution from Mapper Source</h3>
<p>Right-click → <b>Execute (zMyBatis)</b> while the caret is inside:</p>
<ul>
  <li>A MyBatis XML mapper statement tag (<code>select</code>, <code>insert</code>, <code>update</code>, <code>delete</code>)</li>
  <li>A supported <code>@Select</code> / <code>@Insert</code> / <code>@Update</code> / <code>@Delete</code> annotation method in Java</li>
</ul>
<p>Kotlin annotation-source support is not currently implemented in the execution-context path. Provider annotations are detected only to show an explicit unsupported notice.</p>
<p>zMyBatis registers its own execution action; it does not replace or intercept the native Database Tools Execute/Explain actions. Full click-through behavior of native Database Tools UI remains a platform/integration evidence item rather than an automated unit-test claim.</p>

<h3>Dynamic SQL Evaluation</h3>
<p>The implementation routes MyBatis standard dynamic tags through <code>XMLScriptBuilder</code>, including <code>if</code>, <code>choose</code> / <code>when</code> / <code>otherwise</code>, <code>foreach</code>, <code>where</code>, <code>set</code>, <code>trim</code>, and <code>bind</code>.</p>
<p>The maintained automated evaluator contract directly exercises <code>if</code>, <code>choose</code>/<code>when</code>/<code>otherwise</code>, <code>foreach</code>, <code>where</code>, <code>set</code>, <code>trim</code>, and <code>bind</code> at least once. This establishes a self-contained evaluator baseline, not proof of every semantic permutation. zMyBatis also owns parameter discovery, compatibility transformations, OGNL handling around that engine, and conversion of MyBatis parameter mappings to literal SQL. Using MyBatis for parsing therefore does <b>not</b> imply stock JDBC/TypeHandler semantics or arbitrary application-runtime parity.</p>

<h3>Parameter Input Dialog</h3>
<ul>
  <li>Detects <code>#{param}</code>, <code>${param}</code>, and OGNL-driven inputs within the current heuristic extraction rules</li>
  <li>Filters known internal <code>bind</code>/<code>foreach</code> variables before prompting; generated-name and application-runtime naming cases are not treated as proven caller metadata</li>
  <li>Supports scalar and structured JSON input needed by supported navigation and <code>foreach</code> shapes</li>
  <li>Supports nested objects and arrays for dot/index navigation</li>
  <li>Validates structured JSON input before evaluation</li>
  <li>Direct collection/object <code>#{}</code> placeholders are not claimed as supported literal-binding semantics; collections are intended for supported navigation/<code>foreach</code> use</li>
</ul>

<h3>Data Source and Schema Selection</h3>
<p>On execution without a reusable live console, a popup lets you choose the target <b>data source</b> and <b>schema</b>. Live console reuse is scoped per mapper file.</p>
<p>Restart persistence is intentionally narrower: only sessions with a stable datasource UUID and an explicit named schema are persisted/restored. <b>Use Default Schema</b> and datasources without a stable UUID are in-process only. Missing, ambiguous, or failed datasource/schema restoration is rejected rather than redirected.</p>

<h3>Annotation Support</h3>
<ul>
  <li>The extractor contract covers Java annotation literal values, ordered string arrays, and constant-field reference shapes</li>
  <li>Real project parser/index-backed constant resolution remains an explicit platform evidence gap; it is not inferred from the interface-level unit contract</li>
  <li>Unsupported <code>@SelectProvider</code> / <code>@InsertProvider</code> / <code>@UpdateProvider</code> / <code>@DeleteProvider</code> annotations show a clear notice instead of being executed</li>
</ul>

<h3>Database Tools Integration</h3>
<p>The final SQL is injected into a JetBrains Database Tools JDBC console for execution. zMyBatis does not claim that its current automated tests prove every native result-grid, history, export, explain, editor, or restart interaction; those remain explicit platform/integration evidence boundaries where applicable.</p>

<h2>Current Semantic Boundary</h2>
<p>The current implementation combines MyBatis parsing with zMyBatis-owned parameter extraction, compatibility transformations, OGNL behavior, and literal rendering. Do not interpret this plugin as a drop-in reproduction of an application's MyBatis/JDBC runtime, custom TypeHandlers, provider methods, or every dialect-specific binding rule.</p>
<p>The authoritative Leap capability/safety policy, including explicit unsupported/degraded behavior and downstream proof obligations, is documented in <a href="https://github.com/luceat-lux-vestra/zMyBatis/blob/main/docs/product-contract.md">docs/product-contract.md</a>.</p>

<h2>Current Safety Limitations</h2>
<ul>
  <li><code>${}</code> is raw MyBatis interpolation, not a normal bound parameter. The current UI does not yet provide the separate mandatory warning/confirmation required by the Leap target contract.</li>
  <li>INSERT/UPDATE/DELETE currently use the same execution path as SELECT, and SQL Preview is optional. The Leap target requires explicit mutation confirmation.</li>
  <li>Unknown or incomplete mapper semantics such as unresolved <code>&lt;sql&gt;/&lt;include&gt;</code> dependencies must not be inferred as supported from a plausible-looking SQL result.</li>
  <li>Current evaluator failure/unsupported-value paths can still produce SQL-looking diagnostic text; Leap requires typed fail-closed outcomes before those paths are considered safe execution boundaries.</li>
</ul>

<h2>Settings</h2>
<p>Configure via <b>Settings -> Tools -> zMyBatis</b>:</p>

<table>
  <tr><th>Category</th><th>Option</th><th>Description</th></tr>
  <tr><td><b>Execution and Output</b></td><td>SQL Preview</td><td>Show a preview dialog to review resolved SQL before execution</td></tr>
  <tr><td></td><td>Auto-format SQL</td><td>Reformat resolved SQL using IntelliJ's built-in SQL code style</td></tr>
  <tr><td></td><td>Copy to Clipboard</td><td>Auto-copy the final SQL to clipboard after execution</td></tr>
  <tr><td></td><td>Console Session Policy</td><td>REUSE (default) — reuse existing live console per mapper file / NEW_EACH — create a new console for each execution</td></tr>
  <tr><td><b>Parameter Dialog</b></td><td>Remember Last Inputs</td><td>Pre-fill the parameter dialog with last-used values per current statement key</td></tr>
  <tr><td></td><td>Empty Input Handling</td><td>NULL (default) — blank fields bind as SQL NULL / EMPTY_STRING — blank fields bind as empty string</td></tr>
  <tr><td><b>Parsing Engine</b></td><td>Strict OGNL Mode</td><td>Propagate recognized OGNL evaluation failures instead of converting them to the current error-comment form</td></tr>
  <tr><td></td><td>Ignore Unknown Tags</td><td>Strip unrecognised/custom XML tags before parsing while preserving inner content; this is compatibility-altered behavior, not stock MyBatis semantics</td></tr>
</table>

<h2>Requirements and Compatibility Evidence</h2>
<ul>
  <li><b>Declared minimum IDE build:</b> 253 (2025.3 line)</li>
  <li><b>Maintained automated Plugin Verifier target:</b> IntelliJ IDEA Ultimate 2025.3.3</li>
  <li>The JetBrains Database Tools plugin (<code>com.intellij.database</code>) and a configured data source are required</li>
  <li><b>DataGrip:</b> a product integration target, but a separate maintained DataGrip verifier/runtime evidence line has not yet been established under #61/#67</li>
  <li>The declared minimum build does not by itself prove every later IDE build or other JetBrains host compatible; broader claims require explicit maintained evidence</li>
</ul>
<!-- Plugin description end -->

## Installation

Install zMyBatis from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30447-zmybatis--mybatis-dynamic-sql-runner-with-parameters-mybatis):

<kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search for <kbd>zMyBatis</kbd> → <kbd>Install</kbd>

For development/testing, a locally built distribution can also be installed through:

<kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>

## How It Works

1. Place the caret inside a supported MyBatis XML statement tag or supported Java statement annotation method.
2. Right-click and choose **Execute (zMyBatis)**.
3. The plugin extracts the current mapper statement source, heuristically discovers required inputs, evaluates the supported dynamic-SQL path, and converts mapped values into zMyBatis literal SQL.
4. Depending on settings, the SQL may be formatted and/or previewed before it is sent to a JetBrains Database Tools JDBC console.
5. Target reuse/restoration follows the hardened datasource/schema identity rules described above; ambiguous restoration is rejected.

## Product Contract

The current and target capability/safety matrix is maintained in [docs/product-contract.md](./docs/product-contract.md). It is the policy source for Leap Epic #60 / Track #61 and explicitly distinguishes supported, unsupported, compatibility-altered, degraded, and unknown behavior.

## Distribution

- **Canonical source:** https://github.com/luceat-lux-vestra/zMyBatis
- **JetBrains Marketplace:** published.
- **GitHub Releases:** no public release artifacts are currently published from this repository.

## License

zMyBatis is licensed under the [Apache License 2.0](./LICENSE).

## Changelog

See [CHANGELOG.md](./CHANGELOG.md) for a detailed list of changes.

---

Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
