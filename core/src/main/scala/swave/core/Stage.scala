/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core

import java.nio.file.{Path, StandardOpenOption}
import org.reactivestreams.Publisher
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import swave.core.Spout.Unfolding
import swave.core.impl.stages.StageImpl
import swave.core.impl.util.RingBuffer

abstract class Stage private[swave] {
  def kind: Stage.Kind
  def inputStages: List[Stage]
  def outputStages: List[Stage]
  def boundaryOf: List[Module.ID]

  private[swave] def stageImpl: StageImpl
}

object Stage {

  sealed abstract class Kind extends Product {
    def name: String
  }
  object Kind {

    sealed abstract class Spout extends Kind {
      def name = "Spout." + productPrefix
    }
    object Spout {
      final case class Failing(error: Throwable, eager: Boolean)     extends Spout
      final case class FromFile(path: Path, _chunkSize: Int)         extends Spout
      final case class FromPublisher(publisher: Publisher[_])        extends Spout
      final case class FromFuture(future: Future[_])                 extends Spout
      final case class FromIterator(iterator: Iterator[_])           extends Spout
      final case class LazyStart(onStart: () => swave.core.Spout[_]) extends Spout
      final case class Push(initialBufferSize: Int,
                            maxBufferSize: Int,
                            growByInitialSize: Boolean,
                            notifyOnDequeued: Int => Unit,
                            notifyOnCancel: () => Unit)
          extends Spout
      final case class Repeat(element: AnyRef)                                    extends Spout
      final case class FromRingBuffer(buffer: RingBuffer[_])                      extends Spout
      final case class Sub(in: Stage)                                             extends Spout
      final case class Test(id: Int)                                              extends Spout
      case object TestProbe                                                       extends Spout
      final case class Unfold(zero: AnyRef, f: _ => Unfolding[_, _])              extends Spout
      final case class UnfoldAsync(zero: AnyRef, f: _ => Future[Unfolding[_, _]]) extends Spout
      case object WithSubscriber                                                  extends Spout
    }

    sealed abstract class Drain extends Kind {
      def name = "Drain." + productPrefix
    }
    object Drain {
      case object Cancelling                                                               extends Drain
      final case class Foreach(callback: AnyRef ⇒ Unit, terminationPromise: Promise[Unit]) extends Drain
      final case class FromSubscriber(subscriber: org.reactivestreams.Subscriber[_])       extends Drain
      case object Head                                                                     extends Drain
      final case class Ignore(terminationPromise: Promise[Unit])                           extends Drain
      final case class LazyStart(onStart: () => swave.core.Drain[_, _])                    extends Drain
      case object WithPublisher                                                            extends Drain
      final case class Sub(out: Stage)                                                     extends Drain
      final case class Test(id: Int)                                                       extends Drain
      case object TestProbe                                                                extends Drain
      final case class ToFile(path: Path,
                              options: Set[StandardOpenOption],
                              _chunkSize: Int,
                              resultPromise: Promise[Long])
          extends Drain
    }

    sealed abstract class InOut extends Kind {
      def name = productPrefix
    }
    object InOut {
      final case class AsyncBoundary(dispatcherId: String)                                  extends InOut
      final case class AsyncDispatcher(dispatcherId: String)                                extends InOut
      final case class BufferWithBackpressure(size: Int, requestThreshold: Int)             extends InOut
      final case class BufferDropping(size: Int, overflowStrategy: Buffer.OverflowStrategy) extends InOut
      final case class Collect(pf: PartialFunction[_, _])                                   extends InOut
      final case class Conflate(lift: _ => _, aggregate: (_, _) => _)                       extends InOut
      case object Coupling                                                                  extends InOut
      case object Deduplicate                                                               extends InOut
      final case class Delay(delayFor: _ => FiniteDuration)                                 extends InOut
      final case class Drop(count: Long)                                                    extends InOut
      final case class DropLast(count: Int)                                                 extends InOut
      final case class DropWhile(predicate: _ ⇒ Boolean)                                    extends InOut
      final case class DropWithin(duration: FiniteDuration)                                 extends InOut
      final case class Expand(zero: Iterator[_], extrapolate: _ => Iterator[_])             extends InOut
      final case class Filter(predicate: Any ⇒ Boolean, negated: Boolean)                   extends InOut
      final case class FlattenConcat(parallelism: Int)                                      extends InOut
      final case class FlattenMerge(parallelism: Int)                                       extends InOut
      final case class Fold(zero: AnyRef, f: (_, _) ⇒ _)                                    extends InOut
      final case class GroupBy(maxSubstreams: Int, reopenCancelledSubs: Boolean, eagerComplete: Boolean, keyFun: _ ⇒ _)
          extends InOut
      final case class Grouped(groupSize: Int,
                               emitSingleEmpty: Boolean,
                               builder: scala.collection.mutable.Builder[_, _])
          extends InOut
      final case class GroupedWithin(maxSize: Int, duration: FiniteDuration)   extends InOut
      case object Inject                                                       extends InOut
      final case class Intersperse(start: AnyRef, inject: AnyRef, end: AnyRef) extends InOut
      final case class Limit(max: Long, cost: _ ⇒ Long)                        extends InOut
      final case class Map(f: _ ⇒ _)                                           extends InOut
      case object Nop                                                          extends InOut
      final case class OnSignal(callback: StreamEvent[_] ⇒ Unit)               extends InOut
      final case class OnStart(callback: () ⇒ Unit)                            extends InOut
      final case class PrefixAndTail(prefixSize: Int)                          extends InOut
      final case class RecoverWith(maxRecoveries: Long, pf: PartialFunction[Throwable, swave.core.Spout[_]])
          extends InOut
      final case class Scan(zero: AnyRef, f: (_, _) ⇒ _)                                          extends InOut
      final case class Split(commandFor: AnyRef ⇒ swave.core.Split.Command, eagerCancel: Boolean) extends InOut
      final case class Take(count: Long)                                                          extends InOut
      final case class TakeWhile(predicate: Any ⇒ Boolean)                                        extends InOut
      final case class TakeWithin(duration: FiniteDuration)                                       extends InOut
      final case class Throttle(cost: Int, per: FiniteDuration, burst: Int, costFn: _ ⇒ Int)      extends InOut
      final case class WithCompletionTimeout(timeout: FiniteDuration)                             extends InOut
      final case class WithIdleTimeout(timeout: FiniteDuration)                                   extends InOut
      final case class WithInitialTimeout(timeout: FiniteDuration)                                extends InOut
    }

    sealed abstract class FanIn extends Kind {
      def name = "FanIn." + productPrefix
    }
    object FanIn {
      case object Concat        extends FanIn
      case object Merge         extends FanIn
      case object FirstNonEmpty extends FanIn
      case object ToTuple       extends FanIn
      case object ToHList       extends FanIn
    }

    sealed abstract class FanOut extends Kind {
      def name = "FanOut." + productPrefix
    }
    object FanOut {
      final case class Broadcast(eagerCancel: Boolean)                                                 extends FanOut
      final case class BroadcastBuffered(bufferSize: Int, requestThreshold: Int, eagerCancel: Boolean) extends FanOut
      final case class FirstAvailable(eagerCancel: Boolean)                                            extends FanOut
      final case class RoundRobin(eagerCancel: Boolean)                                                extends FanOut
      final case class Switch(branchCount: Int, f: _ ⇒ Int, eagerCancel: Boolean)                      extends FanOut
    }
  }
}
