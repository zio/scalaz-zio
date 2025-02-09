# Benchmarks

Benchmarks are implemented using [JMH](http://openjdk.java.net/projects/code-tools/jmh) and
run with [sbt-jmh plugin](https://github.com/ktoso/sbt-jmh).

To execute set of benchmarks from one class type following in `sbt` terminal:

```
benchmarks/jmh:run .*YourClassWithBenchmarks
```

Alternatively, to minimize noise, a measurement should be performed by

- creating the benchmarks JAR

```
sbt benchmarks/assembly
```

- rebooting to a console

```
systemctl set-default multi-user.target
reboot
```

- running the JAR from the console

```
java -jar benchmarks/target/scala-2.13/benchmarks.jar .*YourClassWithBenchmarks
```

Please consult [JMH](http://openjdk.java.net/projects/code-tools/jmh) and [sbt-jmh plugin](https://github.com/ktoso/sbt-jmh)
websites for more details about creating and running benchmarks. Also, refer to [JMH Playground](https://github.com/Valloric/jmh-playground) for [benchmarking pitfalls](https://github.com/Valloric/jmh-playground?tab=readme-ov-file#benchmarking-pitfalls-to-be-aware-of) and [JMH tips and tricks](https://github.com/Valloric/jmh-playground?tab=readme-ov-file#jmh-tips--tricks).

