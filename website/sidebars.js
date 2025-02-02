const ecosystemSidebar = require("./ecosystem-sidebar");

module.exports = {
  overview_sidebar:
    [
      "overview/getting-started",
      "overview/summary",
      "overview/creating-effects",
      "overview/basic-operations",
      "overview/handling-errors",
      "overview/handling-resources",
      "overview/basic-concurrency",
      "overview/running-effects",
      "overview/performance",
      "overview/platforms"
    ],
  "reference-sidebar": [
    "reference/index",
    {
      type: "category",
      label: "Core",
      collapsed: false,
      items: [
        "reference/core/zio/zio",
        {
          type: "category",
          collapsed: true,
          label: "ZIO Type Aliases",
          items: [
            "reference/core/zio/uio",
            "reference/core/zio/urio",
            "reference/core/zio/task",
            "reference/core/zio/rio",
            "reference/core/zio/io",
          ]
        },
        "reference/core/zioapp",
        "reference/core/runtime",
        "reference/core/exit",
        "reference/core/cause"
      ]
    },
    "reference/control-flow/index",
    {
      type: "category",
      label: "Error Management",
      link: { type: "doc", id: "reference/error-management/index" },
      items:
        [
          {
            type: "category",
            label: "Three Types of Errors in ZIO",
            link: { type: "doc", id: "reference/error-management/types/index" },
            items:
              [
                "reference/error-management/types/failures",
                "reference/error-management/types/defects",
                "reference/error-management/types/fatals"
              ]
          },
          "reference/error-management/imperative-vs-declarative",
          "reference/error-management/expected-and-unexpected-errors",
          "reference/error-management/exceptional-and-unexceptional-effects",
          "reference/error-management/typed-errors-guarantees",
          "reference/error-management/sequential-and-parallel-errors",

          {
            type: "category",
            label: "Recovering From Errors",
            items:
              [
                "reference/error-management/recovering/catching",
                "reference/error-management/recovering/fallback",
                "reference/error-management/recovering/folding",
                "reference/error-management/recovering/retrying",
                "reference/error-management/recovering/timing-out",
                "reference/error-management/recovering/sandboxing",
              ]
          },
          "reference/error-management/error-accumulation",
          {
            type: "category",
            label: "Error Channel Operations",
            items:
              [
                "reference/error-management/operations/map-operations",
                "reference/error-management/operations/chaining-effects-based-on-errors",
                "reference/error-management/operations/filtering-the-success-channel",
                "reference/error-management/operations/tapping-errors",
                "reference/error-management/operations/exposing-errors-in-the-success-channel",
                "reference/error-management/operations/exposing-the-cause-in-the-success-channel",
                "reference/error-management/operations/converting-defects-to-failures",
                "reference/error-management/operations/error-refinement",
                "reference/error-management/operations/flattening-optional-error-types",
                "reference/error-management/operations/merging-the-error-channel-into-the-success-channel",
                "reference/error-management/operations/flipping-error-and-success-channels",
                "reference/error-management/operations/rejecting-some-success-values",
                "reference/error-management/operations/zooming-in-on-nested-values",
              ]
          },
          {
            type: "category",
            label: "Best Practices",
            items:
              [
                "reference/error-management/best-practices/algebraic-data-types",
                "reference/error-management/best-practices/union-types",
                "reference/error-management/best-practices/unexpected-errors",
                "reference/error-management/best-practices/logging-errors"
              ]
          },
          "reference/error-management/examples"
        ]
    },
    "reference/interruption/index",
    {
      type: "category",
      label: "Built-in Services",
      link: { type: "doc", id: "reference/services/index" },
      items: [
        "reference/services/console",
        "reference/services/clock",
        "reference/services/random",
        "reference/services/system"
      ]
    },
    {
      type: "category",
      label: "Application Architecture",
      items: [
        "reference/architecture/programming-paradigms-in-zio",
        "reference/architecture/non-functional-requirements",
        "reference/architecture/architectural-patterns",
        "reference/architecture/functional-design-patterns",
      ]
    },
    {
      type: "category",
      label: "Writing ZIO Services",
      link: { type: "doc", id: "reference/service-pattern/introduction" },
      items: [
        "reference/service-pattern/service-pattern",
        "reference/service-pattern/defining-polymorphic-services-in-zio",
        "reference/service-pattern/the-three-laws-of-zio-environment",
        "reference/service-pattern/reloadable-services",
        "reference/service-pattern/accessor-methods"
      ]
    },
    {
      type: "category",
      label: "Dependency Injection",
      link: { type: "doc", id: "reference/di/index" },
      items: [
        "reference/di/motivation",
        "reference/di/zlayer-constructor-as-a-value",
        "reference/di/dependency-injection-in-zio",
        ,
        {
          type: "category",
          label: "Building Dependency Graph",
          link: { type: "doc", id: "reference/di/building-dependency-graph" },
          items: [
            "reference/di/manual-layer-construction",
            "reference/di/automatic-layer-construction",
          ]
        },
        "reference/di/dependency-propagation",
        "reference/di/providing-different-implementation-of-a-service",
        "reference/di/dependency-memoization",
        "reference/di/overriding-dependency-graph",
        "reference/di/examples",
      ]
    },
    {
      type: "category",
      label: "Contextual Types",
      link: { type: "doc", id: "reference/contextual/index" },
      items:
        [
          "reference/contextual/zenvironment",
          "reference/contextual/zio-environment-use-cases",
          {
            type: "category",
            label: "ZIO Layers",
            items: [
              "reference/contextual/zlayer",
              "reference/contextual/automatic-zlayer-derivation",
              {
                type: "category",
                collapsed: true,
                label: "Type Aliases",
                items: [
                  "reference/contextual/rlayer",
                  "reference/contextual/ulayer",
                  "reference/contextual/layer",
                  "reference/contextual/urlayer",
                  "reference/contextual/tasklayer"
                ]
              }
            ]
          }
        ]
    },
    {
      type: "category",
      label: "Scheduling",
      link: { type: "doc", id: "reference/schedule/index" },
      items: [
        "reference/schedule/repetition",
        "reference/schedule/retrying",
        "reference/schedule/built-in-schedules",
        "reference/schedule/combinators",
        "reference/schedule/examples",
      ]
    },
    {
      type: "category",
      label: "State Management",
      link: { type: "doc", id: "reference/state-management/index" },
      collapsed: true,
      items:
        [
          "reference/state-management/recursion",
          "reference/state-management/global-shared-state",
          {
            type: "category",
            label: "Fiber-local State",
            link: { type: "doc", id: "reference/state-management/fiber-local-state" },
            collapsed: true,
            items:
              [
                "reference/state-management/fiberref",
                "reference/state-management/zstate",
              ]
          }
        ]
    },
    {
      type: "category",
      label: "Concurrency",
      link: { type: "doc", id: "reference/concurrency/index" },
      items: [
        {
          type: "category",
          label: "ZIO Fibers",
          link: { type: "doc", id: "reference/fiber/index" },
          items: [
            "reference/fiber/fiber",
            "reference/fiber/fiberid",
            "reference/fiber/fiberstatus"
          ]
        },
        {
          type: "category",
          label: "Synchronization",
          link: { type: "doc", id: "reference/sync/index" },
          items: [
            "reference/sync/reentrantlock",
            "reference/sync/countdownlatch",
            "reference/sync/cyclicbarrier",
            "reference/sync/concurrentmap",
            "reference/sync/concurrentset",
          ]
        },
        {
          type: "category",
          label: "Concurrency Primitives",
          link: { type: "doc", id: "reference/concurrency/index" },
          items: [
            {
              type: "category",
              label: "Mutable Reference",
              items: [
                "reference/concurrency/ref",
                "reference/concurrency/refsynchronized",
              ]
            },
            "reference/concurrency/promise",
            "reference/concurrency/queue",
            "reference/concurrency/hub",
            "reference/concurrency/semaphore"
          ]
        },
        {
          type: "category",
          label: "STM",
          link: { type: "doc", id: "reference/stm/index" },
          items: [
            "reference/stm/stm",
            "reference/stm/tarray",
            "reference/stm/trandom",
            "reference/stm/tset",
            "reference/stm/tmap",
            "reference/stm/tref",
            "reference/stm/tpriorityqueue",
            "reference/stm/tpromise",
            "reference/stm/tqueue",
            "reference/stm/treentrantlock",
            "reference/stm/tsemaphore",
            "reference/stm/thub",
          ]
        },
      ]
    },
    {
      type: "category",
      label: "Resource Management",
      link: { type: "doc", id: "reference/resource/index" },
      items: [
        "reference/resource/scope",
        "reference/resource/zpool",
        "reference/resource/zkeyedpool",
        "reference/resource/scopedref",
      ]
    },
    {
      type: "category",
      label: "Streaming",
      link: { type: "doc", id: "reference/stream/index" },
      items: [
        "reference/stream/installation",
        "reference/stream/chunk",
        {
          type: "category",
          label: "ZStream",
          link: { type: "doc", id: "reference/stream/zstream/index" },
          items: [
            "reference/stream/zstream/type-aliases",
            "reference/stream/zstream/streams-are-chunked-by-default",
            "reference/stream/zstream/creating-zio-streams",
            "reference/stream/zstream/resourceful-streams",
            "reference/stream/zstream/operations",
            "reference/stream/zstream/consuming-streams",
            "reference/stream/zstream/error-handling",
            "reference/stream/zstream/scheduling",
          ]
        },
        "reference/stream/zpipeline",
        {
          type: "category",
          label: "ZSink",
          link: { type: "doc", id: "reference/stream/zsink/index" },
          items: [
            "reference/stream/zsink/creating-sinks",
            "reference/stream/zsink/operations",
            "reference/stream/zsink/parallel-operators",
            "reference/stream/zsink/leftovers",
          ]
        },
        {
          type: "category",
          label: "ZChannel",
          link: { type: "doc", id: "reference/stream/zchannel/index" },
          items: [
            "reference/stream/zchannel/creating-channels",
            "reference/stream/zchannel/composing-channels",
            "reference/stream/zchannel/running-a-channel",
            "reference/stream/zchannel/channel-operations",
            "reference/stream/zchannel/channel-interruption",
          ]
        },
        "reference/stream/subscription-ref"
      ]
    },
    {
      type: "category",
      label: "Observability",
      items: [
        "reference/observability/logging",
        {
          type: "category",
          label: "Metrics",
          link: { type: "doc", id: "reference/observability/metrics/index" },
          items: [
            {
              type: "category",
              label: "Metric Types",
              items: [
                "reference/observability/metrics/counter",
                "reference/observability/metrics/gauge",
                "reference/observability/metrics/histogram",
                "reference/observability/metrics/summary",
                "reference/observability/metrics/frequency"
              ]
            },
            "reference/observability/metrics/metriclabel",
            "reference/observability/metrics/jvm",
          ]
        },
        "reference/observability/tracing",
        "reference/observability/supervisor",
      ]
    },
    {
      type: "category",
      label: "Configuration",
      link: { type: "doc", id: "reference/configuration/index" },
      items: [ ]
    },
    {
      type: "category",
      label: "Testing",
      link: { type: "doc", id: "reference/test/index" },
      items: [
        "reference/test/why-zio-test",
        "reference/test/installation",
        "reference/test/writing-our-first-test",
        "reference/test/running-tests",
        "reference/test/junit-integration",
        {
          type: "category",
          label: "Assertions",
          link: { type: "doc", id: "reference/test/assertions/index" },
          items: [
            "reference/test/assertions/classic-assertions",
            "reference/test/assertions/smart-assertions",
            "reference/test/assertions/built-in-assertions",
          ]
        },
        "reference/test/test-hierarchies-and-organization",
        "reference/test/sharing-layers-within-the-same-file",
        "reference/test/sharing-layers-between-multiple-files",
        "reference/test/spec",
        {
          type: "category",
          label: "Test Services",
          link: { type: "doc", id: "reference/test/services/index" },
          items: [
            "reference/test/services/console",
            "reference/test/services/clock",
            "reference/test/services/random",
            "reference/test/services/system",
            "reference/test/services/live",
            "reference/test/services/config",
            "reference/test/services/sized",
          ]
        },
        {
          type: "category",
          label: "Test Aspects",
          link: { type: "doc", id: "reference/test/aspects/index" },
          items: [
            "reference/test/aspects/before-after-around",
            "reference/test/aspects/conditional",
            "reference/test/aspects/debugging-and-diagnostics",
            "reference/test/aspects/environment-specific-tests",
            "reference/test/aspects/execution-strategy",
            "reference/test/aspects/flaky-and-non-flaky-tests",
            "reference/test/aspects/ignoring-tests",
            "reference/test/aspects/non-deterministic-test-data",
            "reference/test/aspects/passing-failed-tests",
            "reference/test/aspects/repeat-and-retry",
            "reference/test/aspects/restoring-state-of-test-services",
            "reference/test/aspects/sized",
            "reference/test/aspects/annotating-tests",
            "reference/test/aspects/configuring-tests",
            "reference/test/aspects/timing-out-tests",
          ]
        },
        "reference/test/dynamic-test-generation",
        {
          type: "category",
          label: "Property Testing",
          link: { type: "doc", id: "reference/test/property-testing/index" },
          items: [
            "reference/test/property-testing/getting-started",
            "reference/test/property-testing/how-generators-work",
            "reference/test/property-testing/built-in-generators",
            "reference/test/property-testing/shrinking",
          ]
        }
      ]
    }
  ],
  "guides-sidebar": [
    "guides/index",
    {
      type: "category",
      label: "Quickstart Guides",
      collapsed: false,
      items: [
        "guides/quickstarts/hello-world",
        "guides/quickstarts/restful-webservice",
        "guides/quickstarts/graphql-webservice",
      ]
    },
    {
      type: "category",
      label: "Tutorial Guides",
      items: [
        "guides/tutorials/configurable-zio-application",
        "guides/tutorials/encode-and-decode-json-data",
        "guides/tutorials/enable-logging-in-a-zio-application",
        "guides/tutorials/create-custom-logger-for-a-zio-application",
        "guides/tutorials/run-our-first-zio-project-with-vscode",
        "guides/tutorials/run-our-first-zio-project-with-intellij-idea",
        "guides/tutorials/deploy-a-zio-application-using-docker",
        "guides/tutorials/producing-consuming-data-from-kafka-topics",
        "guides/tutorials/monitor-a-zio-application-using-zio-built-in-metric-system",
        "guides/tutorials/debug-a-zio-application",
        "guides/tutorials/build-a-restful-webservice",
        "guides/tutorials/build-a-graphql-webservice",
        "guides/tutorials/gracefully-shutdown-zio-application",
      ]
    },
    {
      type: "category",
      label: "Integration Guides",
      items: [
        "guides/interop/with-cats-effect",
        "guides/interop/with-future",
        "guides/interop/with-java",
        "guides/interop/with-javascript",
        "guides/interop/with-monix",
        "guides/interop/with-scalaz-7x",
        "guides/interop/with-reactive-streams",
        "guides/interop/with-twitter",
        "guides/interop/with-guava"
      ]
    },
    {
      type: "category",
      label: "Migration Guides",
      collapsed: false,
      items: [
        "guides/migrate/zio-2.x-migration-guide",
        "guides/migrate/from-akka",
        "guides/migrate/from-cats-effect",
        "guides/migrate/from-monix",
      ]
    }
  ],
  "ecosystem-sidebar": [
    "ecosystem/index",
    {
      type: "category",
      label: "Official Libraries",
      collapsed: false,
      link: { type: "doc", id: "ecosystem/officials/index" },
      items: ecosystemSidebar
    },
    {
      type: "category",
      label: "Community Libraries",
      link: { type: "doc", id: "ecosystem/community/index" },
      items: [
        "ecosystem/community/caliban",
        "ecosystem/community/distage",
        "ecosystem/community/fhir-indexer",
        "ecosystem/community/logstage",
        "ecosystem/community/munit-zio",
        "ecosystem/community/rezilience",
        "ecosystem/community/scala-k8s",
        "ecosystem/community/tamer",
        "ecosystem/community/tofu-zio2-logging",
        "ecosystem/community/tranzactio",
        "ecosystem/community/zio-amqp",
        "ecosystem/community/zio-apache-parquet",
        "ecosystem/community/zio-arrow",
        "ecosystem/community/zio-aws-s3",
        "ecosystem/community/zio-grpc",
        "ecosystem/community/zio-k8s",
        "ecosystem/community/zio-kinesis",
        "ecosystem/community/zio-nebula",
        "ecosystem/community/zio-pulsar",
        "ecosystem/community/zio-saga",
        "ecosystem/community/zio-slick-interop",
        "ecosystem/community/zio-temporal",
        "ecosystem/community/zio-test-akka-http",
      ],
    },
    "ecosystem/compatible",
    "ecosystem/tools",
    "ecosystem/templates"
  ],
  "resources-sidebar": [
    "resources/index",
    "resources/articles",
    "resources/videos",
    "resources/cookbooks",
    "resources/cheatsheets",
    "resources/sampleprojects",
    "resources/poweredbyzio"
  ]
}
