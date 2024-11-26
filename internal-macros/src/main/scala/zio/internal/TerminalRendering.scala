package zio.internal

import zio.internal.ansi._

object TerminalRendering {
  def circularityError(circularDependencies: List[(String, String)]): String = {
    val circularDependencyMessage =
      circularDependencies.map { case (a0, b0) =>
        val a = a0.red.bold.underlined
        val b = b0.cyan.bold
        s"""${" Circular Dependency Detected ".red.inverted}
           |
           |A layer simultaneously requires and is required by another:
           |
           |  ${"◉".faint} $b
           |  ${"╰─◉".faint.red} $a
           |    ${"╰─ ◉".faint} $b
           |""".stripMargin.indent(1)

      }.mkString("\n")

    s"""
       |
       |${title("ZLayer Error").red}
       |
       |$circularDependencyMessage
       |       
       |${line.red}
       |
       |""".stripMargin
  }

  def missingLayersError(
    toplevel: List[String],
    transitive: Map[String, List[String]] = Map.empty,
    isUsingProvideSome: Boolean = true
  ) = {

    println(toplevel.mkString("\n"))
    println(transitive.mkString("\n"))

    var index   = 0
    def next    = { index += 1; index }
    var indices = Map.empty[String, String]
    def id(layer: String) = indices.getOrElse(
      layer, {
        val i = s"$next.".faint
        indices += (layer -> i)
        i
      }
    )

    val topLevelString = toplevel.map { layer =>
      s"${id(layer)} ${layer.magenta.bold}"
    }.mkString("\n").indent(3)

    val transitiveStrings = transitive.map { case (layer, deps) =>
      val depsString = deps.map { dep =>
        s"${id(dep)} ${dep.magenta.bold}"
      }.mkString("\n")
      s"Required by ${layer.cyan}\n" + depsString
    }.mkString("\n\n").indent(3)

    val errors =
      List(topLevelString, transitiveStrings).filter(_.trim.nonEmpty).mkString("\n\n")

    val message =
      if (indices.size > 1) {
        s"Please provide layers for the following ${indices.size.toString.underlined} types:"
      } else {
        s"Please provide a layer for the following type:"
      }

    val allMissingTypes = (toplevel ++ transitive.values.flatten)

    val provideSomeSuggestion =
      if (isUsingProvideSome) {
        s"""
 Alternatively, you may add them to the remainder type ascription:
 
   ${methodName("provideSome")}[${allMissingTypes.map(_.magenta.bold).mkString(" & ")}]
"""

      } else {
        ""
      }

    s"""
       |
       |${title("ZLayer Error").red}
       |
       | ${message.bold}
       |
       |$errors
       |$provideSomeSuggestion      
       |${line.red}
       |
       |""".stripMargin
  }

  def missingLayersForZIOApp(value: Set[String]): String =
    missingLayersCustomError(value, "effect", "ZIO APP ERROR")

  def missingLayersForZIOSpec(value: Set[String]): String =
    missingLayersCustomError(value, "suite", "ZIO SPEC ERROR")

  def missingLayersCustomError(value: Set[String], effectType: String, titleString: String): String = {

    val missingLayers = value.toList.map(_.magenta.bold)
    val missingLayersString = missingLayers.zipWithIndex.map { case (layer, index) =>
      val i = s"${index + 1}.".faint
      s"$i $layer"
    }.mkString("\n").indent(3)

    val message =
      if (missingLayers.size > 1)
        s"Your $effectType requires services that are not in the environment.\n".bold +
          s"Please provide layers for the following ${missingLayers.size.toString.underlined} types:"
      else
        s"Your $effectType requires a service that is not in the environment.\n".bold +
          s"Please provide a layer for the following type:"

    s"""
       |
       |${title(titleString).red}
       |
       |${message.indent(1)}
       |
       |$missingLayersString
       |
       | Call your $effectType's ${"provide".green.bold} method with the layers you need.
       | You can read more about layers and providing services here:
       | 
       |   https://zio.dev/reference/contextual/
       |
       |${line.red}
       |
       |""".stripMargin
  }

  def unusedLayersError(layers: List[String]) = {

    val layerStrings = layers.zipWithIndex.map { case (layer, index) =>
      val i = s"${index + 1}.".faint
      s"$i ${layer.cyan}"
    }.mkString("\n").indent(3)

    s"""
       |${title("ZLayer Warning").yellow}
       |
       | ${"You have provided more than is required.".bold}
       | You may remove the following ${pluralizeLayers(layers.size).bold}:
       |   
       |$layerStrings
       |  
       |${line.yellow}
       |
       |""".stripMargin
  }

  def unusedProvideSomeLayersError(types: List[String]) = {

    val typeStrings = types.zipWithIndex.map { case (tpe, index) =>
      val i = s"${index + 1}.".faint
      s"$i ${tpe.magenta}"
    }.mkString("\n").indent(3)

    s"""
       |${title("ZLayer Warning").yellow}
       |
       | ${s"You have provided more arguments to ${methodName("provideSome")} than is required.".bold}
       | You may remove the following ${pluralizeTypes(types.size).bold}:
       |   
       |$typeStrings
       |  
       |${line.yellow}
       |
       |""".stripMargin
  }

  def ambiguousLayersError(duplicates: List[(String, List[String])]): String = {

    val duplicatesString = duplicates.map { case (tpe, layers) =>
      // each layer numbered on its own line:
      val numberedLayers = layers.zipWithIndex.map { case (l, i) =>
        val idx = s"${i + 1}.".faint
        s"$idx ${l.cyan}"
      }.mkString("\n")

      s"${tpe.magenta} is provided by:\n${numberedLayers.indent(3)}"
    }.mkString("\n\n").indent(3)

    val message =
      if (duplicates.size > 1)
        s"You have provided more than one layer for the following ${duplicates.size.toString.underlined} types:"
      else
        s"You have provided more than one layer for the following type:"

    s"""
       |${title("ZLayer Error").red}
       |
       | ${"Ambiguous layers! I cannot decide which to use.".bold}
       | ${message}
       |
       |$duplicatesString
       |
       |${line.red}
       |
       |""".stripMargin
  }

  private def colorMethodSignature(fullMethodSignature: String, parametersToColor: Seq[String], color: Color): String =
    parametersToColor.fold(fullMethodSignature) { (signature, parameter) =>
      signature.replace(parameter, parameter.withAnsi(color))
    }

  private def replaceByNameWithLambdasAndColorMethodSignature(
    fullMethodSignature: String,
    parametersToColor: Seq[String],
    color: Color
  ): String =
    parametersToColor.fold(fullMethodSignature) { (signature, parameter) =>
      signature.replace(parameter, parameter.replace("=>", "() =>").withAnsi(color))
    }

  def byNameParameterInMacroError(method: String, fullMethodSignature: String, byNameParameters: Seq[String]): String =
    s"""
       |${title("ZLayer Error").red}
       |
       | Due to bug in Scala 2 compiler invoking methods with by-name parameters in ${methodName(
      "provide"
    )}/${methodName("provideSome")} method does not work
       | ${colorMethodSignature(fullMethodSignature, byNameParameters, Color.Red)}
       |
       | Bug can be workarounded in following ways:
       |
       | 1. Assign method output to temporary variable
       |    ${s"ZLayer.provide($method(...))".red}
       |    ↓↓↓
       |    ${s"val temp = $method(...)".green}
       |    ${s"ZLayer.provide(temp)".green}
       |
       | 2. Replace by-name parameter with lambda:
       |    ${colorMethodSignature(fullMethodSignature, byNameParameters, Color.Red)}
       |    ↓↓↓
       |    ${replaceByNameWithLambdasAndColorMethodSignature(fullMethodSignature, byNameParameters, Color.Green)}
       |
       |${line.red}
       |""".stripMargin

  def provideSomeNothingEnvError: String =
    s"""${title("ZLayer Warning").yellow}

 ${s"You are using ${methodName("provideSome")} unnecessarily.".bold}
 The layer is fully satisfied.
 Simply use ${methodName("provide")} instead.
       
${line.yellow}
""".stripMargin

  def superfluousProvideCustomError: String =
    s"""${title("ZLayer Warning").yellow}

 ${s"You are using ${methodName("provideCustom")} unnecessarily.".bold}
 None of the default services are required.
 Simply use ${methodName("provide")} instead.
       
${line.yellow}
""".stripMargin

  def main(args: Array[String]): Unit = {
    val missing = Map(
      "UserService.live" -> List("zio.Clock", "example.UserService"),
      "Database.live"    -> List("java.sql.Connection"),
      "Logger.live"      -> List("zio.Console")
    )
    println(missingLayersError(List("Clock", "Database"), missing))
    println(unusedLayersError(List("Clock.live", "UserService.live", "Console.test")))
    println(provideSomeNothingEnvError)
    println(unusedProvideSomeLayersError(List("java.lang.String", "List[Boolean]")))
    println(
      ambiguousLayersError(
        List(
          ("java.lang.String", List("aGreatStringLayer", "myStringLayer")),
          ("List[Boolean]", List("someBooleansLayer", "booleanLayer1", "booleanLayer2"))
        )
      )
    )
    println(
      byNameParameterInMacroError(
        "createLayerByName",
        "def createLayerByName(i: Int, x: => MyLayer): zio.ULayer[MyLayer]",
        Seq("x: => MyLayer")
      )
    )
  }

  /**
   * Pluralizes a word based on the number of items.
   */
  def pluralize(n: Int, singular: String, plural: String): String =
    if (n == 1) singular else s"$n $plural"

  /**
   * Pluralizes the word "layer".
   *
   * layer, 2 layers, 3 layers, etc.
   */
  def pluralizeLayers(n: Int): String =
    pluralize(n, "layer", "layers")

  def pluralizeTypes(n: Int): String =
    pluralize(n, "type", "types")

  /**
   * Styles a method name.
   */
  private def methodName(string: String): String =
    string.green

  private val width = 70

  /**
   * Makes text look like this:
   *
   * ──── NAME ───────────────────────────────
   */
  private def title(name: String): String =
    s"──── ${name.toUpperCase} ".padTo(width, '─')

  /**
   * A solid line, 80 characters long.
   *
   * ────────────────────────────────
   */
  private def line: String =
    "─" * width

  implicit final class StringOps(private val self: String) extends AnyVal {
    def center(n: Int): String =
      " " * n + self + " " * n

    def indent(n: Int): String =
      self.split("\n").map(line => " " * n + line).mkString("\n")
  }

  sealed trait LayerWiringError

  object LayerWiringError {
    final case class MissingTopLevel(layer: String) extends LayerWiringError

    final case class MissingTransitive(layer: String, deps: List[String]) extends LayerWiringError

    final case class Circular(layer: String, dependency: String) extends LayerWiringError
  }

}
