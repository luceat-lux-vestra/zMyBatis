# zMyBatis — Dynamic SQL Runner with Parameters

![Build](https://github.com/luceat-lux-vestra/zMyBatis-public/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

> **Note:** Replace MARKETPLACE_ID in the badge URLs above with the actual plugin ID after publishing to JetBrains Marketplace.

<!-- Plugin description -->
<p><b>zMyBatis</b> is a JetBrains IDE plugin that lets you execute MyBatis mapper queries directly from XML mapper files or Java/Kotlin annotation-based mappers — without leaving the IDE.</p>

<p>It evaluates MyBatis dynamic SQL, prompts you for parameter values, converts the result to native SQL, and runs it on a DataGrip database console in one step.</p>

<h2>Features</h2>

<h3>One-Click Execution</h3>
<p>Press the DataGrip <b>Execute</b> shortcut (<code>Ctrl+Enter</code> / <code>Cmd+Enter</code>) while the caret is inside:</p>
<ul>
  <li>A MyBatis XML mapper statement tag (select, insert, update, delete)</li>
  <li>A @Select / @Insert / @Update / @Delete annotation method (Java/Kotlin)</li>
</ul>
<p>The plugin intercepts the Execute (and Explain Plan) actions and seamlessly redirects them through the MyBatis evaluation pipeline.</p>

<h3>Dynamic SQL Evaluation</h3>
<p>Fully evaluates all MyBatis dynamic SQL tags:<br/>
<code>if</code>, <code>choose</code> / <code>when</code> / <code>otherwise</code>, <code>foreach</code>, <code>where</code>, <code>set</code>, <code>trim</code>, <code>bind</code></p>
<p>Powered by the actual MyBatis XMLScriptBuilder engine (mybatis 3.5.x) for accurate results.</p>

<h3>Parameter Input Dialog</h3>
<ul>
  <li>Automatically detects <code>#{param}</code>, <code>${param}</code>, and OGNL expression parameters</li>
  <li>Excludes internal variables (bind, foreach item, foreach index) — only user-supplied parameters are prompted</li>
  <li><b>Scalar parameters</b> — one-line input: null, numbers, strings, booleans, lists (e.g. <code>[1,2,3]</code>)</li>
  <li><b>Object / nested parameters</b> — multi-line JSON editor for dot-notation params (e.g. <code>#{user.name}</code> -&gt; enter <code>{"name":"Alice","id":1}</code>)</li>
  <li>Supports nested objects (<code>{key:{nestedKey:value}}</code>), and object arrays (<code>[{key:value},{key:value}]</code>)</li>
  <li>JSON validation on object parameters before execution</li>
</ul>

<h3>Data Source and Schema Selection</h3>
<p>On first execution from a mapper file, a popup lets you choose the target <b>data source</b> and <b>schema</b>. The selection is cached per mapper file and automatically restored across IDE restarts.</p>

<h3>Annotation Support</h3>
<ul>
  <li>Works with multi-line string arrays in <code>@Select({...})</code></li>
  <li>Resolves constant field references (e.g. <code>@Select(SqlConstants.FIND_USER)</code>)</li>
  <li>Shows a clear notice for unsupported <code>@SelectProvider</code> / <code>@InsertProvider</code> / <code>@UpdateProvider</code> / <code>@DeleteProvider</code> annotations</li>
</ul>

<h3>Seamless DataGrip Integration</h3>
<p>The resolved native SQL is injected directly into the DataGrip execution pipeline, so all DataGrip features work as usual:</p>
<ul>
  <li>Result grid, export, explain plan</li>
  <li>SQL history and console tabs</li>
</ul>

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
  <li><b>IntelliJ IDEA Ultimate</b>, <b>DataGrip</b>, or any JetBrains IDE with the <b>Database</b> plugin</li>
  <li>IDE version <b>2025.3</b> or later</li>
  <li>A configured data source (database connection) in the Database tool window</li>
</ul>
<!-- Plugin description end -->

## 🚀 Installation

- **From the IDE:**

  <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → Search for **"zMyBatis"** → <kbd>Install</kbd>

- **From JetBrains Marketplace:**

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and click <kbd>Install to …</kbd>.

- **Manual install:**

  Download the [latest release](https://github.com/luceat-lux-vestra/zMyBatis-public/releases/latest) and install via
  <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>

## 🏗 How It Works

1. The plugin registers a ProjectActivity that intercepts DataGrip's **Execute** and **Explain Plan** actions at IDE startup.
2. When triggered inside a MyBatis context (XML tag or annotation), the interceptor:
   - Extracts the full MyBatis statement (XML body or annotation SQL string)
   - Parses #{…}, ${…}, and OGNL expressions to identify required parameters
   - Opens the **Parameter Input Dialog** with previously used values pre-filled
   - Evaluates dynamic SQL through the MyBatis XMLScriptBuilder engine
   - Binds parameter values into native SQL (replacing ? placeholders with literals)
   - Optionally formats the SQL and shows a preview
   - Injects the result into a DataGrip console and executes it
3. When triggered outside a MyBatis context, the original action is passed through unchanged.

## 📝 Changelog

=======
**zMyBatis** is a JetBrains IDE plugin that lets you execute MyBatis mapper queries directly from XML mapper files or Java/Kotlin annotation-based mappers — without leaving the IDE.

It evaluates MyBatis dynamic SQL, prompts you for parameter values, converts the result to native SQL, and runs it on a DataGrip database console in one step.

## ✨ Features

### One-Click Execution
Press the DataGrip **Execute** shortcut (<kbd>Ctrl+Enter</kbd> / <kbd>⌘+Enter</kbd>) while the caret is inside:
- A MyBatis XML mapper statement tag (select, insert, update, delete)
- A @Select / @Insert / @Update / @Delete annotation method (Java/Kotlin)

The plugin intercepts the Execute (and Explain Plan) actions and seamlessly redirects them through the MyBatis evaluation pipeline.

### Dynamic SQL Evaluation
Fully evaluates all MyBatis dynamic SQL tags:
if, choose / when / otherwise, foreach, where, set, trim, bind

Powered by the actual MyBatis XMLScriptBuilder engine (mybatis 3.5.x) for accurate results.

### Parameter Input Dialog
- Automatically detects #{param}, ${param}, and OGNL expression parameters
- Excludes internal variables (bind, foreach item, foreach index) — only user-supplied parameters are prompted
- **Scalar parameters** — one-line input: null, numbers, strings, booleans, lists (e.g. [1,2,3])
- **Object / nested parameters** — multi-line JSON editor for dot-notation params (e.g. #{user.name} → enter {"name":"Alice","id":1})
- Supports nested objects ({key:{nestedKey:value}}), and object arrays ([{key:value},{key:value}])
- JSON validation on object parameters before execution

### Data Source & Schema Selection
On first execution from a mapper file, a popup lets you choose the target **data source** and **schema**. The selection is cached per mapper file and automatically restored across IDE restarts.

### Annotation Support
- Works with multi-line string arrays in @Select({...})
- Resolves constant field references (e.g. @Select(SqlConstants.FIND_USER))
- Shows a clear notice for unsupported @SelectProvider / @InsertProvider / @UpdateProvider / @DeleteProvider annotations

### Seamless DataGrip Integration
The resolved native SQL is injected directly into the DataGrip execution pipeline, so all DataGrip features work as usual:
- Result grid, export, explain plan
- SQL history & console tabs

## ⚙️ Settings

Configure via **Settings → Tools → zMyBatis**:

| Category | Option | Description |
|---|---|---|
| **Execution & Output** | SQL Preview | Show a preview dialog to review resolved SQL before execution |
| | Auto-format SQL | Reformat resolved SQL using IntelliJ's built-in SQL code style |
| | Copy to Clipboard | Auto-copy the final SQL to clipboard after execution |
| | Console Session Policy | REUSE (default) — reuse existing console per mapper file / NEW_EACH — always open a new console |
| **Parameter Dialog** | Remember Last Inputs | Pre-fill the parameter dialog with last-used values per Mapper statement |
| | Empty Input Handling | NULL (default) — blank fields bind as SQL NULL / EMPTY_STRING — blank fields bind as empty string |
| **Parsing Engine** | Strict OGNL Mode | Propagate OGNL evaluation errors immediately instead of silently skipping blocks |
| | Ignore Unknown Tags | Strip unrecognised/custom XML tags before parsing (preserves their text content) |

## 📋 Requirements

- **IntelliJ IDEA Ultimate**, **DataGrip**, or any JetBrains IDE with the **Database** plugin
- IDE version **2025.3** or later
- A configured data source (database connection) in the Database tool window
<!-- Plugin description end -->

## 🚀 Installation

- **From the IDE:**

  <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → Search for **"zMyBatis"** → <kbd>Install</kbd>

- **From JetBrains Marketplace:**

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and click <kbd>Install to …</kbd>.

- **Manual install:**

  Download the [latest release](https://github.com/luceat-lux-vestra/zMyBatis-public/releases/latest) and install via
  <kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>

## 🏗 How It Works

1. The plugin registers a ProjectActivity that intercepts DataGrip's **Execute** and **Explain Plan** actions at IDE startup.
2. When triggered inside a MyBatis context (XML tag or annotation), the interceptor:
   - Extracts the full MyBatis statement (XML body or annotation SQL string)
   - Parses #{…}, ${…}, and OGNL expressions to identify required parameters
   - Opens the **Parameter Input Dialog** with previously used values pre-filled
   - Evaluates dynamic SQL through the MyBatis XMLScriptBuilder engine
   - Binds parameter values into native SQL (replacing ? placeholders with literals)
   - Optionally formats the SQL and shows a preview
   - Injects the result into a DataGrip console and executes it
3. When triggered outside a MyBatis context, the original action is passed through unchanged.

## 📝 Changelog

See [CHANGELOG.md](./CHANGELOG.md) for a detailed list of changes.

---

Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
