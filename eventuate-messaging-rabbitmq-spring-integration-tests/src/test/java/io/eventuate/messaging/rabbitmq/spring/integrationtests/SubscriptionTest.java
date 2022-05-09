package io.eventuate.messaging.rabbitmq.spring.integrationtests;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.eventuate.coordination.leadership.LeaderSelectedCallback;
import io.eventuate.messaging.partitionmanagement.Assignment;
import io.eventuate.messaging.partitionmanagement.Coordinator;
import io.eventuate.messaging.rabbitmq.spring.common.EventuateRabbitMQCommonConfigurationProperties;
import io.eventuate.messaging.rabbitmq.spring.consumer.EventuateRabbitMQConsumerConfigurationProperties;
import io.eventuate.messaging.rabbitmq.spring.consumer.EventuateRabbitMQConsumerConfigurationPropertiesConfiguration;
import io.eventuate.messaging.rabbitmq.spring.consumer.Subscription;
import io.eventuate.messaging.rabbitmq.spring.producer.EventuateRabbitMQProducer;
import io.eventuate.util.test.async.Eventually;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {RabbitMQProducerTestConfiguration.class, EventuateRabbitMQConsumerConfigurationPropertiesConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class SubscriptionTest {

  @Autowired
  private EventuateRabbitMQConsumerConfigurationProperties eventuateRabbitMQConsumerConfigurationProperties;

  @Autowired
  private EventuateRabbitMQProducer eventuateRabbitMQProducer;
  
  

  @Test
  public void leaderAssignedThenRevoked() throws Exception {
    int messages = 100;
    String destination = "destination" + UUID.randomUUID();
    String subscriberId = "subscriber" + UUID.randomUUID();

    ConcurrentLinkedQueue<Integer> concurrentLinkedQueue = new ConcurrentLinkedQueue<>();

    ConnectionFactory factory = createConnectionFactory(eventuateRabbitMQConsumerConfigurationProperties);
    Connection connection = factory.newConnection();

    //created subscribtion selected as leader, assigned all partition to it self
    CoordinationCallbacks coordinationCallbacks = createSubscription(connection, subscriberId, destination, concurrentLinkedQueue);
    coordinationCallbacks.getLeaderSelectedCallback().run(null);
    coordinationCallbacks.getAssignmentUpdatedCallback().accept(new Assignment(ImmutableSet.of(destination), ImmutableMap.of(destination, ImmutableSet.of(0, 1))));

    for (int i = 0; i < messages; i++) {
      eventuateRabbitMQProducer.send(destination,
              String.valueOf(Math.random()),
              String.valueOf(i));
    }

    //check that all sent messages received by subscription
    Eventually.eventually(30, 1, TimeUnit.SECONDS, () -> Assert.assertEquals(messages, concurrentLinkedQueue.size()));

    //removing leadership, resigning all partitions
    coordinationCallbacks.getAssignmentUpdatedCallback().accept(new Assignment(ImmutableSet.of(destination), ImmutableMap.of(destination, ImmutableSet.of())));
    coordinationCallbacks.getLeaderRemovedCallback().run();
    concurrentLinkedQueue.clear();

    //new leader which assigns partitions to itself
    coordinationCallbacks = createSubscription(connection, subscriberId, destination, concurrentLinkedQueue);
    coordinationCallbacks.getLeaderSelectedCallback().run(null);
    coordinationCallbacks.getAssignmentUpdatedCallback().accept(new Assignment(ImmutableSet.of(destination), ImmutableMap.of(destination, ImmutableSet.of(0, 1))));

    for (int i = 0; i < messages; i++) {
      eventuateRabbitMQProducer.send(destination,
              String.valueOf(Math.random()),
              String.valueOf(i));
    }

    Eventually.eventually(30, 1, TimeUnit.SECONDS, () -> Assert.assertEquals(messages, concurrentLinkedQueue.size()));

    connection.close();
  }

  @Test
  public void testParallelHandling() throws Exception {
    //2 partitions split between 2 subscriptions

    int messages = 100;
    String destination = "destination" + UUID.randomUUID();
    String subscriberId = "subscriber" + UUID.randomUUID();

    ConcurrentLinkedQueue<Integer> concurrentLinkedQueue1 = new ConcurrentLinkedQueue<>();

    ConnectionFactory factory = createConnectionFactory(eventuateRabbitMQConsumerConfigurationProperties);
    Connection connection = factory.newConnection();


    CoordinationCallbacks coordinationCallbacks = createSubscription(connection, subscriberId, destination, concurrentLinkedQueue1);
    coordinationCallbacks.getLeaderSelectedCallback().run(null);
    coordinationCallbacks.getAssignmentUpdatedCallback().accept(new Assignment(ImmutableSet.of(destination), ImmutableMap.of(destination, ImmutableSet.of(0))));

    ConcurrentLinkedQueue<Integer> concurrentLinkedQueue2 = new ConcurrentLinkedQueue<>();

    coordinationCallbacks = createSubscription(connection, subscriberId, destination, concurrentLinkedQueue2);
    coordinationCallbacks.getAssignmentUpdatedCallback().accept(new Assignment(ImmutableSet.of(destination), ImmutableMap.of(destination, ImmutableSet.of(1))));

    for (int i = 0; i < messages; i++) {
      eventuateRabbitMQProducer.send(destination,
              String.valueOf(Math.random()),
              String.valueOf(i));
    }

    Eventually.eventually(30, 1, TimeUnit.SECONDS, () -> {
      Assert.assertFalse(concurrentLinkedQueue1.isEmpty());
      Assert.assertFalse(concurrentLinkedQueue2.isEmpty());
      Assert.assertEquals(messages, concurrentLinkedQueue1.size() + concurrentLinkedQueue2.size());
    });

    connection.close();
  }

  private ConnectionFactory createConnectionFactory(EventuateRabbitMQCommonConfigurationProperties rabbitMqProperty) 
			throws KeyManagementException, NoSuchAlgorithmException {
		ConnectionFactory factory = new ConnectionFactory();
		
		factory.setHost(rabbitMqProperty.getBrokerHost());
		factory.setPort(rabbitMqProperty.getBrokerPort());
      factory.setUsername(rabbitMqProperty.getBrokerUsername());
      factory.setPassword(rabbitMqProperty.getBrokerPassword());
      factory.setVirtualHost(rabbitMqProperty.getBrokerVirtualHost());
      
      if(rabbitMqProperty.isBrokerSslEnabled()) {
      	factory.useSslProtocol(rabbitMqProperty.getBrokerSslAlgorithm());
      }
      
      factory.setAutomaticRecoveryEnabled(true);
      factory.setNetworkRecoveryInterval(4000);
      factory.setConnectionTimeout(10000);
      
      return factory;
	}
  
  private CoordinationCallbacks createSubscription(Connection connection, String subscriberId, String destination, ConcurrentLinkedQueue concurrentLinkedQueue) {
    CoordinationCallbacks coordinationCallbacks = new CoordinationCallbacks();

    new Subscription(null, null, null, connection, subscriberId, ImmutableSet.of(destination), 2, (message, runnable) -> {
      concurrentLinkedQueue.add(Integer.valueOf(message.getPayload()));
    }) {
      @Override
      protected Coordinator createCoordinator(String groupMemberId,
                                              String subscriberId,
                                              Set<String> channels,
                                              LeaderSelectedCallback leaderSelectedCallback,
                                              Runnable leaderRemovedCallback,
                                              Consumer<Assignment> assignmentUpdatedCallback) {

        coordinationCallbacks.setLeaderSelectedCallback(leaderSelectedCallback);
        coordinationCallbacks.setLeaderRemovedCallback(leaderRemovedCallback);
        coordinationCallbacks.setAssignmentUpdatedCallback(assignmentUpdatedCallback);

        return Mockito.mock(Coordinator.class);
      }
    };

    return coordinationCallbacks;
  }

  private static class CoordinationCallbacks {
    private LeaderSelectedCallback leaderSelectedCallback;
    private Consumer<Assignment> assignmentUpdatedCallback;
    private Runnable leaderRemovedCallback;

    public CoordinationCallbacks() {
    }

    public CoordinationCallbacks(LeaderSelectedCallback leaderSelectedCallback, Consumer<Assignment> assignmentUpdatedCallback, Runnable leaderRemovedCallback) {
      this.leaderSelectedCallback = leaderSelectedCallback;
      this.assignmentUpdatedCallback = assignmentUpdatedCallback;
      this.leaderRemovedCallback = leaderRemovedCallback;
    }

    public LeaderSelectedCallback getLeaderSelectedCallback() {
      return leaderSelectedCallback;
    }

    public void setLeaderSelectedCallback(LeaderSelectedCallback leaderSelectedCallback) {
      this.leaderSelectedCallback = leaderSelectedCallback;
    }

    public Consumer<Assignment> getAssignmentUpdatedCallback() {
      return assignmentUpdatedCallback;
    }

    public void setAssignmentUpdatedCallback(Consumer<Assignment> assignmentUpdatedCallback) {
      this.assignmentUpdatedCallback = assignmentUpdatedCallback;
    }

    public Runnable getLeaderRemovedCallback() {
      return leaderRemovedCallback;
    }

    public void setLeaderRemovedCallback(Runnable leaderRemovedCallback) {
      this.leaderRemovedCallback = leaderRemovedCallback;
    }
  }
}
