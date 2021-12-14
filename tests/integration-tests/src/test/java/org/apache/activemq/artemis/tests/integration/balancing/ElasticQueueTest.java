/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.balancing;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.security.auth.Subject;
import java.io.File;
import java.net.URI;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.api.config.ActiveMQDefaultConfiguration;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.management.AddressControl;
import org.apache.activemq.artemis.api.core.management.BrokerBalancerControl;
import org.apache.activemq.artemis.api.core.management.ObjectNameBuilder;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.balancing.BrokerBalancerConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.security.CheckType;
import org.apache.activemq.artemis.core.security.Role;
import org.apache.activemq.artemis.core.server.balancing.targets.TargetKey;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.core.settings.impl.SlowConsumerPolicy;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.security.ActiveMQSecurityManager5;
import org.apache.activemq.artemis.spi.core.security.jaas.RolePrincipal;
import org.apache.activemq.artemis.spi.core.security.jaas.UserPrincipal;
import org.apache.activemq.artemis.tests.integration.management.ManagementControlHelper;
import org.apache.activemq.artemis.tests.util.ActiveMQTestBase;
import org.apache.activemq.artemis.utils.Wait;
import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.apache.qpid.jms.JmsConnectionListener;
import org.apache.qpid.jms.message.JmsInboundMessageDispatch;
import org.junit.After;
import org.junit.Test;

public class ElasticQueueTest extends ActiveMQTestBase {

   static final String qName = "EQ";
   static final SimpleString qNameSimple = SimpleString.toSimpleString(qName);

   final int base_port = 61616;
   final Stack<EmbeddedActiveMQ> nodes = new Stack<>();
   private final String balancerConfigName = "role_name_sharder";

   private final ExecutorService executorService = Executors.newFixedThreadPool(3);
   @After
   public void cleanup() {
      for (EmbeddedActiveMQ activeMQ : nodes) {
         try {
            activeMQ.stop();
         } catch (Throwable ignored) {
         }
      }
      nodes.clear();
      executorService.shutdownNow();
   }

   String urlForNodes(Stack<EmbeddedActiveMQ> nodes) {
      StringBuilder builder = new StringBuilder("failover:(");
      int port_start = base_port;
      for (EmbeddedActiveMQ ignored : nodes) {
         if (port_start != base_port) {
            builder.append(",");
         }
         builder.append("amqp://localhost:").append(port_start++);
      }
      // fast reconnect, randomize to get to all brokers and timeout sends that block on no credit
      builder.append(")?failover.randomize=true&failover.maxReconnectAttempts=1&jms.sendTimeout=" + 1000);
      return builder.toString();
   }

   // allow tracking of failover reconnects
   static class ConnectionListener implements JmsConnectionListener {

      AtomicInteger connectionCount;

      ConnectionListener(AtomicInteger connectionCount) {
         this.connectionCount = connectionCount;
      }

      @Override
      public void onConnectionEstablished(URI uri) {
      }

      @Override
      public void onConnectionFailure(Throwable throwable) {
      }

      @Override
      public void onConnectionInterrupted(URI uri) {
      }

      @Override
      public void onConnectionRestored(URI uri) {
         connectionCount.incrementAndGet();
      }

      @Override
      public void onInboundMessage(JmsInboundMessageDispatch jmsInboundMessageDispatch) {
      }

      @Override
      public void onSessionClosed(Session session, Throwable throwable) {
      }

      @Override
      public void onConsumerClosed(MessageConsumer messageConsumer, Throwable throwable) {
      }

      @Override
      public void onProducerClosed(MessageProducer messageProducer, Throwable throwable) {
      }
   }

   // slow consumer
   class EQConsumer implements Runnable {

      final AtomicInteger consumedCount = new AtomicInteger();
      final AtomicInteger connectionCount = new AtomicInteger();
      final AtomicBoolean done = new AtomicBoolean();
      final AtomicInteger delayMillis;
      private final String url;
      long lastConsumed = 0;

      EQConsumer(String url) {
         this(url, 500);
      }

      EQConsumer(String url, int delay) {
         this.url = url;
         this.delayMillis = new AtomicInteger(delay);
      }

      @Override
      public void run() {

         try {
            while (!done.get()) {
               JmsConnectionFactory factory = new JmsConnectionFactory("CONSUMER", "PASSWORD", url);

               try (JmsConnection connection = (JmsConnection) factory.createConnection()) {

                  // track disconnects via faiover listener
                  connectionCount.incrementAndGet();
                  connection.addConnectionListener(new ConnectionListener(connectionCount));
                  connection.start();

                  Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                  MessageConsumer messageConsumer = session.createConsumer(session.createQueue(qName));

                  while (!done.get()) {
                     Message receivedMessage = messageConsumer.receiveNoWait();
                     if (receivedMessage != null) {
                        consumedCount.incrementAndGet();
                        lastConsumed = receivedMessage.getLongProperty("PID");
                        receivedMessage.acknowledge();
                     }
                     TimeUnit.MILLISECONDS.sleep(delayMillis.get());
                  }
               } catch (JMSException okTryAgainWithNewConnection) {
               }
            }
         } catch (Exception outOfHere) {
            outOfHere.printStackTrace();
         }
      }

      public long getLastConsumed() {
         return lastConsumed;
      }
   }

   // regular producer
   static class EQProducer implements Runnable {

      final AtomicInteger producedCount = new AtomicInteger();
      final AtomicInteger connectionCount = new AtomicInteger();
      final AtomicBoolean done = new AtomicBoolean();
      private final String url;

      EQProducer(String url) {
         this.url = url;
      }

      @Override
      public void run() {

         URI connectedToUri = null;
         while (!done.get()) {
            JmsConnectionFactory factory = new JmsConnectionFactory("PRODUCER", "PASSWORD", url);

            try (JmsConnection connection = (JmsConnection) factory.createConnection()) {

               // track disconnects via faiover listener
               connectionCount.incrementAndGet();
               connection.addConnectionListener(new ConnectionListener(connectionCount));
               connection.start();

               Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
               MessageProducer messageProducer = session.createProducer(session.createQueue(qName));


               BytesMessage message = session.createBytesMessage();
               message.writeBytes(new byte[1024]);
               while (!done.get()) {
                  connectedToUri = connection.getConnectedURI();
                  message.setLongProperty("PID", producedCount.get() + 1);
                  messageProducer.send(message);
                  producedCount.incrementAndGet();
               }
            } catch (JMSException expected) {
               System.out.println("expected send failure: " + expected.toString() + " PID: " + producedCount.get() + ", uri: " + connectedToUri);
            }
         }
      }

      public long getLastProduced() {
         return producedCount.get();
      }
   }

   // combined producer/ async consumer
   static class EQProducerAsyncConsumer implements Runnable {

      final AtomicInteger producedCount = new AtomicInteger();
      final AtomicInteger connectionCount = new AtomicInteger();
      final AtomicBoolean done = new AtomicBoolean();
      final AtomicBoolean producerDone = new AtomicBoolean();
      final AtomicInteger consumerSleepMillis = new AtomicInteger(1000);
      private final String url;
      final AtomicInteger consumedCount = new AtomicInteger();
      private final String user;
      private long lastConsumed;

      EQProducerAsyncConsumer(String url, String user) {
         this.url = url;
         this.user = user;
      }

      @Override
      public void run() {

         while (!done.get()) {
            JmsConnectionFactory factory = new JmsConnectionFactory(user, "PASSWORD", url);

            try (JmsConnection connection = (JmsConnection) factory.createConnection()) {

               // track disconnects via faiover listener
               connectionCount.incrementAndGet();
               connection.addConnectionListener(new ConnectionListener(connectionCount));
               connection.start();

               Session clientSession = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
               MessageConsumer messageConsumer = clientSession.createConsumer(clientSession.createQueue(qName));
               // consume async
               messageConsumer.setMessageListener(message -> {
                  consumedCount.incrementAndGet();
                  try {
                     lastConsumed = message.getLongProperty("PID");
                     if (!producerDone.get()) {
                        TimeUnit.MILLISECONDS.sleep(consumerSleepMillis.get());
                     }
                     message.acknowledge();
                  } catch (JMSException | InterruptedException ignored) {
                  }
               });

               Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
               MessageProducer messageProducer = session.createProducer(session.createQueue(qName));
               BytesMessage message = session.createBytesMessage();
               message.writeBytes(new byte[1024]);
               while (!done.get()) {
                  if (!producerDone.get()) {
                     message.setLongProperty("PID", producedCount.get() + 1);
                     messageProducer.send(message);
                     producedCount.incrementAndGet();
                  } else {
                     // just hang about and let the consumer listener work
                     TimeUnit.SECONDS.sleep(5);
                  }
               }
            } catch (JMSException | InterruptedException ignored) {
            }
         }
      }

      public long getLastProduced() {
         return producedCount.get();
      }

      public long getLastConsumed() {
         return lastConsumed;
      }
   }

   MBeanServer mBeanServer = MBeanServerFactory.createMBeanServer();

   // hardwire authenticaton to map USER to EQ_USER etc
   final ActiveMQSecurityManager5 customSecurityManager = new ActiveMQSecurityManager5() {
      @Override
      public Subject authenticate(String user,
                                  String password,
                                  RemotingConnection remotingConnection,
                                  String securityDomain) {
         Subject subject = null;
         if (validateUser(user, password)) {
            subject = new Subject();
            subject.getPrincipals().add(new UserPrincipal(user));
            subject.getPrincipals().add(new RolePrincipal("EQ_" + user));
            if (user.equals("BOTH")) {
               subject.getPrincipals().add(new RolePrincipal("EQ_PRODUCER"));
               subject.getPrincipals().add(new RolePrincipal("EQ_CONSUMER"));
            }
         }
         return subject;
      }

      @Override
      public boolean authorize(Subject subject, Set<Role> roles, CheckType checkType, String address) {
         return true;
      }

      @Override
      public boolean validateUser(final String username, final String password) {
         return (username.equals("CONSUMER") || username.equals("PRODUCER") || username.equals("BOTH"));
      }

      @Override
      public boolean validateUserAndRole(final String username,
                                         final String password,
                                         final Set<Role> requiredRoles,
                                         final CheckType checkType) {
         return username.equals("CONSUMER") || username.equals("PRODUCER") || username.equals("BOTH");
      }
   };

   final ObjectNameBuilder node0NameBuilder = ObjectNameBuilder.create(ActiveMQDefaultConfiguration.getDefaultJmxDomain(), "Node0", true);
   final ObjectNameBuilder node1NameBuilder = ObjectNameBuilder.create(ActiveMQDefaultConfiguration.getDefaultJmxDomain(), "Node1", true);


   /*
    use case is dispatch from memory, with non-blocking producers
    producers add to the head of the broker chain, consumers receive from the tail
    when head == tail we are back to one broker for that address, the end of the chain
   */
   private void prepareNodesAndStartCombinedHeadTail() throws Exception {
      AddressSettings blockingQueue = new AddressSettings();
      blockingQueue
         .setMaxSizeBytes(100 * 1024)
         .setAddressFullMessagePolicy(AddressFullMessagePolicy.FAIL)
         .setSlowConsumerPolicy(SlowConsumerPolicy.KILL).setSlowConsumerThreshold(0).setSlowConsumerCheckPeriod(1)
         .setAutoDeleteQueues(false).setAutoDeleteAddresses(false); // so slow consumer can kick in!

      Configuration baseConfig = new ConfigurationImpl();
      baseConfig.getAddressesSettings().put(qName, blockingQueue);

      BrokerBalancerConfiguration balancerConfiguration = new BrokerBalancerConfiguration();
      balancerConfiguration.setName(balancerConfigName).setTargetKey(TargetKey.ROLE_NAME).setTargetKeyFilter("(?<=^EQ_).*"); // strip EQ_ prefix
      baseConfig.addBalancerConfiguration(balancerConfiguration);

      // prepare two nodes
      for (int nodeId = 0; nodeId < 2; nodeId++) {
         Configuration configuration = baseConfig.copy();
         configuration.setName("Node" + nodeId);
         configuration.setBrokerInstance(new File(getTestDirfile(), configuration.getName()));
         configuration.addAcceptorConfiguration("tcp", "tcp://localhost:" + (base_port + (nodeId)) + "?redirect-to=" + balancerConfigName + ";amqpCredits=1000;amqpMinCredits=300");
         nodes.add(new EmbeddedActiveMQ().setConfiguration(configuration));
         nodes.get(nodeId).setSecurityManager(customSecurityManager);
         nodes.get(nodeId).setMbeanServer(mBeanServer);
      }

      // node0 initially handles both producer & consumer (head & tail)
      nodes.get(0).getConfiguration().getBalancerConfigurations().get(0).setLocalTargetFilter("PRODUCER|CONSUMER");
      nodes.get(0).start();
   }

   @Test (timeout = 60000)
   public void testScale0_1() throws Exception {

      prepareNodesAndStartCombinedHeadTail();

      // slow consumer, delay on each message received
      EQConsumer eqConsumer = new EQConsumer(urlForNodes(nodes));
      executorService.submit(eqConsumer);

      // verify consumer reconnects on no messages
      assertTrue(Wait.waitFor(() -> eqConsumer.connectionCount.get() > 1, 5000, 200));

      EQProducer eqProducer = new EQProducer(urlForNodes(nodes));
      executorService.submit(eqProducer);

      // verify producer reconnects on fail full!
      assertTrue(Wait.waitFor(() -> eqProducer.connectionCount.get() > 1, 10000, 200));

      // operator mode, poll queue control - to allow producer to continue, activate next broker in the 'chain'
      AddressControl addressControl0 = (AddressControl) ManagementControlHelper.createProxy(node0NameBuilder.getAddressObjectName(qNameSimple), AddressControl.class, mBeanServer);

      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl0.getAddressLimitPercent();
         System.out.println("Want 100% on Head&Tail, usage % " + usage);
         return usage == 100;
      },10000, 500));

      // stop producer on Node0, only accept consumers
      BrokerBalancerControl balancerControl0 = (BrokerBalancerControl) ManagementControlHelper.createProxy(node0NameBuilder.getBrokerBalancerObjectName(balancerConfigName), BrokerBalancerControl.class, mBeanServer);
      balancerControl0.setLocalTargetFilter("CONSUMER");

      // start node1 exclusively for Producer
      nodes.get(1).getConfiguration().getBalancerConfigurations().get(0).setLocalTargetFilter("PRODUCER");
      nodes.get(1).start();

      // auto created address when producer connects
      AddressControl addressControl1 = (AddressControl) ManagementControlHelper.createProxy(node1NameBuilder.getAddressObjectName(qNameSimple), AddressControl.class, mBeanServer);
      assertTrue("Producer is on Head, Node1", Wait.waitFor(() -> {
         try {
            int usage = addressControl1.getAddressLimitPercent();
            System.out.println("Node1 (head) usage % " + usage);
            return usage > 10;
         } catch (javax.management.InstanceNotFoundException notYetReadyExpected) {
         }
         return false;
      },5000, 200));

      // wait for Node0 to drain
      eqConsumer.delayMillis.set(0); // fast
      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl0.getAddressLimitPercent();
         System.out.println("Want 0, Node0 (tail) usage % " + usage);
         return usage == 0;
      },20000, 500));
      balancerControl0.setLocalTargetFilter(""); // Node0 is out of service, Node1 (new head&tail) is where it is all at going forward!

      BrokerBalancerControl balancerControl1 = (BrokerBalancerControl) ManagementControlHelper.createProxy(node1NameBuilder.getBrokerBalancerObjectName(balancerConfigName), BrokerBalancerControl.class, mBeanServer);
      balancerControl1.setLocalTargetFilter("CONSUMER|PRODUCER"); // Node1 is serving (head & tail)

      // back to one element in the chain
      nodes.get(0).stop();

      eqConsumer.delayMillis.set(500); // slow

      assertTrue("New head&tail, Node1 full", Wait.waitFor(() -> {
         int usage = addressControl1.getAddressLimitPercent();
         System.out.println("Node1 usage % " + usage);
         return usage == 100;
      },10000, 200));

      // stop the producer
      eqProducer.done.set(true);

      eqConsumer.delayMillis.set(0); // fast again

      // wait for Node1 to drain
      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl1.getAddressLimitPercent();
         System.out.println("Want 0, on producer complete, Node1 usage % " + usage);
         return usage == 0;
      }, 10000, 200));

      assertTrue("Got all produced", Wait.waitFor(() -> {
         System.out.println("consumed pid: " + eqConsumer.getLastConsumed() + ", produced: " + eqProducer.getLastProduced());
         return (eqProducer.getLastProduced() == eqConsumer.getLastConsumed());
      }, 4000, 100));

      eqConsumer.done.set(true);

      nodes.get(1).stop();
   }

   // Q: what happens for a producer/consumer connection?
   // A: we can limit it to a PRODUCER role, and it can only send, with addressControl.pause() the consumer
   // will get nothing to avoid out of order messages, b/c it is connected to the head broker, not the tail!
   // Some pure CONSUMER role needs to drain the tail in this case.
   @Test (timeout = 60000)
   public void testScale0_1_CombinedProducerConsumerConnectionWithProducerRole() throws Exception {

      prepareNodesAndStartCombinedHeadTail();

      EQProducerAsyncConsumer eqProducerConsumer = new EQProducerAsyncConsumer(urlForNodes(nodes), "PRODUCER");
      executorService.submit(eqProducerConsumer);

      AddressControl addressControl0 = (AddressControl) ManagementControlHelper.createProxy(node0NameBuilder.getAddressObjectName(qNameSimple), AddressControl.class, mBeanServer);
      assertTrue(Wait.waitFor(() -> {
         try {
            int usage = addressControl0.getAddressLimitPercent();
            System.out.println("Head&Tail usage % " + usage);
            return usage == 100;
         } catch (javax.management.InstanceNotFoundException notYetReadyExpected) {
         }
         return false;
      },10000, 200));

      assertTrue("producer got full error and reconnected", Wait.waitFor(() -> eqProducerConsumer.connectionCount.get() > 2));

      long lastProducedToHeadTail = eqProducerConsumer.getLastProduced();

      // stop producer on Node0, only accept consumers. make it a tail broker
      BrokerBalancerControl balancerControl0 = (BrokerBalancerControl) ManagementControlHelper.createProxy(node0NameBuilder.getBrokerBalancerObjectName(balancerConfigName), BrokerBalancerControl.class, mBeanServer);
      balancerControl0.setLocalTargetFilter("CONSUMER");

      // start new head exclusively for Producer
      nodes.get(1).getConfiguration().getBalancerConfigurations().get(0).setLocalTargetFilter("PRODUCER");
      nodes.get(1).start();

      // ensure nothing can be consumed from the head
      AddressControl addressControl1 =  (AddressControl) ManagementControlHelper.createProxy(node1NameBuilder.getAddressObjectName(qNameSimple), AddressControl.class, mBeanServer);
      assertTrue(Wait.waitFor(() -> {
         try {
            addressControl1.pause();
            return true;
         } catch (javax.management.InstanceNotFoundException notYetReadyExpected) {
         }
         return false;
      },10000, 200));

      // need another connection to drain tail
      EQConsumer eqConsumer = new EQConsumer(urlForNodes(nodes), 0);
      executorService.submit(eqConsumer);

      // wait for tail to drain
      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl0.getAddressLimitPercent();
         System.out.println("Tail usage % " + usage);
         return usage == 0;
      },10000, 200));

      assertTrue(Wait.waitFor(() -> {
         System.out.println("drain tail, lastProduced: " + lastProducedToHeadTail + ", consumed: " + eqConsumer.getLastConsumed());
         return lastProducedToHeadTail == eqConsumer.getLastConsumed();
      },5000, 100));

      eqConsumer.done.set(true);

      balancerControl0 = (BrokerBalancerControl) ManagementControlHelper.createProxy(node0NameBuilder.getBrokerBalancerObjectName(balancerConfigName), BrokerBalancerControl.class, mBeanServer);
      balancerControl0.setLocalTargetFilter(""); // out of service

      nodes.get(0).stop();

      // resume consumption on new head
      addressControl1.resume();

      // head should fill
      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl1.getAddressLimitPercent();
         System.out.println("Head&Tail usage % " + usage);
         return usage == 100;
      },10000, 200));

      eqProducerConsumer.producerDone.set(true);

      // head should drain
      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl1.getAddressLimitPercent();
         System.out.println("Node1 usage % " + usage);
         return usage == 0;
      },10000, 200));

      assertTrue(Wait.waitFor(() -> {
         System.out.println("current head&tail lastProduced: " + eqProducerConsumer.getLastProduced() + ", consumed: " + eqProducerConsumer.getLastConsumed());
         return eqProducerConsumer.getLastProduced() == eqProducerConsumer.getLastConsumed();
      },5000, 100));

      eqProducerConsumer.done.set(true);

      nodes.get(1).stop();
   }


   // If we had a producer block (based on credit) it could also consume but not produce if we allow
   // it to have both roles. With both roles, we need to be able to turn off production and best with credit.
   // blocking credit takes effect for new links, existing producers will see the FAIL exception.
   // Blocked producers make use of jms.sendTimeout to error out.
   @Test (timeout = 60000)
   public void testScale0_1_CombinedRoleConnection() throws Exception {

      prepareNodesAndStartCombinedHeadTail();

      EQProducerAsyncConsumer eqProducerConsumer = new EQProducerAsyncConsumer(urlForNodes(nodes), "BOTH");
      executorService.submit(eqProducerConsumer);

      AddressControl addressControl0 = (AddressControl) ManagementControlHelper.createProxy(node0NameBuilder.getAddressObjectName(qNameSimple), AddressControl.class, mBeanServer);
      assertTrue(Wait.waitFor(() -> {
         try {
            int usage = addressControl0.getAddressLimitPercent();
            System.out.println("Head&Tail usage % " + usage);
            return usage == 100;
         } catch (javax.management.InstanceNotFoundException notYetReadyExpected) {
         }
         return false;
      },20000, 200));

      assertTrue("producer got full error and reconnected", Wait.waitFor(() -> eqProducerConsumer.connectionCount.get() > 0));

      // stop producer on Node0, only accept consumers. make it a tail broker
      BrokerBalancerControl balancerControl0 = (BrokerBalancerControl) ManagementControlHelper.createProxy(node0NameBuilder.getBrokerBalancerObjectName(balancerConfigName), BrokerBalancerControl.class, mBeanServer);
      balancerControl0.setTargetKeyFilter("(?<=^EQ_)CONSUMER");  // because both roles present, we need to filter the roles with an exact match, otherwise we get the first one!
      // ensure nothing more can be produced
      addressControl0.block();
      System.out.println("Tail blocked!");

      // start new head exclusively for Producer
      nodes.get(1).getConfiguration().getBalancerConfigurations().get(0).setTargetKeyFilter("(?<=^EQ_)PRODUCER"); // just accept the producer role as key
      nodes.get(1).getConfiguration().getBalancerConfigurations().get(0).setLocalTargetFilter(null); // initially won't accept any till we pause

      // new Head needs the address configured, such that we can start the balancer with the address paused
      nodes.get(1).getConfiguration().getAddressConfigurations().add(new CoreAddressConfiguration().setName(qName).addRoutingType(RoutingType.ANYCAST).addQueueConfiguration(new QueueConfiguration(qName).setRoutingType(RoutingType.ANYCAST)));
      nodes.get(1).start();

      // ensure nothing can be consumed from the head
      AddressControl addressControl1 =  (AddressControl) ManagementControlHelper.createProxy(node1NameBuilder.getAddressObjectName(qNameSimple), AddressControl.class, mBeanServer);
      assertTrue(Wait.waitFor(() -> {
         try {
            addressControl1.pause();
            return true;
         } catch (javax.management.InstanceNotFoundException notYetReadyExpected) {
         }
         return false;
      }, 5000, 100));
      BrokerBalancerControl balancerControl1 = (BrokerBalancerControl) ManagementControlHelper.createProxy(node1NameBuilder.getBrokerBalancerObjectName(balancerConfigName), BrokerBalancerControl.class, mBeanServer);
      balancerControl1.setLocalTargetFilter("PRODUCER");
      System.out.println("Head enabled for producers... limit: " + addressControl1.getAddressLimitPercent());

      // let the consumer run as fast as possible
      eqProducerConsumer.consumerSleepMillis.set(0);

      // wait for tail to drain, connection should bounce due to the slow consumer strategy and get to consume from the tail
      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl0.getAddressLimitPercent();
         System.out.println("Want 0, tail usage % " + usage);
         return usage == 0;
      }, 20000, 200));

      System.out.println("Tail drained!");
      balancerControl0.setLocalTargetFilter(null); // out of service

      // resume consumers on the new head&tail
      addressControl1.resume();

      // slow down consumers again
      eqProducerConsumer.consumerSleepMillis.set(2000);

      // head should fill
      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl1.getAddressLimitPercent();
         System.out.println("want 100%, head&tail usage % " + usage);
         return usage == 100;
      }, 20000, 200));

      eqProducerConsumer.producerDone.set(true);

      // head should drain
      assertTrue(Wait.waitFor(() -> {
         int usage = addressControl1.getAddressLimitPercent();
         System.out.println("Want 0, head&tail usage % " + usage);
         return usage == 0;
      }, 20000, 200));

      assertTrue(Wait.waitFor(() -> {
         System.out.println("current head&tail lastProduced: " + eqProducerConsumer.getLastProduced() + ", consumed: " + eqProducerConsumer.getLastConsumed());
         return eqProducerConsumer.getLastProduced() == eqProducerConsumer.getLastConsumed();
      }, 20000, 200));

      eqProducerConsumer.done.set(true);

      nodes.get(1).stop();
   }
}