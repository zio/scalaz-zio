---
id: fhir-indexer
title: "Fhir-indexer"
---

[Fhir-indexer](https://github.com/royashcenazi/fhir-indexer) is a ZIO based library for fetching FHIR resources fast and easy.

## Introduction

Welcome to the FHIR Indexing with ZIO and HAPI FHIR repository! This open-source project aims to provide a robust and efficient solution for indexing FHIR (Fast Healthcare Interoperability Resources) based APIs. The project leverages the power of the ZIO framework to handle concurrency and asynchronicity, while wrapping around the widely-used HAPI FHIR library for working with FHIR resources.

## Features

- **Asynchronous and Concurrent Indexing**: The project takes advantage of ZIO's powerful concurrency model to efficiently index FHIR resources from multiple APIs simultaneously.

- **Modular and Extensible Design**: The codebase is structured in a modular way, allowing easy extension and customization to fit different FHIR-based API implementations.

- **Authentication Support**: The library supports various authentication mechanisms for accessing secure FHIR endpoints, ensuring data privacy and security.

- **Resource Filtering and Transformation**: Users can define custom filters and transformations to tailor the indexing process according to their specific requirements.

## Installation

### SBT

`libraryDependencies += "io.github.royashcenazi" % "fhir-indexer" % "v0.0.2"`

### Maven

```xml
   <dependency>
        <groupId>io.github.royashcenazi</groupId>
        <artifactId>fhir-indexer</artifactId>
        <version>v0.0.2</version>
    </dependency>
```

## Example

```scala
object ZioApp extends ZIOAppDefault {

  class ConditionIndexer(override val hapiFhirClient: FHIRHapiClient) extends ApiIndexer {
    override type ProcessResult = Unit

    override def indexApi[T <: IParam](searchMetadata: RequestMetadata[T]): ZIO[Any, Throwable, Unit] = {
      for {
        data <- createPagedApiIndexingFlow[Encounter, T](searchMetadata)
        _ <- ZIO.log(s"Processed api: ${getClass.getSimpleName} with ${data.size} entries")
      } yield ()
    }
  }

  object ConditionIndexer {
    val layer: ZLayer[FHIRHapiClient, Nothing, ConditionIndexer] = ZLayer {
      for {
        hapiClient <- ZIO.service[FHIRHapiClient]
      } yield new ConditionIndexer(hapiClient)
    }
  }

  def myApp: ZIO[ConditionIndexer, Nothing, Exit[Throwable, Unit]] = {
    val requestMetadata = RequestMetadata[TokenClientParam]("")
    for {
      conditionsService <- ZIO.service[ConditionIndexer]
      zio = conditionsService.indexApi[TokenClientParam](requestMetadata)
    } yield {
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(zio)
      }
    }
  }

  object FhirConfigTest {
    val layer: zio.ZLayer[Any, Nothing, FhirIndexingConfig] =
      zio.ZLayer.succeed(FhirIndexingConfig(url = "",
        authUrl ="/oauth2/token",
        clientId = "",
        secret = ""))
  }


  override def run: ZIO[Any, Throwable, Exit[Throwable, Unit]] = {
    myApp.debug("example")
      .provide(
        Client.default,
        FhirConfigTest.layer,
        FhirAuthClientImpl.layer(),
        FHIRHapiClientImpl.layer(),
        ConditionIndexer.layer
      )
  }
}
```

Happy indexing!