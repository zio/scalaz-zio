#!/usr/bin/env bash

packages=(
  "@zio.dev/izumi-reflect"
  "@zio.dev/zio-aws"
  "@zio.dev/zio-cache"
  "@zio.dev/zio-cli"
  "@zio.dev/zio-config"
  "@zio.dev/zio-direct"
  "@zio.dev/zio-dynamodb"
  "@zio.dev/zio-ftp"
  "@zio.dev/zio-http"
  "@zio.dev/zio-json"
  "@zio.dev/zio-kafka"
  "@zio.dev/zio-lambda"
  "@zio.dev/zio-logging"
  "@zio.dev/zio-metrics-connectors"
  "@zio.dev/zio-parser"
  "@zio.dev/zio-prelude"
  "@zio.dev/zio-process"
  "@zio.dev/zio-profiling"
  "@zio.dev/zio-query"
  "@zio.dev/zio-quill"
  "@zio.dev/zio-redis"
  "@zio.dev/zio-rocksdb"
  "@zio.dev/zio-s3"
  "@zio.dev/zio-sbt"
  "@zio.dev/zio-schema"
  "@zio.dev/zio-sqs"
  "@zio.dev/zio-telemetry"
  "@zio.dev/zio2-interop-cats2"
  # this seems to be deleted and no longer exists
  # "@zio.dev/zio2-interop-cats3"
)

# Construct the package add command by joining the packages with a space
cmd="yarn add ${packages[@]}"

# Run the command
eval $cmd