package zio.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

private[test] trait PrettyPrintVersionSpecific {
  def labels(product: Product): Iterator[String] = product.productElementNames
}
