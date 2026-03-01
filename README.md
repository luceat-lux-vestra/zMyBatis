# zMyBatis-private

![Build](https://github.com/luceat-lux-vestra/zMyBatis-private/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

## Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [ ] Get familiar with the [template documentation][template].
- [ ] Adjust the [pluginGroup](./gradle.properties) and [pluginName](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml) and [sources package](./src/main/kotlin).
- [ ] Adjust the plugin description in `README` (see [Tips][docs:plugin-description])
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [ ] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.
- [ ] Configure the [CODECOV_TOKEN](https://docs.codecov.com/docs/quick-start) secret for automated test coverage reports on PRs

<!-- Plugin description -->
**zMyBatis** is a DataGrip / IntelliJ IDEA Ultimate plugin that lets you execute MyBatis mapper queries directly from your Java/Kotlin source or XML mapper files — without leaving the IDE.

### Features

- **One-click execution** — Press the DataGrip *Execute* shortcut while the caret is inside a MyBatis `<select>`, `<insert>`, `<update>`, or `<delete>` tag (XML mapper), or a `@Select` / `@Insert` / `@Update` / `@Delete` annotation (Java).
- **Dynamic SQL evaluation** — Fully evaluates MyBatis dynamic SQL tags: `<if>`, `<choose>`, `<when>`, `<otherwise>`, `<foreach>`, `<where>`, `<set>`, `<trim>`, and `<bind>`.
- **Parameter input dialog** — Automatically detects `#{param}` and OGNL parameters, and prompts you to enter values before execution. Supports `null`, numbers, strings, booleans, and lists (`[1,2,3]`).
- **Seamless DataGrip integration** — The resolved pure SQL is injected directly into the DataGrip execution pipeline, so all DataGrip features (result grid, export, explain plan, etc.) work as usual.
- **Annotation support** — Works with multi-line string arrays in `@Select({...})` and resolves constant field references.

### Requirements

- IntelliJ IDEA Ultimate or DataGrip **2025.3** or later
- Java plugin enabled
- A configured DataGrip data source / connection
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "zMyBatis-private"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/luceat-lux-vestra/zMyBatis-private/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
