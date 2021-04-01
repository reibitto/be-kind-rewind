package bekindrewind.akkahttp

import io.circe.{ Decoder, Encoder }

private[akkahttp] case class AkkaHttpResponseStore(httpProtocol: String, contentType: String, data: String)

private[akkahttp] object AkkaHttpResponseStore {
  implicit val encoder: Encoder[AkkaHttpResponseStore] =
    Encoder.forProduct3("httpProtocol", "contentType", "data")(o => (o.httpProtocol, o.contentType, o.data))

  implicit val decoder: Decoder[AkkaHttpResponseStore] =
    Decoder.forProduct3("httpProtocol", "contentType", "data")(AkkaHttpResponseStore.apply)
}
