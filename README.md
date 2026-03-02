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

## 🛠 To-Do: Configuration & Options
사용자 경험 개선을 위한 설정 메뉴(`Settings > Tools > zMyBatis`) 추가 개발 목록입니다.

### 1. 실행 및 결과 제어 (Execution & Output)
- [x] **Generate Configurable UI**: IntelliJ 설정 창에 플러그인 설정 페이지 UI 생성 (`Settings → Tools → zMyBatis`)
- [x] **SQL Preview Option**: `[Checkbox]` 변환된 Native SQL을 바로 실행하지 않고 미리보기 창을 띄울지 여부 (`SqlPreviewDialog`)
- [x] **Auto Format SQL**: `[Checkbox]` 실행 전 변환된 SQL을 포맷팅 (`SqlFormatter`)
- [x] **Console Session Policy**: `[Select]` DB 콘솔 세션 처리 방식 (`REUSE` vs `NEW_EACH`)

### 2. 파라미터 입력 편의성 (Parameter Dialog)
- [x] **Persist State Component**: 마지막 입력값을 저장하기 위한 IntelliJ 영속성(State Persistence) 서비스 구현 (`ZMyBatisSettings`, `ParameterHistoryService`)
- [x] **Remember Last Inputs**: `[Checkbox]` 동일한 Mapper ID 실행 시 직전 입력값 자동 바인딩 기능
- [x] **Empty String Handling**: `[Select]` 입력란 공란 처리 정책 (빈 문자열 `""` vs `NULL`)
- [ ] **List Delimiter**: `[Input]` `<foreach>` 바인딩을 위한 리스트 입력 구분자 설정 (기본값 `,`)

### 3. 파싱 엔진 설정 (Parsing Engine)
- [ ] **Strict OGNL Mode**: `[Checkbox]` OGNL 표현식 엄격 모드 활성화/비활성화 (Type Safety 관련)
- [ ] **Ignore Unknown Tags**: `[Checkbox]` 비표준/커스텀 태그 만났을 때 에러 무시하고 텍스트로 렌더링하도록 처리

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
