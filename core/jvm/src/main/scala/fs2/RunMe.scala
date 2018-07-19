package fs2

import cats.effect.IO
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object RunMe extends StreamApp[IO] {

  override def stream(args: List[String],
                      requestShutdown: IO[Unit]): fs2.Stream[IO, StreamApp.ExitCode] =
    Stream
      .unfold(1)(i => Some((i, i + 1)))
      .flatMap(Stream.emit(_).delayBy[IO](1.second))
      .interruptWhen(Stream.emit(true).delayBy[IO](15.seconds))
      .evalMap(i => IO { println("XXXX: " + i); i })
      .fold(0) { (t, i) =>
        println(s" XXXB $i "); t + i
      }
      .evalMap(i => IO { println("XXXA: " + i); i })
      .drain ++ Stream.emit(StreamApp.ExitCode.Success)
}
