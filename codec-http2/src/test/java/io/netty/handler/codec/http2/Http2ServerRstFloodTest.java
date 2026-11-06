/*
 * Copyright 2025 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.logging.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.netty.handler.codec.http2.Http2Error.ENHANCE_YOUR_CALM;
import static io.netty.handler.codec.http2.Http2Error.FLOW_CONTROL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Error.STREAM_CLOSED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for "MadeYouReset" (CVE-2025-55163): a remote peer sends frames that are valid on the wire but
 * cause a stream error, so the server itself keeps generating RST_STREAM frames. These RST frames are never sent by
 * the peer, so the inbound RST limit does not apply; the outbound RST limit must tear down the connection instead.
 */
public class Http2ServerRstFloodTest {

    // Matches AbstractHttp2ConnectionHandlerBuilder.DEFAULT_MAX_RST_FRAMES_PER_CONNECTION_FOR_SERVER.
    private static final int DEFAULT_MAX_RST_FRAMES = 200;
    private static final int ATTACK_ATTEMPTS = 1000;

    private static final int ZERO_WINDOW_UPDATE = 0;
    private static final int WINDOW_UPDATE_OVERFLOW = 1;
    private static final int DATA_ON_HALF_CLOSED_STREAM = 2;

    private EmbeddedChannel channel;
    private Http2FrameInboundWriter inboundWriter;
    private RecordingFrameLogger frameLogger;

    @AfterEach
    public void tearDown() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void serverClosesConnectionOnRstFloodTriggeredByZeroWindowUpdate() {
        assertDefaultServerStopsRstFlood(ZERO_WINDOW_UPDATE, PROTOCOL_ERROR);
    }

    @Test
    public void serverClosesConnectionOnRstFloodTriggeredByWindowUpdateOverflow() {
        assertDefaultServerStopsRstFlood(WINDOW_UPDATE_OVERFLOW, FLOW_CONTROL_ERROR);
    }

    @Test
    public void serverClosesConnectionOnRstFloodTriggeredByDataOnHalfClosedStream() {
        assertDefaultServerStopsRstFlood(DATA_ON_HALF_CLOSED_STREAM, STREAM_CLOSED);
    }

    @Test
    public void serverHonoursConfiguredEncoderRstLimit() {
        setUpServer(new Http2ConnectionHandlerBuilder().encoderEnforceMaxRstFramesPerWindow(10, 30));

        int streamId = 3;
        for (int i = 0; i < 10; i++) {
            triggerServerRst(ZERO_WINDOW_UPDATE, streamId);
            streamId += 2;
        }
        assertTrue(channel.isOpen());
        assertEquals(10, frameLogger.outboundRstFrames);
        assertEquals(-1, frameLogger.outboundGoAwayErrorCode);

        triggerServerRst(ZERO_WINDOW_UPDATE, streamId);
        assertFalse(channel.isOpen());
        assertEquals(ENHANCE_YOUR_CALM.code(), frameLogger.outboundGoAwayErrorCode);
    }

    @Test
    public void serverEncoderRstLimitCanBeDisabled() {
        setUpServer(new Http2ConnectionHandlerBuilder().encoderEnforceMaxRstFramesPerWindow(0, 30));

        int streamId = 3;
        for (int i = 0; i < DEFAULT_MAX_RST_FRAMES * 2; i++) {
            triggerServerRst(ZERO_WINDOW_UPDATE, streamId);
            streamId += 2;
        }
        assertTrue(channel.isOpen());
        assertEquals(DEFAULT_MAX_RST_FRAMES * 2, frameLogger.outboundRstFrames);
        assertEquals(-1, frameLogger.outboundGoAwayErrorCode);
    }

    private void assertDefaultServerStopsRstFlood(int vector, Http2Error expectedRstError) {
        setUpServer(new Http2ConnectionHandlerBuilder());

        int streamId = 3;
        // Up to the limit every malicious frame results in a RST_STREAM but the connection stays usable.
        for (int i = 0; i < DEFAULT_MAX_RST_FRAMES; i++) {
            triggerServerRst(vector, streamId);
            streamId += 2;
        }
        assertTrue(channel.isOpen());
        assertEquals(DEFAULT_MAX_RST_FRAMES, frameLogger.outboundRstFrames);
        assertEquals(expectedRstError.code(), frameLogger.lastOutboundRstErrorCode);
        assertEquals(-1, frameLogger.outboundGoAwayErrorCode);

        // Keep flooding: the server must stop generating RST frames and close the connection.
        for (int i = 0; i < ATTACK_ATTEMPTS && channel.isOpen(); i++) {
            triggerServerRst(vector, streamId);
            streamId += 2;
        }
        assertFalse(channel.isOpen(), "server kept sending RST_STREAM frames without closing the connection");
        assertEquals(ENHANCE_YOUR_CALM.code(), frameLogger.outboundGoAwayErrorCode);
        assertEquals(DEFAULT_MAX_RST_FRAMES + 1, frameLogger.outboundRstFrames);
    }

    private void setUpServer(Http2ConnectionHandlerBuilder builder) {
        frameLogger = new RecordingFrameLogger();
        Http2ConnectionHandler handler = builder.server(true)
                .frameListener(new Http2FrameAdapter())
                .frameLogger(frameLogger)
                .build();
        channel = new EmbeddedChannel(handler);
        inboundWriter = new Http2FrameInboundWriter(channel);

        channel.writeInbound(Http2CodecUtil.connectionPrefaceBuf());
        inboundWriter.writeInboundSettings(new Http2Settings());
        inboundWriter.writeInboundSettingsAck();
        assertTrue(channel.isOpen());
        releaseOutbound();
    }

    private void triggerServerRst(int vector, int streamId) {
        Http2Headers request = new DefaultHttp2Headers()
                .method("GET").scheme("https").authority("example.com").path("/");
        switch (vector) {
            case ZERO_WINDOW_UPDATE:
                inboundWriter.writeInboundHeaders(streamId, request, 0, false);
                inboundWriter.writeInboundWindowUpdate(streamId, 0);
                break;
            case WINDOW_UPDATE_OVERFLOW:
                inboundWriter.writeInboundHeaders(streamId, request, 0, false);
                inboundWriter.writeInboundWindowUpdate(streamId, Integer.MAX_VALUE);
                break;
            case DATA_ON_HALF_CLOSED_STREAM:
                inboundWriter.writeInboundHeaders(streamId, request, 0, true);
                inboundWriter.writeInboundData(streamId, Unpooled.wrappedBuffer(new byte[] { 1 }), 0, false);
                break;
            default:
                throw new IllegalArgumentException("unknown vector: " + vector);
        }
        releaseOutbound();
    }

    private void releaseOutbound() {
        for (;;) {
            ByteBuf buf = channel.readOutbound();
            if (buf == null) {
                break;
            }
            buf.release();
        }
    }

    private static final class RecordingFrameLogger extends Http2FrameLogger {
        int outboundRstFrames;
        long lastOutboundRstErrorCode = -1;
        long outboundGoAwayErrorCode = -1;

        RecordingFrameLogger() {
            super(LogLevel.TRACE);
        }

        @Override
        public void logRstStream(Direction direction, ChannelHandlerContext ctx, int streamId, long errorCode) {
            if (direction == Direction.OUTBOUND) {
                outboundRstFrames++;
                lastOutboundRstErrorCode = errorCode;
            }
            super.logRstStream(direction, ctx, streamId, errorCode);
        }

        @Override
        public void logGoAway(Direction direction, ChannelHandlerContext ctx, int lastStreamId, long errorCode,
                              ByteBuf debugData) {
            if (direction == Direction.OUTBOUND) {
                outboundGoAwayErrorCode = errorCode;
            }
            super.logGoAway(direction, ctx, lastStreamId, errorCode, debugData);
        }
    }
}
