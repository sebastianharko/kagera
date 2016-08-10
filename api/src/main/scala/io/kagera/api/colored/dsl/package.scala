package io.kagera.api.colored

import io.kagera.api._
import io.kagera.api.multiset._

import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContext, Future }
import scalax.collection.Graph
import scalax.collection.edge.WLDiEdge

package object dsl {

  implicit class TransitionDSL[C](t: Transition[_, C, _]) {
    def ~>[A](p: Place[C], weight: Long = 1): Arc = arc(t, p, weight)
  }

  implicit class PlaceDSL[C](p: Place[C]) {
    def ~>(t: Transition[C, _, _], weight: Long = 1, filter: C ⇒ Boolean = token ⇒ true): Arc = arc[C](p, t, weight, filter)
  }

  def arc(t: Transition[_, _, _], p: Place[_], weight: Long): Arc = WLDiEdge[Node, String](Right(t), Left(p))(weight, "")

  def arc[C](p: Place[C], t: Transition[_, _, _], weight: Long, filter: C ⇒ Boolean = (token: C) ⇒ true): Arc = {
    val innerEdge = new PTEdgeImpl[C](weight, filter)
    WLDiEdge[Node, PTEdge[C]](Left(p), Right(t))(weight, innerEdge)
  }

  def nullPlace(id: Long, label: String) = Place[Unit](id, label)

  def constantTransition[I, O, S](id: Long, label: String, isManaged: Boolean = false, constant: O) =
    new IdentityTransition[I, O, S](id, label, isManaged, Duration.Undefined) {
      override def apply(inAdjacent: MultiSet[Place[_]], outAdjacent: MultiSet[Place[_]])(implicit executor: ExecutionContext): (ColoredMarking, S, I) ⇒ Future[(ColoredMarking, O)] = {

        (marking, state, input) ⇒
          {
            val producedTokens: Map[Place[_], MultiSet[_]] = outAdjacent.map {
              case (place, weight) ⇒ place -> produceTokens(place, weight.toInt)
            }.toMap

            Future.successful(ColoredMarking(producedTokens) -> constant)
          }
      }

      override def produceTokens[C](place: Place[C], count: Int): MultiSet[C] = MultiSet.empty[C] + (constant.asInstanceOf[C] -> count)
    }

  def nullTransition[S](id: Long, label: String, isManaged: Boolean = false) = constantTransition[Unit, Unit, S](id, label, isManaged, ())

  def process[S](params: Arc*)(implicit ec: ExecutionContext): ColoredPetriNetProcess[S] =
    new ScalaGraphPetriNet(Graph(params: _*)) with ColoredTokenGame with TransitionExecutor[S] {
      override val executionContext = ec
    }
}
