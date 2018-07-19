package fs2.internal

case class Interrupt(
    recoverAtScope: Token
) extends Throwable
