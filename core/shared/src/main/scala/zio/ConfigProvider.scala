/*
 * Copyright 2022-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zio

import scala.annotation.tailrec
import scala.collection.immutable.TreeSet
import scala.util.Try

/**
 * A ConfigProvider is a service that provides configuration given a description
 * of the structure of that configuration.
 */
trait ConfigProvider {
  self =>

  /**
   * Loads the specified configuration, or fails with a config error.
   */
  def load[A](config: Config[A])(implicit trace: Trace): IO[Config.Error, A]

  /**
   * Loads the configuration of type `A` using implicit Config[A], or fails with
   * a config error.
   */
  def load[A](implicit trace: Trace, config: Config[A]): IO[Config.Error, A] = load(config)

  /**
   * Returns a new config provider that will automatically tranform all path
   * configuration names with the specified function. This can be utilized to
   * adapt the names of configuration properties from one naming convention to
   * another.
   */
  final def contramapPath(f: String => String): ConfigProvider =
    ConfigProvider.fromFlat(self.flatten.contramapPath(f))

  /**
   * Flattens this config provider into a simplified config provider that knows
   * only how to deal with flat (key/value) properties.
   */
  def flatten: ConfigProvider.Flat =
    new ConfigProvider.Flat {
      def load[A](path: Chunk[String], config: Config.Primitive[A])(implicit trace: Trace): IO[Config.Error, Chunk[A]] =
        ZIO.die(new NotImplementedError("ConfigProvider#flatten"))

      def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
        ZIO.die(new NotImplementedError("ConfigProvider#flatten"))
    }

  /**
   * Returns a new config provider that will automatically convert all property
   * names to kebab case. This can be utilized to adapt the names of
   * configuration properties from the default naming convention of camel case
   * to the naming convention of a config provider.
   */
  final def kebabCase: ConfigProvider =
    contramapPath(_.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase)

  /**
   * Returns a new config provider that will automatically convert all property
   * names to lower case. This can be utilized to adapt the names of
   * configuration properties from the default naming convention of camel case
   * to the naming convention of a config provider.
   */
  final def lowerCase: ConfigProvider =
    contramapPath(_.toLowerCase)

  /**
   * Returns a new config provider that will automatically nest all
   * configuration under the specified property name. This can be utilized to
   * aggregate separate configuration sources that are all required to load a
   * single configuration value.
   */
  final def nested(name: String): ConfigProvider =
    ConfigProvider.fromFlat(self.flatten.nested(name))

  /**
   * Returns a new config provider that preferentially loads configuration data
   * from this one, but which will fall back to the specified alternate provider
   * if there are any issues loading the configuration from this provider.
   */
  final def orElse(that: ConfigProvider): ConfigProvider =
    ConfigProvider.fromFlat(self.flatten.orElse(that.flatten))

  /**
   * Returns a new config provider that will automatically convert all property
   * names to snake case. This can be utilized to adapt the names of
   * configuration properties from the default naming convention of camel case
   * to the naming convention of a config provider.
   */
  final def snakeCase: ConfigProvider =
    contramapPath(_.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase)

  /**
   * Returns a new config provider that will automatically unnest all
   * configuration from the specified property name.
   */
  final def unnested(name: String): ConfigProvider =
    ConfigProvider.fromFlat(self.flatten.unnested(name))

  /**
   * Returns a new config provider that will automatically convert all property
   * names to upper case. This can be utilized to adapt the names of
   * configuration properties from the default naming convention of camel case
   * to the naming convention of a config provider.
   */
  final def upperCase: ConfigProvider =
    contramapPath(_.toUpperCase)

  /**
   * Returns a new config provider that transforms the config provider with the
   * specified function within the specified path.
   */
  final def within(path: Chunk[String])(f: ConfigProvider => ConfigProvider): ConfigProvider = {
    val unnested = path.foldLeft(self)((configProvider, name) => configProvider.unnested(name))
    val nested   = path.foldRight(f(unnested))((name, configProvider) => configProvider.nested(name))
    nested.orElse(self)
  }
}

object ConfigProvider {

  /**
   * A simplified config provider that knows only how to deal with flat
   * (key/value) properties. Because these providers are common, there is
   * special support for implementing them.
   */
  trait Flat {
    self =>

    import Flat._

    def load[A](path: Chunk[String], config: Config.Primitive[A])(implicit trace: Trace): IO[Config.Error, Chunk[A]]

    def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]]

    def contramapPath(f: String => String): Flat =
      new Flat {
        override def load[A](path: Chunk[String], config: Config.Primitive[A], split: Boolean)(implicit
          trace: Trace
        ): IO[Config.Error, Chunk[A]] =
          self.load(path, config, split)

        def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
          self.enumerateChildren(path)

        def load[A](path: Chunk[String], config: Config.Primitive[A])(implicit
          trace: Trace
        ): IO[Config.Error, Chunk[A]] =
          load(path, config, true)

        override def patch: PathPatch =
          self.patch.mapName(f)
      }

    def load[A](path: Chunk[String], config: Config.Primitive[A], split: Boolean)(implicit
      trace: Trace
    ): IO[Config.Error, Chunk[A]] =
      load(path, config)

    final def nested(name: String): Flat =
      new Flat {
        override def load[A](path: Chunk[String], config: Config.Primitive[A], split: Boolean)(implicit
          trace: Trace
        ): IO[Config.Error, Chunk[A]] =
          self.load(path, config, split)

        def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
          self.enumerateChildren(path)

        def load[A](path: Chunk[String], config: Config.Primitive[A])(implicit
          trace: Trace
        ): IO[Config.Error, Chunk[A]] =
          load(path, config, true)

        override def patch: PathPatch =
          self.patch.nested(name)
      }

    def patch: PathPatch =
      PathPatch.empty

    final def orElse(that: Flat): Flat =
      new Flat {
        override def load[A](path: Chunk[String], config: Config.Primitive[A], split: Boolean)(implicit
          trace: Trace
        ): IO[Config.Error, Chunk[A]] =
          ZIO
            .fromEither(self.patch(path))
            .flatMap(self.load(_, config, split))
            .catchAll(e1 =>
              ZIO
                .fromEither(that.patch(path))
                .flatMap(that.load(_, config, split))
                .catchAll(e2 => ZIO.fail(e1 || e2))
            )

        def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
          for {
            l <- ZIO.fromEither(self.patch(path)).flatMap(self.enumerateChildren).either
            r <- ZIO.fromEither(that.patch(path)).flatMap(that.enumerateChildren).either
            result <- (l, r) match {
                        case (Left(e1), Left(e2)) => ZIO.fail(e1 && e2)
                        case (Left(_), Right(r))  => ZIO.succeed(r)
                        case (Right(l), Left(_))  => ZIO.succeed(l)
                        case (Right(l), Right(r)) => ZIO.succeed(if (l.nonEmpty) l else r)
                      }
          } yield result

        def load[A](path: Chunk[String], config: Config.Primitive[A])(implicit
          trace: Trace
        ): IO[Config.Error, Chunk[A]] =
          load(path, config, true)

        override def patch: PathPatch =
          PathPatch.empty
      }

    final def unnested(name: String): Flat =
      new Flat {
        override def load[A](path: Chunk[String], config: Config.Primitive[A], split: Boolean)(implicit
          trace: Trace
        ): IO[Config.Error, Chunk[A]] =
          self.load(path, config, split)

        def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
          self.enumerateChildren(path)

        def load[A](path: Chunk[String], config: Config.Primitive[A])(implicit
          trace: Trace
        ): IO[Config.Error, Chunk[A]] =
          load(path, config, true)

        override def patch: PathPatch =
          self.patch.unnested(name)
      }
  }

  object Flat {
    sealed trait PathPatch {
      self =>

      import PathPatch._

      def apply[A](path: Chunk[String]): Either[Config.Error, Chunk[String]] = {

        @tailrec
        def loop(path: Chunk[String], patches: List[PathPatch]): Either[Config.Error, Chunk[String]] =
          patches match {
            case AndThen(first, second) :: tail =>
              loop(path, first :: second :: tail)
            case Empty :: tail =>
              loop(path, tail)
            case MapName(f) :: tail =>
              loop(path.map(f), tail)
            case Nested(name) :: tail =>
              loop(name +: path, tail)
            case Unnested(name) :: tail =>
              if (path.headOption.contains(name)) loop(path.tail, tail)
              else Left(Config.Error.MissingData(path, s"Expected $name to be in path in ConfigProvider#unnested"))
            case Nil =>
              Right(path)
          }

        loop(path, List(self))
      }

      def mapName(f: String => String): PathPatch =
        AndThen(self, MapName(f))

      def nested(name: String): PathPatch =
        AndThen(self, Nested(name))

      def unnested(name: String): PathPatch =
        AndThen(self, Unnested(name))
    }

    object PathPatch {

      val empty: PathPatch =
        Empty

      private final case class AndThen(first: PathPatch, second: PathPatch) extends PathPatch
      private case object Empty                                             extends PathPatch
      private final case class MapName(f: String => String)                 extends PathPatch
      private final case class Nested(name: String)                         extends PathPatch
      private final case class Unnested(name: String)                       extends PathPatch
    }

    object util {
      def splitPathString(text: String, escapedDelim: String): Chunk[String] =
        Chunk.fromArray(text.split("\\s*" + escapedDelim + "\\s*"))

      def parsePrimitive[A](
        text: String,
        path: Chunk[String],
        name: String,
        primitive: Config.Primitive[A],
        escapedDelim: String,
        split: Boolean
      ): IO[Config.Error, Chunk[A]] = {
        val name = path.lastOption.getOrElse("<unnamed>")

        if (!split)
          ZIO
            .fromEither(primitive.parse(text))
            .map(Chunk(_))
            .mapError(_.prefixed(path))
        else {

          ZIO
            .foreach(splitPathString(text, escapedDelim))(s => ZIO.fromEither(primitive.parse(s.trim)))
            .mapError(_.prefixed(path))
        }
      }

      def parsePrimitive[A](
        text: String,
        path: Chunk[String],
        name: String,
        primitive: Config.Primitive[A],
        escapedDelim: String
      ): IO[Config.Error, Chunk[A]] =
        parsePrimitive(text, path, name, primitive, escapedDelim, true)
    }
  }

  /**
   * A config provider layer that loads configuration from interactive console
   * prompts, using the default Console service.
   */
  lazy val console: ZLayer[Any, Nothing, ConfigProvider] =
    ZLayer.succeed(consoleProvider)

  lazy val consoleProvider: ConfigProvider =
    consoleProvider()

  def consoleProvider(seqDelim: String = ","): ConfigProvider =
    fromFlat(new Flat {
      override def load[A](path: Chunk[String], primitive: Config.Primitive[A], split: Boolean)(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] = {
        val name        = path.lastOption.getOrElse("<unnamed>")
        val description = primitive.description
        val sourceError = (e: Throwable) =>
          Config.Error.SourceUnavailable(
            path :+ name,
            "There was a problem reading configuration from the console",
            Cause.fail(e)
          )

        for {
          _       <- Console.printLine(s"Please enter ${description} for property ${name}:").mapError(sourceError)
          line    <- Console.readLine.mapError(sourceError)
          results <- Flat.util.parsePrimitive(line, path, name, primitive, ",", split)
        } yield results
      }

      def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
        (for {
          _    <- Console.printLine(s"Enter the keys you want for the table ${path}, separated by commas:")
          keys <- Console.readLine.map(_.split(",").map(_.trim))
        } yield keys.toSet).mapError(e =>
          Config.Error
            .SourceUnavailable(path, "There was a problem reading configuration from the console", Cause.fail(e))
        )

      def load[A](path: Chunk[String], primitive: Config.Primitive[A])(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] =
        load(path, primitive, true)
    })

  lazy val defaultProvider: ConfigProvider =
    envProvider.orElse(propsProvider)

  /**
   * A config provider layer that loads configuration from environment
   * variables, using the default System service.
   */
  lazy val env: ZLayer[Any, Nothing, ConfigProvider] =
    ZLayer.succeed(envProvider)

  /**
   * A config provider that loads configuration from environment variables,
   * using the default System service.
   */
  lazy val envProvider: ConfigProvider =
    fromEnv()

  /**
   * A config provider that loads configuration from command-line arguments.
   * Keys should start with "-" or "--". Boolean flags can be specified as
   * "-flag" or "--flag". Key Value pairs can be specified as "--key=value" or
   * "--key value". Sequences can be specified as "--key value1 value2 ..." or
   * "--key=value1 --key=value2"
   */
  def fromAppArgs(args: ZIOAppArgs, pathDelim: String = ".", seqDelim: Option[String] = None): ConfigProvider = {
    val escapedPathDelim = java.util.regex.Pattern.quote(pathDelim)
    val escapedSeqDelim  = java.util.regex.Pattern.quote(seqDelim.getOrElse(","))

    def makePathString(path: Chunk[String]): String = path.mkString(pathDelim)

    def unmakePathString(pathString: String): Chunk[String] =
      Chunk.fromArray(pathString.split(escapedPathDelim))

    def isKeyValue(s: String): Boolean = s.startsWith("-")

    def splitAtEquals(text: String): (Option[String], Option[String]) = {
      val splitted = text.split("=", 2)
      splitted.headOption.filterNot(_.isEmpty) -> splitted.lift(1)
    }

    def stripKey(s: String): String = {
      val toRemove = '-'
      s.headOption match {
        case Some(c) if c == toRemove => stripKey(s.tail)
        case _                        => s
      }
    }

    sealed trait State
    object State {
      case object Initial                    extends State
      case class KeyOrSequence(key: String)  extends State
      case class ExpectingValue(key: String) extends State
    }

    def loop(args: List[String], state: State): List[(String, String)] =
      args match {
        case h :: t =>
          state match {
            case State.Initial =>
              if (isKeyValue(h))
                splitAtEquals(stripKey(h)) match {
                  case (Some(key), Some(value)) =>
                    (key, value) :: loop(t, State.KeyOrSequence(key))
                  case (Some(key), None) =>
                    loop(t, State.ExpectingValue(key))
                  case (None, _) =>
                    // First argument must be a valid key, dropping until a valid key is found
                    loop(t, State.Initial)
                }
              else
                // First argument is not a key, dropping until a valid key is found
                loop(t, State.Initial)
            case State.KeyOrSequence(previousKey) =>
              if (isKeyValue(h))
                splitAtEquals(stripKey(h)) match {
                  case (Some(key), Some(value)) =>
                    (key, value) :: loop(t, State.KeyOrSequence(key))
                  case (Some(key), None) =>
                    loop(t, State.ExpectingValue(key))
                  case (None, Some(value)) =>
                    (previousKey, value) :: loop(t, State.KeyOrSequence(previousKey))
                  case (None, None) =>
                    // no key or value, dropping
                    loop(t, State.KeyOrSequence(previousKey))
                }
              else
                (previousKey, h) :: loop(t, State.KeyOrSequence(previousKey))
            case State.ExpectingValue(key) =>
              if (isKeyValue(h))
                // a key is found, so no value for previous key
                // we add the previous with "true" value, assuming it's a boolean and continue with argument list.
                // This is to support boolean flags like -v, -h, etc.
                // Using state.Initial as adding multiple boolean values is not allowed.
                (key, "true") :: loop(args, State.Initial)
              else
                (key, h) :: loop(t, State.KeyOrSequence(key))

          }
        case Nil =>
          state match {
            case State.Initial             => Nil
            case State.KeyOrSequence(key)  => Nil
            case State.ExpectingValue(key) =>
              // we expected a value for the key, but no more arguments are left
              // so we add the key with "true" value, assuming it's a boolean
              // This is to support boolean flags like -v, -h, etc.
              List((key, "true"))
          }
      }

    lazy val map =
      loop(args.getArgs.toList, State.Initial)
        .groupBy(_._1)
        .mapValues(values => Chunk.fromIterable(values.map(_._2)))
        .toMap

    fromFlat(new Flat {
      val mapWithIndexSplit = splitIndexInKeys(map, unmakePathString, makePathString)

      override def load[A](path: Chunk[String], primitive: Config.Primitive[A], split: Boolean)(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] = {
        val pathString = makePathString(path)
        val name       = path.lastOption.getOrElse("<unnamed>")
        val valueOpt   = mapWithIndexSplit.get(pathString)

        for {
          values <- ZIO
                      .fromOption(valueOpt)
                      .mapError(_ => Config.Error.MissingData(path, s"Expected ${pathString} to be set in arguments"))
          results <-
            ZIO.foreach(values)(value => Flat.util.parsePrimitive(value, path, name, primitive, escapedSeqDelim, split))
        } yield results.flatten
      }

      lazy val keyPaths = TreeSet.empty[String] ++ mapWithIndexSplit.keySet

      def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
        ZIO.succeed {
          val pathString = if (path.nonEmpty) path.mkString("", pathDelim, pathDelim) else ""
          keyPaths
            .iteratorFrom(pathString)
            .takeWhile(_.startsWith(pathString))
            .flatMap(s => unmakePathString(s).slice(path.length, path.length + 1))
            .toSet
        }

      def load[A](path: Chunk[String], primitive: Config.Primitive[A])(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] =
        load(path, primitive, seqDelim.isDefined)
    })
  }

  /**
   * Constructs a ConfigProvider that loads configuration information from
   * environment variables, using the default System service and the specified
   * delimiter strings.
   */
  def fromEnv(pathDelim: String = "_", seqDelim: String = ","): ConfigProvider =
    fromFlat(new Flat {
      val sourceUnavailable = (path: Chunk[String]) =>
        (e: Throwable) =>
          Config.Error.SourceUnavailable(path, "There was a problem reading environment variables", Cause.fail(e))

      val escapedSeqDelim  = java.util.regex.Pattern.quote(seqDelim)
      val escapedPathDelim = java.util.regex.Pattern.quote(pathDelim)

      def makePathString(path: Chunk[String]): String = path.mkString(pathDelim).toUpperCase

      def unmakePathString(pathString: String): Chunk[String] =
        Chunk.fromArray(pathString.split(escapedPathDelim))

      override def load[A](path: Chunk[String], primitive: Config.Primitive[A], split: Boolean)(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] = {
        val pathString = makePathString(path)
        val name       = path.lastOption.getOrElse("<unnamed>")

        for {
          valueOpt <- zio.System.env(pathString).mapError(sourceUnavailable(path))
          value <-
            ZIO
              .fromOption(valueOpt)
              .mapError(_ => Config.Error.MissingData(path, s"Expected ${pathString} to be set in the environment"))
          results <- Flat.util.parsePrimitive(value, path, name, primitive, escapedSeqDelim, split)
        } yield results
      }

      def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
        zio.System.envs.map { envs =>
          val keyPaths = Chunk.fromIterable(envs.keys).map(_.toUpperCase).map(unmakePathString)

          keyPaths.filter(_.startsWith(path.map(_.toUpperCase))).map(_.drop(path.length).take(1)).flatten.toSet

        }.mapError(sourceUnavailable(path))

      def load[A](path: Chunk[String], primitive: Config.Primitive[A])(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] =
        load(path, primitive, true)
    }).contramapPath(_.replaceAll("-", "_"))

  /**
   * Constructs a new ConfigProvider from a key/value (flat) provider, where
   * nesting is embedded into the string keys.
   */
  def fromFlat(flat: Flat): ConfigProvider =
    new ConfigProvider {

      import Config._

      def extend[A, B](leftDef: Int => A, rightDef: Int => B)(left: Chunk[A], right: Chunk[B]): (Chunk[A], Chunk[B]) = {
        val leftPad = Chunk.unfold(left.length) { index =>
          if (index >= right.length) None else Some(leftDef(index) -> (index + 1))
        }
        val rightPad = Chunk.unfold(right.length) { index =>
          if (index >= left.length) None else Some(rightDef(index) -> (index + 1))
        }

        val leftExtension  = left ++ leftPad
        val rightExtension = right ++ rightPad

        (leftExtension, rightExtension)
      }

      def returnEmptyListIfValueIsNil[A](
        prefix: Chunk[String],
        continue: ZIO[Any, Error, Chunk[Chunk[A]]]
      ): ZIO[Any, Error, Chunk[Chunk[A]]] =
        (for {
          possibleNil <- flat.load(prefix, Config.Text, split = false)
          result <- if (possibleNil.headOption.exists(string => string.toLowerCase().trim == "<nil>"))
                      ZIO.succeed(Chunk(Chunk.empty))
                    else continue
        } yield result).orElse(continue)

      def loop[A](prefix: Chunk[String], config: Config[A], split: Boolean, patchTail: Boolean = true)(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] =
        config match {
          case fallback: Fallback[A] =>
            loop(prefix, fallback.first, split).catchAll(e1 =>
              if (fallback.condition(e1)) loop(prefix, fallback.second, split).catchAll(e2 => ZIO.fail(e1 || e2))
              else ZIO.fail(e1)
            )

          case Described(config, _) => loop(prefix, config, split)

          case Lazy(thunk) => loop(prefix, thunk(), split)

          case MapOrFail(original, f) =>
            loop(prefix, original, split).flatMap { as =>
              ZIO.foreach(as)(a => ZIO.fromEither(f(a)).mapError(_.prefixed(prefix)))
            }

          case Sequence(config) =>
            for {
              patchedPrefix <- ZIO.fromEither(flat.patch(prefix))
              indices <- flat
                           .enumerateChildren(patchedPrefix)
                           .flatMap(set => indicesFrom(set))

              values <-
                if (indices.isEmpty) {
                  returnEmptyListIfValueIsNil(
                    prefix = patchedPrefix,
                    continue = loop(prefix, config, split = true).map(Chunk(_))
                  )
                } else
                  ZIO
                    .foreach(Chunk.fromIterable(indices)) { index =>
                      loop(prefix :+ BracketedIndex(index), config, split = true)
                    }
                    .map { chunkChunk =>
                      val flattened = chunkChunk.flatten
                      if (flattened.isEmpty) Chunk(Chunk.empty)
                      else Chunk(flattened)
                    }
            } yield values

          case Nested(name, config) =>
            loop(prefix :+ name, config, split)

          case Switch(config, map) =>
            loop(prefix, config, split).flatMap { as =>
              ZIO
                .foreach(as) { a =>
                  map.get(a) match {
                    case Some(config) => loop(prefix, config, split)
                    case None         => ZIO.fail(Config.Error.InvalidData(prefix, s"Invalid case: ${a}"))
                  }
                }
                .map(_.flatten)
            }

          case table: Table[valueType] =>
            import table.valueConfig
            for {
              patchedPrefix <- ZIO.fromEither(flat.patch(prefix))
              keys          <- flat.enumerateChildren(patchedPrefix)
              values <- ZIO.foreach(Chunk.fromIterable(keys))(key =>
                          loop(prefix ++ Chunk(key), valueConfig, split, patchTail = false)
                        )
            } yield
              if (values.isEmpty) Chunk(Map.empty[String, valueType])
              else values.transpose.map(values => keys.zip(values).toMap)

          case zipped: Zipped[leftType, rightType, c] =>
            import zipped.{left, right, zippable}
            for {
              l <- loop(prefix, left, split).either
              r <- loop(prefix, right, split).either
              result <- (l, r) match {
                          case (Left(e1), Left(e2)) => ZIO.fail(e1 && e2)
                          case (Left(e1), Right(_)) => ZIO.fail(e1)
                          case (Right(_), Left(e2)) => ZIO.fail(e2)
                          case (Right(l), Right(r)) =>
                            val path = prefix.mkString(".")

                            def lfail(index: Int): Either[Config.Error, leftType] =
                              Left(
                                Config.Error.MissingData(
                                  prefix,
                                  s"The element at index ${index} in a sequence at ${path} was missing"
                                )
                              )

                            def rfail(index: Int): Either[Config.Error, rightType] =
                              Left(
                                Config.Error.MissingData(
                                  prefix,
                                  s"The element at index ${index} in a sequence at ${path} was missing"
                                )
                              )

                            val (ls, rs) = extend(lfail, rfail)(l.map(Right(_)), r.map(Right(_)))

                            ZIO.foreach(ls.zip(rs)) { case (l, r) =>
                              ZIO.fromEither(l).zipWith(ZIO.fromEither(r))(zippable.zip(_, _))
                            }
                        }
            } yield result

          case Constant(value) =>
            ZIO.succeed(Chunk(value))

          case Fail(message) =>
            ZIO.fail(Config.Error.MissingData(prefix, message))

          case primitive: Primitive[A] =>
            for {
              prefix <- if (patchTail) ZIO.fromEither(flat.patch(prefix))
                        else ZIO.fromEither(flat.patch(prefix.dropRight(1))).map(_ :+ prefix.last)
              vs <- flat.load(prefix, primitive, split)
              result <- if (vs.isEmpty)
                          ZIO.fail(primitive.missingError(prefix.lastOption.getOrElse("<n/a>")))
                        else ZIO.succeed(vs)
            } yield result
        }

      def load[A](config: Config[A])(implicit trace: Trace): IO[Config.Error, A] =
        loop(Chunk.empty, config, false).flatMap { chunk =>
          chunk.headOption match {
            case Some(a) => ZIO.succeed(a)
            case _ =>
              ZIO.fail(Config.Error.MissingData(Chunk.empty, s"Expected a single value having structure ${config}"))
          }
        }

      override def flatten: Flat = flat
    }

  /**
   * Constructs a ConfigProvider using a map and the specified delimiter string,
   * which determines how to split the keys in the map into path segments.
   */
  def fromMap(map: Map[String, String], pathDelim: String = ".", seqDelim: String = ","): ConfigProvider =
    fromFlat(new Flat {
      val escapedSeqDelim   = java.util.regex.Pattern.quote(seqDelim)
      val escapedPathDelim  = java.util.regex.Pattern.quote(pathDelim)
      val mapWithIndexSplit = splitIndexInKeys(map, unmakePathString, makePathString)

      def makePathString(path: Chunk[String]): String = path.mkString(pathDelim)

      def unmakePathString(pathString: String): Chunk[String] =
        Chunk.fromArray(pathString.split(escapedPathDelim))

      override def load[A](path: Chunk[String], primitive: Config.Primitive[A], split: Boolean)(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] = {
        val pathString = makePathString(path)
        val name       = path.lastOption.getOrElse("<unnamed>")
        val valueOpt   = mapWithIndexSplit.get(pathString)

        for {
          value <- ZIO
                     .fromOption(valueOpt)
                     .mapError(_ => Config.Error.MissingData(path, s"Expected ${pathString} to be set in properties"))
          results <- Flat.util.parsePrimitive(value, path, name, primitive, escapedSeqDelim, split)
        } yield results
      }

      lazy val keyPaths = TreeSet.empty[String] ++ mapWithIndexSplit.keySet

      def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
        ZIO.succeed {
          val pathString = if (path.nonEmpty) path.mkString("", pathDelim, pathDelim) else ""
          keyPaths
            .iteratorFrom(pathString)
            .takeWhile(_.startsWith(pathString))
            .flatMap(s => unmakePathString(s).slice(path.length, path.length + 1))
            .toSet
        }

      def load[A](path: Chunk[String], primitive: Config.Primitive[A])(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] =
        load(path, primitive, true)
    })

  /**
   * Constructs a ConfigProvider that loads configuration information from
   * system properties, using the default System service and the specified
   * delimiter strings.
   */
  def fromProps(pathDelim: String = ".", seqDelim: String = ","): ConfigProvider =
    fromFlat(new Flat {
      val sourceUnavailable = (path: Chunk[String]) =>
        (e: Throwable) => Config.Error.SourceUnavailable(path, "There was a problem reading properties", Cause.fail(e))

      val escapedSeqDelim  = java.util.regex.Pattern.quote(seqDelim)
      val escapedPathDelim = java.util.regex.Pattern.quote(pathDelim)

      def makePathString(path: Chunk[String]): String = path.mkString(pathDelim)

      def unmakePathString(pathString: String): Chunk[String] =
        Chunk.fromArray(pathString.split(escapedPathDelim))

      override def load[A](path: Chunk[String], primitive: Config.Primitive[A], split: Boolean)(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] = {
        val pathString = makePathString(path)
        val name       = path.lastOption.getOrElse("<unnamed>")

        for {
          valueOpt <- zio.System.property(pathString).mapError(sourceUnavailable(path))
          value <- ZIO
                     .fromOption(valueOpt)
                     .mapError(_ => Config.Error.MissingData(path, s"Expected ${pathString} to be set in properties"))
          results <- Flat.util.parsePrimitive(value, path, name, primitive, escapedSeqDelim, split)
        } yield results
      }

      def enumerateChildren(path: Chunk[String])(implicit trace: Trace): IO[Config.Error, Set[String]] =
        zio.System.properties.map { envs =>
          val keyPaths = Chunk.fromIterable(envs.keys).map(unmakePathString)

          keyPaths.filter(_.startsWith(path)).map(_.drop(path.length).take(1)).flatten.toSet
        }.mapError(sourceUnavailable(path))

      def load[A](path: Chunk[String], primitive: Config.Primitive[A])(implicit
        trace: Trace
      ): IO[Config.Error, Chunk[A]] =
        load(path, primitive, true)
    })

  /**
   * A config provider layer that loads configuration from system properties,
   * using the default System service.
   */
  lazy val props: ZLayer[Any, Nothing, ConfigProvider] =
    ZLayer.succeed(propsProvider)

  /**
   * A configuration provider that loads configuration from system properties,
   * using the default System service.
   */
  lazy val propsProvider: ConfigProvider =
    fromProps()

  /**
   * The tag that describes the ConfigProvider service.
   */
  lazy val tag: Tag[ConfigProvider] = Tag[ConfigProvider]

  private def indicesFrom(indices: Set[String]) =
    ZIO
      .foreach(indices) { index =>
        ZIO.fromOption(index match {
          case BracketedIndex(index) => Some(index)
          case _                     => None
        })
      }
      .mapBoth(_ => Chunk.empty, set => Chunk.fromIterable(set).sorted)
      .either
      .map(_.merge)

  private object BracketedIndex {
    private lazy val indexRegex = """(\[(\d+)\])""".stripMargin.r

    def apply(value: Int): String = s"[${value}]"

    def unapply(value: String): Option[Int] =
      for {
        regexMatched  <- indexRegex.findPrefixMatchOf(value).filter(_.group(0).nonEmpty)
        possibleIndex <- Option(regexMatched.group(2))
        index         <- Try(possibleIndex.toInt).toOption
      } yield index
  }

  private def splitIndexInKeys[V](
    map: Map[String, V],
    unmakePathString: String => Chunk[String],
    makePathString: Chunk[String] => String
  ): Map[String, V] =
    map.map { case (pathString, value) =>
      val keyWithIndex =
        for {
          key <- unmakePathString(pathString)
          keyWithIndex <-
            splitIndexFrom(key) match {
              case Some((key, index)) => Chunk(key, BracketedIndex(index))
              case None               => Chunk(key)
            }
        } yield keyWithIndex

      makePathString(keyWithIndex) -> value
    }

  private lazy val strIndexRegex = """(^.+)(\[(\d+)\])$""".stripMargin.r

  private def splitIndexFrom(key: String): Option[(String, Int)] =
    strIndexRegex
      .findPrefixMatchOf(key)
      .filter(_.group(0).nonEmpty)
      .flatMap { regexMatched =>
        val optionalString: Option[String] = Option(regexMatched.group(1))
          .flatMap(s => if (s.isEmpty) None else Some(s))

        val optionalIndex: Option[Int] = Option(regexMatched.group(3))
          .flatMap(s => if (s.isEmpty) None else Try(s.toInt).toOption)

        optionalString.flatMap(str => optionalIndex.map(ind => (str, ind)))
      }
}
