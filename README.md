# zMyBatis — Contract-driven MyBatis SQL execution for JetBrains IDEs

[![Build](https://github.com/luceat-lux-vestra/zMyBatis/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/luceat-lux-vestra/zMyBatis/actions/workflows/build.yml)
[![Marketplace Version](https://img.shields.io/jetbrains/plugin/v/com.algorist.zMyBatis)](https://plugins.jetbrains.com/plugin/index?xmlId=com.algorist.zMyBatis)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.algorist.zMyBatis)](https://plugins.jetbrains.com/plugin/index?xmlId=com.algorist.zMyBatis)
[![License](https://img.shields.io/github/license/luceat-lux-vestra/zMyBatis)](LICENSE)

> **Distribution status:** zMyBatis is published on [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30447-zmybatis--mybatis-dynamic-sql-runner-with-parameters-mybatis). This GitHub repository is the canonical public source repository. No public GitHub Release artifact is currently published.

<!-- Plugin description -->
<p><b>zMyBatis</b> is a JetBrains IDE plugin for executing supported MyBatis mapper statements directly from XML mapper files and direct Java statement annotations.</p>

<p>The registered action captures authoritative mapper source, derives a provenanced input contract, prepares supported dynamic SQL through isolated stock MyBatis semantics, creates one immutable SQL-text execution artifact only for explicitly proven materialization shapes, and dispatches that exact artifact through JetBrains Database Tools.</p>

<h2>Execution model</h2>
<ul>
  <li>Supported XML mapper statements and direct Java <code>@Select</code> / <code>@Insert</code> / <code>@Update</code> / <code>@Delete</code> annotations use one Leap execution authority.</li>
  <li><code>#{}</code> bound inputs and <code>${}</code> raw interpolation remain distinct end-to-end.</li>
  <li>Raw interpolation and mutation declarations require explicit final-artifact confirmation.</li>
  <li>Unsupported or ambiguous source, input, MyBatis, materialization, target, or execution cases fail closed instead of falling back to a legacy evaluator.</li>
  <li>Kotlin direct-annotation execution and Provider annotations are currently unsupported.</li>
</ul>

<h2>Input and MyBatis preparation</h2>
<p>Inputs come from typed/provenanced parameter contracts rather than regex-discovered names. The UI renders the contract, validates submitted values independently from Swing, and can optionally retain eligible inputs by canonical statement identity. Raw-interpolation values are never retained.</p>

<p>MyBatis is the semantic authority for admitted dynamic-SQL behavior. Source graphs, including supported <code>&lt;sql&gt;</code>/<code>&lt;include&gt;</code> dependencies, are captured and revalidated before execution. Missing, ambiguous, cyclic, stale, or otherwise unsupported authority is a typed refusal.</p>

<h2>Execution artifacts and Database Tools</h2>
<p>A prepared statement is executable only when zMyBatis has a proven SQL-text materializer for that shape. The DB-neutral zero-binding path and the maintained PostgreSQL BIGINT path have native Database Tools execution evidence; other nonzero dialect/type shapes remain fail-closed until separately proven.</p>

<p>Preview and native execution use the same immutable SQL artifact. Formatting is presentation-only and cannot modify the payload sent to Database Tools.</p>

<h2>Datasource and schema identity</h2>
<p>Restart persistence stores only current v3 source-to-target descriptors: canonical source identity, stable datasource UUID, and explicit named schema. Live consoles are ephemeral resources. Startup may prune stale v3 source associations but never recreates a console or executes SQL.</p>

<p><b>Use Default Schema</b> and datasources without a stable UUID are in-process only. Missing or ambiguous persisted targets require explicit re-selection rather than redirection.</p>

<h2>Settings</h2>
<p>Configure via <b>Settings → Tools → zMyBatis</b>:</p>
<table>
  <tr><th>Category</th><th>Option</th><th>Description</th></tr>
  <tr><td><b>Execution &amp; Output</b></td><td>SQL Preview</td><td>Optional preview for safe paths; mandatory mutation/raw confirmation overrides this setting.</td></tr>
  <tr><td></td><td>Auto-format SQL Preview</td><td>Formatting affects presentation only, never the immutable execution artifact.</td></tr>
  <tr><td></td><td>Console Session Policy</td><td>REUSE a matching live console or acquire NEW_EACH; this is a resource policy, not persisted target identity.</td></tr>
  <tr><td><b>Parameter Dialog</b></td><td>Remember Last Inputs</td><td>Opt-in retention for eligible contract inputs keyed by canonical statement identity; raw values are excluded.</td></tr>
</table>

<h2>Compatibility evidence</h2>
<ul>
  <li><b>Declared minimum IDE build:</b> 262 (2026.2 line)</li>
  <li><b>Maintained Plugin Verifier targets:</b> IntelliJ IDEA Ultimate 2026.2.3 and 2026.2</li>
  <li>JetBrains Database Tools (<code>com.intellij.database</code>) and a configured datasource are required for execution.</li>
  <li><b>DataGrip:</b> remains a separately maintained host-evidence obligation; IDEA evidence is not automatically generalized to DataGrip.</li>
</ul>

<p>The authoritative capability matrix and fail-closed boundaries are maintained in <a href="https://github.com/luceat-lux-vestra/zMyBatis/blob/main/docs/product-contract.md">docs/product-contract.md</a>.</p>
<!-- Plugin description end -->

## Installation

Install zMyBatis from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30447-zmybatis--mybatis-dynamic-sql-runner-with-parameters-mybatis):

<kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd> → search for <kbd>zMyBatis</kbd> → <kbd>Install</kbd>

For development/testing, a locally built distribution can also be installed through:

<kbd>Settings/Preferences</kbd> → <kbd>Plugins</kbd> → <kbd>⚙️</kbd> → <kbd>Install plugin from disk…</kbd>

## How it works

1. Place the caret inside a supported XML mapper statement or direct Java statement annotation.
2. Invoke **Execute (zMyBatis)**.
3. zMyBatis captures and revalidates the source/statement authority and exact datasource/schema target.
4. The contract-driven input UI collects required values; cancellation terminates the invocation.
5. Isolated MyBatis preparation produces structured SQL/mapping evidence.
6. A supported materializer freezes one target-bound SQL artifact; unsupported shapes fail closed.
7. Required approval is shown for raw interpolation/mutations, then the exact approved artifact is executed through Database Tools.

## Engineering documentation

- [Product contract](./docs/product-contract.md) — supported/unsupported capability and safety policy
- [Leap architecture](./docs/leap-architecture.md) — current architecture and ownership boundaries
- [Test contracts](./docs/test-contracts.md) — what automated evidence does and does not prove
- [Starter / Driver E2E](./docs/e2e-testing.md) — process-level applicability and maintained scenarios
- [Session persistence](./docs/session-persistence.md) — v3 datasource/schema persistence contract
- [Release recovery](./docs/release-recovery.md) — fail-closed Marketplace recovery procedure
- [Contributing](./CONTRIBUTING.md) and [Security](./SECURITY.md) — repository mechanics and vulnerability reporting

## Distribution

- **Canonical source:** https://github.com/luceat-lux-vestra/zMyBatis
- **JetBrains Marketplace:** published.
- **GitHub Releases:** no public release artifacts are currently published from this repository.

## License

zMyBatis is licensed under the [Apache License 2.0](./LICENSE).

## Changelog

See [CHANGELOG.md](./CHANGELOG.md) for historical changes.

---

Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
