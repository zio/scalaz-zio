import { LoadContext, PluginOptions } from '@docusaurus/types'
import { SidebarItemConfig, SidebarItemCategoryConfig, SidebarItemCategoryLinkDoc } from '@docusaurus/plugin-content-docs/src/sidebars/types'
import * as fs from 'fs'

function zioEcosystemPlugin(context: LoadContext, options: PluginOptions) {
  return {
    name: 'zio-ecosystem-plugin',
    extendCli(cli) {
      cli.command('generate-sidebar')
        .description("This command generates a sidebar file for the ZIO ecosystem")
        .action(() => {

          (zioNpmProjects()).forEach(project => {
            copy(`node_modules/@zio.dev/${project}`, `./docs/${project}`)
          })

          function categoryTemplate(name: string, items: SidebarItemConfig[]): SidebarItemCategoryConfig {
            return {
              type: 'category',
              label: name,
              link: { type: "doc", id: "index" },
              items: items
            }
          }

          const result =
            oldStyledZioProjects
              // If the sidebar is simple and doesn't have label
              .filter(e => (require(`@zio.dev/${e.routeBasePath}/${e.sidebarPath}`).sidebar[0] == "index"))
              .map(project => {
                return mapConfig(
                  categoryTemplate(
                    project.name,
                    require(`@zio.dev/${project.routeBasePath}/${project.sidebarPath}`)
                      .sidebar
                     .filter(e => e != "index")),
                  `${project.routeBasePath}/`
                )
              })
              .concat(
                zioNpmProjects()
                  // If the sidebar has all the metadata including the label
                  .filter(e => {
                    return require(`@zio.dev/${e}/sidebars.js`).sidebar[0].label !== undefined;
                  })
                  .map(project =>
                    mapConfig(require(`@zio.dev/${project}/sidebars.js`).sidebar[0], `${project}/`)
                  )
              )
              .sort((a, b) => a.label < b.label ? -1 : a.label > b.label ? 1 : 0)

          const content =
            `const sidebar = ${JSON.stringify(result, null, '  ')}\nmodule.exports = sidebar`

          fs.writeFile('ecosystem-sidebar.js', content, err => {
            if (err) {
              console.error(err);
            }
          });
        })
    }
  }
}

function zioNpmProjects(): string[] {
  return Object
    .entries(
      (JSON.parse(
        fs.readFileSync('package.json', 'utf8'))
      ).dependencies as { [key: string]: string }
    )
    .filter(([key, _]) =>
      key.startsWith("@zio.dev"))
    .map(([key, _]) => key.split("/")[1])
    .sort()
}

function copy(srcDir: string, destDir: string) {
  const fs = require('fs-extra');
  try {
    fs.copySync(srcDir, destDir, { overwrite: true || false })
  } catch (err) {
    console.error(err)
  }
}

const oldStyledZioProjects =
  [
    {
      name: 'Caliban Deriving',
      routeBasePath: 'caliban-deriving',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Interop Monix',
      routeBasePath: 'interop-monix',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Izumi Reflect',
      routeBasePath: 'izumi-reflect',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Actors',
      routeBasePath: 'zio-actors',
      sidebarPath: 'sidebars.js',
    },
    {
      name: "ZIO Akka Cluster",
      routeBasePath: 'zio-akka-cluster',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO AWS',
      routeBasePath: 'zio-aws',
      sidebarPath: 'sidebars.js',
    },
    {
      name: "ZIO Connect",
      routeBasePath: 'zio-connect',
      sidebarPath: 'sidebars.js',
    },
    {
      name: "ZIO Deriving",
      routeBasePath: 'zio-deriving',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Flow',
      routeBasePath: 'zio-flow',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Interop Guava',
      routeBasePath: 'zio-interop-guava',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Interop Scalaz',
      routeBasePath: 'zio-interop-scalaz',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Interop Twitter',
      routeBasePath: 'zio-interop-twitter',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Memberlist',
      routeBasePath: 'zio-memberlist',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Meta',
      routeBasePath: 'zio-meta',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO NIO',
      routeBasePath: 'zio-nio',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Metrics Connectors',
      routeBasePath: 'zio-metrics-connectors',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Rocksdb',
      routeBasePath: 'zio-rocksdb',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO S3',
      routeBasePath: 'zio-s3',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO SQS',
      routeBasePath: 'zio-sqs',
      sidebarPath: 'sidebars.js',
    },
    {
      name: 'ZIO Webhooks',
      routeBasePath: 'zio-webhooks',
      sidebarPath: 'sidebars.js',
    }
  ]

function isSidebarItemCategoryConfig(item: SidebarItemConfig): item is SidebarItemCategoryConfig {
  return (item as SidebarItemCategoryConfig).items !== undefined
}

function mapConfig(config: SidebarItemConfig, prefix: string) {
  if (typeof (config) === "string") {
    return prefix + config
  } else if (isSidebarItemCategoryConfig(config)) {
    return {
      ...config,
      collapsed: true,
      link: config.link ? { ...config.link, 'id': prefix + (config.link as SidebarItemCategoryLinkDoc).id } : undefined,
      items:
        ((config as SidebarItemCategoryConfig).items as SidebarItemConfig[])
          .map(item => mapConfig(item, prefix))
    }
  }
}

export default zioEcosystemPlugin