/*
 * Copyright 2015-2018 the original author or authors.
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

package io.rsocket;

import static io.rsocket.frame.FrameHeaderFlyweight.frameType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.rsocket.frame.*;
import io.rsocket.test.util.TestDuplexConnection;
import io.rsocket.test.util.TestSubscriber;
import io.rsocket.util.DefaultPayload;
import io.rsocket.util.EmptyPayload;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.Assertions;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class RSocketServerTest {

  @Rule public final ServerSocketRule rule = new ServerSocketRule();

  @Test(timeout = 2000)
  @Ignore
  public void testHandleKeepAlive() throws Exception {
    rule.connection.addToReceivedBuffer(
        KeepAliveFrameFlyweight.encode(ByteBufAllocator.DEFAULT, true, 0, Unpooled.EMPTY_BUFFER));
    ByteBuf sent = rule.connection.awaitSend();
    assertThat("Unexpected frame sent.", frameType(sent), is(FrameType.KEEPALIVE));
    /*Keep alive ack must not have respond flag else, it will result in infinite ping-pong of keep alive frames.*/
    assertThat(
        "Unexpected keep-alive frame respond flag.",
        KeepAliveFrameFlyweight.respondFlag(sent),
        is(false));
  }

  @Test(timeout = 2000)
  @Ignore
  public void testHandleResponseFrameNoError() throws Exception {
    final int streamId = 4;
    rule.connection.clearSendReceiveBuffers();

    rule.sendRequest(streamId, FrameType.REQUEST_RESPONSE);

    Collection<Subscriber<ByteBuf>> sendSubscribers = rule.connection.getSendSubscribers();
    assertThat("Request not sent.", sendSubscribers, hasSize(1));
    assertThat("Unexpected error.", rule.errors, is(empty()));
    Subscriber<ByteBuf> sendSub = sendSubscribers.iterator().next();
    assertThat(
        "Unexpected frame sent.",
        frameType(rule.connection.awaitSend()),
        anyOf(is(FrameType.COMPLETE), is(FrameType.NEXT_COMPLETE)));
  }

  @Test(timeout = 2000)
  @Ignore
  public void testHandlerEmitsError() throws Exception {
    final int streamId = 4;
    rule.sendRequest(streamId, FrameType.REQUEST_STREAM);
    assertThat("Unexpected error.", rule.errors, is(empty()));
    assertThat(
        "Unexpected frame sent.", frameType(rule.connection.awaitSend()), is(FrameType.ERROR));
  }

  @Test(timeout = 2_0000)
  public void testCancel() {
    final int streamId = 4;
    final AtomicBoolean cancelled = new AtomicBoolean();
    rule.setAcceptingSocket(
        new AbstractRSocket() {
          @Override
          public Mono<Payload> requestResponse(Payload payload) {
            return Mono.<Payload>never().doOnCancel(() -> cancelled.set(true));
          }
        });
    rule.sendRequest(streamId, FrameType.REQUEST_RESPONSE);

    assertThat("Unexpected error.", rule.errors, is(empty()));
    assertThat("Unexpected frame sent.", rule.connection.getSent(), is(empty()));

    rule.connection.addToReceivedBuffer(
        CancelFrameFlyweight.encode(ByteBufAllocator.DEFAULT, streamId));

    assertThat("Unexpected frame sent.", rule.connection.getSent(), is(empty()));
    assertThat("Subscription not cancelled.", cancelled.get(), is(true));
  }

  @Test(timeout = 2_000)
  @SuppressWarnings("unchecked")
  public void
      testServerSideRequestStreamShouldNotHangInfinitelySendingElementsAndShouldProduceDataValuingConnectionBackpressure() {
    final int streamId = 5;
    final Queue<Object> received = new ConcurrentLinkedQueue<>();
    final Queue<Long> requests = new ConcurrentLinkedQueue<>();

    rule.setAcceptingSocket(
        new AbstractRSocket() {
          @Override
          public Flux<Payload> requestStream(Payload payload) {
            return Flux.<Payload>generate(s -> s.next(payload.retain())).doOnRequest(requests::add);
          }
        },
        256);

    rule.sendRequest(streamId, FrameType.REQUEST_STREAM);

    assertThat("Unexpected error.", rule.errors, is(empty()));

    Subscriber next = rule.connection.getSendSubscribers().iterator().next();

    Mockito.doAnswer(
            invocation -> {
              received.add(invocation.getArgument(0));

              if (received.size() == 256) {
                throw new RuntimeException();
              }

              return null;
            })
        .when(next)
        .onNext(Mockito.any());

    rule.connection.addToReceivedBuffer(
        RequestNFrameFlyweight.encode(ByteBufAllocator.DEFAULT, streamId, Integer.MAX_VALUE));
    Assertions.assertThat(requests).containsOnly(1L, 2L, 253L);
  }

  @Test(timeout = 2_000)
  @SuppressWarnings("unchecked")
  public void
      testServerSideRequestChannelShouldNotHangInfinitelySendingElementsAndShouldProduceDataValuingConnectionBackpressure() {
    final int streamId = 5;
    final Queue<Object> received = new ConcurrentLinkedQueue<>();
    final Queue<Long> requests = new ConcurrentLinkedQueue<>();

    rule.setAcceptingSocket(
        new AbstractRSocket() {
          @Override
          public Flux<Payload> requestChannel(Publisher<Payload> payload) {
            return Flux.<Payload>generate(s -> s.next(EmptyPayload.INSTANCE))
                .doOnRequest(requests::add);
          }
        },
        256);

    rule.sendRequest(streamId, FrameType.REQUEST_CHANNEL);

    assertThat("Unexpected error.", rule.errors, is(empty()));

    Subscriber next = rule.connection.getSendSubscribers().iterator().next();

    Mockito.doAnswer(
            invocation -> {
              received.add(invocation.getArgument(0));

              if (received.size() == 256) {
                throw new RuntimeException();
              }

              return null;
            })
        .when(next)
        .onNext(Mockito.any());

    rule.connection.addToReceivedBuffer(
        RequestNFrameFlyweight.encode(ByteBufAllocator.DEFAULT, streamId, Integer.MAX_VALUE));
    Assertions.assertThat(requests).containsOnly(1L, 2L, 253L);
  }

  public static class ServerSocketRule extends AbstractSocketRule<RSocketServer> {

    private RSocket acceptingSocket;

    @Override
    protected void init() {
      acceptingSocket =
          new AbstractRSocket() {
            @Override
            public Mono<Payload> requestResponse(Payload payload) {
              return Mono.just(payload);
            }
          };
      super.init();
    }

    public void setAcceptingSocket(RSocket acceptingSocket) {
      this.acceptingSocket = acceptingSocket;
      connection = new TestDuplexConnection();
      connectSub = TestSubscriber.create();
      errors = new ConcurrentLinkedQueue<>();
      super.init();
    }

    public void setAcceptingSocket(RSocket acceptingSocket, int prefetch) {
      this.acceptingSocket = acceptingSocket;
      connection = new TestDuplexConnection();
      connection.setInitialSendRequestN(prefetch);
      connectSub = TestSubscriber.create();
      errors = new ConcurrentLinkedQueue<>();
      super.init();
    }

    @Override
    protected RSocketServer newRSocket() {
      return new RSocketServer(
          ByteBufAllocator.DEFAULT,
          connection,
          acceptingSocket,
          DefaultPayload::create,
          throwable -> errors.add(throwable));
    }

    private void sendRequest(int streamId, FrameType frameType) {
      ByteBuf request;

      switch (frameType) {
        case REQUEST_CHANNEL:
          request =
              RequestChannelFrameFlyweight.encode(
                  ByteBufAllocator.DEFAULT, streamId, false, false, 1, EmptyPayload.INSTANCE);
          break;
        case REQUEST_STREAM:
          request =
              RequestStreamFrameFlyweight.encode(
                  ByteBufAllocator.DEFAULT, streamId, false, 1, EmptyPayload.INSTANCE);
          break;
        case REQUEST_RESPONSE:
          request =
              RequestResponseFrameFlyweight.encode(
                  ByteBufAllocator.DEFAULT, streamId, false, EmptyPayload.INSTANCE);
          break;
        default:
          throw new IllegalArgumentException("unsupported type: " + frameType);
      }

      connection.addToReceivedBuffer(request);
      connection.addToReceivedBuffer(
          RequestNFrameFlyweight.encode(ByteBufAllocator.DEFAULT, streamId, 2));
    }
  }
}
