---
id: zpipeline 
title: "ZPipeline"
---

```scala mdoc:invisible
import zio.stream.{UStream, ZStream, ZPipeline, ZSink}
import zio.{Schedule, Chunk, IO, ZIO}
import zio.Console._
import java.nio.file.Paths
```

## Introduction

A `ZPipeline[+LowerEnv, -UpperEnv, +LowerErr, -UpperErr, +LowerElem, -UpperElem]` is a stream transformer. Pipelines accept a stream as input, and return the transformed stream as output.

ZPipelines can be thought of as a recipe for calling a bunch of methods on a source stream, to yield a new (transformed) stream. A nice mental model is the following type alias:

```scala
type ZPipeline[Env, Err, In, Out] = ZStream[Env, Err, In] => ZStream[Env, Err, Out]
```

There is no fundamental requirement for pipelines to exist, because everything pipelines do can be done directly on a stream. However, because pipelines separate the stream transformation from the source stream itself, it becomes possible to abstract over stream transformations at the level of values, creating, storing, and passing around reusable transformation pipelines that can be applied to many different streams. 

## Creation

### From Function

By using `ZPipeline.map` we convert a function into a pipeline. Let's create a pipeline which converts a stream of strings into a stream of characters:

```scala mdoc:silent:nest
val chars = 
 ZPipeline.map[String, Chunk[Char]](s => Chunk.fromArray(s.toArray)) >>>
   ZPipeline.mapChunks[Chunk[Char], Char](_.flatten)
```

There is also a `ZPipeline.mapZIO` which is an effectful version of this constructor.

## Built-in Pipelines

### Identity

The identity pipeline passes elements through without any modification:

```scala mdoc:silent:nest
ZStream(1,2,3).via(ZPipeline.identity[Int])
// Ouput: 1, 2, 3
```

### Splitting

**ZPipeline.splitOn** — A pipeline that splits strings on a delimiter:

```scala mdoc:silent:nest
ZStream("1-2-3", "4-5", "6", "7-8-9-10")
  .via(ZPipeline.splitOn("-"))
  .map(_.toInt)
// Ouput: 1, 2, 3, 4, 5, 6, 7, 8, 9 10
```

**ZPipeline.splitLines** — A pipeline that splits strings on newlines. Handles both Windows newlines (`\r\n`) and UNIX newlines (`\n`):

```scala mdoc:silent:nest
ZStream("This is the first line.\nSecond line.\nAnd the last line.")
  .via(ZPipeline.splitLines)
// Output: "This is the first line.", "Second line.", "And the last line."
```

**ZPipeline.splitOnChunk** — A pipeline that splits elements on a delimiter and transforms the splits into desired output:

```scala mdoc:silent:nest
ZStream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  .via(ZPipeline.splitOnChunk(Chunk(4, 5, 6)))
// Output: Chunk(1, 2, 3), Chunk(7, 8, 9, 10)
```

### Dropping

**ZPipeline.dropWhile** — Creates a pipeline that starts consuming values as soon as one fails the given predicate:

```scala mdoc:silent:nest
ZStream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  .via(ZPipeline.dropWhile((x: Int) => x <= 5))
// Output: 6, 7, 8, 9, 10
```

The `ZPipeline` also has `dropWhileZIO` which takes an effectful predicate `p: I => ZIO[R, E, Boolean]`.

### Prepending

The `ZPipeline.prepend` creates a pipeline that emits the provided chunks before emitting any other values:

```scala mdoc:silent:nest
ZStream(2, 3, 4).via(
  ZPipeline.prepend(Chunk(0, 1))
)
// Output: 0, 1, 2, 3, 4
```

### Compression

**ZPipeline.deflate** — The `deflate` pipeline compresses a stream of bytes as specified by [RFC 1951](https://tools.ietf.org/html/rfc1951).

```scala mdoc:silent
import zio.stream.ZStream
import zio.stream.ZPipeline.deflate
import zio.stream.compression.{CompressionLevel, CompressionStrategy, FlushMode}

def compressWithDeflate(clearText: ZStream[Any, Nothing, Byte]): ZStream[Any, Nothing, Byte] = {
  val bufferSize: Int = 64 * 1024 // Internal buffer size. Few times bigger than upstream chunks should work well.
  val noWrap: Boolean = false // For HTTP Content-Encoding should be false.
  val level: CompressionLevel = CompressionLevel.DefaultCompression
  val strategy: CompressionStrategy = CompressionStrategy.DefaultStrategy
  val flushMode: FlushMode = FlushMode.NoFlush
  clearText.via(deflate(bufferSize, noWrap, level, strategy, flushMode))
}

def deflateWithDefaultParameters(clearText: ZStream[Any, Nothing, Byte]): ZStream[Any, Nothing, Byte] =
  clearText.via(deflate())
```

**ZPipeline.gzip** — The `gzip` pipeline compresses a stream of bytes as using _gzip_ method:

```scala mdoc:silent:nest
import zio.stream.compression._

ZStream
  .fromFileName("file.txt")
  .via(
    ZPipeline.gzip(
      bufferSize = 64 * 1024,
      level = CompressionLevel.DefaultCompression,
      strategy = CompressionStrategy.DefaultStrategy,
      flushMode = FlushMode.NoFlush
    )
  )
  .run(
    ZSink.fromFileName("file.gz")
  )
```

### Decompression

If we are reading `Content-Encoding: deflate`, `Content-Encoding: gzip` streams, or other such streams of compressed data, the following pipelines can be helpful. Both decompression methods will fail with `CompressionException` when input wasn't properly compressed:

**ZPipeline.inflate** — This pipeline allows decompressing stream of _deflated_ inputs, according to [RFC 1951](https://tools.ietf.org/html/rfc1951).

```scala mdoc:silent:nest
import zio.stream.ZStream
import zio.stream.ZPipeline.{ gunzip, inflate }
import zio.stream.compression.CompressionException

def decompressDeflated(deflated: ZStream[Any, Nothing, Byte]): ZStream[Any, CompressionException, Byte] = {
  val bufferSize: Int = 64 * 1024 // Internal buffer size. Few times bigger than upstream chunks should work well.
  val noWrap: Boolean = false     // For HTTP Content-Encoding should be false.
  deflated.via(inflate(bufferSize, noWrap))
}
```

**ZPipeline.gunzip** — This pipeline can be used to decompress stream of _gzipped_ inputs, according to [RFC 1952](https://tools.ietf.org/html/rfc1952):

```scala mdoc:silent:nest
import zio.stream.ZStream
import zio.stream.ZPipeline.{ gunzip, inflate }
import zio.stream.compression.CompressionException

def decompressGzipped(gzipped: ZStream[Any, Nothing, Byte]): ZStream[Any, CompressionException, Byte] = {
  val bufferSize: Int = 64 * 1024 // Internal buffer size. Few times bigger than upstream chunks should work well.
  gzipped.via(gunzip(bufferSize))
}
```

**ZPipeline.gunzipAuto** — This pipeline can be used to decompress stream of *possibly* _gzipped_ inputs, according to [RFC 1952](https://tools.ietf.org/html/rfc1952). If the input is gzipped, it will be decompressed; if not, it will be passed downstream as-is:

```scala mdoc:silent:nest
import zio.stream.ZStream
import zio.stream.ZPipeline.gunzipAuto
import zio.stream.compression.CompressionException

def decompressMaybeGzipped(maybeGzipped: ZStream[Any, Nothing, Byte]): ZStream[Any, CompressionException, Byte] = {
  val bufferSize: Int = 64 * 1024 // Internal buffer size. Few times bigger than upstream chunks should work well.
  maybeGzipped.via(gunzipAuto(bufferSize))
}
```

### Decoders

ZIO stream has a wide variety of pipelines to decode chunks of bytes into strings:

| Decoder                     | Input          | Output |
|-----------------------------|----------------|--------|
| `ZPipeline.utfDecode`     | Unicode bytes  | String |
| `ZPipeline.utf8Decode`    | UTF-8 bytes    | String |
| `ZPipeline.utf16Decode`   | UTF-16         | String |
| `ZPipeline.utf16BEDecode` | UTF-16BE bytes | String |
| `ZPipeline.utf16LEDecode` | UTF-16LE bytes | String |
| `ZPipeline.utf32Decode`   | UTF-32 bytes   | String |
| `ZPipeline.utf32BEDecode` | UTF-32BE bytes | String |
| `ZPipeline.utf32LEDecode` | UTF-32LE bytes | String |
| `ZPipeline.usASCIIDecode` | US-ASCII bytes | String |

## Operations

### Output Transformation (Mapping)

To transform the _outputs_ of the pipeline, we can use the `ZPipeline#map` combinator for the success channel, and the `ZPipeline#mapError` combinator for the failure channel. Also, the `ZPipeline.mapChunks` takes a function of type `Chunk[O] => Chunk[O2]` and transforms chunks emitted by the pipeline.

### Input Transformation (Contramap)

To transform the _inputs_ of the pipeline, we can use the `ZPipeline#contramap` combinator. It takes a map function of type `J => I` and convert a `ZPipeline[R, E, I, O]` to `ZPipeline[R, E, J, O]`:

```scala
class ZPipeline[-R, +E, -I, +O] {
  final def contramap[J](f: J => I): ZPipeline[R, E, J, O] = ???
}
```

Let's create an integer parser pipeline using `ZPipeline.contramap`:

```scala mdoc:silent:nest
val numbers: ZStream[Any, Nothing, Int] =
 ZStream("1-2-3-4-5")
   .mapConcat(_.split("-"))
   .via(
     ZPipeline.map[String, Int](_.toInt)
   )
```

### Composing

We can compose pipelines in two ways:

1. **Composing Two Pipelines** — One pipeline can be composed with another pipeline, resulting in a composite pipeline:

```scala mdoc:silent:nest
val lines: ZStream[Any, Throwable, String] =
  ZStream
    .fromFileName("file.txt")
    .via(
      ZPipeline.utf8Decode >>> ZPipeline.splitLines
    )
```

2. **Composing ZPipeline with ZSink** — One pipeline can be composed with a sink, resulting in a sink that processes elements by piping them through the pipeline and piping the results into the sink:

```scala mdoc:silent:nest
import java.nio.charset.CharacterCodingException

val refine: ZIO[Any, Throwable, Long] = {
  val stream: ZStream[Any, Throwable, Byte] = ZStream.fromFileName("file.txt")
  val pipeline: ZPipeline[Any, CharacterCodingException, Byte, String] =
    ZPipeline.utf8Decode >>> ZPipeline.splitLines >>> ZPipeline.filter[String](_.contains('₿'))
  val fileSink: ZSink[Any, Throwable, String, Byte, Long] = ZSink
    .fromFileName("file.refined.txt")
    .contramapChunks[String](
      _.flatMap(line => (line + System.lineSeparator()).getBytes())
    )
  val pipeSink: ZSink[Any, Throwable, Byte, Byte, Long] = pipeline >>> fileSink
  stream >>> pipeSink
}
```
