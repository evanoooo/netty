/*
 * Copyright 2025 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.uring;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.util.NetUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class IoUringBufferRingTest {
    @BeforeAll
    public static void loadJNI() {
        assumeTrue(IoUring.isAvailable());
    }

    @Test
    public void testRegister() {
        RingBuffer ringBuffer = Native.createRingBuffer(8, 0);
        try {
            int ringFd = ringBuffer.fd();
            long ioUringBufRingAddr = Native.ioUringRegisterBuffRing(ringFd, 4, (short) 1, 0);
            assumeTrue(
                    ioUringBufRingAddr > 0,
                    "ioUringSetupBufRing result must great than 0, but now result is " + ioUringBufRingAddr);
            int freeRes = Native.ioUringUnRegisterBufRing(ringFd, ioUringBufRingAddr, 4, 1);
            assertEquals(
                    0,
                    freeRes,
                    "ioUringFreeBufRing result must be 0, but now result is " + freeRes
            );
        } finally {
            ringBuffer.close();
        }
    }

    @Test
    public void testProviderBufferRead() throws InterruptedException {
        final BlockingQueue<ByteBuf> bufferSyncer = new LinkedBlockingQueue<>();
        IoUringIoHandlerConfig ioUringIoHandlerConfiguration = new IoUringIoHandlerConfig();
        IoUringBufferRingConfig bufferRingConfig = new IoUringBufferRingConfig(
                (short) 1, (short) 2, 1024, ByteBufAllocator.DEFAULT);
        ioUringIoHandlerConfiguration.addBufferRingConfig(bufferRingConfig);

        IoUringBufferRingConfig bufferRingConfig1 = new IoUringBufferRingConfig(
                (short) 2, (short) 16,
                1024, ByteBufAllocator.DEFAULT,
                12
        );
        ioUringIoHandlerConfiguration.addBufferRingConfig(bufferRingConfig1);

        MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1,
                IoUringIoHandler.newFactory(ioUringIoHandlerConfiguration)
        );
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.channel(IoUringServerSocketChannel.class);

        String randomString = UUID.randomUUID().toString();
        int randomStringLength = randomString.length();

        ArrayBlockingQueue<IoUringBufferRingExhaustedEvent> eventSyncer = new ArrayBlockingQueue<>(1);

        Channel serverChannel = serverBootstrap.group(group)
                .childHandler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        bufferSyncer.offer((ByteBuf) msg);
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof IoUringBufferRingExhaustedEvent) {
                            eventSyncer.add((IoUringBufferRingExhaustedEvent) evt);
                        }
                    }
                })
                .childOption(IoUringChannelOption.IO_URING_BUFFER_GROUP_ID, bufferRingConfig.bufferGroupId())
                .bind(NetUtil.LOCALHOST, 0)
                .syncUninterruptibly().channel();

        Bootstrap clientBoostrap = new Bootstrap();
        clientBoostrap.group(group)
                .channel(IoUringSocketChannel.class)
                .handler(new ChannelInboundHandlerAdapter());
        ChannelFuture channelFuture = clientBoostrap.connect(serverChannel.localAddress()).syncUninterruptibly();
        assumeTrue(channelFuture.isSuccess());
        Channel clientChannel = channelFuture.channel();

        //is provider buffer read?
        ByteBuf writeBuffer = Unpooled.directBuffer(randomStringLength);
        ByteBufUtil.writeAscii(writeBuffer, randomString);
        ByteBuf userspaceIoUringBufferElement1 = sendAndRecvMessage(clientChannel, writeBuffer, bufferSyncer);
        assertInstanceOf(IoUringBufferRing.IoUringBufferRingByteBuf.class, userspaceIoUringBufferElement1);
        ByteBuf userspaceIoUringBufferElement2 = sendAndRecvMessage(clientChannel, writeBuffer, bufferSyncer);
        assertInstanceOf(IoUringBufferRing.IoUringBufferRingByteBuf.class, userspaceIoUringBufferElement2);
        // Directly after the second read we will see the event as it will be triggered inline when doing the submit.
        assertEquals(bufferRingConfig.bufferGroupId(), eventSyncer.take().bufferGroupId());
        assertEquals(0, eventSyncer.size());

        // We ran out of buffers in the buffer ring
        ByteBuf readBuffer = sendAndRecvMessage(clientChannel, writeBuffer, bufferSyncer);
        assertFalse(readBuffer instanceof IoUringBufferRing.IoUringBufferRingByteBuf);
        readBuffer.release();

        // Now we release the buffer and so put it back into the buffer ring.
        userspaceIoUringBufferElement1.release();
        userspaceIoUringBufferElement2.release();

        // As we already had the next read scheduled we will see one more buffer that was not taken out of the ring
        readBuffer = sendAndRecvMessage(clientChannel, writeBuffer, bufferSyncer);
        assertFalse(readBuffer instanceof IoUringBufferRing.IoUringBufferRingByteBuf);
        readBuffer.release();

        // The next buffer is expected to be provided out of the ring again.
        readBuffer = sendAndRecvMessage(clientChannel, writeBuffer, bufferSyncer);
        assertInstanceOf(IoUringBufferRing.IoUringBufferRingByteBuf.class, readBuffer);
        readBuffer.release();

        writeBuffer.release();

        serverChannel.close();
        clientChannel.close();
        group.shutdownGracefully();
    }

    private ByteBuf sendAndRecvMessage(Channel clientChannel, ByteBuf writeBuffer, BlockingQueue<ByteBuf> bufferSyncer)
            throws InterruptedException {
        //retain the buffer to assert
        clientChannel.writeAndFlush(writeBuffer.retainedDuplicate()).sync();
        ByteBuf readBuffer = bufferSyncer.take();
        assertEquals(writeBuffer.readableBytes(), readBuffer.readableBytes());
        assertTrue(ByteBufUtil.equals(writeBuffer, readBuffer));
        return readBuffer;
    }
}
