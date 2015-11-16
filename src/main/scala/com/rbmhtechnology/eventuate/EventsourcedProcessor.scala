/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import com.typesafe.config.Config

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._

private class EventsourcedProcessorSettings(config: Config) {
  val readTimeout =
    config.getDuration("eventuate.processor.read-timeout", TimeUnit.MILLISECONDS).millis

  val writeTimeout =
    config.getDuration("eventuate.processor.write-timeout", TimeUnit.MILLISECONDS).millis
}

object EventsourcedProcessor {
  type Process = PartialFunction[Any, Seq[Any]]
}

trait EventsourcedProcessor extends EventsourcedWriter[Long, Long] {
  import ReplicationProtocol._
  import context.dispatcher

  type Process = EventsourcedProcessor.Process

  private val settings = new EventsourcedProcessorSettings(context.system.settings.config)

  private var processedEvents: Vector[DurableEvent] = Vector.empty
  private var storedSequenceNr: Long = 0L

  def targetEventLog: ActorRef

  def processEvent: Process

  override final val onEvent: Receive = {
    case payload if processEvent.isDefinedAt(payload) =>
      if (lastSequenceNr > storedSequenceNr)
        processedEvents = processEvent(payload).map(durableEvent(_, Set.empty)).foldLeft(processedEvents)(_ :+ _)
  }

  override final def write(): Future[Long] =
    if (lastSequenceNr > storedSequenceNr) {
      val result = targetEventLog.ask(ReplicationWrite(processedEvents, id, lastSequenceNr, VectorTime()))(Timeout(writeTimeout)).flatMap {
        case ReplicationWriteSuccess(_, progress, _) => Future.successful(progress)
        case ReplicationWriteFailure(cause)          => Future.failed(cause)
      }
      processedEvents = Vector.empty
      result
    } else Future.successful(storedSequenceNr)

  override final def read(): Future[Long] = {
    targetEventLog.ask(GetReplicationProgress(id))(Timeout(readTimeout)).flatMap {
      case GetReplicationProgressSuccess(_, progress, _) => Future.successful(progress)
      case GetReplicationProgressFailure(cause)          => Future.failed(cause)
    }
  }

  override def writeSuccess(progress: Long): Unit = {
    storedSequenceNr = progress
    super.writeSuccess(progress)
  }

  override def readSuccess(progress: Long): Option[Long] = {
    storedSequenceNr = progress
    super.readSuccess(progress)
  }

  def writeTimeout: FiniteDuration =
    settings.writeTimeout

  def readTimeout: FiniteDuration =
    settings.readTimeout

  override def preStart(): Unit = {
    if (eventLog == targetEventLog) require(!sharedClockEntry, "A processor writing to the source log must set sharedClockEntry=false")
    super.preStart()
  }
}

trait StatelessProcessor extends EventsourcedProcessor {
  override final def sharedClockEntry: Boolean = 
    true

  override final def readSuccess(progress: Long): Option[Long] = {
    super.readSuccess(progress)
    Some(progress + 1L)
  }
}