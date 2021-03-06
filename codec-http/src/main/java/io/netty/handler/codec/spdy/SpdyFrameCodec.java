/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.spdy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.UnsupportedMessageTypeException;

import java.util.List;

/**
 * A {@link ChannelHandler} that encodes and decodes SPDY Frames.
 */
public final class SpdyFrameCodec extends ByteToMessageDecoder implements SpdyFrameDecoderDelegate {

    private static final SpdyProtocolException INVALID_FRAME =
            new SpdyProtocolException("Received invalid frame");

    private final SpdyFrameDecoder spdyFrameDecoder;
    private final SpdyFrameEncoder spdyFrameEncoder;
    private final SpdyHeaderBlockDecoder spdyHeaderBlockDecoder;
    private final SpdyHeaderBlockEncoder spdyHeaderBlockEncoder;

    private SpdyHeadersFrame spdyHeadersFrame;
    private SpdySettingsFrame spdySettingsFrame;

    private ChannelHandlerContext ctx;

    /**
     * Creates a new instance with the specified {@code version} and
     * the default decoder and encoder options
     * ({@code maxChunkSize (8192)}, {@code maxHeaderSize (16384)},
     * {@code compressionLevel (6)}, {@code windowBits (15)},
     * and {@code memLevel (8)}).
     */
    public SpdyFrameCodec(SpdyVersion version) {
        this(version, 8192, 16384, 6, 15, 8);
    }

    /**
     * Creates a new instance with the specified decoder and encoder options.
     */
    public SpdyFrameCodec(
            SpdyVersion version, int maxChunkSize, int maxHeaderSize,
            int compressionLevel, int windowBits, int memLevel) {
        this(version, maxChunkSize,
                SpdyHeaderBlockDecoder.newInstance(version, maxHeaderSize),
                SpdyHeaderBlockEncoder.newInstance(version, compressionLevel, windowBits, memLevel));
    }

    protected SpdyFrameCodec(SpdyVersion version, int maxChunkSize,
            SpdyHeaderBlockDecoder spdyHeaderBlockDecoder, SpdyHeaderBlockEncoder spdyHeaderBlockEncoder) {
        spdyFrameDecoder = new SpdyFrameDecoder(version, this, maxChunkSize);
        spdyFrameEncoder = new SpdyFrameEncoder(version);
        this.spdyHeaderBlockDecoder = spdyHeaderBlockDecoder;
        this.spdyHeaderBlockEncoder = spdyHeaderBlockEncoder;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        this.ctx = ctx;
        ctx.channel().closeFuture().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                spdyHeaderBlockDecoder.end();
                spdyHeaderBlockEncoder.end();
            }
        });
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        spdyFrameDecoder.decode(in);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        ByteBuf frame;

        if (msg instanceof SpdyDataFrame) {

            SpdyDataFrame spdyDataFrame = (SpdyDataFrame) msg;
            frame = spdyFrameEncoder.encodeDataFrame(
                    ctx.alloc(),
                    spdyDataFrame.getStreamId(),
                    spdyDataFrame.isLast(),
                    spdyDataFrame.content()
            );
            spdyDataFrame.release();
            ctx.write(frame, promise);

        } else if (msg instanceof SpdySynStreamFrame) {

            SpdySynStreamFrame spdySynStreamFrame = (SpdySynStreamFrame) msg;
            ByteBuf headerBlock = spdyHeaderBlockEncoder.encode(spdySynStreamFrame);
            try {
                frame = spdyFrameEncoder.encodeSynStreamFrame(
                        ctx.alloc(),
                        spdySynStreamFrame.getStreamId(),
                        spdySynStreamFrame.getAssociatedToStreamId(),
                        spdySynStreamFrame.getPriority(),
                        spdySynStreamFrame.isLast(),
                        spdySynStreamFrame.isUnidirectional(),
                        headerBlock
                );
            } finally {
                headerBlock.release();
            }
            ctx.write(frame, promise);

        } else if (msg instanceof SpdySynReplyFrame) {

            SpdySynReplyFrame spdySynReplyFrame = (SpdySynReplyFrame) msg;
            ByteBuf headerBlock = spdyHeaderBlockEncoder.encode(spdySynReplyFrame);
            try {
                frame = spdyFrameEncoder.encodeSynReplyFrame(
                        ctx.alloc(),
                        spdySynReplyFrame.getStreamId(),
                        spdySynReplyFrame.isLast(),
                        headerBlock
                );
            } finally {
                headerBlock.release();
            }
            ctx.write(frame, promise);

        } else if (msg instanceof SpdyRstStreamFrame) {

            SpdyRstStreamFrame spdyRstStreamFrame = (SpdyRstStreamFrame) msg;
            frame = spdyFrameEncoder.encodeRstStreamFrame(
                    ctx.alloc(),
                    spdyRstStreamFrame.getStreamId(),
                    spdyRstStreamFrame.getStatus().getCode()
            );
            ctx.write(frame, promise);

        } else if (msg instanceof SpdySettingsFrame) {

            SpdySettingsFrame spdySettingsFrame = (SpdySettingsFrame) msg;
            frame = spdyFrameEncoder.encodeSettingsFrame(
                    ctx.alloc(),
                    spdySettingsFrame
            );
            ctx.write(frame, promise);

        } else if (msg instanceof SpdyPingFrame) {

            SpdyPingFrame spdyPingFrame = (SpdyPingFrame) msg;
            frame = spdyFrameEncoder.encodePingFrame(
                    ctx.alloc(),
                    spdyPingFrame.getId()
            );
            ctx.write(frame, promise);

        } else if (msg instanceof SpdyGoAwayFrame) {

            SpdyGoAwayFrame spdyGoAwayFrame = (SpdyGoAwayFrame) msg;
            frame = spdyFrameEncoder.encodeGoAwayFrame(
                    ctx.alloc(),
                    spdyGoAwayFrame.getLastGoodStreamId(),
                    spdyGoAwayFrame.getStatus().getCode()
            );
            ctx.write(frame, promise);

        } else if (msg instanceof SpdyHeadersFrame) {

            SpdyHeadersFrame spdyHeadersFrame = (SpdyHeadersFrame) msg;
            ByteBuf headerBlock = spdyHeaderBlockEncoder.encode(spdyHeadersFrame);
            try {
                frame = spdyFrameEncoder.encodeHeadersFrame(
                        ctx.alloc(),
                        spdyHeadersFrame.getStreamId(),
                        spdyHeadersFrame.isLast(),
                        headerBlock
                );
            } finally {
                headerBlock.release();
            }
            ctx.write(frame, promise);

        } else if (msg instanceof SpdyWindowUpdateFrame) {

            SpdyWindowUpdateFrame spdyWindowUpdateFrame = (SpdyWindowUpdateFrame) msg;
            frame = spdyFrameEncoder.encodeWindowUpdateFrame(
                    ctx.alloc(),
                    spdyWindowUpdateFrame.getStreamId(),
                    spdyWindowUpdateFrame.getDeltaWindowSize()
            );
            ctx.write(frame, promise);
        } else {
            throw new UnsupportedMessageTypeException(msg);
        }
    }

    @Override
    public void readDataFrame(int streamId, boolean last, ByteBuf data) {
        SpdyDataFrame spdyDataFrame = new DefaultSpdyDataFrame(streamId, data);
        spdyDataFrame.setLast(last);
        ctx.fireChannelRead(spdyDataFrame);
    }

    @Override
    public void readSynStreamFrame(
            int streamId, int associatedToStreamId, byte priority, boolean last, boolean unidirectional) {
        SpdySynStreamFrame spdySynStreamFrame = new DefaultSpdySynStreamFrame(streamId, associatedToStreamId, priority);
        spdySynStreamFrame.setLast(last);
        spdySynStreamFrame.setUnidirectional(unidirectional);
        spdyHeadersFrame = spdySynStreamFrame;
    }

    @Override
    public void readSynReplyFrame(int streamId, boolean last) {
        SpdySynReplyFrame spdySynReplyFrame = new DefaultSpdySynReplyFrame(streamId);
        spdySynReplyFrame.setLast(last);
        spdyHeadersFrame = spdySynReplyFrame;
    }

    @Override
    public void readRstStreamFrame(int streamId, int statusCode) {
        SpdyRstStreamFrame spdyRstStreamFrame = new DefaultSpdyRstStreamFrame(streamId, statusCode);
        ctx.fireChannelRead(spdyRstStreamFrame);
    }

    @Override
    public void readSettingsFrame(boolean clearPersisted) {
        spdySettingsFrame = new DefaultSpdySettingsFrame();
        spdySettingsFrame.setClearPreviouslyPersistedSettings(clearPersisted);
    }

    @Override
    public void readSetting(int id, int value, boolean persistValue, boolean persisted) {
        spdySettingsFrame.setValue(id, value, persistValue, persisted);
    }

    @Override
    public void readSettingsEnd() {
        Object frame = spdySettingsFrame;
        spdySettingsFrame = null;
        ctx.fireChannelRead(frame);
    }

    @Override
    public void readPingFrame(int id) {
        SpdyPingFrame spdyPingFrame = new DefaultSpdyPingFrame(id);
        ctx.fireChannelRead(spdyPingFrame);
    }

    @Override
    public void readGoAwayFrame(int lastGoodStreamId, int statusCode) {
        SpdyGoAwayFrame spdyGoAwayFrame = new DefaultSpdyGoAwayFrame(lastGoodStreamId, statusCode);
        ctx.fireChannelRead(spdyGoAwayFrame);
    }

    @Override
    public void readHeadersFrame(int streamId, boolean last) {
        spdyHeadersFrame = new DefaultSpdyHeadersFrame(streamId);
        spdyHeadersFrame.setLast(last);
    }

    @Override
    public void readWindowUpdateFrame(int streamId, int deltaWindowSize) {
        SpdyWindowUpdateFrame spdyWindowUpdateFrame = new DefaultSpdyWindowUpdateFrame(streamId, deltaWindowSize);
        ctx.fireChannelRead(spdyWindowUpdateFrame);
    }

    @Override
    public void readHeaderBlock(ByteBuf headerBlock) {
        try {
            spdyHeaderBlockDecoder.decode(headerBlock, spdyHeadersFrame);
        } catch (Exception e) {
            ctx.fireExceptionCaught(e);
        }
    }

    @Override
    public void readHeaderBlockEnd() {
        Object frame = null;
        try {
            spdyHeaderBlockDecoder.endHeaderBlock(spdyHeadersFrame);
            frame = spdyHeadersFrame;
            spdyHeadersFrame = null;
        } catch (Exception e) {
            ctx.fireExceptionCaught(e);
        }
        if (frame != null) {
            ctx.fireChannelRead(frame);
        }
    }

    @Override
    public void readFrameError(String message) {
        ctx.fireExceptionCaught(INVALID_FRAME);
    }
}
