package bekindrewind.util

private[bekindrewind] object Eithers {

  /** Applies `f` to each element, stopping at the first error. */
  def traverse[A, B](as: Vector[A])(f: A => Either[Throwable, B]): Either[Throwable, Vector[B]] =
    as.foldLeft[Either[Throwable, Vector[B]]](Right(Vector.empty))((acc, a) => acc.flatMap(bs => f(a).map(bs :+ _)))
}
