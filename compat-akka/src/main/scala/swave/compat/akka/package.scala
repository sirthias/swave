/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.compat

import scala.concurrent.{Future, Promise}
import _root_.akka.NotUsed
import _root_.akka.stream.Materializer
import _root_.akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import swave.compat.akka.impl.ByteStringBytes
import swave.core.impl.Inport
import swave.core._

package object akka {

  implicit class RichSource[T, Mat](val underlying: Source[T, Mat]) extends AnyVal {

    def toSpoutAndMatFuture(implicit m: Materializer): (Spout[T], Future[Mat]) = {
      val matPromise = Promise[Mat]()
      val spout      = toSpoutWithMatCapture(matPromise)
      spout → matPromise.future
    }

    def toSpout(implicit m: Materializer): Spout[T] =
      toSpoutWithMatCapture(null)

    def toSpoutWithMatCapture(matPromise: Promise[Mat])(implicit m: Materializer): Spout[T] = {
      val (spout, subscriber) = Spout.withSubscriber[T]
      val runnableGraph       = underlying.to(Sink.fromSubscriber(subscriber))
      spout.onStart { () ⇒
        val mat = runnableGraph.run()
        if (matPromise ne null) matPromise.success(mat)
        ()
      }
    }
  }

  implicit class RichFlow[A, B, Mat](val underlying: Flow[A, B, Mat]) extends AnyVal {

    def toPipeAndMatFuture(implicit m: Materializer): (Pipe[A, B], Future[Mat]) = {
      val matPromise = Promise[Mat]()
      val pipe       = toPipeWithMatCapture(matPromise)
      pipe → matPromise.future
    }

    implicit def toPipe(implicit m: Materializer): Pipe[A, B] =
      toPipeWithMatCapture(null)

    def toPipeWithMatCapture(matPromise: Promise[Mat])(implicit m: Materializer): Pipe[A, B] = {
      val drain               = Drain.toPublisher[A]()
      val (spout, subscriber) = Spout.withSubscriber[B]
      val runnableGraph =
        Source.fromPublisher(drain.result).viaMat(underlying)(Keep.right).to(Sink.fromSubscriber(subscriber))
      Pipe.fromDrainAndSpout(drain.dropResult, spout).onStart { () ⇒
        val mat = runnableGraph.run()
        if (matPromise ne null) matPromise.success(mat)
        ()
      }
    }
  }

  implicit class RichSink[T, Mat](val underlying: Sink[T, Mat]) extends AnyVal {

    def toDrain(implicit m: Materializer): Drain[T, Future[Mat]] = {
      val drain         = Drain.toPublisher[T]()
      val runnableGraph = Source.fromPublisher(drain.result).toMat(underlying)(Keep.right)
      val matPromise    = Promise[Mat]()
      Pipe[T].onStart { () ⇒
        val mat = runnableGraph.run()
        matPromise.success(mat)
        ()
      }.to(new Drain(drain.outport, matPromise.future))
    }
  }

  implicit def richSpout[T](underlying: Spout[T]): RichSpout[T] = // workaround for SI-7685
    new RichSpout(underlying.inport)

  implicit class RichPipe[A, B](val underlying: Pipe[A, B]) extends AnyVal {

    def toAkkaFlow(implicit env: StreamEnv): Flow[A, B, NotUsed] =
      Flow.fromSinkAndSource(underlying.inputAsDrain.toAkkaSink, underlying.outputAsSpout.toAkkaSource)
  }

  implicit class RichDrain[T, R](val underlying: Drain[T, R]) extends AnyVal {

    def toAkkaSink(implicit env: StreamEnv): Sink[T, R] = {
      val (spout, subscriber) = Spout.withSubscriber[T]
      val piping              = spout.to(underlying)
      Sink.fromSubscriber(subscriber).mapMaterializedValue { _ ⇒
        piping.run().get // provoke exception on start error
      }
    }
  }

  implicit val byteStringBytes: ByteStringBytes = new ByteStringBytes
}

package akka {

  class RichSpout[T](val underlying: Inport) extends AnyVal {

    def toAkkaSource(implicit env: StreamEnv): Source[T, NotUsed] = {
      val drain  = Drain.toPublisher[T]()
      val piping = new Spout(underlying).to(drain)
      Source.fromPublisher(drain.result).mapMaterializedValue { notUsed ⇒
        piping.run().get // provoke exception on start error
        notUsed
      }
    }
  }
}
