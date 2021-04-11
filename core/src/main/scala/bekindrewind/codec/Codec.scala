package bekindrewind.codec

import bekindrewind.VcrEntries

trait Codec {
  def decode(text: String): Either[Throwable, VcrEntries]

  def encode(entries: VcrEntries): String
}
