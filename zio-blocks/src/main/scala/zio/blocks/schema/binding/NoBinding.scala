package zio.blocks.schema.binding

sealed trait NoBinding[T, A]
object NoBinding {
  private val _noBinding = new NoBinding[Any, Any] {}

  def apply[T, A](): NoBinding[T, A] = _noBinding.asInstanceOf[NoBinding[T, A]]
}
