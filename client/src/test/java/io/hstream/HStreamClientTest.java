package io.hstream;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HStreamClientTest {

  private static final Logger logger = LoggerFactory.getLogger(HStreamClientTest.class);
  private static final String serviceUrl = "localhost:6570";
  private static final String TEST_STREAM_PREFIX = "TEST_STREAM_";
  private static final String TEST_SUBSCRIPTION_PREFIX = "TEST_SUB_";
  private HStreamClient client;
  private String testStreamName;
  private String testSubscriptionId;

  @BeforeEach
  public void setUp() {
    client = HStreamClient.builder().serviceUrl(serviceUrl).build();
    String suffix = RandomStringUtils.randomAlphanumeric(10);
    testStreamName = TEST_STREAM_PREFIX + suffix;
    testSubscriptionId = TEST_SUBSCRIPTION_PREFIX + suffix;
    client.createStream(testStreamName);
    Subscription subscription =
        new Subscription(
            testSubscriptionId,
            testStreamName,
            new SubscriptionOffset(SubscriptionOffset.SpecialOffset.LATEST),
            10);
    client.createSubscription(subscription);
  }

  @AfterEach
  public void cleanUp() {
    TestUtils.deleteAllSubscriptions(client);
    client.deleteStream(testStreamName);
  }

  // @Test
  // public void testReceiverException() throws Exception {
  //   Consumer consumer =
  //       client
  //           .newConsumer()
  //           .subscription(TEST_SUBSCRIPTION)
  //           .rawRecordReceiver(
  //               (receivedRawRecord, responder) -> {
  //                 throw new RuntimeException("receiver exception");
  //               })
  //           .build();
  //   Service.Listener listener =
  //       new Service.Listener() {
  //         @Override
  //         public void starting() {
  //           super.starting();
  //         }

  //         @Override
  //         public void running() {
  //           super.running();
  //         }

  //         @Override
  //         public void stopping(Service.State from) {
  //           super.stopping(from);
  //         }

  //         @Override
  //         public void terminated(Service.State from) {
  //           super.terminated(from);
  //         }

  //         @Override
  //         public void failed(Service.State from, Throwable failure) {
  //           logger.error("consumer failed from state {} ", from, failure);
  //           super.failed(from, failure);
  //           // consumer.stopAsync();
  //         }
  //       };

  //   consumer.addListener(listener, Executors.newSingleThreadExecutor());
  //   consumer.startAsync().awaitRunning();

  //   Producer producer = client.newProducer().stream(TEST_STREAM).build();
  //   Random random = new Random();
  //   byte[] rawRecord = new byte[100];
  //   for (int i = 0; i < 5; ++i) {
  //     Thread.sleep(5000);
  //     random.nextBytes(rawRecord);
  //     producer.write(rawRecord);
  //   }

  //   consumer.awaitTerminated(1, TimeUnit.SECONDS);
  //   consumer.stopAsync().awaitTerminated();
  // }

  @Test
  @Order(1)
  public void testWriteRawRecord() throws Exception {
    CompletableFuture<RecordId> recordIdFuture = new CompletableFuture<>();
    Consumer consumer =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .rawRecordReceiver(
                (receivedRawRecord, responder) -> {
                  recordIdFuture.thenAccept(
                      recordId ->
                          Assertions.assertEquals(recordId, receivedRawRecord.getRecordId()));
                  responder.ack();
                })
            .build();
    consumer.startAsync().awaitRunning();

    Producer producer = client.newProducer().stream(testStreamName).build();
    Random random = new Random();
    byte[] rawRecord = new byte[100];
    random.nextBytes(rawRecord);
    RecordId recordId = producer.write(rawRecord);
    recordIdFuture.complete(recordId);

    Thread.sleep(1000);
    consumer.stopAsync().awaitTerminated();
  }

  @Test
  @Order(2)
  public void testWriteHRecord() throws Exception {

    Producer producer = client.newProducer().stream(testStreamName).build();
    HRecord hRecord =
        HRecord.newBuilder().put("key1", 10).put("key2", "hello").put("key3", true).build();
    RecordId recordId = producer.write(hRecord);

    CountDownLatch countDownLatch = new CountDownLatch(1);
    Consumer consumer =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .hRecordReceiver(
                (receivedHRecord, responder) -> {
                  logger.info("receivedHRecord: {}", receivedHRecord.getHRecord());
                  Assertions.assertEquals(recordId, receivedHRecord.getRecordId());
                  countDownLatch.countDown();
                  responder.ack();
                })
            .build();
    consumer.startAsync().awaitRunning();

    countDownLatch.await();
    consumer.stopAsync().awaitTerminated();
  }

  @Test
  @Order(3)
  public void testWriteBatchRawRecord() throws Exception {

    Producer producer =
        client.newProducer().stream(testStreamName).enableBatch().recordCountLimit(10).build();
    Random random = new Random();
    final int count = 100;
    CompletableFuture<RecordId>[] recordIdFutures = new CompletableFuture[count];
    for (int i = 0; i < count; ++i) {
      byte[] rawRecord = new byte[100];
      random.nextBytes(rawRecord);
      CompletableFuture<RecordId> future = producer.writeAsync(rawRecord);
      recordIdFutures[i] = future;
    }
    CompletableFuture.allOf(recordIdFutures).join();

    logger.info("producer finish");

    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger index = new AtomicInteger();
    Consumer consumer =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .rawRecordReceiver(
                (receivedRawRecord, responder) -> {
                  Assertions.assertEquals(
                      recordIdFutures[index.getAndIncrement()].join(),
                      receivedRawRecord.getRecordId());
                  if (index.get() == count - 1) {
                    latch.countDown();
                  }
                  responder.ack();
                })
            .build();
    consumer.startAsync().awaitRunning();

    latch.await();
    consumer.stopAsync().awaitTerminated();
  }

  @Test
  @Order(4)
  public void testWriteBatchRawRecordMultiThread() throws Exception {
    Producer producer =
        client.newProducer().stream(testStreamName).enableBatch().recordCountLimit(10).build();
    Random random = new Random();
    final int count = 100;
    CompletableFuture<RecordId>[] recordIdFutures = new CompletableFuture[count];

    Thread thread1 =
        new Thread(
            () -> {
              for (int i = 0; i < count / 2; ++i) {
                byte[] rawRecord = new byte[100];
                random.nextBytes(rawRecord);
                CompletableFuture<RecordId> future = producer.writeAsync(rawRecord);
                recordIdFutures[i] = future;
              }
            });

    Thread thread2 =
        new Thread(
            () -> {
              for (int i = count / 2; i < count; ++i) {
                byte[] rawRecord = new byte[100];
                random.nextBytes(rawRecord);
                CompletableFuture<RecordId> future = producer.writeAsync(rawRecord);
                recordIdFutures[i] = future;
              }
            });

    thread1.start();
    thread2.start();

    AtomicInteger readCount = new AtomicInteger();
    Consumer consumer =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .rawRecordReceiver(
                (receivedRawRecord, responder) -> {
                  readCount.incrementAndGet();
                  responder.ack();
                })
            .build();
    consumer.startAsync().awaitRunning();

    Thread.sleep(3000);
    Assertions.assertEquals(count, readCount.get());
    consumer.stopAsync().awaitTerminated();
  }

  @Test
  @Order(5)
  public void testFlush() throws Exception {
    Producer producer =
        client.newProducer().stream(testStreamName).enableBatch().recordCountLimit(100).build();
    Random random = new Random();
    final int count = 10;
    CompletableFuture<RecordId>[] recordIdFutures = new CompletableFuture[count];
    for (int i = 0; i < count; ++i) {
      byte[] rawRecord = new byte[100];
      random.nextBytes(rawRecord);
      CompletableFuture<RecordId> future = producer.writeAsync(rawRecord);
      recordIdFutures[i] = future;
    }
    producer.flush();

    // CompletableFuture.allOf(recordIdFutures).join();

    logger.info("producer finish");

    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger index = new AtomicInteger();
    Consumer consumer =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .rawRecordReceiver(
                (receivedRawRecord, responder) -> {
                  Assertions.assertEquals(
                      recordIdFutures[index.getAndIncrement()].join(),
                      receivedRawRecord.getRecordId());
                  if (index.get() == count - 1) {
                    latch.countDown();
                  }
                  responder.ack();
                })
            .build();
    consumer.startAsync().awaitRunning();

    latch.await();
    consumer.stopAsync().awaitTerminated();
  }

  // @Disabled
  @Test
  @Order(6)
  public void testFlushMultiThread() throws Exception {
    AtomicInteger readCount = new AtomicInteger();
    Consumer consumer =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .rawRecordReceiver(
                (receivedRawRecord, responder) -> {
                  readCount.incrementAndGet();
                  responder.ack();
                })
            .build();
    consumer.startAsync().awaitRunning();

    Producer producer =
        client.newProducer().stream(testStreamName).enableBatch().recordCountLimit(100).build();
    Random random = new Random();
    final int count = 10;

    Thread thread1 =
        new Thread(
            () -> {
              for (int i = 0; i < count; ++i) {
                byte[] rawRecord = new byte[100];
                random.nextBytes(rawRecord);
                producer.writeAsync(rawRecord);
              }
              producer.flush();
            });

    Thread thread2 =
        new Thread(
            () -> {
              for (int i = 0; i < count; ++i) {
                byte[] rawRecord = new byte[100];
                random.nextBytes(rawRecord);
                producer.writeAsync(rawRecord);
              }
              producer.flush();
            });

    thread1.start();
    thread2.start();
    thread1.join();
    thread2.join();

    Thread.sleep(5000);
    consumer.stopAsync().awaitTerminated();
    Assertions.assertEquals(count * 2, readCount.get());
  }

  @Test
  @Order(7)
  public void testConsumerGroup() throws Exception {
    Producer producer = client.newProducer().stream(testStreamName).build();
    Random random = new Random();
    byte[] rawRecord = new byte[100];
    for (int i = 0; i < 9; ++i) {
      random.nextBytes(rawRecord);
      producer.write(rawRecord);
    }

    logger.info("write done");

    Consumer consumer1 =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .name("consumer-1")
            .rawRecordReceiver(
                (receivedRawRecord, responder) -> {
                  logger.info("consumer-1 recv {}", receivedRawRecord.getRecordId().getBatchId());
                  responder.ack();
                })
            .build();

    Consumer consumer2 =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .name("consumer-2")
            .rawRecordReceiver(
                (receivedRawRecord, responder) -> {
                  logger.info("consumer-2 recv {}", receivedRawRecord.getRecordId().getBatchId());
                  responder.ack();
                })
            .build();

    Consumer consumer3 =
        client
            .newConsumer()
            .subscription(testSubscriptionId)
            .name("consumer-3")
            .rawRecordReceiver(
                (receivedRawRecord, responder) -> {
                  logger.info("consumer-3 recv {}", receivedRawRecord.getRecordId().getBatchId());
                  responder.ack();
                })
            .build();

    consumer1.startAsync().awaitRunning();
    consumer2.startAsync().awaitRunning();
    consumer3.startAsync().awaitRunning();

    logger.info("consumers ready");

    Thread.sleep(5000);

    consumer1.stopAsync().awaitTerminated();
    consumer2.stopAsync().awaitTerminated();
    consumer3.stopAsync().awaitTerminated();
  }

  @Disabled("wait for fix HS-456")
  @Test
  @Order(8)
  public void testStreamQuery() throws Exception {
    AtomicInteger receivedCount = new AtomicInteger(0);
    Observer<HRecord> observer =
        new Observer<HRecord>() {
          @Override
          public void onNext(HRecord value) {
            logger.info("get hrecord: {}", value);
            receivedCount.incrementAndGet();
          }

          @Override
          public void onError(Throwable t) {
            logger.error("error: ", t);
          }

          @Override
          public void onCompleted() {}
        };

    Queryer queryer =
        client
            .newQueryer()
            .sql("select * from " + testStreamName + " where temperature > 30 emit changes;")
            .resultObserver(observer)
            .build();

    queryer.startAsync().awaitRunning();

    logger.info("begin to write");

    Producer producer = client.newProducer().stream(testStreamName).build();
    HRecord hRecord1 = HRecord.newBuilder().put("temperature", 29).put("humidity", 20).build();
    HRecord hRecord2 = HRecord.newBuilder().put("temperature", 34).put("humidity", 21).build();
    HRecord hRecord3 = HRecord.newBuilder().put("temperature", 35).put("humidity", 22).build();
    producer.write(hRecord1);
    producer.write(hRecord2);
    producer.write(hRecord3);

    try {
      Thread.sleep(5000);
      Assertions.assertEquals(2, receivedCount.get());
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    queryer.stopAsync().awaitTerminated();
  }
}
