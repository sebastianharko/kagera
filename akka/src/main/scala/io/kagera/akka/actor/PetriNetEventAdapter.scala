package io.kagera.akka.actor

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.google.protobuf.ByteString
import io.kagera.akka.actor.PetriNetEventAdapter._
import io.kagera.akka.actor.PetriNetEventSourcing._
import io.kagera.akka.actor.PetriNetExecution.Instance
import io.kagera.akka.persistence.{ ConsumedToken, ProducedToken, SerializedData }
import io.kagera.api._
import io.kagera.api.colored._

import scala.runtime.BoxedUnit

object PetriNetEventAdapter {

  /**
   * TODO:
   *
   * This approach is fragile, the identifier function cannot change ever or recovery breaks
   * a more robust alternative is to generate the ids and persist them
   */
  def tokenIdentifier[C](p: Place[C]): Any ⇒ Int = obj ⇒ hashCodeOf[Any](obj)

  def hashCodeOf[T](e: T): Int = {
    if (e == null)
      -1
    else
      e.hashCode()
  }
}

/**
 * This trait is responsible for translating the PetriNetProcess.TransitionFiredEvent to and from the persistence.TransitionFiredEvent
 * (which is generated by scalaPB and serializes to protobuff.)
 *
 * TODO:
 *
 * This should not be a trait, the mix in pattern is not optimally reusable. A more functional approach would be better.
 * Additionally the (de)serialization should not depend on akka per se.
 */
trait PetriNetEventAdapter[S] {

  def system: ActorSystem

  private lazy val serialization = SerializationExtension.get(system)

  def deserializeEvent(instance: Instance[S]): AnyRef ⇒ PetriNetEventSourcing.Event = {
    case e: io.kagera.akka.persistence.Initialized      ⇒ deserialize(instance, e)
    case e: io.kagera.akka.persistence.TransitionFired  ⇒ deserialize(instance, e)
    case e: io.kagera.akka.persistence.TransitionFailed ⇒ null
  }

  def serializeEvent(state: Instance[S]): PetriNetEventSourcing.Event ⇒ AnyRef = {
    case e: InitializedEvent[_]   ⇒ serialize(e.asInstanceOf[InitializedEvent[S]])
    case e: TransitionFiredEvent  ⇒ serialize(e)
    case e: TransitionFailedEvent ⇒ null
  }

  private def serializeObject(obj: AnyRef): Option[SerializedData] = {
    // no need to serialize unit
    if (obj.isInstanceOf[Unit]) {
      None
    } else {
      // for now we re-use akka Serialization extension for pluggable serializers
      val serializer = serialization.findSerializerFor(obj)
      val bytes = serializer.toBinary(obj)

      // we should not have to copy the bytes
      Some(SerializedData(
        serializerId = Some(serializer.identifier),
        manifest = None,
        data = Some(ByteString.copyFrom(bytes))
      ))
    }
  }

  private def deserializeProducedMarking(instance: Instance[S], produced: Seq[io.kagera.akka.persistence.ProducedToken]): Marking = {
    produced.foldLeft(Marking.empty) {
      case (accumulated, ProducedToken(Some(placeId), Some(tokenId), Some(count), data)) ⇒
        val place = instance.process.places.getById(placeId)
        val value = deserializeObject(data)
        accumulated.add(place.asInstanceOf[Place[Any]], value, count)
      case _ ⇒ throw new IllegalStateException("Missing data in persisted ProducedToken")
    }
  }

  private def serializeProducedMarking(produced: Marking): Seq[io.kagera.akka.persistence.ProducedToken] = {
    produced.data.toSeq.flatMap {
      case (place, tokens) ⇒ tokens.toSeq.map {
        case (value, count) ⇒ ProducedToken(
          placeId = Some(place.id.toInt),
          tokenId = Some(tokenIdentifier(place)(value)),
          count = Some(count),
          tokenData = serializeObject(value.asInstanceOf[AnyRef])
        )
      }
    }
  }

  private def deserializeObject(obj: Option[SerializedData]): AnyRef = {
    obj.map {
      case SerializedData(None, _, Some(data)) ⇒
        throw new IllegalStateException(s"Missing serializer id")
      case SerializedData(Some(serializerId), _, Some(data)) ⇒
        val serializer = serialization.serializerByIdentity.getOrElse(serializerId,
          throw new IllegalStateException(s"No serializer found with id $serializerId")
        )
        serializer.fromBinary(data.toByteArray)
    }.getOrElse(BoxedUnit.UNIT)
  }

  def deserialize(instance: Instance[S], e: io.kagera.akka.persistence.Initialized): InitializedEvent[S] = {
    val initialMarking = deserializeProducedMarking(instance, e.initialMarking)
    val initialState = deserializeObject(e.initialState).asInstanceOf[S]
    InitializedEvent(initialMarking, initialState)
  }

  def serialize(e: InitializedEvent[S]): io.kagera.akka.persistence.Initialized = {
    val initialMarking = serializeProducedMarking(e.marking)
    val initialState = serializeObject(e.state.asInstanceOf[AnyRef])
    io.kagera.akka.persistence.Initialized(initialMarking, initialState)
  }

  def serialize(e: TransitionFiredEvent): io.kagera.akka.persistence.TransitionFired = {

    val consumedTokens: Seq[ConsumedToken] = e.consumed.data.toSeq.flatMap {
      case (place, tokens) ⇒ tokens.toSeq.map {
        case (value, count) ⇒ ConsumedToken(
          placeId = Some(place.id.toInt),
          tokenId = Some(tokenIdentifier(place)(value)),
          count = Some(count)
        )
      }
    }

    val producedTokens = serializeProducedMarking(e.produced)

    val protobufEvent = io.kagera.akka.persistence.TransitionFired(
      jobId = Some(e.jobId),
      transitionId = Some(e.transitionId),
      timeStarted = Some(e.timeStarted),
      timeCompleted = Some(e.timeCompleted),
      consumed = consumedTokens,
      produced = producedTokens,
      data = serializeObject(e.out.asInstanceOf[AnyRef])
    )

    protobufEvent
  }

  def deserialize(instance: Instance[S], e: io.kagera.akka.persistence.TransitionFired): TransitionFiredEvent = {

    val transition = instance.process.getTransitionById(e.transitionId.get)

    val consumed = e.consumed.foldLeft(Marking.empty) {
      case (accumulated, ConsumedToken(Some(placeId), Some(tokenId), Some(count))) ⇒
        val place = instance.marking.markedPlaces.getById(placeId)
        val value = instance.marking(place).keySet.find(e ⇒ tokenIdentifier(place)(e) == tokenId).get
        accumulated.add(place.asInstanceOf[Place[Any]], value, count)
      case _ ⇒ throw new IllegalStateException("Missing data in persisted ConsumedToken")
    }

    val produced = deserializeProducedMarking(instance, e.produced)

    val data = deserializeObject(e.data)

    def missingFieldException(field: String) = throw new IllegalStateException(s"Missing field in serialized data: $field")

    val jobId = e.jobId.getOrElse(missingFieldException("job_id"))
    val timeStarted = e.timeStarted.getOrElse(missingFieldException("time_started"))
    val timeCompleted = e.timeCompleted.getOrElse(missingFieldException("time_completed"))

    TransitionFiredEvent(jobId, transition.id, timeStarted, timeCompleted, consumed, produced, data)
  }
}
