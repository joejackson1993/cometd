/*
 * Copyright (c) 2008-2021 the original author or authors.
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
package org.cometd.client.ext;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.ConfigurableServerChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.ClientServerTest;
import org.cometd.server.ext.ActivityExtension;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ActivityExtensionTest extends ClientServerTest {
    private long timeout;
    private String channelName = "/test";
    private ScheduledExecutorService scheduler;

    @Before
    public void prepare() throws Exception {
        timeout = 1000;
        Map<String, String> options = new HashMap<>();
        options.put("timeout", String.valueOf(timeout));
        start(options);
        bayeux.createChannelIfAbsent(channelName, new ConfigurableServerChannel.Initializer.Persistent());
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @After
    public void dispose() throws Exception {
        scheduler.shutdown();
        scheduler.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    public void testClientInactivity() throws Exception {
        long maxInactivityPeriod = 4000;
        bayeux.addExtension(new ActivityExtension(ActivityExtension.Activity.CLIENT, maxInactivityPeriod));

        scheduler.scheduleWithFixedDelay(() -> bayeux.getChannel(channelName).publish(null, "test", Promise.noop()), 0, timeout / 4, TimeUnit.MILLISECONDS);

        final BayeuxClient client = newBayeuxClient();
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                client.getChannel(channelName).subscribe((c, m) -> {
                });
            }
        });

        final CountDownLatch latch = new CountDownLatch(2);
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Map<String, Object> advice = message.getAdvice();
            if (advice != null && Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD))) {
                latch.countDown();
            }
        });
        client.getChannel(Channel.META_DISCONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> latch.countDown());

        client.handshake();

        Assert.assertTrue(latch.await(2 * maxInactivityPeriod, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientServerInactivity() throws Exception {
        long maxInactivityPeriod = 4000;
        bayeux.addExtension(new ActivityExtension(ActivityExtension.Activity.CLIENT_SERVER, maxInactivityPeriod));

        final BayeuxClient client = newBayeuxClient();
        client.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            if (message.isSuccessful()) {
                client.getChannel(channelName).subscribe((c, m) -> {
                });
            }
        });

        final AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(2));
        client.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> {
            Map<String, Object> advice = message.getAdvice();
            if (advice != null && Message.RECONNECT_NONE_VALUE.equals(advice.get(Message.RECONNECT_FIELD))) {
                latch.get().countDown();
            }
        });
        client.getChannel(Channel.META_DISCONNECT).addListener((ClientSessionChannel.MessageListener)(channel, message) -> latch.get().countDown());

        client.handshake();
        Assert.assertTrue(latch.get().await(2 * maxInactivityPeriod, TimeUnit.MILLISECONDS));
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.DISCONNECTED));

        // Wait for the /meta/connect to return.
        Thread.sleep(1000);

        // Handshake again
        latch.set(new CountDownLatch(2));
        client.handshake();
        TimeUnit.MILLISECONDS.sleep(maxInactivityPeriod * 3 / 4);

        // Do some client activity
        client.getChannel(channelName).publish("");

        // Sleep for a while, we must still be connected
        Assert.assertFalse(latch.get().await(maxInactivityPeriod / 2, TimeUnit.MILLISECONDS));

        // Do some server activity
        bayeux.getChannel(channelName).publish(null, "test", Promise.noop());

        // Sleep for a while, we must still be connected
        Assert.assertFalse(latch.get().await(maxInactivityPeriod * 3 / 4, TimeUnit.MILLISECONDS));

        // Finally we must disconnect
        Assert.assertTrue(latch.get().await(maxInactivityPeriod / 2, TimeUnit.MILLISECONDS));
    }
}
