# 设计

这组页面顺着一条消息往下讲。有的地方把 Eclipse Paho 1.2.5 的 mqttv3 客户端和我们的代码放在一起，因为两边在同一件事上的做法差得很清楚。更多的地方只讲 Macchiatto 自己的路径：队列满了怎么办、标识符怎么发、字节怎么拼进同一块缓冲。那些设计本身就成立，不必每一步都找一个对照。

`MqttEngine` 里的代码是内部实现，不是公开 API。

顺序是：

- 一条 I/O 线程怎么拨号、怎么等、什么时候被叫醒
- 失败之后等多久，抖动怎么把一群客户端错开
- 出站队列满了是拒绝，还是丢掉最旧的一条
- 字节怎么拼进 32 KiB 的舞台，什么时候算写完
- 在途窗口怎么发报文标识符，配额怎么算
- CONNACK 之后订阅和未确认的消息怎么再送
- 回调为什么在另一条线程上，慢了会怎样

空载延迟没有稳定优势。这些选择里量得出来的，是堆上少分配。数字在仓库的 `benchmark/RESULTS.md`。

# 一条 I/O 线程

Broker 闪断一下，客户端要自己再拨。看起来只是「等一会儿再连」。真正要定下来的是：等待由谁来计时，套接字又在谁手里。

Paho 的自动重连默认是关的。打开之后，断线会新建一只 `java.util.Timer`，到点由定时器线程去调用 `connect()`。第一次等 1 秒，失败就加倍，直到 128 秒。这段等待是静态字段，同一个进程里的客户端共用它。心跳另有一只 `MQTT Ping` 定时器。发送、接收、回调又各是一条线程。定时器一旦抛出未检查异常，这只 `Timer` 的线程就停了，后头那一轮也不会再跑。`cancel()` 还会把已经排上、还没执行的任务丢掉。

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

Macchiatto 不另请一位计时的人。会话只有两条平台线程：`mqtt-macchiatto-io` 握着套接字，`mqtt-macchiatto-cb` 只跑消息回调。拨号、读写、算下一次等多久，都在 I/O 这一条上。套接字是非阻塞的，`TCP_NODELAY` 打开，线程大部分时间停在 `Selector.select` 里。超时取三样里最近的一个：CONNACK 还剩多久、下一次心跳、在途报文该不该重试。

`session.reconnect()` 不拉起第三条线程。它把 `reconnectNow` 置上，解开可能睡在 `park` 里的 I/O 线程，如果它正堵在 `select` 里再叫醒它。这是你要求马上再拨，不算一次失败，尝试次数不加。排队里的 `publish` 不碰这个标志，所以下一条消息不会把退避提前打断。

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

调用方入队时，只有 I/O 线程正在 `select` 里才 `wakeup`。它正在组包或写，下一次循环自己会看见队列。

```java
private void wakeIo() {
    if (ioWaiting) {
        selector.wakeup();
    }
}
```

套接字因此只有一个主人，退避按原来算好的时间走完，也没有第二只定时器会自己停掉。代价在同一条线程上：`connAck`、`disconnected`、`reconnecting` 都是当场调用的，必须马上返回。`close()` 同样解开并叫醒这条线程，它写出 DISCONNECT 再退。

失败了先看原因，再决定等不等。客户端标识符被拒绝、账号错误、未授权，就停。协议不被接受、并且配置了一次回退，就换协议再拨，只换一次。多个地址按顺序试，试完回到第一个，然后才睡觉。

# 退避

一批设备同时掉线，如果按同一个表等待，会在同一毫秒一起拨回去。Broker 刚起来就被这一下挤满。

每个会话自己数自己的尝试次数。可重试的失败先加一，再取一个带抖动的延迟。延迟小于 0，会话进入 `FAILED`，不再拨。账号错误、标识符被拒绝、未授权不走这里。`session.reconnect()` 引起的那一次也不加次数，马上再拨，因为那是你要它现在就试。

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

`ReconnectPolicy.standard()` 从 200 毫秒起，每次加倍，上限 10 秒，次数不限。`delayMillis(attempt)` 是确定的：初始值乘上 `multiplier` 的 `attempt - 1` 次方，到顶之后一直是上限。测试断言用这个数。真正睡觉的是 `jitteredDelayMillis`。它留下基准的一半，再在剩下的一半里取一个随机份额。结果落在一半到基准之间，不会比基准更长。基准不超过 1 毫秒时不再抖动。

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

同时掉线的那一批不会挤在同一毫秒，单次等待也不会被随机数拉得比策略更长。默认次数不限，Broker 没起来时 `connect()` 会一直等到第一条 CONNACK。要失败一次就停，用 `ReconnectPolicy.none()`，它的 `maxAttempts` 是 0。也可以把次数设成一个有限的数。

睡觉之前会把会话映像刷到存储。`reconnecting` 在这一跳定下来时调用，带来当时的 `MqttLink`：状态、第几次、哪个地址、还要等多久。这个钩子在 I/O 线程上，必须马上返回。

Paho 打开自动重连之后，等待从 1 秒精确加倍到 128 秒，中间没有这一段随机份额，所以同一批客户端仍会排着队撞上来。抖动是为了把这一下错开，不是为了模仿那段等待。

# 出站队列

`publish`、`emit`、`subscribe` 都不碰套接字。调用线程从 `WorkPool` 拿一个已经准备好的 `Work`，填上种类、主题、载荷和 Future，放进 `ArrayBlockingQueue`。容量是 `maxQueued()` 再加 1。池在启动时按 `maxQueued()` 把 `Work` 放好，队列没满就不必为这一条再 `new`。放进去就叫醒 I/O 线程，调用返回。

断线期间，没发出去的消息就站在这一条队列里。重连之后，I/O 线程继续从同一条队列往外拿。没有第二块名叫「离线缓冲」的内存，也没有为了保险再把热路径上的每一条写进文件。

队列满了怎么办，启动时就定下来：

- `REJECT` 留下队列里已有的，新的这一条失败。这是默认。
- `DROP_NEWEST` 同样留下队列，新的这一条失败，异常里写明是因为队列满了。
- `DROP_OLDEST` 从队头找一条还没发出去的 PUBLISH，完成它的 Future，把位置让给新的一条。找不到这样的 PUBLISH，新的一条仍然失败。

已经拿到报文标识符、躺在在途槽里的消息不在这个队列里。三种策略都不会碰它们。那些是已经答应要送达的。

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

`emit` 没有 Future，队列满了就抛 `MqttSessionException`。`publish` 把同一个异常放进返回的 Future，调用本身不抛。存储打开时，断线期间入队的 QoS 1 和 2 会记一笔，记的是这一条，不是把整个队列再拷一份。

队列里的主题和载荷仍是你交进来的那两份数组，写出之前不能改。

# 组包

一条 64 字节的温度，如果每发一次都复制载荷、再分配一个等待对象，每秒几十万次的时候，堆就主要花在包装上。

Paho 收下载荷时会复制一份。`setPayload` 里就是一次 `clone`。后面编报文还要再写一块缓冲。QoS 0 也要一个 token，调用才知道这次结束了。一次发布，载荷在内存里至少出现两份。

```java
public void setPayload(byte[] payload) {
    checkMutable();
    if (payload == null) {
        throw new NullPointerException();
    }
    this.payload = payload.clone();
}
```

Macchiatto 把你的数组直接放进 `Work`，不另拷一份。I/O 线程在 `fill()` 里把好几条小包拼进同一块 direct buffer。这块舞台在构造时分配一次，默认 32 KiB。CONNECT 比它大，就按 CONNECT 的尺寸来。它不进堆分配的统计。每轮 `clear` 之后接着写。

一轮里先写赶时间的：还没发出的 AUTH、到点的 PINGREQ、入站应答、CONNACK 之后还没发完的订阅、还没重发的在途 QoS 1 和 2。然后才从用户队列取。放得进剩余空间的就接在后面，一次写出去。放不下的留到下一块。单条比舞台还大，就写进单独留着的那块 direct buffer，只装这一条，下一条大包接着用。这块缓冲跟着最大的那条长大，字节还没写完不会被清掉。

读是另一块堆上的缓冲，一开始 64 KiB，报文更大时按两倍长大，上限是配置的最大包长再加报文头。入站字节要解析，所以留在堆上。主题先在一块按会话留下的字节缓冲里校验，再拷成 `String`。载荷再拷一份交给监听器，这份数组不会被下一条报文拿去复用。出站的热路径留在 direct buffer 上，少一次从堆拷到本地内存。

QoS 0 的 `publish` 把 Future 挂在这一批上。整块写进套接字，或者写进 TLS、WebSocket 的待发缓冲之后，这些 Future 一起完成。完成的意思是字节已经交给传输。QoS 0 没有 PUBACK。

`emit` 把这条路再收短一截：QoS 0、不保留、也不建 Future。`Work` 用完放回池。池热了以后，`emit(byte[], byte[])` 不为这条消息再分配堆对象。`emit(String, byte[])` 仍要把主题编成 UTF-8，这一次编码有分配。

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

对着本机 EMQX、无人订阅的 QoS 0，Macchiatto 是 5.3 B/op，Paho 是 1017 B/op。那组吞吐受 Broker 限制。进程内、64 字节、两边都不订阅时，是 8.8 B/op 对 908 B/op。约 1 MB 的 QoS 0 大包，Macchiatto 堆上停在 329 B/op，Paho 按载荷体积分配，约 1.05 MB/op。空载延迟没有稳定优势，两边对着 EMQX 的 p50 都在 1 毫秒以内。

数组不复制，所以写出之前它还是你的，不能改。进程马上要退出时用会完成的 `publish`。`emit` 没有完成信号，`close()` 不会等它。

# 在途窗口

QoS 1 和 2 要一个报文标识符，后面的 PUBACK、PUBREC、PUBREL、PUBCOMP 才知道自己在回答哪一条。标识符用尽的时候，新的可靠消息只能等。

标识符从 1 排到 `maxInflight()`，堆在栈 `freeIds` 里。`alloc()` 弹出一个，握手结束时 `release()` 推回去。槽数组用 `packetId - 1` 当下标，对上一条在途消息是一次数组访问。只有一条 I/O 线程会碰这些槽，所以不必再拿一把锁，让发送线程和接收线程互相等。

发出去时占一个槽，记下主题、载荷、Future 和状态。QoS 1 等 PUBACK，QoS 2 等 PUBREC。发送配额同时减一。配额的上限是本端窗口和 Broker 在 CONNACK 里给的 Receive Maximum 里较小的那个。MQTT 3 没有 Receive Maximum，配额就是本端窗口。这样不会一次发出去超过对方愿意接着收的条数。

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

窗口满了，或者配额用完了，队列里下一条 QoS 1 或 2 先站着。QoS 0 不占标识符，也不占配额，仍然可以编进当前这块。SUBSCRIBE 和 UNSUBSCRIBE 各占一个标识符，用来对上 SUBACK 和 UNSUBACK，用尽时同样等一等。

字节写进传输之后，槽标成已经在线上，下一次重试是现在加上 `retryMillis`。到点还没有应答，同一条 I/O 线程把 DUP 置上再送一次。应答到了才还标识符，Future 在那时完成。存储打开时，占槽的同时把这条出站消息记下来，进程重启后还能按同一个标识符再送。

QoS 0 的 Future 不进槽。它在这一块写完时就完成，那一步写在组包那一页。

# CONNACK 之后

连接又通了。Broker 可能还记得这个客户端，也可能把它忘了。客户端这边记着的订阅和没确认的消息，要在这一刻决定哪些再送一遍。

Paho 收到成功的 CONNACK，会按持久化把在途消息恢复出来再送，但不会把订阅再发一遍。监听器还在这个客户端对象里。Broker 一旦把会话丢了，过滤器就没了，消息不再进来。使用方得在 `connectComplete` 里自己订回去，而且必须用当初同一个监听器。

```java
public void connectComplete(boolean reconnect, String serverURI) {
    if (reconnect) {
        client.subscribe("sensors/#", 1, listener);
    }
}
```

Macchiatto 在每一条成功的 CONNACK 上把订阅游标和重发游标拉回 0，尝试次数也归零。下一次真的断线，退避从策略的初始延迟重新算，不接着上次已经封顶的 10 秒。

过滤器会再发一遍，哪怕 Broker 说会话还在。会话还在时会多一次 SUBSCRIBE。这是用一次重复订阅，换订阅不会悄悄消失。已经取消的订阅 `live` 是假的，游标扫过时跳过。占着标识符的槽全部标成需要重发，`onWire` 清掉，下一次组包按 DUP 再送。还在等 PUBACK 或 PUBREC 的槽继续占着发送配额，重发不会把在途条数撑过 Broker 的 Receive Maximum。

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

`sessionPresent` 为假，说明 Broker 已经不记得这个会话。还没完成的入站 QoS 2 连同存储里的入站记录一起清掉，不再续一条对方已经忘掉的 PUBREL。会话还在时，这些记录留着。同一条 QoS 2 再次出现，对得上标识符，就不再交给监听器一次。

组包的时候，再订阅和重发排在用户队列前面。窗口里还有旧消息没送完，新的 QoS 1 和 2 就等标识符。QoS 0 仍然可以填进当前这块剩下的空间。重连回调里不用再写一次订阅。

# 回调线程

消息到了，监听器如果去做一件慢事，套接字还要不要继续读，是这里要分开的两件事。

I/O 线程读到 PUBLISH，按还活着的订阅做主题匹配，从 `DeliveryPool` 拿一个 `Delivery`，放进有界队列 `deliveries`。这条队列至少 256、至多 4096，并且不超过 `maxQueued()`。`mqtt-macchiatto-cb` 在队列上等待，按到达顺序调用监听器。MQTT 5 走 `onMessage(MqttDelivery)`，带上属性和订阅标识符。否则是主题、载荷、QoS、保留和重复。

监听器抛出的异常交给 `listenerThrew`，回调线程继续下一条。一条业务异常不会把后面的消息都停掉。`Delivery` 用完放回池。

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

`deliveries` 放不下时，投递改堆进 `overflow`。只要这里不空，I/O 循环就先不读套接字。接收窗口会慢慢填满，对端停发。每次循环开头先把积压挪回 `deliveries`，回调追上之后读才继续。写和心跳仍排在「要不要读」之前，所以回调慢，停的是读，不是这条连接上的心跳。

这也是回调里不能阻塞着等待同一个会话的原因。在回调里等一次 `publish` 的 Future，或者再 `subscribe` 直到 SUBACK，都会占着投递队列，读跟着停。要做的事如果会等这条连接，离开回调再做。主题和载荷可以留着，不必为了躲开下一条报文再拷一次。

`connAck`、`disconnected`、`reconnecting` 不走回调线程。它们在 I/O 线程上当场调用，慢了会挡住拨号。监听器拿到的主题和载荷是这次解析拷出来的，回调返回之后仍然有效。解析缓冲和校验主题的临时字节会留下复用，不会改到你手里的数组。

Paho 把读、写、回调分成三条线程，回调队列满了就让接收线程在 `spaceAvailable` 上等待。我们把读和写留在同一条 I/O 线程上，只把呼叫用户分出去。慢下来时，停读、继续心跳，靠的是这一次循环里的顺序，不是再加一条线程。
