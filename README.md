# zMyBatis — Contract-driven MyBatis SQL execution for JetBrains IDEs

[![GitHub Stars](https://img.shields.io/github/stars/luceat-lux-vestra/zMyBatis?style=flat&label=Stars)](https://www.star-history.com/?repos=luceat-lux-vestra%2FzMyBatis&type=date)
[![Build](https://github.com/luceat-lux-vestra/zMyBatis/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/luceat-lux-vestra/zMyBatis/actions/workflows/build.yml)
[![Marketplace Version](https://img.shields.io/jetbrains/plugin/v/com.algorist.zMyBatis)](https://plugins.jetbrains.com/plugin/index?xmlId=com.algorist.zMyBatis)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.algorist.zMyBatis)](https://plugins.jetbrains.com/plugin/index?xmlId=com.algorist.zMyBatis)
[![License](https://img.shields.io/github/license/luceat-lux-vestra/zMyBatis)](LICENSE)

> **Distribution status:** zMyBatis is published on [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30447-zmybatis--mybatis-dynamic-sql-runner-with-parameters-mybatis). This GitHub repository is the canonical public source repository. No public GitHub Release artifact is currently published.

> **Pre-1.0 development baseline:** the registered execution path uses the
> [zMyBatis 1.0 MyBatis-compatibility architecture](docs/mybatis-compatibility-architecture.md):
> stock MyBatis owns preparation semantics, one immutable `PreparedExecutionPlan` is approved and
> revalidated, supported bound plans are materialized into one exact executable SQL artifact, and
> both bound and zero-binding statements are dispatched through the native Database Tools console/result
> surface. Unsupported materialization shapes fail closed; the deleted legacy evaluator is not restored.

<!-- Plugin description -->
<p><b>zMyBatis</b> is a JetBrains IDE plugin for executing supported MyBatis mapper statements directly from XML mapper files and direct Java statement annotations.</p>

<p>The registered action captures authoritative mapper source, derives caller-authoritative input, prepares dynamic SQL through isolated stock MyBatis semantics, freezes one immutable <code>PreparedExecutionPlan</code>, materializes only explicitly supported bound values into the exact approved executable SQL artifact, revalidates source/target/lifecycle authority, and dispatches through the native Database Tools console/result path.</p>

<h2>Execution model</h2>
<ul>
  <li>Supported XML mapper statements and direct Java <code>@Select</code> / <code>@Insert</code> / <code>@Update</code> / <code>@Delete</code> annotations use one Leap execution authority.</li>
  <li><code>#{}</code> bound inputs and <code>${}</code> raw interpolation remain distinct end-to-end.</li>
  <li>Raw interpolation and mutation declarations require explicit final-artifact confirmation.</li>
  <li>Unsupported or ambiguous source, input, MyBatis runtime capability, target, binding/materialization, or execution cases fail closed; there is no silent fallback to the retired prepared-result modal or legacy evaluator.</li>
  <li>Kotlin direct-annotation execution and Provider annotations are currently unsupported.</li>
</ul>

<h2>Input and MyBatis preparation</h2>
<p>Inputs come from typed/provenanced parameter contracts rather than regex-discovered names. The UI renders the contract, validates submitted values independently from Swing, and can optionally retain eligible inputs by canonical statement identity. Raw-interpolation values are never retained.</p>

<p>MyBatis is the semantic authority for admitted dynamic-SQL behavior. Source graphs, including supported <code>&lt;sql&gt;</code>/<code>&lt;include&gt;</code> dependencies, are captured and revalidated before execution. Missing, ambiguous, cyclic, stale, or otherwise unsupported authority is a typed refusal.</p>

<h2>Execution plans and Database Tools</h2>
<p>Stock MyBatis preparation is frozen into one immutable <code>PreparedExecutionPlan</code>. Zero-binding plans remain exact SQL text. Supported PostgreSQL bound plans use ordered MyBatis binding metadata to produce one explicit, tested executable SQL artifact; unsupported type/dialect/value shapes fail typed rather than switching execution backends.</p>

<p>Preview and execution refer to the same immutable plan identity and materialized artifact. With SQL Preview disabled, an ordinary read executes directly after input collection. When preview is enabled—or required for mutation/raw interpolation—the dialog shows the exact executable SQL plus binding metadata; formatting is presentation-only. Database Tools owns the resulting native Services/result grid and history.</p>

<h2>Datasource and schema identity</h2>
<p>Restart persistence stores only current v3 source-to-target descriptors: canonical source identity, stable datasource UUID, and explicit named schema. Live consoles are ephemeral resources. Startup may prune stale v3 source associations but never recreates a console or executes SQL.</p>

<p><b>Use Default Schema</b> and datasources without a stable UUID are in-process only. Missing or ambiguous persisted targets require explicit re-selection rather than redirection.</p>

<h2>Settings</h2>
<p>Configure via <b>Settings → Tools → zMyBatis</b>:</p>
<table>
  <tr><th>Category</th><th>Option</th><th>Description</th></tr>
  <tr><td><b>Execution &amp; Output</b></td><td>SQL Preview</td><td>Optional preview for safe paths; mandatory mutation/raw confirmation overrides this setting.</td></tr>
  <tr><td></td><td>Auto-format SQL Preview</td><td>Formatting affects presentation only, never the immutable execution artifact.</td></tr>
  <tr><td><b>Parameter Dialog</b></td><td>XML-only input</td><td>When no mapper method authority is available, source-proven dotted paths are offered as convenient Fields alongside an editable generated JSON object; both submit the same XML parameter object.</td></tr>
  <tr><td><b>Parameter Dialog</b></td><td>Remember Last Inputs</td><td>Opt-in retention for eligible contract inputs keyed by canonical statement identity; raw values are excluded.</td></tr>
</table>

<h2>Compatibility evidence</h2>
<ul>
  <li><b>Declared minimum IDE build:</b> 263 (2026.3 line)</li>
  <li><b>Configured compile/test SDK:</b> IntelliJ IDEA Ultimate 2026.3 (IU-263.7307.200); 2026.3 support requires exact-head evidence from issue #521.</li>
  <li><b>Configured Plugin Verifier targets:</b> IntelliJ IDEA Ultimate 2026.3 and DataGrip 2026.3, each requiring independent exact-HEAD verifier and process E2E evidence. 2026.2 is no longer supported; verifier success does not establish full GUI/runtime coverage.</li>
  <li>JetBrains Database Tools (<code>com.intellij.database</code>) and a configured datasource are required for execution.</li>
  <li><b>Runtime host evidence:</b> IDEA Ultimate retains the full maintained PostgreSQL execution suite. DataGrip independently proves packaged-plugin/service loading, typed XML no-datasource refusal, and one bounded XML prepared-PostgreSQL route through the Database Tools session backend. DataGrip evidence does not imply Java-annotation parity or native Database Tools result/history parity.</li>
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
5. Isolated stock MyBatis preparation produces structured SQL/mapping evidence.
6. zMyBatis freezes one target-bound `PreparedExecutionPlan` with ordered bindings and safety metadata.
7. Required approval is shown for raw interpolation/mutations; after immediate source/target/lifecycle revalidation, bound plans execute prepared and zero-binding/exact-text plans execute through the native Database Tools backend.

## Engineering documentation

- [Product contract](./docs/product-contract.md) — supported/unsupported capability and safety policy
- [Leap architecture](./docs/leap-architecture.md) — current architecture and ownership boundaries
- [Test contracts](./docs/test-contracts.md) — what automated evidence does and does not prove
- [Starter / Driver E2E](./docs/e2e-testing.md) — process-level applicability and maintained scenarios
- [Release acceptance fixture](./samples/manual-release-validation/README.md) — automated acceptance inventory plus minimal exact-ZIP human UX smoke
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
