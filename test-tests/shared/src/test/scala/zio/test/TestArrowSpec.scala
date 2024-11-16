package zio.test

object TestArrowSpec extends ZIOBaseSpec {

  import TestArrow._

  def createMeta(
    span: Option[Span] = None,
    parentSpan: Option[Span] = None,
    code: Option[String] = None,
    location: Option[String] = None,
    completeCode: Option[String] = None,
    customLabel: Option[String] = None,
    genFailureDetails: Option[GenFailureDetails] = None
  ) =
    new Meta(
      TestArrowF[Any, Nothing](_ => TestTrace.fail),
      span,
      parentSpan,
      code,
      location,
      completeCode,
      customLabel,
      genFailureDetails
    )

  def spec =
    suite("TestArrowSpec")(
      suite(".meta")(
        test("change None span") {
          val span = Some(Span(0, 1))
          val meta = createMeta(span = None)
          val res1 = meta.meta(span = None).asInstanceOf[Meta[Any, Nothing]].span
          val res2 = meta.meta(span = span).asInstanceOf[Meta[Any, Nothing]].span
          assertTrue(res1 == None && res2 == span)
        },
        test("change Some span") {
          val span1 = Some(Span(0, 1))
          val span2 = Some(Span(2, 3))
          val meta  = createMeta(span = span1)
          val res1  = meta.meta(span = None).asInstanceOf[Meta[Any, Nothing]].span
          val res2  = meta.meta(span = span2).asInstanceOf[Meta[Any, Nothing]].span
          assertTrue(res1 == span1 && res2 == span2)
        },
        test("change None parentSpan") {
          val parentSpan = Some(Span(0, 1))
          val meta       = createMeta(parentSpan = None)
          val res1       = meta.meta(parentSpan = None).asInstanceOf[Meta[Any, Nothing]].parentSpan
          val res2       = meta.meta(parentSpan = parentSpan).asInstanceOf[Meta[Any, Nothing]].parentSpan
          assertTrue(res1 == None && res2 == parentSpan)
        },
        test("change Some parentSpan") {
          val parentSpan1 = Some(Span(0, 1))
          val parentSpan2 = Some(Span(2, 3))
          val meta        = createMeta(parentSpan = parentSpan1)
          val res1        = meta.meta(parentSpan = None).asInstanceOf[Meta[Any, Nothing]].parentSpan
          val res2        = meta.meta(parentSpan = parentSpan2).asInstanceOf[Meta[Any, Nothing]].parentSpan
          assertTrue(res1 == parentSpan1 && res2 == parentSpan2)
        },
        test("change None code") {
          val code = Some("some code")
          val meta = createMeta(code = None)
          val res1 = meta.meta(code = None).asInstanceOf[Meta[Any, Nothing]].code
          val res2 = meta.meta(code = code).asInstanceOf[Meta[Any, Nothing]].code
          assertTrue(res1 == None && res2 == code)
        },
        test("change Some code") {
          val code1 = Some("some code")
          val code2 = Some("other code")
          val meta  = createMeta(code = code1)
          val res1  = meta.meta(code = None).asInstanceOf[Meta[Any, Nothing]].code
          val res2  = meta.meta(code = code2).asInstanceOf[Meta[Any, Nothing]].code
          assertTrue(res1 == code1 && res2 == code2)
        },
        test("change None location") {
          val location = Some("some location")
          val meta     = createMeta(location = None)
          val res1     = meta.meta(location = None).asInstanceOf[Meta[Any, Nothing]].location
          val res2     = meta.meta(location = location).asInstanceOf[Meta[Any, Nothing]].location
          assertTrue(res1 == None && res2 == location)
        },
        test("change Some location") {
          val location1 = Some("some location")
          val location2 = Some("other location")
          val meta      = createMeta(location = location1)
          val res1      = meta.meta(location = None).asInstanceOf[Meta[Any, Nothing]].location
          val res2      = meta.meta(location = location2).asInstanceOf[Meta[Any, Nothing]].location
          assertTrue(res1 == location1 && res2 == location2)
        },
        test("change None completeCode") {
          val completeCode = Some("some completeCode")
          val meta         = createMeta(completeCode = None)
          val res1         = meta.meta(completeCode = None).asInstanceOf[Meta[Any, Nothing]].completeCode
          val res2         = meta.meta(completeCode = completeCode).asInstanceOf[Meta[Any, Nothing]].completeCode
          assertTrue(res1 == None && res2 == completeCode)
        },
        test("change Some completeCode") {
          val completeCode1 = Some("some completeCode")
          val completeCode2 = Some("other completeCode")
          val meta          = createMeta(completeCode = completeCode1)
          val res1          = meta.meta(completeCode = None).asInstanceOf[Meta[Any, Nothing]].completeCode
          val res2          = meta.meta(completeCode = completeCode2).asInstanceOf[Meta[Any, Nothing]].completeCode
          assertTrue(res1 == completeCode1 && res2 == completeCode2)
        },
        test("change None customLabel") {
          val customLabel = Some("some customLabel")
          val meta        = createMeta(customLabel = None)
          val res1        = meta.meta(customLabel = None).asInstanceOf[Meta[Any, Nothing]].customLabel
          val res2        = meta.meta(customLabel = customLabel).asInstanceOf[Meta[Any, Nothing]].customLabel
          assertTrue(res1 == None && res2 == customLabel)
        },
        test("change Some customLabel") {
          val customLabel1 = Some("some customLabel")
          val customLabel2 = Some("other customLabel")
          val meta         = createMeta(customLabel = customLabel1)
          val res1         = meta.meta(customLabel = None).asInstanceOf[Meta[Any, Nothing]].customLabel
          val res2         = meta.meta(customLabel = customLabel2).asInstanceOf[Meta[Any, Nothing]].customLabel
          assertTrue(res1 == customLabel1 && res2 == customLabel2)
        },
        test("change None genFailureDetails") {
          val genFailureDetails = Some(GenFailureDetails(None, None, 1))
          val meta              = createMeta(genFailureDetails = None)
          val res1              = meta.meta(genFailureDetails = None).asInstanceOf[Meta[Any, Nothing]].genFailureDetails
          val res2              = meta.meta(genFailureDetails = genFailureDetails).asInstanceOf[Meta[Any, Nothing]].genFailureDetails
          assertTrue(res1 == None && res2.map(_.iterations == 1).getOrElse(false))
        },
        test("change Some genFailureDetails") {
          val genFailureDetails1 = Some(GenFailureDetails(None, None, 1))
          val genFailureDetails2 = Some(GenFailureDetails(None, None, 2))
          val meta               = createMeta(genFailureDetails = genFailureDetails1)
          val res1               = meta.meta(genFailureDetails = None).asInstanceOf[Meta[Any, Nothing]].genFailureDetails
          val res2 =
            meta.meta(genFailureDetails = genFailureDetails2).asInstanceOf[Meta[Any, Nothing]].genFailureDetails
          assertTrue(res1.map(_.iterations == 1).getOrElse(false) && res2.map(_.iterations == 2).getOrElse(false))
        }
      ),
      suite("Span substring bounds")(
        test("correctly handles valid span bounds within string length") {
          val span = Span(0, 3)
          assertTrue(span.substring("foo bar baz") == "foo")
        },
        test("clamps start when it is out of bounds") {
          val span = Span(-3, 3)
          assertTrue(span.substring("foo bar baz") == "foo")
        },
        test("clamps end when it exceeds string length") {
          val span = Span(0, 10)
          assertTrue(span.substring("foo") == "foo")
        },
        test("clamps both start and end when both are out of bounds") {
          val span = Span(-5, 10)
          assertTrue(span.substring("baz") == "baz")
        },
        test("returns empty string when start equals end") {
          val span = Span(3, 3)
          assertTrue(span.substring("foo bar baz") == "")
        },
        test("returns empty string when start is greater than end") {
          val span = Span(4, 2)
          assertTrue(span.substring("foo bar baz") == "")
        }
      )
    )

}
