/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.drain

import org.reactivestreams.Subscriber
import swave.core.Stage
import swave.core.impl.Inport
import swave.core.impl.rs.ForwardToRunnerSubscription
import swave.core.impl.stages.DrainStage
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class SubscriberDrainStage(subscriber: Subscriber[AnyRef]) extends DrainStage {

  def kind = Stage.Kind.Drain.FromSubscriber(subscriber)

  connectInAndSealWith { in ⇒
    region.impl.requestDispatcherAssignment()
    region.impl.registerForXStart(this)
    awaitingXStart(in)
  }

  def awaitingXStart(in: Inport): State = state(
    xStart = () => {
      subscriber.onSubscribe(new ForwardToRunnerSubscription(this))
      running(in)
    })

  def running(in: Inport): State = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) => {
      subscriber.onNext(elem)
      stay()
    },

    onComplete = _ => {
      subscriber.onComplete()
      stop()
    },

    onError = (e, _) => {
      subscriber.onError(e)
      stop(e)
    },

    xEvent = { case ForwardToRunnerSubscription.IllegalRequest(n) =>
        subscriber.onError(new IllegalArgumentException(
          "The number of elements requested must be > 0 (see reactive-streams spec, rule 3.9)"))
        stopCancel(in)
    })
}
