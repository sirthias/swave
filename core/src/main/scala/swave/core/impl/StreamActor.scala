/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.impl

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal
import scala.annotation.tailrec
import com.typesafe.scalalogging.Logger
import org.jctools.queues.MpscLinkedQueue8
import swave.core.Dispatcher

/**
 * Minimalistic actor implementation without `become`.
 */
private[impl] abstract class StreamActor(
    protected final val throughput: Int,
    protected final val log: Logger,
    final val dispatcher: Dispatcher) {

  type MessageType <: AnyRef

  /**
   * The mailbox.
   * Currently a non-intrusive MPSC-Queue-Implementation using the algorithm described here:
   * http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
   */
  private[this] val mailbox: java.util.Queue[MessageType] = {
    // TODO: benchmark against MpscChunkedArray queue or upgrade to intrusive variant to save the Node instance allocations
    // intrusive variant: http://www.1024cores.net/home/lock-free-algorithms/queues/intrusive-mpsc-node-based-queue
    new MpscLinkedQueue8[MessageType]()
  }

  /*
   * Internal object instance hiding the AtomicBoolean and Runnable interfaces.
   * We could have the StreamActor itself extend `AtomicBoolean with Runnable` and save an allocation
   * but we pay this small cost and prevent leaking `get`, `set`, `run` and other methods to the outside.
   * The atomic boolean signals whether we are currently running or
   * scheduled to run on the env.executionContext or whether we are "idle".
   *
   * We start out in state SCHEDULED to protect ourselves from starting
   * mailbox processing before the object has been fully initialized.
   */
  private[this] val scheduled: AtomicBoolean with Runnable =
    new AtomicBoolean(true) with Runnable {
      final def run(): Unit =
        try {
          @tailrec def processMailbox(maxRemainingMessages: Int): Unit =
            if (maxRemainingMessages > 0) {
              val nextMsg = mailbox.poll()
              if (nextMsg ne null) {
                receive(nextMsg)
                processMailbox(maxRemainingMessages - 1)
              }
            }
          processMailbox(throughput)
        } catch {
          case NonFatal(e) ⇒ log.error("Uncaught exception in SimpleActor::receive", e)
        } finally {
          startMessageProcessing()
        }
    }

  protected def receive(msg: MessageType): Unit

  protected final def enqueue(msg: MessageType): Unit = {
    mailbox.add(msg)
    trySchedule()
  }

  private def trySchedule(): Unit =
    if (scheduled.compareAndSet(false, true)) {
      try dispatcher.execute(scheduled)
      catch {
        case NonFatal(e) ⇒
          scheduled.set(true)
          throw e
      }
    }

  // must be called at the end of the outermost constructor
  protected final def startMessageProcessing(): Unit = {
    scheduled.set(false)
    if (!mailbox.isEmpty)
      trySchedule()
  }
}