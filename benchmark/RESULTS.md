# Benchmark record, 2026-11-03

JMH 1.37 on Temurin 26.0.2+10 (`C:\My\data\Jir\home\occupy\bin\java.exe`). One fork, two 1-second warmups, three 1-second measurements, one thread, `-prof gc`. The raw log is `jmh-2026-11-03.log` and the machine-readable result is `jmh-2026-11-03.json`.

Both clients talk to the in-process `TestBroker`, not EMQX. Neither client subscribes. QoS 0 sends 1000 messages and joins the last one. QoS 1 sends 200 messages and joins the last acknowledgement. `@OperationsPerInvocation` makes the score a message rate. Macchiatto inflight is 64. Paho inflight is 1000. Paho is 1.2.5.

Three samples make the 99.9% confidence interval very wide. The table uses the JMH average. The publish samples are listed under it.

## Encode one QoS 0 PUBLISH

| Payload | Macchiatto | Paho |
| --- | ---: | ---: |
| 64 B | 107.3 M ops/s, ~0 B/op | 27.6 M ops/s, 200 B/op |
| 1024 B | 63.8 M ops/s, ~0 B/op | 21.2 M ops/s, 272 B/op |

Macchiatto writes into a reused buffer. Paho's `getHeader()` allocates on every call.

## Publish to the in-process broker

| Case | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 0, 64 B | 4.27 M msg/s, 8.8 B/op | 151 k msg/s, 908 B/op |
| QoS 1, 64 B | 208 k msg/s, 47.6 B/op | 185 k msg/s, 1912 B/op |

QoS 0 samples, messages per second:

- Macchiatto: 4,162,797; 4,214,701; 4,444,106
- Paho: 151,269; 151,872; 149,029

QoS 1 samples, messages per second:

- Macchiatto: 177,488; 231,087; 214,585
- Paho: 167,534; 196,921; 189,694

The allocation figure is for the whole JVM, including the test broker. QoS 1 throughput is limited by the acknowledgement round trip. The allocation gap is the stable part of that comparison.

## Extended run, same day

Same JDK, JMH, fork, and warmup. Raw log `jmh-2026-11-03-extended.log`, JSON `jmh-2026-11-03-extended.json`. QoS 2 was measured again after the first Paho fork aborted; that pair is `jmh-2026-11-03-qos2.log` and `jmh-2026-11-03-qos2.json`. The broker is still the in-process `TestBroker`. Its TCP buffer is 2 MiB so a publish under the client's 1 MiB packet cap fits. Heap figures omit direct buffers.

The large payload is UTF-8 JSON of the form `{"pad":"xxx…"}`. Nothing in the client or the broker parses it.

### QoS 2, 64-byte payload

Both inflight windows are 128. Each invocation publishes 32 messages and waits until every handshake has finished. Paho 1.2.5 throws "too many publications" when a new publish arrives while its inflight counter is still at the cap, so the batch stays under the window.

| | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 2 | 83.2 k msg/s, 208 B/op | 32.6 k msg/s, 3078 B/op |

Samples, messages per second:

- Macchiatto: 96,733; 84,096; 68,807
- Paho: 30,833; 33,175; 33,760

The Macchiatto samples step down across the three seconds, so 83.2 k is a short-run average.

### Large JSON on the socket

Eight messages per invocation. QoS 0 joins the last write. QoS 1 joins the last acknowledgement. Both inflight windows are 64. A 32,768-byte payload already makes the PUBLISH larger than the 32 KiB stage, so Macchiatto encodes that packet into its own direct buffer. That buffer is not in the heap column.

| Payload | Macchiatto QoS 0 | Paho QoS 0 | Macchiatto QoS 1 | Paho QoS 1 |
| --- | ---: | ---: | ---: | ---: |
| 32,768 B | 39.2 k msg/s, 271 B/op | 28.1 k msg/s, 34.1 kB/op | 27.4 k msg/s, 295 B/op | 25.1 k msg/s, 35.1 kB/op |
| 262,144 B | 6.55 k msg/s, 284 B/op | 5.07 k msg/s, 264 kB/op | 6.24 k msg/s, 360 B/op | 4.61 k msg/s, 265 kB/op |
| 1,048,000 B | 1.95 k msg/s, 329 B/op | 1.42 k msg/s, 1.05 MB/op | 1.49 k msg/s, 420 B/op | 1.15 k msg/s, 1.05 MB/op |

QoS 0 samples sit in a narrow band (Macchiatto 32 KiB is 38.3–39.9 k). QoS 1 at 1,048,000 bytes swings more: Macchiatto 1,198 / 1,492 / 1,749 and Paho 987 / 1,149 / 1,456.

Rough QoS 0 payload throughput is about 1.3 GB/s, 1.7 GB/s, and 2.0 GB/s for Macchiatto, and 0.9 GB/s, 1.3 GB/s, and 1.5 GB/s for Paho.

### Encode only, same JSON sizes

This one does not write a socket. Macchiatto copies the payload into a reused buffer. Paho's `getHeader()` builds a 272-byte header and `getPayload()` returns the existing array, so its rate stays near 20.8 M ops/s at every size and does not include a body copy.

| Payload | Macchiatto | Paho |
| --- | ---: | ---: |
| 32,768 B | 1.47 M ops/s, ~0 B/op | 20.9 M ops/s, 272 B/op |
| 262,144 B | 187 k ops/s, ~0 B/op | 20.9 M ops/s, 272 B/op |
| 1,048,000 B | 26.8 k ops/s, ~0 B/op | 20.8 M ops/s, 272 B/op |

Macchiatto's encode rate tracks a memory copy, about 45 GB/s at 32 KiB and 256 KiB, about 28 GB/s at 1,048,000 bytes. Use the socket table above to compare a large publish.

### Other paths, 64-byte payload

| Case | Macchiatto | Paho |
| --- | ---: | ---: |
| QoS 0 delivered to one subscriber | 112 k msg/s, 1453 B/op | 102 k msg/s, 2388 B/op |
| QoS 0 on a 203-byte topic, MQTT 3 | 2.51 M msg/s, 11.2 B/op | 146 k msg/s, 2215 B/op |
| QoS 0 on that topic, MQTT 5 alias | 2.56 M msg/s, 11.5 B/op | — |
| QoS 0 over WebSocket | 2.59 M msg/s, 9.6 B/op | 99.1 k msg/s, 3635 B/op |
| QoS 1, memory | 185 k msg/s, 90 B/op | 178 k msg/s, 2012 B/op |
| QoS 1, disk force before the write | 3.30 k msg/s, 764 B/op | 235 msg/s, 6555 B/op |

The subscriber case counts messages in the callback, so the broker's fan-out copy is inside both numbers. Samples are tight: Macchiatto 110.4–113.6 k, Paho 100.4–103.5 k.

Paho 1.2.5 has no MQTT 5 client. The alias run is Macchiatto MQTT 5 against Macchiatto MQTT 3 on the topic `plant/area/` plus `segment/` repeated to 203 bytes. After warmup the alias is hot. Message rate barely moves, because this QoS 0 loop is not limited by those 200 topic bytes. Paho on the same topic is the 146 k row.

WebSocket is `ws://127.0.0.1/mqtt` on the same broker. Disk rewrites the session image and calls `FileChannel.force(true)` before the batch is written. Those two rows are stable: Macchiatto 3,287–3,309 and Paho 233–237 messages per second. The memory rows in this table use a batch of 32, so they are not the morning QoS 1 numbers.

## EMQX, same day

Broker is Docker `emqx/emqx:5.8.6` on `127.0.0.1:1883` (`macchiatto-emqx`). Other containers were left running. JDK, JMH, and Paho match the morning run. These numbers are broker-bound. Do not put them next to the in-process `TestBroker` rates.

The payload is JSON-shaped UTF-8 (`{"n":0,"pad":"xxx…"}`) of exactly 128 or 1,024 bytes. Nothing parses it.

### Connect, then one QoS 1 publish

Thirty cycles each. A cycle builds a new client, connects, publishes one 128-byte message, and closes. Failures: 0. Raw log `emqx-2026-11-03-probe.log`.

| | p50 | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: |
| Macchiatto | 3.9 ms | 25 ms | 57 ms | 57 ms |
| Paho | 307 ms | 311 ms | 348 ms | 348 ms |

Paho sits near 300 ms on every cycle. Macchiatto's median is a few milliseconds, with one slower tail at 57 ms.

### One message, end to end

One publisher and one subscriber. The clock starts just before `publish` and stops in the subscriber callback. Fifty warmup messages, then 500 samples. No sample timed out. Same 128-byte payload.

| | p50 | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: |
| Macchiatto QoS 0 | 0.40 ms | 0.97 ms | 2.3 ms | 3.3 ms |
| Paho QoS 0 | 0.45 ms | 1.0 ms | 3.5 ms | 11 ms |
| Macchiatto QoS 1 | 0.41 ms | 2.1 ms | 3.1 ms | 6.8 ms |
| Paho QoS 1 | 0.47 ms | 1.1 ms | 2.8 ms | 4.9 ms |

Unloaded round trip on this machine is under a millisecond at the median for both clients. QoS 1 tails overlap.

### Fixed load, one subscriber

The publisher keeps 32 messages in flight. The subscriber uses the same QoS. `ack` is every handshake finished. `received` is what the subscriber counted, including the time spent draining after the last ack. Errors are 0 in every row.

| Case | Sent | Macchiatto ack | Macchiatto received | Paho ack | Paho received |
| --- | ---: | ---: | ---: | ---: | ---: |
| QoS 0 | 30,000 | 162 k msg/s in 185 ms | 30,000 by 349 ms | 104 k msg/s in 289 ms | 30,000 by 453 ms |
| QoS 1 | 15,000 | 23.5 k msg/s in 637 ms | 15,000 by 658 ms | 20.6 k msg/s in 727 ms | 15,000 by 727 ms |
| QoS 2 | 3,000 | 14.4 k msg/s in 208 ms | 3,000 by 208 ms | 12.3 k msg/s in 243 ms | 3,000 by 264 ms |

Every message that was published was delivered. QoS 2 did not show an extra copy in the subscriber count.

### Pipelined publish, no subscriber

JMH 1.37, one fork, two 1-second warmups, three 1-second measurements, `-prof gc`. Log `jmh-emqx-2026-11-03.log`, JSON `jmh-emqx-2026-11-03.json`. QoS 0 sends 200 messages and joins the last write. QoS 1 and 2 send 32 and wait for every handshake. Both inflight windows are 128. Nobody subscribes, so this is the rate at which EMQX accepts the stream, not a fan-out rate.

Three samples make the 99.9% interval very wide on QoS 1 and 2. The table uses the average. The sample span is underneath.

| Payload | QoS | Macchiatto | Paho |
| --- | ---: | ---: | ---: |
| 128 B | 0 | 216 k msg/s, 5.3 B/op | 169 k msg/s, 1017 B/op |
| 1,024 B | 0 | 157 k msg/s, 2.9 B/op | 146 k msg/s, 1921 B/op |
| 128 B | 1 | 30.1 k msg/s, 156 B/op | 22.4 k msg/s, 2301 B/op |
| 1,024 B | 1 | 24.1 k msg/s, 163 B/op | 20.0 k msg/s, 3120 B/op |
| 128 B | 2 | 19.4 k msg/s, 179 B/op | 9.2 k msg/s, 3533 B/op |
| 1,024 B | 2 | 16.3 k msg/s, 184 B/op | 8.3 k msg/s, 4429 B/op |

Sample spans, messages per second:

- Macchiatto QoS 0: 128 B is 216.0–217.0 k; 1,024 B is 155.7–158.1 k
- Paho QoS 0: 128 B is 168.6–170.7 k; 1,024 B is 139.7–152.4 k
- Macchiatto QoS 1: 128 B is 25.0–34.3 k; 1,024 B is 21.8–25.4 k
- Paho QoS 1: 128 B is 20.7–24.9 k; 1,024 B is 17.8–21.2 k
- Macchiatto QoS 2: 128 B is 18.4–21.1 k; 1,024 B is 15.0–17.5 k
- Paho QoS 2: 128 B is 8.6–10.2 k; 1,024 B is 7.3–9.6 k

QoS 0 at 128 bytes is the tight comparison: both clients are in a narrow band, and EMQX, not the library, is the ceiling. QoS 1 moves around inside a few thousand messages per second. The allocation gap is stable across that noise. Heap figures do not include direct buffers.

### Receive, same broker

Two views. The isolation run keeps one Macchiatto publisher and swaps only the subscriber, so the publish implementation is not the difference. The JMH run pairs each library with itself and stops the iteration when that subscriber has the batch. Raw isolation log `emqx-2026-11-03-recv.log`. JMH log `jmh-emqx-recv-2026-11-03.log`, JSON `jmh-emqx-recv-2026-11-03.json`.

Isolation, 128-byte payload, 32 in flight. `callback` is the rate from the first subscriber callback to the last. Errors are 0. Every row received the full count. A wildcard filter `bench/emqx/wild/#` also received 2,000 of 2,000 on both clients.

| QoS | Sent | Macchiatto callback | Paho callback |
| --- | ---: | ---: | ---: |
| 0 | 30,000 | 74.0 k msg/s | 62.7 k msg/s |
| 1 | 15,000 | 21.0 k msg/s | 14.2 k msg/s |
| 2 | 3,000 | 8.5 k msg/s | 7.5 k msg/s |

On QoS 0 the Macchiatto subscriber was still draining for 284 ms after the publisher finished. The Paho subscriber slowed the same publisher (383 ms to finish the writes, 142 ms left to drain). A slow consumer pushes back through EMQX onto the publisher, so the callback span is the receive rate.

JMH delivered rate. The subscriber is subscribed at QoS 2, and each benchmark publishes at the row's QoS, so delivery uses that publish QoS. QoS 0 is 200 messages, then the last write is joined and the loop waits for the subscriber. QoS 1 and 2 are 32 messages, every publisher handshake is joined, then the same wait. Separate topics, so one client's publish is not also delivered to the other. Sample spans are wide; the table is the three-second average.

| Payload | QoS | Macchiatto | Paho |
| --- | ---: | ---: | ---: |
| 128 B | 0 | 49.1 k msg/s, 288 B/op | 15.7 k msg/s, 2434 B/op |
| 1,024 B | 0 | 38.9 k msg/s, 1179 B/op | 10.7 k msg/s, 6955 B/op |
| 128 B | 1 | 14.1 k msg/s, 453 B/op | 11.7 k msg/s, 4290 B/op |
| 1,024 B | 1 | 13.2 k msg/s, 1374 B/op | 9.0 k msg/s, 8634 B/op |
| 128 B | 2 | 9.7 k msg/s, 555 B/op | 8.2 k msg/s, 6294 B/op |
| 1,024 B | 2 | 7.2 k msg/s, 1467 B/op | 5.8 k msg/s, 10872 B/op |

Sample spans, messages per second:

- Macchiatto QoS 0: 128 B is 45.0–51.3 k; 1,024 B is 37.1–40.7 k
- Paho QoS 0: 128 B is 12.1–17.5 k; 1,024 B is 9.5–12.3 k
- Macchiatto QoS 1: 128 B is 12.2–15.7 k; 1,024 B is 12.0–14.5 k
- Paho QoS 1: 128 B is 10.8–12.7 k; 1,024 B is 7.9–10.3 k
- Macchiatto QoS 2: 128 B is 8.9–10.6 k; 1,024 B is 6.4–7.6 k
- Paho QoS 2: 128 B is 7.6–8.7 k; 1,024 B is 5.3–6.6 k

The 1,024-byte rows allocate about the payload plus a little on Macchiatto (1,179 B/op at QoS 0). Paho's receive path allocates several copies of that payload. Heap figures do not include direct buffers. This table is not the no-subscriber publish table above: the clock runs until the callback has counted the batch.
