package io.chrisdavenport.http4swebsocketexample

import cats.effect.IO
import fs2.StreamApp


object Main extends StreamApp[IO] {
  import scala.concurrent.ExecutionContext.Implicits.global

  def stream(args: List[String], requestShutdown: IO[Unit]) = Server.server[IO]

}