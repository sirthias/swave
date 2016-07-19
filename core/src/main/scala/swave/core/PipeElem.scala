/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

sealed trait PipeElem {
  def inputElems: List[PipeElem]
  def outputElems: List[PipeElem]
  def boundaryOf: List[Module.ID]
  def pipeElemType: String
  def pipeElemParams: List[Any]
}

object PipeElem {

  object Unconnected extends PipeElem {
    def inputElems = Nil
    def outputElems = Nil
    def boundaryOf = Nil
    def pipeElemType = "Unconnected"
    def pipeElemParams = Nil
  }

  sealed trait Source extends PipeElem {
    def outputElem: PipeElem
    final def inputElems = Nil
    final def outputElems: List[PipeElem] = outputElem :: Nil
  }
  object Source {
    trait Failing extends Source
    trait File extends Source
    trait FromPublisher extends Source
    trait Future extends Source
    trait Iterator extends Source
    trait Lazy extends Source
    trait Repeat extends Source
    trait Sub extends Source
    trait Test extends Source
    trait Subscriber extends Source
  }

  sealed trait Drain extends PipeElem {
    def inputElem: PipeElem
    final def inputElems: List[PipeElem] = inputElem :: Nil
    final def outputElems = Nil
  }
  object Drain {
    trait Cancelling extends Drain
    trait File extends Drain
    trait Foreach extends Drain
    trait FromSubscriber extends Drain
    trait Head extends Drain
    trait Lazy extends Drain
    trait Publisher extends Drain
    trait Sub extends Drain
    trait Test extends Drain
  }

  sealed trait InOut extends PipeElem {
    def inputElem: PipeElem
    def outputElem: PipeElem
    final def inputElems: List[PipeElem] = inputElem :: Nil
    final def outputElems: List[PipeElem] = outputElem :: Nil
  }
  object InOut {
    trait AsyncBoundary extends InOut
    trait BufferWithBackpressure extends InOut
    trait BufferDropping extends InOut
    trait Collect extends InOut
    trait Conflate extends InOut
    trait Coupling extends InOut
    trait Deduplicate extends InOut
    trait Drop extends InOut
    trait DropLast extends InOut
    trait DropWhile extends InOut
    trait DropWithin extends InOut
    trait Filter extends InOut
    trait FlattenConcat extends InOut
    trait Fold extends InOut
    trait Grouped extends InOut
    trait Inject extends InOut
    trait Limit extends InOut
    trait Map extends InOut
    trait Nop extends InOut
    trait OnEvent extends InOut
    trait OnStart extends InOut
    trait Scan extends InOut
    trait Take extends InOut
    trait Throttle extends InOut
  }

  sealed trait FanIn extends PipeElem {
    def outputElem: PipeElem
    final def outputElems: List[PipeElem] = outputElem :: Nil
  }
  object FanIn {
    trait Concat extends FanIn
    trait FirstNonEmpty extends FanIn
    trait ToProduct extends FanIn
  }

  sealed trait FanOut extends PipeElem {
    def inputElem: PipeElem
    final def inputElems: List[PipeElem] = inputElem :: Nil
  }
  object FanOut {
    trait Broadcast extends FanOut
    trait FirstAvailable extends FanOut
    trait RoundRobin extends FanOut
    trait Switch extends FanOut
  }
}
