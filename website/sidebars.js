module.exports = {
  overview_sidebar: {
    Overview: [
      "overview/overview_index",
      "overview/overview_creating_effects",
      "overview/overview_basic_operations",
      "overview/overview_handling_errors",
      "overview/overview_handling_resources",
      "overview/overview_basic_concurrency",
      "overview/overview_testing_effects",
      "overview/overview_running_effects",
      "overview/overview_background",
      "overview/overview_performance",
      "overview/overview_platforms"
    ]
  },
  "datatypes-sidebar": {
    "Overview": [
      "datatypes/index"
    ],
    "Core Data Types": [
        {
            type: "category",
            label: "ZIO Effects",
            items: [
                "datatypes/core/zio/zio",
                "datatypes/core/zio/uio",
                "datatypes/core/zio/urio",
                "datatypes/core/zio/task",
                "datatypes/core/zio/rio",
                "datatypes/core/zio/io",
            ]
        },
        "datatypes/core/zioapp",
        "datatypes/core/runtime",
        "datatypes/core/exit",
        "datatypes/core/cause"
    ],
    "Contextual Types": [
        "datatypes/contextual/index",
        "datatypes/contextual/has",
        {
            type: "category",
            label: "ZIO Layers",
            items: [
                "datatypes/contextual/zlayer",
                "datatypes/contextual/rlayer",
                "datatypes/contextual/ulayer",
                "datatypes/contextual/layer",
                "datatypes/contextual/urlayer",
                "datatypes/contextual/tasklayer"
            ]
        },
        {
            type: "category",
            label: "Built-in Services",
            items: [
                "datatypes/contextual/services/index",
                "datatypes/contextual/services/console",
                "datatypes/contextual/services/clock",
                "datatypes/contextual/services/random",
                "datatypes/contextual/services/system"
            ]
        }
    ],
    "Concurrency": [
        {
            type: "category",
            label: "ZIO Fibers",
            items: [
                "datatypes/fiber/index",
                "datatypes/fiber/fiber",
                "datatypes/fiber/fiberref",
                "datatypes/fiber/fiberid",
                "datatypes/fiber/fiberstatus"
            ]
        },
        {
            type: "category",
            label: "Concurrency Primitives",
            items: [
                "datatypes/concurrency/index",
                {
                    type: "category",
                    label: "Mutable References",
                    items: [
                        "datatypes/concurrency/zref",
                        "datatypes/concurrency/zrefsynchronized",
                        "datatypes/concurrency/ref",
                        "datatypes/concurrency/refsynchronized",
                    ]
                },
                "datatypes/concurrency/promise",
                "datatypes/concurrency/queue",
                "datatypes/concurrency/hub",
                "datatypes/concurrency/semaphore"
            ]
        },
        {
            type: "category",
            label: "STM",
            items: [
                "datatypes/stm/index",
                "datatypes/stm/stm",
                "datatypes/stm/tarray",
                "datatypes/stm/trandom",
                "datatypes/stm/tset",
                "datatypes/stm/tmap",
                "datatypes/stm/tref",
                "datatypes/stm/tpriorityqueue",
                "datatypes/stm/tpromise",
                "datatypes/stm/tqueue",
                "datatypes/stm/treentrantlock",
                "datatypes/stm/tsemaphore",
                "datatypes/stm/zthub",
            ]
        },
    ],
    "Resource Safety": [
      "datatypes/resource/index",
      "datatypes/resource/zmanaged",
      "datatypes/resource/managed",
      "datatypes/resource/task-managed",
      "datatypes/resource/rmanaged",
      "datatypes/resource/umanaged",
      "datatypes/resource/urmanaged"
    ],
    "Streaming": [
        "datatypes/stream/index",
        {
            type: "category",
            label: "Main Components",
            items: [
                {
                    type: "category",
                    label: "ZStream",
                    items: [
                        "datatypes/stream/zstream",
                        "datatypes/stream/stream",
                        "datatypes/stream/ustream",
                    ]
                },
                {
                    type: "category",
                    label: "ZTransducer",
                    items: [
                        "datatypes/stream/ztransducer",
                        "datatypes/stream/transducer",
                    ]
                },
                {
                    type: "category",
                    label: "ZSink",
                    items: [
                        "datatypes/stream/zsink",
                        "datatypes/stream/sink",
                    ]
                }
            ]
        },
        "datatypes/stream/subscription-ref"
    ],
      "Metrics": [
          "datatypes/metrics/index",
          {
              type: "category",
              label: "Metric Types",
              items: [
                  "datatypes/metrics/counter",
                  "datatypes/metrics/gauge",
                  "datatypes/metrics/histogram",
                  "datatypes/metrics/summary",
                  "datatypes/metrics/setcount"
              ]
          },
          "datatypes/metrics/metriclabel"
      ],
    "Miscellaneous": [
      "datatypes/misc/chunk",
      "datatypes/misc/schedule",
      "datatypes/misc/supervisor"
    ]
  },
  "usecases-sidebar": {
    "Use Cases": [
      "usecases/usecases_index",
      "usecases/usecases_asynchronous",
      "usecases/usecases_concurrency",
      "usecases/usecases_parallelism",
      "usecases/usecases_queueing",
      "usecases/usecases_retrying",
      "usecases/usecases_scheduling",
      "usecases/usecases_streaming",
      "usecases/usecases_testing"
    ]
  },
  "howto-sidebar": {
    "Overview": ["howto/index"],
    "How to": [
      "howto/use-test-assertions",
      "howto/test-effects",
      "howto/mock-services",
      "howto/handle-errors",
      "howto/access-system-information",
      "howto/use-zio-macros"
    ],
    "Interop": [
      "howto/interop/with-cats-effect",
      "howto/interop/with-future",
      "howto/interop/with-java",
      "howto/interop/with-javascript",
      "howto/interop/with-monix",
      "howto/interop/with-scalaz-7x",
      "howto/interop/with-reactive-streams",
      "howto/interop/with-twitter",
      "howto/interop/with-guava"
    ],
    "Migrate": [
      "howto/migrate/from-monix",
      "howto/migrate/zio-2.x-migration-guide"
    ]
  },
  "resources-sidebar": {
    "Overview": [
      "resources/index"
    ],
    "Learning": [
      "resources/learning/articles",
      "resources/learning/videos",
      "resources/learning/cookbooks",
      "resources/learning/cheatsheets",
      "resources/learning/sampleprojects",
      "resources/learning/poweredbyzio"
    ],
    "Ecosystem": [
        {
            type: "category",
            label: "Official Libraries",
            items: [
                "resources/ecosystem/officials/index",
                "resources/ecosystem/officials/zio-actors",
                "resources/ecosystem/officials/zio-akka-cluster",
                "resources/ecosystem/officials/zio-cache",
                "resources/ecosystem/officials/zio-config",
                "resources/ecosystem/officials/zio-ftp",
                "resources/ecosystem/officials/zio-json",
                "resources/ecosystem/officials/zio-kafka",
                "resources/ecosystem/officials/zio-logging",
                "resources/ecosystem/officials/zio-metrics",
                "resources/ecosystem/officials/zio-nio",
                "resources/ecosystem/officials/zio-optics",
                "resources/ecosystem/officials/zio-prelude",
                "resources/ecosystem/officials/zio-process",
                "resources/ecosystem/officials/zio-query",
                "resources/ecosystem/officials/zio-redis",
                "resources/ecosystem/officials/zio-rocksdb",
                "resources/ecosystem/officials/zio-s3",
                "resources/ecosystem/officials/zio-schema",
                "resources/ecosystem/officials/zio-sqs",
                "resources/ecosystem/officials/zio-telemetry",
                "resources/ecosystem/officials/zio-zmx",
            ],
        },
        {
            type: "category",
            label: "Community Libraries",
            items: [
                "resources/ecosystem/community/index",
                "resources/ecosystem/community/caliban",
                "resources/ecosystem/community/distage",
                "resources/ecosystem/community/logstage",
                "resources/ecosystem/community/munit-zio",
                "resources/ecosystem/community/quill",
                "resources/ecosystem/community/rezilience",
                "resources/ecosystem/community/tranzactio",
                "resources/ecosystem/community/zio-amqp",
                "resources/ecosystem/community/zio-arrow",
                "resources/ecosystem/community/zio-aws",
                "resources/ecosystem/community/zio-aws-s3",
                "resources/ecosystem/community/zio-grpc",
                "resources/ecosystem/community/zio-http",
                "resources/ecosystem/community/zio-k8s",
                "resources/ecosystem/community/zio-kinesis",
                "resources/ecosystem/community/zio-pulsar",
                "resources/ecosystem/community/zio-saga",
                "resources/ecosystem/community/zio-slick-interop",
                "resources/ecosystem/community/zio-test-akka-http",
            ],
        },
        "resources/ecosystem/compatible",
        "resources/ecosystem/tools",
        "resources/ecosystem/templates"
    ]
  },
  "about-sidebar": {
    "About": [
      "about/index",
      "about/coding-guidelines",
      "about/contributing",
      "about/contributing-to-documentation",
      "about/code-of-conduct"
    ]
  }
}
