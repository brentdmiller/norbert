/*
 * Copyright 2009-2010 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.norbert.network.netty

import org.jboss.netty.channel.group.ChannelGroup
import com.linkedin.norbert.logging.Logging
import com.linkedin.norbert.protos.NorbertProtos
import com.linkedin.norbert.network.InvalidMessageException
import org.jboss.netty.channel._
import com.google.protobuf.{InvalidProtocolBufferException, Message}
import com.linkedin.norbert.network.server.{MessageHandlerRegistry, MessageExecutor}
import org.jboss.netty.handler.codec.oneone.{OneToOneEncoder, OneToOneDecoder}
import com.linkedin.norbert.jmx.{AverageTimeTracker, JMX}
import com.linkedin.norbert.jmx.JMX.MBean
import java.util.UUID

object RequestContext {
  def apply(requestId: UUID): RequestContext = RequestContext(requestId, System.currentTimeMillis)
}

case class RequestContext(requestId: UUID, receivedAt: Long)

@ChannelPipelineCoverage("all")
class RequestContextDecoder extends OneToOneDecoder {
  def decode(ctx: ChannelHandlerContext, channel: Channel, msg: Any) = {
    val norbertMessage = msg.asInstanceOf[NorbertProtos.NorbertMessage]
    val requestId = new UUID(norbertMessage.getRequestIdMsb, norbertMessage.getRequestIdLsb)

    if (norbertMessage.getStatus != NorbertProtos.NorbertMessage.Status.OK) {
      val ex = new InvalidMessageException("Invalid request, message has status set to ERROR")
      Channels.write(ctx, Channels.future(channel), ResponseHelper.errorResponse(requestId, ex))
      throw ex
    }

    (RequestContext(requestId), norbertMessage)
  }
}

@ChannelPipelineCoverage("all")
class RequestContextEncoder(serviceName: String) extends OneToOneEncoder with Logging {
  private val statsActor = new NetworkStatisticsActor(100)
  statsActor.start

  private val requestProcessingTime = new AverageTimeTracker(100)

  private val jmxHandle = JMX.register(new MBean(classOf[NetworkServerStatisticsMBean], "service=%s".format(serviceName)) with NetworkServerStatisticsMBean {
    import statsActor.Stats._

    def getRequestsPerSecond = statsActor !? GetRequestsPerSecond match {
      case RequestsPerSecond(rps) => rps
    }

    def getAverageRequestProcessingTime = statsActor !? GetAverageProcessingTime match {
      case AverageProcessingTime(time) => time
    }
  })

  def encode(ctx: ChannelHandlerContext, channel: Channel, msg: Any) = {
    val (context, norbertMessage) = msg.asInstanceOf[(RequestContext, NorbertProtos.NorbertMessage)]

    statsActor ! statsActor.Stats.NewProcessingTime((System.currentTimeMillis - context.receivedAt).toInt)

    norbertMessage
  }

  def shutdown {
    statsActor ! 'quit
    jmxHandle.foreach { JMX.unregister(_) }
  }
}

@ChannelPipelineCoverage("all")
class ServerChannelHandler(channelGroup: ChannelGroup, messageHandlerRegistry: MessageHandlerRegistry, messageExecutor: MessageExecutor) extends SimpleChannelHandler with Logging {
  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    val channel = e.getChannel
    log.ifTrace("channelOpen: " + channel)
    channelGroup.add(channel)
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val (context, norbertMessage) = e.getMessage.asInstanceOf[(RequestContext, NorbertProtos.NorbertMessage)]
    val channel = e.getChannel
    val message = messageHandlerRegistry.requestMessageDefaultInstanceFor(norbertMessage.getMessageName) map { di =>
      try {
        di.newBuilderForType.mergeFrom(norbertMessage.getMessage).build
      } catch {
        case ex: InvalidProtocolBufferException =>
          Channels.write(ctx, Channels.future(channel), (context, ResponseHelper.errorResponse(context.requestId, ex)))
          throw ex
      }
    } getOrElse {
      val ex = new InvalidMessageException("No such message of type %s registered".format(norbertMessage.getMessageName))
      Channels.write(ctx, Channels.future(channel), (context, ResponseHelper.errorResponse(context.requestId, ex)))
      throw ex
    }

    messageExecutor.executeMessage(message, either => responseHandler(context, e.getChannel, either))
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) = log.info(e.getCause, "Caught exception in channel: %s".format(e.getChannel))

  def responseHandler(context: RequestContext, channel: Channel, either: Either[Exception, Message]) {
    val message = either match {
      case Left(ex) => ResponseHelper.errorResponse(context.requestId, ex)
      case Right(message) => ResponseHelper.responseBuilder(context.requestId)
              .setMessageName(message.getDescriptorForType.getFullName)
              .setMessage(message.toByteString)
              .build
    }

    log.ifDebug("Sending response: %s", message)

    channel.write((context, message))
  }
}

private[netty] object ResponseHelper {
  def responseBuilder(requestId: UUID) = {
    NorbertProtos.NorbertMessage.newBuilder.setRequestIdMsb(requestId.getMostSignificantBits).setRequestIdLsb(requestId.getLeastSignificantBits)
  }

  def errorResponse(requestId: UUID, ex: Exception) = {
    responseBuilder(requestId)
            .setMessageName(ex.getClass.getName)
            .setStatus(NorbertProtos.NorbertMessage.Status.ERROR)
            .setErrorMessage(if (ex.getMessage == null) "" else ex.getMessage)
            .build
  }
}

trait NetworkServerStatisticsMBean {
  def getRequestsPerSecond: Int
  def getAverageRequestProcessingTime: Int
}
