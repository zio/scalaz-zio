---
id: contributing-to-zio-ecosystem
slug: contributing-to-zio-ecosystem
title: "Contributing to the ZIO Ecosystem Projects"
---

The ZIO ecosystem is provided by a worldwide community, just like the project itself. So if you are reading this page, you can help us to improve the ecosystem.

Please read the [Contributor Guideline](contributor-guidelines.md) before contributing to the ecosystem.

## Branching Naming Convention

Until now, the [ZIO core library](https://github.com/zio/zio) has undergone two major releases: 1.x and 2.x. Therefore, the ZIO ecosystem projects may maintain two branches: `series/1.x` and `series/2.x`, with the default branch typically set to `series/2.x`. The `series/1.x` branch accomodates releases compatible with ZIO 1.x, while the `series/2.x` branch is designated for releases compatible with ZIO 2.x.

When considering a contribution to a ZIO ecosystem project, it's essential to determine the targeted ZIO version beforehand. Once identified, we can proceed to checkout the corresponding branch, make changes, and submit a pull request against that specific branch.

## Documentation

The documentation for official libraries is also part of the ZIO documentation. However, the source code for their documentation is not housed within the [ZIO repository](https://github.com/zio/zio). Instead, they are located in the `docs` directory of each library repository.

As an example, the documentation for the ZIO Prelude library, accessible at [https://zio.dev/zio-prelude](https://zio.dev/zio-prelude), can be found within its respective respository at [https://github.com/zio/zio-prelude/tree/series/2.x/docs](https://github.com/zio/zio-prelude/tree/series/2.x/docs).

Each official ZIO project maintains its dedicated documentation project, which is published with every release to the npm registry under the [@zio.dev](https://www.npmjs.com/package/@zio.dev/) organization. Subsequently, these documentation projects are seamlessly integrated into the [ZIO Website](https://zio.dev) during the CI process of [ZIO core](https://github.com/zio/zio) repository.

## Documentation Structure

The base minimum structure of the documentation source code under the `docs` directory of each library repository is as follows:

```shell
~/zio-xyz > tree docs
docs
├── index.md
├── package.json
└── sidebars.js
```

Now, let's delve into each of them.

### The `package.json` File

Each library's documentation source code is a NPM project that will be published to the NPM registry. So the `package.json` file is required. The file is used to specify the name, description, and license of the documentation project. Example:

```json
{
  "name": "@zio.dev/zio-prelude",
  "description": "ZIO Prelude Documentation",
  "license": "Apache-2.0"
}
```

That is, the name of the documentation project must be `@zio.dev/<library-name>`, and the description and license must be the same as the library. All official libraries will be published under the [`@zio.dev`](https://www.npmjs.com/package/@zio.dev/) scope on the npm registry. For example, the ZIO Prelude's documentation package will be published at [https://www.npmjs.com/package/@zio.dev/zio-prelude](https://www.npmjs.com/package/@zio.dev/zio-prelude). We will discuss more about this on the CI section.

### The `index.md` File

The `index.md` file serves as the main page of the documentation, providing a description of the library and its features. The structure of this file is as follows:

```scala mdoc:passthrough
println(
  """
    |```markdown
    |---
    |id: index
    |title: "Introduction to ZIO XYZ"
    |sidebar_title: "ZIO XYZ"
    |---
    |
    |ZIO XYZ is a ZIO Scala library that ...
    |
    |@‎PROJECT_BADGES@
    |
    |## Introduction
    |
    |Key features of ZIO XYZ are:
    |  - Feature 1
    |  - Feature 2
    |  - ...
    |
    |## Installation
    |
    |In order to use this library, we need to add the following line in our `build.sbt` file:
    |
    |‎`‎`‎`‎scala
    |libraryDependencies += "dev.zio" %% "zio-xyz" % "@‎VERSION@"
    |`‎`‎`‎
    |
    |## Example
    |
    |Let's see an example of how to use this library:
    |
    |....
    |```
    |""".stripMargin)
```

Inside the `index.md` file, the `sidebar_title` field is used to set the name of the library in the sidebar of the documentation website on [this page](ecosystem/officials/index.md). Therefore, make sure to set it at the top of the `docs/index.md` file.

Also, we can use `@‎PROJECT_BADGES@` placeholder to add badges to the documentation. The `@‎PROJECT_BADGES@` placeholder will be replaced with the badges of the library. Also, the `@‎VERSION@` placeholder will be replaced with the latest version of the library.

The `index.md` file is the primary source for generating the `README.md` file of the library. Further details on this process will be discussed in the [ZIO SBT Website Plugin](#zio-sbt-website-plugin) section.

### The `sidebars.js` File

Inside the `sidebars.js` file, we can specify the documentation's structure, including the order of pages, the arrangement of sections within each page, and the introduction of sections and subsections. The structure of this file is as follows:

```js
const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO XYZ", // Project Name
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [ ]
    }
  ]
};

module.exports = sidebars;
```

To add a new page to the documentation, in addition to the `index.md` page, we can include a new item in the `items` array. For instance, to add a new page with the id `getting-started`, we can do the following:

```js
const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO XYZ", // Project Name
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [ ]
    }
  ]
};

module.exports = sidebars;
```

To introduce a new section, simply append a new object to the `items` array. Set the `type` field to `category`, the `label` field to the desired section name, the `link` field to the main page's ID corresponding to the section, and finally, populate the `items` field with a list of pages to be included in that section:

```js
const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO XYZ", // Project Name
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        `installation`,
        `getting-started`,
        `basics`,
        {
          type: "category",
          label: "Examples",
          collapsed: false,
          link: { type: "doc", id: "examples/index" },
          items: [
            `examples/example-1`,
            `examples/example-2`
          ]
        }
      ]
    }
  ]
};

module.exports = sidebars;
```

In the above example, we have introduced a new section called `Examples` which refers to the `examples/index.md` page. Additionally, we have added two pages to this section.

To learn more about the `sidebars.js` file, please refer to the [Docusaurus documentation](https://docusaurus.io/docs/sidebar).

## ZIO SBT Website Plugin

After preparing the documentation source code inside the `docs` directory of the library repository, we are ready to compile the documentation. To do this, we need to install the `zio-sbt-website` plugin.

With this plugin, we gain the capability to enhance our documentation in several ways:

1. **Ensure Type-Checked Code Snippets:** Leverage the power of the [`mdoc`](https://scalameta.org/mdoc/) tool to include type-checked code snippets seamlessly within our documentation.
2. **Effortless README.md Generation:** Streamline our documentation process by generating the `README.md` file automatically from the content inside `docs/index.md` file.
3. **Simplified Documentation Deployment:** Install and preview our documentation website effortlessly.

### Installing the Plugin

To install the plugin, we need to add the following line to the `project/plugins.sbt` file of the library repository:

```scala
// replace <version> with the latest version currently 0.4.0-alpha.22
addSbtPlugin("dev.zio" % "zio-sbt-website" % "<version>") 
```

To enable the plugin, we suggest adding new submodule to the `build.sbt` file called `docs` and then configuring the plugin for that submodule:

```scala
lazy val root = (project in file("."))
  .settings(publish / skip := true)
  .aggregate(core, docs)

lazy val core = (project in file("zio-xyz"))

lazy val docs = (project in file("zio-xyz-docs"))
  .enablePlugins(WebsitePlugin)
  .settings(projectName := "ZIO XYZ")
  .settings(mainModuleName := (core / moduleName).value)
  .settings(projectStage := ProjectStage.ProductionReady)
```

Let's explain the above settings:

- `projectName`: This setting is used to set the name of the documentation project.
- `mainModuleName`: This setting is used to specify the name of the main module of the library. It is employed to create the `@‎PROJECT_BADGES@` placeholder in the `docs/index.md` file.
- `projectStage`: This setting is used to determine the stage of the documentation project. Possible values are `ProductionReady`, `Development`, `Experimental`, `Research`, `Concept`, and `Deprecated`. It is utilized to create the `@‎PROJECT_STAGE@` placeholder in the `docs/index.md` file. To learn more about these stages, please refer to [this page](https://github.com/zio#project-status).

### Configuring Readme Settings

After installing the plugin, we can generate the `README.md` file by running the `sbt docs/generateReadme` command. This command will generate the `README.md` file from the `docs/index.md` file and place it in the root directory of the repository.

:::note
As a contributor to a well-established official library, you don't need to update the `README.md` file every time you make changes to the `docs/index.md` file. The CI of the ZIO repository will take care of this for you. After merging any pull request that has changes to the `docs/index.md` file, the CI of the ZIO repository will automatically create a new pull request to update the `README.md` file. We will discuss this later.
:::

There are some sbt settings used to generate the `README.md` file, and they have default values. However, if you want to customize them, you can do so. These settings are as follows:

- `readmeBanner`
- `readmeDocumentation`
- `readmeContribution`
- `readmeCodeOfConduct`
- `readmeSupport`
- `readmeMaintainers`
- `readmeCredits`
- `readmeAcknowledgement`
- `readmeLicense`

For example, to change the credits section, we can add the following setting to the `build.sbt` file:

```scala
lazy val docs = (project in file("zio-xyz-docs"))
  .enablePlugins(WebsitePlugin)
  .settings(projectName := "ZIO XYZ")
  .settings(mainModuleName := (core / moduleName).value)
  .settings(projectStage := ProjectStage.ProductionReady)
  .settings(
    readmeCredits :=
      """
        |The creation of this library is deeply influenced by the exploration and  
        | implementation conducted at ABC corporation.""".stripMargin,
  )
```

### Live Preview of Documentation Website

Before creating pull requests for the documentation of an official library, it is advisable to check if everything is fine, especially when there are numerous changes to the documentation. To do so, we can locally build the documentation by following these steps:

1. Install the local website using the `sbt docs/installWebsite` command.
2. Run the `sbt docs/previewWebsite` command to serve the website locally.
3. Open the http://localhost:3000/ address in the browser.

By editing the documentation source code of the library, we can observe the changes on the local website.

:::note
If the `sbt docs/previewWebsite` command fails, please clean the `target` directory for the documentation module and try again from step 1.
:::

## Publishing Documentation

To contribute to the documentation of an official library, one must make changes to the documentation source code of that library and then create a pull request to the respective repository. Upon merging that pull request, the goal is to have the documentation for that library updated on the zio.dev website with the release of the next version of the library.

To achieve this, the only thing that needs to be done from the library repository perspective is to publish the documentation to the npm registry. There are two options for this:

1. Write a manual CI job responsible for publishing the documentation package after each release of the library.
2. Use the ZIO SBT CI plugin to generate the CI jobs automatically, including the job for publishing the documentation package.

## Writing Manual CI for Publishing Documentation

### Releasing Documentation Package

By adding the following CI job to the `.github/workflows/<workflow-name>.yaml` file, we can utilize the `sbt docs/publishToNpm` command provided by the ZIO SBT Website plugin to publish the documentation package to the npm registry:

```yaml
release-docs:
  name: Release Docs
  runs-on: ubuntu-latest
  continue-on-error: false
  needs:
  - release
  if: ${{ ((github.event_name == 'release') && (github.event.action == 'published')) }}
  steps:
  - name: Git Checkout
    uses: actions/checkout@v4.1.1
    with:
      fetch-depth: '0'
  - name: Setup NodeJs
    uses: actions/setup-node@v4
    with:
      node-version: 20.x
      registry-url: https://registry.npmjs.org
  - name: Publish Docs to NPM Registry
    run: sbt docs/publishToNpm
    env:
      NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
```

The `NPM_TOKEN` is a secret used to publish the documentation to the npm registry. All official libraries under GitHub's [ZIO organization](https://github.com/zio) have access to this secret.

### Notifying the Main Repository

After publishing the documentation package, we need to notify the main repository about the new release of the documentation package. To do so, we can add the following CI job to the `.github/workflows/<workflow-name>.yaml` file:

```yaml
notify-docs-release:
  name: Notify Docs Release
  runs-on: ubuntu-latest
  continue-on-error: false
  needs:
  - release-docs
  if: ${{ (github.event_name == 'release') && (github.event.action == 'published') }}
  steps:
  - name: Git Checkout
    uses: actions/checkout@v4.1.1
    with:
      fetch-depth: '0'
  - name: notify the main repo about the new release of docs package
    run: |
      PACKAGE_NAME=$(cat docs/package.json | grep '"name"' | awk -F'"' '{print $4}')
      PACKAGE_VERSION=$(npm view $PACKAGE_NAME version)
      curl -L \
        -X POST \
        -H "Accept: application/vnd.github+json" \
        -H "Authorization: token ${{ secrets.PAT_TOKEN }}"\
          https://api.github.com/repos/zio/zio/dispatches \
          -d '{
                "event_type":"update-docs",
                "client_payload":{
                  "package_name":"'"${PACKAGE_NAME}"'",
                  "package_version": "'"${PACKAGE_VERSION}"'"
                }
              }'
```

This CI job will create a new dispatch event to the ZIO repository, which will then generate a new pull request to update the documentation package version in the `website/package.json` file of the ZIO repository. After merging that pull request, the new version of the documentation will be deployed to the zio.dev website.

### Checking Website Build

Before releasing the documentation package, it is advisable to check if everything is fine. To do so, we can add the following CI steps to `build` phase of the CI:

```diff
  build:
    runs-on: ubuntu-22.04
    timeout-minutes: 60
    steps:
      - name: Checkout current branch
        uses: actions/checkout@v4.1.1
      - name: Setup Java
        uses: actions/setup-java@v4.2.1
        with:
          distribution: temurin
          java-version: 17
          check-latest: true
+     - name: Setup NodeJs
+       uses: actions/setup-node@v4
+       with:
+         node-version: 16.x
+         registry-url: https://registry.npmjs.org
      - name: Cache scala dependencies
        uses: coursier/cache-action@v6
      - name: Check Building Packages
        run: ./sbt +publishLocal
+     - name: Check Website Build Process
+       run: sbt docs/clean; sbt docs/buildWebsite
```

### Automate README Generation

Everytime we change the content of the `docs/index.md` file, we need to update the `README.md` file. To automate this process, we can add the following CI job to the `.github/workflows/<workflow-name>.yaml` file:

```yaml
update-readme:
  name: Update README
  runs-on: ubuntu-latest
  continue-on-error: false
  if: ${{ github.event_name == 'push' }}
  steps:
  - name: Git Checkout
    uses: actions/checkout@v4.1.1
    with:
      fetch-depth: '0'
  - name: Setup Scala
    uses: actions/setup-java@v4.2.1
    with:
      distribution: temurin
      java-version: 17
      check-latest: true
  - name: Cache Dependencies
    uses: coursier/cache-action@v6
  - name: Generate Readme
    run: sbt docs/generateReadme
  - name: Commit Changes
    run: |
      git config --local user.email "zio-assistant[bot]@users.noreply.github.com"
      git config --local user.name "ZIO Assistant"
      git add README.md
      git commit -m "Update README.md" || echo "No changes to commit"
  - name: Generate Token
    id: generate-token
    uses: zio/generate-github-app-token@v1.0.0
    with:
      app_id: ${{ secrets.APP_ID }}
      app_private_key: ${{ secrets.APP_PRIVATE_KEY }}
  - name: Create Pull Request
    id: cpr
    uses: peter-evans/create-pull-request@v5.0.2
    with:
      body: |-
        Autogenerated changes after running the `sbt docs/generateReadme` command of the [zio-sbt-website](https://zio.dev/zio-sbt) plugin.

        I will automatically update the README.md file whenever there is new change for README.md, e.g.
          - After each release, I will update the version in the installation section.
          - After any changes to the "docs/index.md" file, I will update the README.md file accordingly.
      branch: zio-sbt-website/update-readme
      commit-message: Update README.md
      token: ${{ steps.generate-token.outputs.token }}
      delete-branch: true
      title: Update README.md
  - name: Approve PR
    if: ${{ steps.cpr.outputs.pull-request-number }}
    run: gh pr review "$PR_URL" --approve
    env:
      GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      PR_URL: ${{ steps.cpr.outputs.pull-request-url }}
  - name: Enable Auto-Merge
    if: ${{ steps.cpr.outputs.pull-request-number }}
    run: gh pr merge --auto --squash "$PR_URL" || gh pr merge --squash "$PR_URL"
    env:
      GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      PR_URL: ${{ steps.cpr.outputs.pull-request-url }}
```

This CI job will create a new pull request to update the `README.md` file after each push event. The pull request will be merged automatically if the CI job is successful. It utilizes the [ZIO Assistant](https://github.com/apps/zio-assistant), which requires a token to be generated. The token will be generated using the [`zio/generate-github-app-token`](https://github.com/zio/generate-github-app-token) action.

## Utilizing Autogenerated CI through the ZIO SBT CI Plugin (Recommended)

The ZIO SBT CI plugin can generate the CI jobs automatically, including the job for publishing the documentation package to the NPM registry.

To do this, first, we need to install the plugin in the `project/plugins.sbt` file of the library repository:

```scala
// replace <version> with the latest version currently 0.4.0-alpha.22
addSbtPlugin("dev.zio" % "zio-sbt-ci" % "<version>") 
```

By adding this line, the plugin will be enabled, and we can use the `sbt ciGenerateGithubWorkflow` command to generate the CI jobs. The generated CI jobs will be placed in the `.github/workflows/ci.yml`.

:::note
After each release of an official library, the documentation for that library will be published to the npm registry. In the CI of the ZIO repository, the latest versions of documentations that are published to the NPM registry will be downloaded and integrated into the zio.dev site.
:::

To learn more about the ZIO SBT CI plugin, please refer to [this page](https://zio.dev/zio-sbt).

## ZIO SBT Ecosystem Plugin (Configuring Project's SBT Settings)

Over time, within the ZIO Ecosystem projects, we've identified a common thread: the need to configure specific settings when initiating a new project. These settings often share a commonality across projects, albeit with some variations.

This involves setting up crucial elements such as build information, test configurations, documentation settings, macro settings, activating industry-standard plugins, ensuring cross-platform compatibility, and configuring supported Scala versions, among other essential parameters.

The ZIO SBT Ecosystem was created to address this need. It attempts to configure common settings among ZIO ecosystem projects. Besides providing various sbt tasks/settings, it also configures and installs essential SBT plugins.

Let's take a closer look at this plugin and see how to use it.

### Installing the Plugin

To install the plugin, we need to add the following line to the `project/plugins.sbt` file of the library repository:

```scala
// replace <version> with the latest version currently 0.4.0-alpha.22
addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % "<version>")
```

Then add the following line to the `build.sbt` file of the library repository:

```scala
enablePlugins(ZioSbtEcosystemPlugin)
```

Now, let's explore the various settings available for configuring our project modules:

### Global Settings

After enabling the `zio-sbt-ecosystem` plugin, it will automatically apply some global settings, such as `licenses` to `Apache2`, `organization` to `dev.zio`, homepage to `https://zio.dev/<project-name>`, and also configuring the `scmInfo` setting.

### Project Settings

Additionally, there are some settings that will be activated in the scope of each project, such as introducing new commands and tasks, as well as the help manual:

1. Commands:
   1. `fix` - Fixes source files using scalafix.
   2. `fmt` - Formats source files using scalafmt.
   3. `quiet` - Disable/enable welcome banner.
   4. `lint` - Verifies that all source files are properly formatted and have had all scalafix rules applied.
   5. `prepare` - Prepares sources by applying scalafmt and running scalafix:
   6. `publishAll` - Signs and publishes all artifacts to Maven Central.
2. Tasks:
   1. `build` - Compiles all sources including tests.
   2 `enableStrictCompile` - Enables strict compile option.
   3 `disableStrictCompile` - Disables strict compile option.

### Build Settings

The following settings will be activated at the build scope:

1. `scala3` - Version of Scala 3 to use.
2. `scala212` - Version of Scala 2.12 to use.
3. `scalaVersion` - The default version of scala used for build.
4. `crossScalaVersions` - The versions of Scala used when cross-building which by default is set to `Seq(scala212.value, scala213.value, scala3.value)`
5. `zioVersion` - The version of ZIO to use.
6. `javaPlatform` - The java platform to use which by default is set to `8`

### Helper Functions

The ZIO SBT Ecosystem Plugin also provides some helper functions that can be used in the `build.sbt` file to configure the project:

1. `stdSettings`
2. `enableZIO` 
3. `crossProjectSettings`
4. `scalafixSettings`
5. `scalaReflectTestSettings`
6. `buildInfoSettings`  
7. `macroDefinitionSettings`
8. `jsSettings` and `scalajs`
9. `nativeSettings`

## Configuring Scala Steward

[Scala Steward](https://github.com/scala-steward-org/scala-steward) is a bot that helps you keep library dependencies and sbt plugins up-to-date. It is a GitHub App that automatically updates dependencies in Scala projects with pull requests.

To configure Scala Steward on a repository inside the ZIO organization, we have two options:

1. Using the public Scala Steward instance (recommended)
2. Using the ZIO Scala Steward instance

Most of the ZIO ecosystem projects use the public instance of Scala Steward, and we recommend using it. To enable Scala Steward on a repository, you can add the project to [this file](https://github.com/VirtusLab/scala-steward-repos/blob/main/repos-github.md) and then create a pull request.

However, some projects may require the ZIO Scala Steward instance hosted on the ZIO organization. In this case, please contact the ZIO team through the [ZIO's Contributor Channel on Discord](https://discord.com/channels/629491597070827530/629491597070827534). After enabling the ZIO Scala Steward bot on the repository, add the following CI job to the `.github/workflows/scala-steward.yml` file:

```yaml
name: Scala Steward

# This workflow will launch everyday at 00:00
on:
  schedule:
    - cron: '0 0 * * *'
  workflow_dispatch: {}

jobs:
  scala-steward:
    timeout-minutes: 45
    runs-on: ubuntu-latest
    name: Scala Steward
    steps:
      - name: Scala Steward
        uses: scala-steward-org/scala-steward-action@v2.61.0
        with:
          github-app-id: ${{ secrets.SCALA_STEWARD_GITHUB_APP_ID }}
          github-app-installation-id: ${{ secrets.SCALA_STEWARD_GITHUB_APP_INSTALLATION_ID }}
          github-app-key: ${{ secrets.SCALA_STEWARD_GITHUB_APP_PRIVATE_KEY }}
          github-app-auth-only: true
```

## Project Checklist

As a ZIO ecosystem contributor, you can help us improve by checking if the project follows our guidelines. Some are must-follow, others are optional. Follow as many as you can. If the project doesn't meet any, kindly make a pull request to fix it. Here are the guidelines:

### General Checklist

1. [ ] The project has a `LICENSE` file (required).
2. [ ] The CI workflow is generated using the `zio-sbt-ci` plugin (optional).
3. [ ] The project uses the `zio-sbt-ecosystem` plugin to configure SBT settings (optional).

### Documentation Checklist

1. [ ] The documentation project uses the `zio-sbt-website` plugin (required).
2. [ ] The project has well-written documentation in the `docs` directory (required).
3. [ ] The project has a `docs/index.md` file that serves as the main page of the documentation (required).
4. [ ] The project has a `docs/sidebars.js` file that defines the structure of the documentation (required).
5. [ ] The project has a `docs/package.json` file that defines the name, description, and license of the documentation project (required).
6. [ ] The documentation project uses type-safe code snippets using the `mdoc` tool (optional).

### CI Checklist

Check if all the following items are done on `pull_request` and `push` (push to the main branch) and `release` events:

1. [ ] Lint: Check if all source files are properly formatted (required).
2. [ ] Test: Check if all tests are passing (required).
3. [ ] Build: The CI checks if the documentation is building successfully (required).
   1. [ ] Check if all source files (including the test sources) are compiled successfully.
   2. [ ] Check if packaging the project is successful.
   3. [ ] Check if packaging the documentation project is successful.

For releasing the project, check if all the following items are done on `release` event:

1. [ ] If the lint, test, and build CI jobs are successful, release the project and its documentation package to the Maven Central and NPM registries (required).
2. [ ] After the release job, notify the main repository about the new release of the project (optional).

On `push` events to the main branch, check if all the following items are done:

1. [ ] The CI generates a pull request to update the `README.md` file and creates a pull request to the main repository (optional).
