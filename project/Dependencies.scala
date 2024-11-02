import sbt.*

object Dependencies {
  val ZioMetricsConnectorsVersion      = "2.3.1"
  val ZioHttpVersion                   = "3.0.1"
  val IzumiVersion                     = "1.2.11"
  val ZioConfigVersion                 = "4.0.2"
  val ZioFtpVersion                    = "0.4.2"
  val ZioJsonVersion                   = "0.6.2"
  val ZioPreludeVersion                = "1.0.0-RC27"
  val ZioProcessVersion                = "0.7.2"
  val ZioRocksDBVersion                = "0.4.4"
  val ZioS3Version                     = "0.4.3"
  val ZioSchemaVersion                 = "1.3.0"
  val ZioSqsVersion                    = "0.6.0"
  val ZioOpenTracingVersion            = "2.0.3"
  val ZioInteropCatsVersion            = "23.1.0.2"
  val ZioInteropScalaz7xVersion        = "7.3.3.0"
  val ZioInteropReactiveStreamsVersion = "2.0.2"
  val ZioInteropTwitterVersion         = "21.2.0.2.2"
  val ZioZmxVersion                    = "0.0.13"
  val ZioQueryVersion                  = "0.7.4"
  val ZioMockVersion                   = "1.0.0-RC12"
  val ZioAkkaClusterVersion            = "0.3.0"
  val ZioCacheVersion                  = "0.2.3"
  val ZioKafkaVersion                  = "2.7.4"
  val ZioLoggingVersion                = "2.3.0"
  val ZioNioVersion                    = "2.0.2"
  val ZioOpticsVersion                 = "0.2.1"

  val `zio-http`                    = "dev.zio"        %% "zio-http"                    % ZioHttpVersion
  val `distage-core`                = "io.7mind.izumi" %% "distage-core"                % IzumiVersion
  val `logstage-core`               = "io.7mind.izumi" %% "logstage-core"               % IzumiVersion
  val `zio-config`                  = "dev.zio"        %% "zio-config"                  % ZioConfigVersion
  val `zio-config-magnolia`         = "dev.zio"        %% "zio-config-magnolia"         % ZioConfigVersion
  val `zio-config-typesafe`         = "dev.zio"        %% "zio-config-typesafe"         % ZioConfigVersion
  val `zio-config-refined`          = "dev.zio"        %% "zio-config-refined"          % ZioConfigVersion
  val `zio-ftp`                     = "dev.zio"        %% "zio-ftp"                     % ZioFtpVersion
  val `zio-json`                    = "dev.zio"        %% "zio-json"                    % ZioJsonVersion
  val `zio-nio`                     = "dev.zio"        %% "zio-nio"                     % ZioNioVersion
  val `zio-optics`                  = "dev.zio"        %% "zio-optics"                  % ZioOpticsVersion
  val `zio-akka-cluster`            = "dev.zio"        %% "zio-akka-cluster"            % ZioAkkaClusterVersion
  val `zio-cache`                   = "dev.zio"        %% "zio-cache"                   % ZioCacheVersion
  val `zio-kafka`                   = "dev.zio"        %% "zio-kafka"                   % ZioKafkaVersion
  val `zio-logging`                 = "dev.zio"        %% "zio-logging"                 % ZioLoggingVersion
  val `zio-logging-slf4j`           = "dev.zio"        %% "zio-logging-slf4j"           % ZioLoggingVersion
  val `zio-prelude`                 = "dev.zio"        %% "zio-prelude"                 % ZioPreludeVersion
  val `zio-process`                 = "dev.zio"        %% "zio-process"                 % ZioProcessVersion
  val `zio-rocksdb`                 = "dev.zio"        %% "zio-rocksdb"                 % ZioRocksDBVersion
  val `zio-s3`                      = "dev.zio"        %% "zio-s3"                      % ZioS3Version
  val `zio-schema`                  = "dev.zio"        %% "zio-schema"                  % ZioSchemaVersion
  val `zio-sqs`                     = "dev.zio"        %% "zio-sqs"                     % ZioSqsVersion
  val `zio-opentracing`             = "dev.zio"        %% "zio-opentracing"             % ZioOpenTracingVersion
  val `zio-interop-cats`            = "dev.zio"        %% "zio-interop-cats"            % ZioInteropCatsVersion
  val `zio-interop-scalaz7x`        = "dev.zio"        %% "zio-interop-scalaz7x"        % ZioInteropScalaz7xVersion
  val `zio-interop-reactivestreams` = "dev.zio"        %% "zio-interop-reactivestreams" % ZioInteropReactiveStreamsVersion
  val `zio-interop-twitter`         = "dev.zio"        %% "zio-interop-twitter"         % ZioInteropTwitterVersion
  val `zio-zmx`                     = "dev.zio"        %% "zio-zmx"                     % ZioZmxVersion
  val `zio-query`                   = "dev.zio"        %% "zio-query"                   % ZioQueryVersion
  val `zio-mock`                    = "dev.zio"        %% "zio-mock"                    % ZioMockVersion
  val `zio-metrics-connectors`      = "dev.zio"        %% "zio-metrics-connectors"      % ZioMetricsConnectorsVersion
  val `zio-metrics-connectors-prometheus` =
    "dev.zio" %% "zio-metrics-connectors-prometheus" % ZioMetricsConnectorsVersion
}
