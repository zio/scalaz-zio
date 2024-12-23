---
id: frequency 
title: "Frequency"
---

A `Frequency` represents the number of occurrences of specified values. We can think of a `Frequency` as a set of counters associated with each value except that new counters will automatically be created when new values are observed.

Essentially, a `Frequency` is a set of related counters sharing the same name and tags. The counters are set apart from each other by an additional configurable tag. The values of the tag represent the observed distinct values.

## API
```scala
object Metric {
  def frequency(name: String): Frequency[String] = ???
}
```
## Use Cases

Sets are used to count the occurrences of distinct string values:
- Tracking number of invocations for each service, for an application that uses logical names for its services.
- Tracking frequency of different types of failures.

## Examples

Create a `Frequency` to observe the occurrences of unique `Strings`. It can be applied to effects yielding a `String`:

```scala mdoc:silent:nest
import zio.metrics._

val freq = Metric.frequency("MySet")
```

Now we can generate some keys within an effect and start counting the occurrences for each value:

```scala mdoc:silent:nest
import zio._

(Random.nextIntBounded(10).map(v => s"MyKey-$v") @@ freq).repeatN(100)
```
