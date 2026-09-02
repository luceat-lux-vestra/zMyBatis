# zMyBatis — Dynamic SQL Runner with Parameters

> **Distribution status:** zMyBatis is not yet published to JetBrains Marketplace and no public GitHub Release is available. Marketplace publication and repository consolidation are still in progress.

<!-- Plugin description -->
<p><b>zMyBatis</b> is a JetBrains IDE plugin that lets you execute MyBatis mapper queries directly from XML mapper files or supported Java annotation-based mappers — without leaving the IDE.</p>

<p>It evaluates supported MyBatis dynamic SQL, prompts you for parameter values, converts the result to executable SQL using zMyBatis-owned parameter/literalization rules, and runs it on a DataGrip database console in one step.</p>

<h2>Features</h2>

<h3>One-Click Execution</h3>
<p>Right-click → <b>Execute (zMyBatis)</b> while the caret is inside:</p>
<ul>
  <li>A MyBatis XML mapper statement tag (select, insert, update, delete)</li>
  <li>A supported @Select / @Insert / @Update / @Delete annotation method in Java</li>
</ul>
<p>Kotlin annotation-source support is not currently claimed without fresh compatibility evidence.</p>
<p>The plugin evaluates the supported MyBatis dynamic SQL and runs the resulting SQL through the DataGrip execution pipeline. DataGrip's own Execute and Explain Plan actions are untouched and work as usual.</p>

<h3>Dynamic SQL Evaluation</h3>
<p>Uses MyBatis <code>XMLScriptBuilder</code> for supported dynamic SQL tags including:<br/>
<code>if</code>, <code>choose</code> / <code>when</code> / <code>otherwise</code>, <code>foreach</code>, <code>where</code>, <code>set</code>, <code>trim</code>, <code>bind</code></p>
<p>zMyBatis also owns parameter discovery, compatibility transformations, OGNL handling around that engine, and conversion of MyBatis parameter mappings to literal SQL. Using MyBatis for parsing therefore does <b>not</b> imply stock JDBC/TypeHandler semantics or arbitrary application-runtime parity.</p>

<h3>Parameter Input Dialog</h3>
<ul>
  <li>Detects <code>#{param}</code>, <code>${param}</code>, and OGNL-driven inputs within the currently supported extraction rules</li>
  <li>Filters known internal bind/foreach variables before prompting; generated-name and application-specific edge cases remain part of the active compatibility work</li>
  <li><b>Scalar parameters</b> — one-line input: null, numbers, strings, booleans, lists (e.g. <code>[1,2,3]</code>)</li>
  <li><b>Object / nested parameters</b> — multi-line JSON editor for dot-notation params (e.g. <code>#{user.name}</code> -&gt; enter <code>{"name":"Alice","id":1}</code>)</li>
  <li>Supports nested objects (<code>{key:{nestedKey:value}}</code>), and object arrays (<code>[{key:value},{key:value}]</code>)</li>
  <li>JSON validation on object parameters before execution</li>
</ul>

<h3>Data Source and Schema Selection</h3>
<p>On first execution from a mapper file, a popup lets you choose the target <b>data source</b> and <b>schema</b>. The selection is cached per mapper file and automatically restored across IDE restarts.</p>

<h3>Annotation Support</h3>
<ul>
  <li>Works with supported Java annotation SQL, including multi-line string arrays in <code>@Select({...})</code></li>
  <li>Resolves supported constant field references (e.g. <code>@Select(SqlConstants.FIND_USER)</code>)</li>
  <li>Shows a clear notice for unsupported <code>@SelectProvider</code> / <code>@InsertProvider</code> / <code>@UpdateProvider</code> / <code>@DeleteProvider</code> annotations</li>
</ul>

<h3>Seamless DataGrip Integration</h3>
<p>The resolved SQL is injected into the DataGrip execution pipeline, so the normal database-console workflow remains available:</p>
<ul>
  <li>Result grid, export, explain plan</li>
  <li>SQL history and console tabs</li>
</ul>

<h2>Current Semantic Boundary</h2>
<p>The current implementation combines MyBatis parsing with zMyBatis-owned parameter extraction, compatibility transformations, OGNL behavior, and literal rendering. Until the active evaluation/execution contract work is complete, do not interpret this plugin as a drop-in reproduction of an application's MyBatis/JDBC runtime, custom TypeHandlers, provider methods, or every dialect-specific binding rule.</p>

<h2>Settings</h2>
<p>Configure via <b>Settings -> Tools -> zMyBatis</b>:</p>

<table>
  <tr><th>Category</th><th>Option</th><th>Description</th></tr>
  <tr><td><b>Execution and Output</b></td><td>SQL Preview</td><td>Show a preview dialog to review resolved SQL before execution</td></tr>
  <tr><td></td><td>Auto-format SQL</td><td>Reformat resolved SQL using IntelliJ's built-in SQL code style</td></tr>
  <tr><td></td><td>Copy to Clipboard</td><td>Auto-copy the final SQL to clipboard after execution</td></tr>
  <tr><td></td><td>Console Session Policy</td><td>REUSE (default) — reuse existing console per mapper file / NEW_EACH — always open a new console</td></tr>
  <tr><td><b>Parameter Dialog</b></td><td>Remember Last Inputs</td><td>Pre-fill the parameter dialog with last-used values per Mapper statement</td></tr>
  <tr><td></td><td>Empty Input Handling</td><td>NULL (default) — blank fields bind as SQL NULL / EMPTY_STRING — blank fields bind as empty string</td></tr>
  <tr><td><b>Parsing Engine</b></td><td>Strict OGNL Mode</td><td>Propagate OGNL evaluation errors immediately instead of silently skipping blocks</td></tr>
  <tr><td></td><td>Ignore Unknown Tags</td><td>Strip unrecognised/custom XML tags before parsing (preserves their text content)</td></tr>
</table>

<h2>Requirements</h2>
<ul>
  <li><b>IntelliJ IDEA Ultimate</b>, <b>DataGrip</b>, or a compatible JetBrains IDE with database tooling</li>
  <li>IDE build line <b>2025.3</b> or later; the maintained automated verifier target is pinned separately and broader host/version claims require release evidence</li>
  <li>A configured data source (database connection) in the Database tool window</li>
</ul>
<!-- Plugin description end -->

## 🚀 Installation

zMyBatis is currently a development build. There is no Marketplace listing or public release artifact yet.

For development/testing, build the plugin from this repository and install the generated distribution through:

<kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>

Marketplace and public-release installation instructions will be added only after those channels are actually published.

## 🏗 How It Works

1. Place the caret inside a MyBatis XML statement tag or a supported Java `@Select` / `@Insert` / `@Update` / `@Delete` annotation method.
2. Right-click and choose **Execute (zMyBatis)**.
3. The plugin:
   - Extracts the supported mapper statement source
   - Discovers required inputs using zMyBatis-owned extraction rules
   - Opens the **Parameter Input Dialog** with previously used values pre-filled
   - Evaluates supported dynamic SQL through MyBatis `XMLScriptBuilder` plus zMyBatis compatibility handling
   - Converts the resulting parameter mappings to literal SQL using zMyBatis-owned rendering rules
   - Optionally formats the SQL and shows a preview
   - Injects the result into a DataGrip console and executes it
4. DataGrip's own Execute, Explain Plan, and all other actions are **not overridden** and continue to work normally.

## 📝 Changelog

See [CHANGELOG.md](./CHANGELOG.md) for a detailed list of changes.

---

Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-user-experience
