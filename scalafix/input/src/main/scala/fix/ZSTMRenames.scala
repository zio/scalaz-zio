/*
rule = Zio2Upgrade
 */
package fix

import zio.stm.ZSTM

@annotation.nowarn("msg=pure expression does nothing")
object ZSTMRenames {
  ZSTM.collectAll_ _
  ZSTM.foreach_ _
  ZSTM.fromFunction _
  ZSTM.fromFunctionM _
  ZSTM.ifM _
  ZSTM.loop_ _
  ZSTM.partial _
  ZSTM.replicateM _
  ZSTM.replicateM_ _
  ZSTM.unlessM _
  ZSTM.whenCaseM _
  ZSTM.whenM _
}
