package bekindrewind.util

import java.io.{ ByteArrayOutputStream, InputStream, OutputStream }
import scala.annotation.tailrec

object IOUtils {
  def toByteArray(input: InputStream): Array[Byte] = {
    val output = new ByteArrayOutputStream
    transfer(input, output)
    output.toByteArray
  }

  def transfer(input: InputStream, output: OutputStream): Unit = {
    var read   = 0
    val buffer = new Array[Byte](1024)

    @tailrec
    def transfer(): Unit = {
      read = input.read(buffer, 0, buffer.length)
      if (read != -1) {
        output.write(buffer, 0, read)
        transfer()
      }
    }

    transfer()
  }
}
