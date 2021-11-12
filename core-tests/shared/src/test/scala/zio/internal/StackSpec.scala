package zio.internal

import zio.test.Assertion.equalTo
import zio.test._
import zio.test.TestAspect._
import zio.{Has, Random, ZIOBaseSpec}

object StackSpec extends ZIOBaseSpec {

  def spec = suite("StackSpec")(
    test("Size tracking") {
      checkAll(gen)(list => assert(Stack.fromIterable(list).size)(equalTo(list.length)))
    },
    test("From/to list identity") {
      checkAll(gen)(list => assert(Stack.fromIterable(list).toList)(equalTo(list)))
    },
    test("Push/pop example") {
      checkAll(gen) { list =>
        val stack = Stack[Boolean]()

        list.foreach(stack.push)

        list.reverse.foldLeft(assert(true)(equalTo(true))) { case (result, flag) =>
          result && assert(stack.popOrElse(!flag))(equalTo(flag))
        }
      }
    },
    test("Peek/pop identity") {
      checkAll(gen) { list =>
        val stack = Stack[Boolean]()

        list.foreach(stack.push)

        list.reverse.foldLeft(assert(true)(equalTo(true))) { case (result, flag) =>
          val peeked = stack.peekOrElse(!flag)
          val popped = stack.popOrElse(!flag)

          result && assert(peeked)(equalTo(popped))
        }
      }
    },
    test("concurrent reads") {
      import zio._

      def makeWriter(stack: Stack[String]) = ZIO.succeed {
        var goUp   = 90
        var goDown = 80
        stack.push("1")
        while (!stack.isEmpty) {
          (0 to goUp).foreach(_ => stack.push("1"))
          (0 to goDown).foreach(_ => if (!stack.isEmpty) stack.pop())
          goUp = goUp - 1
          goDown = goDown + 1
        }
      }

      for {
        stack   <- ZIO.succeed(Stack[String]())
        fiber   <- makeWriter(stack).forever.fork
        readers <- ZIO.forkAll(List.fill(100)(ZIO.succeed(stack.toList.forall(_ != null))))
        noNulls <- readers.join.map(_.forall(a => a)) <* fiber.interrupt
      } yield assertTrue(noNulls)
    } @@ nonFlaky
  )

  sealed trait Boolean {
    def unary_! : Boolean = this match {
      case True  => False
      case False => True
    }
  }
  case object True  extends Boolean
  case object False extends Boolean

  val gen: Gen[Has[Random] with Has[Sized], List[Boolean]] =
    Gen.large(n => Gen.listOfN(n)(Gen.elements(True, False)))
}
