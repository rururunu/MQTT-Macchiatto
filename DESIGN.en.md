# Design

These pages follow one message through the session. A few of them put the Eclipse Paho 1.2.5 mqttv3 client next to our code, because the two sides do that one job in clearly different ways. Most of the path is just Macchiatto: what happens when the queue is full, how a packet id is handed out, how several small packets share one buffer. Those choices stand on their own. They do not each need a counterpart.

The code in `MqttEngine` is internal. It is not a public API.

The order is:

- How the I/O thread dials, waits, and wakes
- How long a failure waits, and how jitter spreads a fleet apart
- Whether a full outbound queue rejects the new message or drops the oldest
- How bytes are packed into the 32 KiB stage, and when a write counts as done
- How the in-flight window hands out packet ids, and how the send quota is counted
- How subscriptions and unacked messages are sent again after CONNACK
- Why callbacks run on a second thread, and what a slow callback does

Unloaded latency has no stable advantage. What these choices show up as, in the measurements, is less allocation on the heap. The numbers are in `benchmark/RESULTS.md` in the repository.

# One I/O thread

The broker blinks, and the client has to dial again. The wait itself is the small part. What has to be settled is who keeps the time, and who is holding the socket.

Paho automatic reconnect is off until you turn it on. After a drop it creates a `java.util.Timer`, and that thread calls `connect()` when the timer fires. The first wait is 1 second. Each failure doubles it, up to 128 seconds. The delay is a static field, so every client in the process shares it. A second timer, `MQTT Ping`, handles the keepalive. Sending, receiving, and callbacks are three more threads. If a timer task throws an unchecked exception, that `Timer` stops, and a later round does not run. `cancel()` also drops a task that was scheduled and has not started.

```java
private void startReconnectCycle() {
    reconnectTimer = new Timer("MQTT Reconnect: " + clientId);
    reconnectTimer.schedule(new ReconnectTask(), reconnectDelay);
}

private class ReconnectTask extends TimerTask {
    public void run() {
        attemptReconnect();
    }
}
```

Macchiatto does not add a timekeeper. A session has two platform threads. `mqtt-macchiatto-io` holds the socket. `mqtt-macchiatto-cb` only runs message callbacks. Dialing, reading, writing, and deciding how long to wait all stay on the I/O thread. The socket is non-blocking, `TCP_NODELAY` is on, and the thread spends most of its time in `Selector.select`. The timeout is the soonest of three things: time left for CONNACK, the next keepalive, and the next in-flight retry.

`session.reconnect()` does not start a third thread. It sets `reconnectNow`, unparks the I/O thread if it is in `park`, and wakes it if it is inside `select`. You asked for a dial now, so this is not counted as a failure. A queued `publish` does not touch the flag, so the next message does not cut the backoff short.

```java
public void reconnect() {
    reconnectNow = true;
    LockSupport.unpark(ioThread);
    selector.wakeup();
}

private void waitForRetry(long delayMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
    while (!closing.get()) {
        if (reconnectNow) {
            reconnectNow = false;
            return;
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            return;
        }
        LockSupport.parkNanos(left);
    }
}
```

A caller wakes the selector only when the I/O thread is inside `select`. While it is assembling or writing, the next loop sees the queue on its own.

```java
private void wakeIo() {
    if (ioWaiting) {
        selector.wakeup();
    }
}
```

The socket has one owner. The wait finishes on the clock that was already chosen, and there is no second timer that can stop itself. The cost sits on that same thread. `connAck`, `disconnected`, and `reconnecting` run inline and have to return immediately. `close()` unparks and wakes the thread, which writes DISCONNECT and then exits.

A failure is read before anyone waits. A rejected client id, bad credentials, or a not-authorized CONNACK stops the session. If the protocol is unacceptable and a fallback was configured, the session switches protocol and dials again, once. Several endpoints are tried in order. After the last one, the cursor returns to the first, and only then does the thread sleep.

# Backoff

A fleet that drops together, and then waits by the same table, dials back on the same millisecond. The broker is crowded the instant it returns.

Each session counts its own attempts. A retryable failure increments the count, then takes a jittered delay. A delay below zero moves the session to `FAILED`, and it stops dialing. Bad credentials, a rejected client id, and not-authorized never take this path. The hop from `session.reconnect()` does not increment the count either. That dial happens at once, because you asked for it.

```java
failureCount++;
long delay = retry
        ? reconnect.jitteredDelayMillis(failureCount, ThreadLocalRandom.current()::nextDouble)
        : -1L;
if (delay < 0) {
    giveUp(failure);
    break;
}
```

`ReconnectPolicy.standard()` starts at 200 milliseconds, doubles, caps at 10 seconds, and does not limit the number of attempts. `delayMillis(attempt)` stays exact: the initial delay times `multiplier` to the power of `attempt - 1`, and then the cap. Tests assert that value. The thread sleeps on `jitteredDelayMillis`. Half of the base is kept, and a random share of the rest is added. The result lands between half and the base, and it is never longer than the base. A base of 1 millisecond or less is left alone.

```java
public long jitteredDelayMillis(int attempt, DoubleSupplier unitRandom) {
    long base = delayMillis(attempt);
    if (base <= 1) {
        return base;
    }
    long half = base / 2;
    return half + (long) (unitRandom.getAsDouble() * (base - half));
}
```

A fleet that dropped together does not stampede back on one millisecond, and a single wait is not stretched past the policy. The default attempt count is unlimited, so `connect()` waits until the first CONNACK when the broker is down. `ReconnectPolicy.none()` stops after the first failure. Its `maxAttempts` is 0. A finite `maxAttempts` stops after that many tries.

The session image is flushed before the sleep. `reconnecting` runs when the hop is chosen, with the `MqttLink` of that moment: state, attempt, endpoint, and how long the wait is. The hook is on the I/O thread and has to return immediately.

Once Paho automatic reconnect is on, the wait doubles exactly from 1 second to 128 seconds, with no random share, so that same fleet still arrives together. The jitter is there to spread that arrival out. It is not a copy of that wait.

# The outbound queue

`publish`, `emit`, and `subscribe` do not touch the socket. The caller takes a `Work` the pool already allocated, fills in the kind, topic, payload, and future, and offers it to an `ArrayBlockingQueue`. The capacity is `maxQueued()` plus one. The pool is filled with `maxQueued()` objects at startup, so a message that fits does not `new` another. A successful offer wakes the I/O thread, and the call returns.

During an outage, messages that have not been written stay in this one queue. After reconnect, the I/O thread keeps taking from it. There is no second block of memory called an offline buffer, and the hot path does not write every message to a file just in case.

What happens when the queue is full is fixed when the session starts:

- `REJECT` keeps what is already queued and fails the new message. This is the default.
- `DROP_NEWEST` also keeps the queue and fails the new message. The exception says the queue was full.
- `DROP_OLDEST` finds a PUBLISH that has not been written, completes its future, and gives the space to the new message. If no such PUBLISH exists, the new message still fails.

A message that already has a packet id sits in an in-flight slot, not in this queue. None of the three policies touch it. Those messages were already accepted for delivery.

```java
private boolean offer(Work work) {
    if (enqueue(work)) {
        return true;
    }
    if (config.overflow() == MqttOverflow.DROP_OLDEST && dropOldestPublish() && enqueue(work)) {
        return true;
    }
    pool.recycle(work);
    return false;
}
```

`emit` has no future. A full queue throws `MqttSessionException`. `publish` completes the returned future with that exception and does not throw from the call. When persistence is on, a QoS 1 or 2 message accepted while the socket is down is recorded. The record is that one message, not a second copy of the whole queue.

The topic and payload arrays in the queue are still the ones you passed in. Do not mutate them until they have been written.

# Assembling packets

A 64-byte temperature reading, copied on every send and wrapped in a new wait object, spends the heap on the wrapping once you send hundreds of thousands a second. The temperature itself is the small part.

Paho copies the payload when it accepts it. `setPayload` is one `clone`. The message is then encoded again into another buffer. QoS 0 still allocates a token, so the call can finish. One publish, and the payload exists at least twice.

```java
public void setPayload(byte[] payload) {
    checkMutable();
    if (payload == null) {
        throw new NullPointerException();
    }
    this.payload = payload.clone();
}
```

Macchiatto puts your arrays straight into the `Work` and does not copy them. The I/O thread, in `fill()`, packs several small packets into one direct buffer. That stage is allocated once, in the constructor, at 32 KiB. If CONNECT is larger, the buffer is sized for CONNECT. It stays outside the heap-allocation count. Each batch `clear`s it and writes again.

A batch writes what is in a hurry first: an AUTH still waiting, a PINGREQ that is due, inbound acknowledgements, subscriptions not yet resent after CONNACK, and in-flight QoS 1 and 2 that still need a resend. Then it takes from the user queue. A packet that fits is appended, and the buffer is written once. A packet that does not fit waits for the next buffer. A packet larger than the stage is written into one direct buffer kept for the next large packet. That buffer grows with the largest packet, and it is not cleared while those bytes are still going out.

Reads use a different buffer, on the heap. It starts at 64 KiB and doubles when a packet needs more room, up to the configured maximum plus a header. Inbound bytes are parsed, so they stay on the heap. The topic is checked in a scratch buffer kept for the session, then copied into a `String`. The payload is copied again for the listener, and that array is not reused for the next packet. The outbound hot path stays on the direct stage and skips a copy from the heap into native memory.

A QoS 0 `publish` parks its future on the batch. After the buffer has been written to the socket, or into the TLS or WebSocket pending buffer, those futures complete together. Completion means the bytes were handed to the transport. QoS 0 has no PUBACK.

`emit` shortens that path again. It is QoS 0, not retained, and it builds no future. The `Work` goes back to the pool. Once the pool is warm, `emit(byte[], byte[])` allocates no further heap object for that message. `emit(String, byte[])` still encodes the topic as UTF-8, and that encoding does allocate.

```java
public void emit(byte[] topicUtf8, byte[] payload) {
    Work work = pool.take();
    work.kind = Work.PUBLISH;
    work.topic = topicUtf8;
    work.payload = payload;
    work.qos = 0;
    work.retain = false;
    if (!offer(work)) {
        throw new MqttSessionException(queueFullMessage());
    }
}
```

Against local EMQX, a QoS 0 publish with nobody subscribed is 5.3 B/op for Macchiatto and 1017 B/op for Paho. That set is broker-bound. In-process, 64 bytes, neither side subscribed, the heap is 8.8 B/op against 908 B/op. On a QoS 0 payload of about 1 MB, Macchiatto stays at 329 B/op. Paho allocates with the payload, about 1.05 MB/op. Unloaded latency has no stable advantage. Against EMQX, p50 is under a millisecond for both.

The arrays are not copied, so until the write has gone out they are still yours. Do not mutate them. Use `publish` when the process is about to exit. `emit` has no completion signal, and `close()` does not wait for it.

# The in-flight window

QoS 1 and 2 need a packet id, so that PUBACK, PUBREC, PUBREL, and PUBCOMP know which publish they are answering. When the ids run out, a new reliable message has to wait.

Ids run from 1 through `maxInflight()` and sit in a stack, `freeIds`. `alloc()` pops one. `release()` pushes it back when the handshake finishes. The slot array is indexed by `packetId - 1`, so matching an acknowledgement to its publish is one array access. Only the I/O thread touches these slots, so there is no lock for a sender thread and a receiver thread to wait on.

Taking a slot records the topic, the payload, the future, and the state. QoS 1 waits for PUBACK. QoS 2 waits for PUBREC. The send quota drops by one. The cap is the smaller of the local window and the broker's Receive Maximum from CONNACK. MQTT 3 has no Receive Maximum, so the quota is the local window. The session does not put more messages on the wire than the peer agreed to hold.

```java
int id = alloc();
sendQuota--;
Slot slot = slots[id - 1];
slot.packetId = id;
slot.state = work.qos == 1 ? WAIT_PUBACK : WAIT_PUBREC;
slot.topic = work.topic;
slot.payload = work.payload;
slot.future = work.future;
store.rememberOutbound(id, slot.state, slot.qos, slot.retain, slot.topic, slot.payload, slot.properties);
```

When the window is full, or the quota is used up, the next QoS 1 or 2 stays in the queue. QoS 0 takes neither an id nor quota, so it can still be encoded into the current buffer. SUBSCRIBE and UNSUBSCRIBE each take an id, matched later to SUBACK and UNSUBACK. They wait too when no id is free.

After the bytes are written, the slot is marked on the wire. The next retry is now plus `retryMillis`. If no acknowledgement has arrived, the same I/O thread sends the packet again with DUP set. The id comes back, and the future completes, when the acknowledgement arrives. With persistence on, the outbound message is recorded as the slot is taken, so a restarted process can send it again under the same id.

A QoS 0 future does not enter a slot. It completes when the buffer finishes writing. That step is on the assembling-packets page.

# After CONNACK

The connection is back. The broker may still remember this client, or it may have forgotten it. Subscriptions and unacked messages kept on the client have to be sent again, or dropped, at this moment.

On a successful CONNACK, Paho restores in-flight messages from persistence and sends them again. It does not send the subscriptions again. The listener is still on the client object. If the broker has dropped the session, the filter is gone and messages simply stop arriving. The caller subscribes again inside `connectComplete`, with the same listener.

```java
public void connectComplete(boolean reconnect, String serverURI) {
    if (reconnect) {
        client.subscribe("sensors/#", 1, listener);
    }
}
```

Macchiatto resets the subscription cursor, the resend cursor, and the attempt count on every successful CONNACK. The next real drop starts backoff at the policy's initial delay, rather than continuing from a wait that had already reached the 10 second cap.

Filters are sent again even when the broker says the session is still present. A present session costs one extra SUBSCRIBE. That trade is a repeated subscribe, so a subscription does not disappear quietly. A subscription you already cancelled has `live` set false. The cursor skips it. Every slot that holds a packet id is marked for resend, and `onWire` is cleared. The next batch encodes those packets again with DUP set. Slots still waiting for PUBACK or PUBREC keep their place in the send quota, so a resend cannot push the in-flight count past the broker's Receive Maximum.

```java
resubCursor = 0;
resendCursor = 0;
if (!sessionPresent && !inboundQos2.isEmpty()) {
    inboundQos2.clear();
    store.clearInbound();
}
for (Slot slot : slots) {
    if (slot.packetId == 0) {
        continue;
    }
    slot.needResend = true;
    slot.onWire = false;
}
```

When `sessionPresent` is false, the broker has forgotten the session. Unfinished inbound QoS 2 is cleared, including the inbound records in the store, and the session does not continue a PUBREL the peer has forgotten. When the session is present, those records stay. A QoS 2 publish that shows up again with a known id is not handed to the listener a second time.

While a batch is assembled, resubscribe and resend run before the user queue. A new QoS 1 or 2 waits for an id while old messages in the window are still going out. QoS 0 can still fill leftover space in the current buffer. The reconnect callback does not subscribe again.

# The callback thread

A message has arrived, and the listener goes off to do something slow. Reading the socket and calling the user are two different jobs, and this page keeps them apart.

The I/O thread matches a PUBLISH against each live subscription, takes a `Delivery` from `DeliveryPool`, and offers it to the bounded queue `deliveries`. That queue holds at least 256 and at most 4096, and it is not larger than `maxQueued()`. `mqtt-macchiatto-cb` waits on the queue and calls listeners in arrival order. MQTT 5 calls `onMessage(MqttDelivery)`, with properties and subscription identifiers. Otherwise the listener receives the topic, payload, QoS, retain flag, and duplicate flag.

An exception from a listener is reported through `listenerThrew`. The callback thread continues with the next delivery, so one business exception does not stop the messages behind it. The `Delivery` goes back to the pool.

```java
delivery = deliveries.poll(200, TimeUnit.MILLISECONDS);
try {
    if (delivery.rich != null) {
        delivery.listener.onMessage(delivery.rich);
    } else {
        delivery.listener.onMessage(
                delivery.topic, delivery.payload, delivery.qos, delivery.retained, delivery.duplicate);
    }
} catch (Throwable failure) {
    safeEvent(() -> events.listenerThrew(failure));
} finally {
    deliveryPool.recycle(delivery);
}
```

When `deliveries` cannot take another item, the delivery is appended to `overflow`. While that list is not empty, the I/O loop does not read the socket. The receive window fills, and the peer stops sending. At the start of each loop the backlog is moved back into `deliveries` if there is room. Reads resume once the callback thread has caught up. Writes and the keepalive are ordered ahead of the decision to read. A slow callback pauses reads. It does not pause the keepalive on this connection.

That is why a callback must not block waiting on the same session. Waiting on a `publish` future, or calling `subscribe` until SUBACK, holds a place in the delivery queue and stalls reads. If the work has to wait on this connection, do it after the callback returns. The topic and payload can be kept. They do not have to be copied again to survive the next packet.

`connAck`, `disconnected`, and `reconnecting` do not use the callback thread. They run inline on the I/O thread, and a slow one delays the next dial. The topic and payload a listener receives are copies made for that delivery, and they stay valid after the callback returns. The parse buffer and the scratch used to check the topic are reused. They are not the arrays the listener holds.

Paho splits reading, writing, and the callback across three threads. When its callback queue is full, the receiver waits on `spaceAvailable`. We leave reading and writing on the one I/O thread, and only move the call to the user aside. When the listener falls behind, reads pause and the keepalive continues, because of the order inside that one loop, not because another thread was added.
