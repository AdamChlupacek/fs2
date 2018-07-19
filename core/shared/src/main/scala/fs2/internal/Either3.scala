package fs2.internal

object Either3 {

  case class Left[+A](a: A) extends Either3[A, Nothing, Nothing]
  case class Middle[+B](b: B) extends Either3[Nothing, B, Nothing]
  case class Right[+C](c: C) extends Either3[Nothing, Nothing, C]

  def fromEither[A, B](either: Either[A, B]): Either3[A, Nothing, B] = either match {
    case scala.util.Left(a)  => Left(a)
    case scala.util.Right(b) => Right(b)
  }
}

trait Either3[+A, +B, +C]
