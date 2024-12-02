"use strict";
var __assign = (this && this.__assign) || function () {
    __assign = Object.assign || function(t) {
        for (var s, i = 1, n = arguments.length; i < n; i++) {
            s = arguments[i];
            for (var p in s) if (Object.prototype.hasOwnProperty.call(s, p))
                t[p] = s[p];
        }
        return t;
    };
    return __assign.apply(this, arguments);
};
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (k !== "default" && Object.prototype.hasOwnProperty.call(mod, k)) __createBinding(result, mod, k);
    __setModuleDefault(result, mod);
    return result;
};
exports.__esModule = true;
var fs = __importStar(require("fs"));
function zioEcosystemPlugin(context, options) {
    return {
        name: 'zio-ecosystem-plugin',
        extendCli: function (cli) {
            cli.command('generate-sidebar')
                .description("This command generates a sidebar file for the ZIO ecosystem")
                .action(function () {
                (zioNpmProjects()).forEach(function (project) {
                    copy("node_modules/@zio.dev/".concat(project), "./docs/".concat(project));
                });
                function categoryTemplate(name, items) {
                    return {
                        type: 'category',
                        label: name,
                        link: { type: "doc", id: "index" },
                        items: items
                    };
                }
                var result = oldStyledZioProjects
                    // If the sidebar is simple and doesn't have label
                    .filter(function (e) { return (require("@zio.dev/".concat(e.routeBasePath, "/").concat(e.sidebarPath)).sidebar[0] == "index"); })
                    .map(function (project) {
                    return mapConfig(categoryTemplate(project.name, require("@zio.dev/".concat(project.routeBasePath, "/").concat(project.sidebarPath))
                        .sidebar
                        .filter(function (e) { return e != "index"; })), "".concat(project.routeBasePath, "/"));
                })
                    .concat(zioNpmProjects()
                    // If the sidebar has all the metadata including the label
                    .filter(function (e) {
                    return require("@zio.dev/".concat(e, "/sidebars.js")).sidebar[0].label !== undefined;
                })
                    .map(function (project) {
                    return mapConfig(require("@zio.dev/".concat(project, "/sidebars.js")).sidebar[0], "".concat(project, "/"));
                }))
                    .sort(function (a, b) { return a.label < b.label ? -1 : a.label > b.label ? 1 : 0; });
                var content = "const sidebar = ".concat(JSON.stringify(result, null, '  '), "\nmodule.exports = sidebar");
                fs.writeFile('ecosystem-sidebar.js', content, function (err) {
                    if (err) {
                        console.error(err);
                    }
                });
            });
        }
    };
}
function zioNpmProjects() {
    return Object
        .entries((JSON.parse(fs.readFileSync('package.json', 'utf8'))).dependencies)
        .filter(function (_a) {
        var key = _a[0], _ = _a[1];
        return key.startsWith("@zio.dev");
    })
        .map(function (_a) {
        var key = _a[0], _ = _a[1];
        return key.split("/")[1];
    })
        .sort();
}
function copy(srcDir, destDir) {
    var fs = require('fs-extra');
    try {
        fs.copySync(srcDir, destDir, { overwrite: true || false });
    }
    catch (err) {
        console.error(err);
    }
}
var oldStyledZioProjects = [
    {
        name: 'Caliban Deriving',
        routeBasePath: 'caliban-deriving',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Interop Monix',
        routeBasePath: 'interop-monix',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Izumi Reflect',
        routeBasePath: 'izumi-reflect',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Actors',
        routeBasePath: 'zio-actors',
        sidebarPath: 'sidebars.js'
    },
    {
        name: "ZIO Akka Cluster",
        routeBasePath: 'zio-akka-cluster',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO AWS',
        routeBasePath: 'zio-aws',
        sidebarPath: 'sidebars.js'
    },
    {
        name: "ZIO Connect",
        routeBasePath: 'zio-connect',
        sidebarPath: 'sidebars.js'
    },
    {
        name: "ZIO Deriving",
        routeBasePath: 'zio-deriving',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Flow',
        routeBasePath: 'zio-flow',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Interop Guava',
        routeBasePath: 'zio-interop-guava',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Interop Scalaz',
        routeBasePath: 'zio-interop-scalaz',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Interop Twitter',
        routeBasePath: 'zio-interop-twitter',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Memberlist',
        routeBasePath: 'zio-memberlist',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Meta',
        routeBasePath: 'zio-meta',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO NIO',
        routeBasePath: 'zio-nio',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Metrics Connectors',
        routeBasePath: 'zio-metrics-connectors',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Rocksdb',
        routeBasePath: 'zio-rocksdb',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO S3',
        routeBasePath: 'zio-s3',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO SQS',
        routeBasePath: 'zio-sqs',
        sidebarPath: 'sidebars.js'
    },
    {
        name: 'ZIO Webhooks',
        routeBasePath: 'zio-webhooks',
        sidebarPath: 'sidebars.js'
    }
];
function isSidebarItemCategoryConfig(item) {
    return item.items !== undefined;
}
function mapConfig(config, prefix) {
    if (typeof (config) === "string") {
        return prefix + config;
    }
    else if (isSidebarItemCategoryConfig(config)) {
        return __assign(__assign({}, config), { collapsed: true, link: config.link ? __assign(__assign({}, config.link), { 'id': prefix + config.link.id }) : undefined, items: config.items
                .map(function (item) { return mapConfig(item, prefix); }) });
    }
}
exports["default"] = zioEcosystemPlugin;
