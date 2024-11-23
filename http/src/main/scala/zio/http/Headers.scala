package zio.http

final case class Headers(values: Map[String, String] = Map.empty) {
  def add(name: String, value: String): Headers = 
    Headers(values + (name -> value))
    
  def get(name: String): Option[String] = values.get(name)
  def contains(name: String): Boolean = values.contains(name)
}

object Headers {
  val empty: Headers = Headers()
} 