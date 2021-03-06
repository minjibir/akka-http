/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.impl.engine.http2

import akka.annotation.InternalApi
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode
import akka.http.impl.engine.http2.Http2Protocol.ErrorCode.FLOW_CONTROL_ERROR
import akka.http.scaladsl.settings.Http2ServerSettings
import akka.stream.Attributes
import akka.stream.BidiShape
import akka.stream.Inlet
import akka.stream.Outlet
import akka.stream.impl.io.ByteStringParser.ParsingException
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, StageLogging }
import akka.util.ByteString

import scala.collection.immutable
import scala.util.control.NonFatal

import FrameEvent._

/**
 * INTERNAL API
 *
 * This stage contains all control logic for handling frames and (de)muxing data to/from substreams.
 *
 * The BidiStage consumes and produces FrameEvents from the network. It will output one Http2SubStream
 * for incoming frames per substream and likewise accepts a single Http2SubStream per substream with
 * outgoing frames.
 *
 * (An alternative API would just push a BidiHttp2SubStream(subStreamFlow: Flow[StreamFrameEvent, StreamFrameEvent])
 *  similarly to IncomingConnection. This would more accurately model the one-to-one relation between incoming and
 *  outgoing Http2Substream directions but wouldn't stack so nicely with other BidiFlows.)
 *
 * Backpressure logic:
 *
 *  * read all incoming frames without applying backpressure
 *    * this ensures that all "control" frames are read in a timely manner
 *    * though, make sure limits are not exceeded
 *      * max connection limit (which limits number of parallel requests)
 *      * window sizes for incoming data frames
 *    * that means we need to buffer incoming substream data until the user handler (consuming the source in the Http2SubStream)
 *      will read it
 *    * per-connection and per-stream window updates should reflect how much data was (not) yet passed
 *      into the user handler and therefore are the main backpressure mechanism towards the peer
 *  * for the outgoing frame side we need to decide which frames to send per incoming demand
 *    * control frames (settings, ping, acks, window updates etc.) -> responses to incoming frames
 *    * substream frames -> incoming frame data from substreams
 *    * to be able to make a decision some data must already be buffered for those two sources of incoming frames
 *
 * Demultiplexing:
 *  * distribute incoming frames to their respective targets:
 *    * control frames: handled internally, may generate outgoing control frames directly
 *    * incoming HEADERS frames: creates a new Http2SubStream including a SubSource that will receive all upcoming
 *      data frames
 *    * incoming data frames: buffered and pushed to the SubSource of the respective substream
 *
 * Multiplexing:
 *  * schedule incoming frames from multiple sources to be pushed onto the shared medium
 *    * control frames: as generated from the stage itself (should probably preferred over everything else)
 *    * Http2SubStream produced by the user handler: read and push initial frame ASAP
 *    * outgoing data frames for each of the substreams: will comprise the bulk of the data and is
 *      where any clever, prioritizing, etc. i.e. tbd later sending strategies will apply
 *
 * In the best case we could just flattenMerge the outgoing side (hoping for the best) but this will probably
 * not work because the sending decision relies on dynamic window size and settings information that will be
 * only available in this stage.
 */
@InternalApi
private[http2] class Http2ServerDemux(http2Settings: Http2ServerSettings, initialDemuxerSettings: immutable.Seq[Setting], upgraded: Boolean) extends GraphStage[BidiShape[Http2SubStream, FrameEvent, FrameEvent, Http2SubStream]] {
  val frameIn = Inlet[FrameEvent]("Demux.frameIn")
  val frameOut = Outlet[FrameEvent]("Demux.frameOut")

  val substreamOut = Outlet[Http2SubStream]("Demux.substreamOut")
  val substreamIn = Inlet[Http2SubStream]("Demux.substreamIn")

  override val shape =
    BidiShape(substreamIn, frameOut, frameIn, substreamOut)

  def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with Http2MultiplexerSupport with Http2StreamHandling with GenericOutletSupport with StageLogging {
      logic =>

      override def settings: Http2ServerSettings = http2Settings
      override def isUpgraded: Boolean = upgraded

      override protected def logSource: Class[_] = classOf[Http2ServerDemux]

      val multiplexer = createMultiplexer(frameOut, StreamPrioritizer.first())

      override def preStart(): Unit = {
        if (initialDemuxerSettings.nonEmpty) {
          log.debug("Applying {} initial settings!", initialDemuxerSettings.length)
          applySettings(initialDemuxerSettings)
        }

        pullFrameIn()
        pull(substreamIn)

        multiplexer.pushControlFrame(SettingsFrame(Nil)) // server side connection preface
      }

      /**
       * The "last peer-initiated stream that was or might be processed on the sending endpoint in this connection"
       * @see http://httpwg.org/specs/rfc7540.html#rfc.section.6.8
       *
       * We currently don't support tracking that value accurately.
       * TODO: track more accurately
       */
      def lastStreamId: Int = 1

      def pushGOAWAY(errorCode: ErrorCode, debug: String): Unit = {
        // http://httpwg.org/specs/rfc7540.html#rfc.section.6.8
        val last = lastStreamId
        val frame = GoAwayFrame(last, errorCode, ByteString(debug))
        multiplexer.pushControlFrame(frame)
        // FIXME: handle the connection closing according to the specification
      }
      private[this] var allowReadingIncomingFrames: Boolean = true
      override def allowReadingIncomingFrames(allow: Boolean): Unit = {
        if (allow != allowReadingIncomingFrames)
          if (allow) {
            log.debug("Resume reading incoming frames")
            if (!hasBeenPulled(frameIn)) pull(frameIn)
          } else log.debug("Suspended reading incoming frames") // can't retract pending pull but that's ok

        allowReadingIncomingFrames = allow
      }
      def pullFrameIn(): Unit = if (allowReadingIncomingFrames && !hasBeenPulled(frameIn)) pull(frameIn)

      setHandler(frameIn, new InHandler {

        def onPush(): Unit = {
          val in = grab(frameIn)
          in match {
            case WindowUpdateFrame(streamId, increment) => multiplexer.updateWindow(streamId, increment) // handled specially
            case p: PriorityFrame                       => multiplexer.updatePriority(p)
            case s: StreamFrameEvent                    => handleStreamEvent(s)

            case SettingsFrame(settings) =>
              if (settings.nonEmpty) log.debug("Got {} settings!", settings.length)

              var settingsAppliedOk = true

              settings.foreach {
                case Setting(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, value) =>
                  if (value >= 0) {
                    log.debug("Setting initial window to {}", value)
                    multiplexer.updateDefaultWindow(value)
                  } else {
                    pushGOAWAY(FLOW_CONTROL_ERROR, s"Invalid value for SETTINGS_INITIAL_WINDOW_SIZE: $value")
                    settingsAppliedOk = false
                  }
                case Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, value) =>
                  multiplexer.updateMaxFrameSize(value)
                case Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, value) =>
                  log.debug("Setting max concurrent streams to {} (not enforced)", value)
                case Setting(id, value) =>
                  log.debug("Ignoring setting {} -> {} (in Demux)", id, value)
              }

              if (settingsAppliedOk) {
                multiplexer.pushControlFrame(SettingsAckFrame(settings))
              }

            case SettingsAckFrame(Nil) =>
            // Currently, we only expect an ack for the initial (currently empty) settings frame, sent
            // above in preStart. Since, it was empty, there's nothing to do here.
            // If we want to support setting and enforcing settings, we'll need to act here to commit
            // to the settings we sent out before.
            // https://github.com/akka/akka-http/issues/3185

            case PingFrame(true, _)    =>
            // ignore for now (we don't send any pings)
            case PingFrame(false, data) =>
              multiplexer.pushControlFrame(PingFrame(ack = true, data))

            case e =>
              log.debug("Got unhandled event {}", e)
            // ignore unknown frames
          }
          pullFrameIn()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          ex match {
            // every IllegalHttp2StreamIdException will be a GOAWAY with PROTOCOL_ERROR
            case e: Http2Compliance.IllegalHttp2StreamIdException =>
              pushGOAWAY(ErrorCode.PROTOCOL_ERROR, e.getMessage)

            case e: Http2Compliance.Http2ProtocolException =>
              pushGOAWAY(e.errorCode, e.getMessage)

            case e: Http2Compliance.Http2ProtocolStreamException =>
              resetStream(e.streamId, e.errorCode)

            case e: ParsingException =>
              e.getCause match {
                case null  => super.onUpstreamFailure(e) // fail with the raw parsing exception
                case cause => onUpstreamFailure(cause) // unwrap the cause, which should carry ComplianceException and recurse
              }

            // handle every unhandled exception
            case NonFatal(e) =>
              super.onUpstreamFailure(e)
          }
        }
      })

      // FIXME: What if user handler doesn't pull in new substreams? Should we reject them
      //        after a while or buffer only a limited amount? We should also be able to
      //        keep the buffer limited to the number of concurrent streams as negotiated
      //        with the other side.
      val bufferedSubStreamOutput = new BufferedOutlet[Http2SubStream](substreamOut)
      def dispatchSubstream(sub: Http2SubStream): Unit = bufferedSubStreamOutput.push(sub)

      setHandler(substreamIn, new InHandler {
        def onPush(): Unit = {
          val sub = grab(substreamIn)
          pull(substreamIn)
          multiplexer.registerSubStream(sub)
        }
      })

      private def applySettings(settings: immutable.Seq[Setting]): Boolean = {
        var settingsAppliedOk = true

        settings.foreach {
          case Setting(Http2Protocol.SettingIdentifier.SETTINGS_INITIAL_WINDOW_SIZE, value) =>
            if (value >= 0) {
              log.debug("Setting initial window to {}", value)
              multiplexer.updateDefaultWindow(value)
            } else {
              pushGOAWAY(FLOW_CONTROL_ERROR, s"Invalid value for SETTINGS_INITIAL_WINDOW_SIZE: $value")
              settingsAppliedOk = false
            }
          case Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_FRAME_SIZE, value) =>
            multiplexer.updateMaxFrameSize(value)
          case Setting(Http2Protocol.SettingIdentifier.SETTINGS_MAX_CONCURRENT_STREAMS, value) =>
            log.debug("Setting max concurrent streams to {} (not enforced)", value)
          case Setting(id, value) =>
            log.debug("Ignoring setting {} -> {} (in Demux)", id, value)
        }
        settingsAppliedOk
      }

      override def postStop(): Unit = {
        multiplexer.shutdown()
        shutdownStreamHandling()
      }
    }
}
