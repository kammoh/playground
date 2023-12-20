package playground

import scala.language.implicitConversions

class SeqIf[A](ifSeq: => Seq[A], cond: => Boolean) {
  def ++(that: Seq[A]): Seq[A] = this.toSeq ++ that

  def Else(elseItems: A*): Seq[A] = {
    if (cond) ifSeq else elseItems
  }

  def toSeq: Seq[A] = if (cond) ifSeq else Seq()
}

object SeqIf {
  implicit def ifSeqToSeq[A](ifSeq: SeqIf[A]): Seq[A] = ifSeq.toSeq

  def apply[A](cond: Boolean)(ifItems: A*): SeqIf[A] = {
    new SeqIf(ifItems, cond)
  }

  //    def apply[A, B](opt: => Option[A])(item0: => B, itemMaps: PartialFunction[A, B]*): SeqIf[B] =
  //      new SeqIf(item0 +: itemMaps.collect(f => f(opt.get)), opt.nonEmpty)

  def apply[A, B](opt: => Option[A])(itemMap0: PartialFunction[A, B], itemMaps: PartialFunction[A, B]*): SeqIf[B] =
    new SeqIf((itemMap0 +: itemMaps).collect(f => f(opt.get)), opt.nonEmpty)
}
