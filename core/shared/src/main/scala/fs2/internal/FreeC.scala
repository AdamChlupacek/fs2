package fs2.internal

import cats.{MonadError, ~>}
import cats.effect.{ExitCase, Sync}

import fs2.CompositeFailure
import FreeC._

import scala.util.control.NonFatal

/** Free monad with a catch -- catches exceptions and provides mechanisms for handling them. */
private[fs2] sealed abstract class FreeC[F[_], +R] {

  def flatMap[R2](f: R => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R, R2](
      this,
      e =>
        e match {
          case Either3.Right(r) =>
            try f(r)
            catch { case NonFatal(e) => FreeC.Fail(e) }
          case Either3.Left(e)   => FreeC.Fail(e)
          case Either3.Middle(i) => FreeC.Interrupted(i)
      }
    )

  def transformWith[R2](f: Either[Throwable, R] => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R, R2](
      this,
      r =>
        r match {
          case Either3.Right(r) =>
            try f(Right(r))
            catch { case NonFatal(e) => FreeC.Fail(e) }
          case Either3.Left(e) =>
            try f(Left(e))
            catch { case NonFatal(e) => FreeC.Fail(e) }
          case Either3.Middle(i) => FreeC.Interrupted(i)
      }
    )

  def transformWith3[R2](f: Either3[Throwable, Interrupt, R] => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R, R2](
      this,
      r =>
        try f(r)
        catch { case NonFatal(e) => FreeC.Fail(e) }
    )

  def map[R2](f: R => R2): FreeC[F, R2] =
    Bind(
      this,
      (r: Either3[Throwable, Interrupt, R]) =>
        r match {
          case Either3.Right(r) =>
            try FreeC.Pure(f(r))
            catch { case NonFatal(e) => FreeC.Fail(e) }
          case Either3.Left(e)   => FreeC.Fail(e)
          case Either3.Middle(i) => FreeC.Interrupted(i)
      }
    )

  def handleErrorWith[R2 >: R](h: Throwable => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R2, R2](
      this,
      e =>
        e match {
          case Either3.Right(a) => FreeC.Pure(a)
          case Either3.Left(e) =>
            try h(e)
            catch { case NonFatal(e) => FreeC.Fail(e) }
          case Either3.Middle(i) => FreeC.Interrupted(i)
      }
    )

  def asHandler(e: Throwable): FreeC[F, R] = viewL.get match {
    case Pure(_)        => Fail(e)
    case Fail(e2)       => Fail(e)
    case Bind(_, k)     => k(Either3.Left(e))
    case Eval(_)        => sys.error("impossible 3")
    case Interrupted(_) => Fail(e)
  }

  def asInterruptHandler(i: Interrupt): FreeC[F, R] = viewL.get match {
    case Pure(_)        => Interrupted(i)
    case Fail(e)        => Fail(e)
    case Bind(_, k)     => k(Either3.Middle(i))
    case Eval(_)        => sys.error("impossible 4")
    case Interrupted(_) => sys.error("impossible 5")
  }

  def viewL: ViewL[F, R] = mkViewL(this)

  def translate[G[_]](f: F ~> G): FreeC[G, R] = FreeC.suspend {
    viewL.get match {
      case Pure(r) => Pure(r)
      case Bind(fx, k) =>
        Bind(fx.translate(f), (e: Either3[Throwable, Interrupt, Any]) => k(e).translate(f))
      case Fail(e)        => Fail(e)
      case Interrupted(i) => Interrupted(i)
      case Eval(fx)       => sys.error("impossible 6")
    }
  }
}

private[fs2] object FreeC {
  final case class Pure[F[_], R](r: R) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] =
      this.asInstanceOf[FreeC[G, R]]
    override def toString: String = s"FreeC.Pure($r)"
  }
  final case class Eval[F[_], R](fr: F[R]) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] =
      try Eval(f(fr))
      catch { case NonFatal(t) => Fail[G, R](t) }
    override def toString: String = s"FreeC.Eval($fr)"
  }
  final case class Bind[F[_], X, R](fx: FreeC[F, X],
                                    f: Either3[Throwable, Interrupt, X] => FreeC[F, R])
      extends FreeC[F, R] {
    override def toString: String = s"FreeC.Bind($fx, $f)"
  }
  final case class Fail[F[_], R](error: Throwable) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] =
      this.asInstanceOf[FreeC[G, R]]
    override def toString: String = s"FreeC.Fail($error)"
  }

  final case class Interrupted[F[_], R](interrupt: Interrupt) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] =
      this.asInstanceOf[FreeC[G, R]]
    override def toString: String = s"FreeC.Interrupted($interrupt)"
  }

  private val pureContinuation_ = (e: Either3[Throwable, Interrupt, Any]) =>
    e match {
      case Either3.Right(r)  => Pure[Any, Any](r)
      case Either3.Left(e)   => Fail[Any, Any](e)
      case Either3.Middle(i) => Interrupted[Any, Any](i)
  }

  def pureContinuation[F[_], R]: Either3[Throwable, Interrupt, R] => FreeC[F, R] =
    pureContinuation_.asInstanceOf[Either3[Throwable, Interrupt, R] => FreeC[F, R]]

  def suspend[F[_], R](fr: => FreeC[F, R]): FreeC[F, R] =
    Pure[F, Unit](()).flatMap(_ => fr)

  /**
    * Unrolled view of a `FreeC` structure. The `get` value is guaranteed to be one of:
    * `Pure(r)`, `Fail(e)`, `Bind(Eval(fx), k)`.
    */
  final class ViewL[F[_], +R](val get: FreeC[F, R]) extends AnyVal

  private def mkViewL[F[_], R](free: FreeC[F, R]): ViewL[F, R] = {
    @annotation.tailrec
    def go[X](free: FreeC[F, X]): ViewL[F, R] =
//      println("XIOIO free now" + free)
      free match {
        case Pure(x)        => new ViewL(free.asInstanceOf[FreeC[F, R]])
        case Interrupted(i) => new ViewL(free.asInstanceOf[FreeC[F, R]])
        case Eval(fx)       => new ViewL(Bind(free.asInstanceOf[FreeC[F, R]], pureContinuation[F, R]))
        case Fail(err)      => new ViewL(free.asInstanceOf[FreeC[F, R]])
        case b: FreeC.Bind[F, y, X] =>
          b.fx match {
            case Pure(x)        => go(b.f(Either3.Right(x)))
            case Fail(e)        => go(b.f(Either3.Left(e)))
            case Interrupted(i) => go(b.f(Either3.Middle(i)))
            case Eval(_)        => new ViewL(b.asInstanceOf[FreeC[F, R]])
            case Bind(w, g) =>
              go(Bind(w, (e: Either3[Throwable, Interrupt, Any]) => Bind(g(e), b.f)))
          }
      }
    go(free)
  }

  implicit final class InvariantOps[F[_], R](private val self: FreeC[F, R]) extends AnyVal {
    def run(implicit F: MonadError[F, Throwable]): F[R] =
      self.viewL.get match {
        case Pure(r) => F.pure(r)
        case Fail(e) => F.raiseError(e)
        case Bind(fr, k) =>
          F.flatMap(F.attempt(fr.asInstanceOf[Eval[F, Any]].fr)) { e =>
            k(Either3.fromEither(e)).run
          }
        case Eval(_)        => sys.error("impossible 7")
        case Interrupted(i) => F.raiseError(i)
      }
  }

  implicit def syncInstance[F[_]]: Sync[FreeC[F, ?]] = new Sync[FreeC[F, ?]] {
    def pure[A](a: A): FreeC[F, A] = FreeC.Pure(a)
    def handleErrorWith[A](fa: FreeC[F, A])(f: Throwable => FreeC[F, A]): FreeC[F, A] =
      fa.handleErrorWith(f)
    def raiseError[A](t: Throwable): FreeC[F, A] = FreeC.Fail(t)
    def flatMap[A, B](fa: FreeC[F, A])(f: A => FreeC[F, B]): FreeC[F, B] =
      fa.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => FreeC[F, Either[A, B]]): FreeC[F, B] =
      f(a).flatMap {
        case Left(a)  => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
    def suspend[A](thunk: => FreeC[F, A]): FreeC[F, A] = FreeC.suspend(thunk)
    def bracketCase[A, B](acquire: FreeC[F, A])(use: A => FreeC[F, B])(
        release: (A, ExitCase[Throwable]) => FreeC[F, Unit]): FreeC[F, B] =
      acquire.flatMap { a =>
        val used =
          try use(a)
          catch { case NonFatal(t) => FreeC.Fail[F, B](t) }
        used.transformWith { result =>
          release(a, ExitCase.attempt(result)).transformWith {
            case Left(t2) => FreeC.Fail(result.fold(t => CompositeFailure(t, t2, Nil), _ => t2))
            case Right(_) => result.fold(FreeC.Fail(_), FreeC.Pure(_))
          }
        }
      }
  }
}
