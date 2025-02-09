package zio.internal.macros

import zio.internal.macros.LayerTree._

final case class Graph[Key, A](nodes: List[Node[Key, A]], keyEquals: (Key, Key) => Boolean) {

  def buildComplete(outputs: List[Key]): Either[::[GraphError[Key, A]], LayerTree[A]] =
    forEach(outputs) { output =>
      getNodeWithOutput[GraphError[Key, A]](output, error = GraphError.MissingTopLevelDependency(output))
        .flatMap(node => buildNode(node, Set(node)))
    }
      .map(_.distinct.combineHorizontally)

  def buildNodes(nodes: List[Node[Key, A]]): Either[::[GraphError[Key, A]], LayerTree[A]] =
    forEach(nodes)(buildNode).map(_.combineHorizontally)

  private def buildNode(node: Node[Key, A]): Either[::[GraphError[Key, A]], LayerTree[A]] =
    forEach(node.inputs) { output =>
      getNodeWithOutput[GraphError[Key, A]](output, error = GraphError.missingTransitiveDependency(node, output))
        .flatMap(node => buildNode(node, Set(node)))
    }
      .map(_.distinct.combineHorizontally)
      .map(_ >>> LayerTree.succeed(node.value))

  def map[B](f: A => B): Graph[Key, B] =
    Graph(nodes.map(_.map(f)), keyEquals)

  private val nodeWithOutputCache = new java.util.HashMap[Key, Option[Node[Key, A]]]

  private def getNodeWithOutput[E](output: Key, error: => E): Either[::[E], Node[Key, A]] =
    nodeWithOutputCache.computeIfAbsent(output, findNodeWithOutput).toRight(::(error, Nil))

  private def findNodeWithOutput(output: Key): Option[Node[Key, A]] =
    nodes.find(_.outputs.exists(keyEquals(_, output)))

  private def buildNode(
    node: Node[Key, A],
    seen: Set[Node[Key, A]]
  ): Either[::[GraphError[Key, A]], LayerTree[A]] =
    forEach(node.inputs) { input =>
      for {
        out    <- getNodeWithOutput(input, error = GraphError.missingTransitiveDependency(node, input))
        _      <- assertNonCircularDependency(node, seen, out)
        result <- buildNode(out, seen + out)
      } yield result
    }.map {
      _.distinct.combineHorizontally >>> LayerTree.succeed(node.value)
    }

  private def assertNonCircularDependency(
    node: Node[Key, A],
    seen: Set[Node[Key, A]],
    dependency: Node[Key, A]
  ): Either[::[GraphError[Key, A]], Unit] =
    if (seen(dependency))
      Left(::(GraphError.CircularDependency(node, dependency, seen.size), Nil))
    else
      Right(())

  private def forEach[B, C](
    list: List[B]
  )(f: B => Either[::[GraphError[Key, A]], C]): Either[::[GraphError[Key, A]], List[C]] =
    list.foldRight[Either[::[GraphError[Key, A]], List[C]]](Right(List.empty)) { (a, b) =>
      (f(a), b) match {
        case (Left(::(e, es)), Left(e1s)) => Left(::(e, es ++ e1s))
        case (Left(es), _)                => Left(es)
        case (_, Left(es))                => Left(es)
        case (Right(a), Right(b))         => Right(a +: b)
      }
    }
}
