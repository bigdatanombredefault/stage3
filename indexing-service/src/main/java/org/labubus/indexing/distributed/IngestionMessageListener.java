package org.labubus.indexing.distributed;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.labubus.indexing.service.IndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A background service that listens for messages from an ActiveMQ queue.
 * When a message is received, it triggers the IndexingService to index the specified book.
 * It's designed to be resilient and run in a separate thread.
 */
public class IngestionMessageListener implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(IngestionMessageListener.class);

    private static final String BROKER_URL_ENV_VAR = "BROKER_URL";
    private static final String MSG_PROP_SOURCE_NODE_IP = "sourceNodeIp";

    private static final String CONSUMERS_ENV_VAR = "INDEXER_CONSUMERS";
    private static final String MAX_DELIVERIES_ENV_VAR = "INDEXER_MAX_DELIVERIES";

    private final String brokerUrl;
    private final String queueName;
    private final String currentNodeIp;
    private final IndexingService indexingService;

    private volatile boolean running = true;
    private final List<Connection> connections = new ArrayList<>();

    public IngestionMessageListener(String brokerUrl, String queueName, String currentNodeIp, IndexingService indexingService) {
        this.brokerUrl = brokerUrl;
        this.queueName = queueName;
        this.currentNodeIp = currentNodeIp;
        this.indexingService = indexingService;
    }

    /**
     * Starts the listener in a new background daemon thread.
     */
    public void start() {
        int consumers = readPositiveIntEnvOrDefault(CONSUMERS_ENV_VAR, 1);
        for (int i = 0; i < consumers; i++) {
            Thread t = new Thread(this);
            t.setDaemon(true);
            t.setName("ActiveMQ-Listener-" + (i + 1));
            t.start();
        }
    }

    /**
     * Signals the listener to stop processing and shut down gracefully.
     */
    public void stop() {
        this.running = false;

        for (Connection c : connections) {
            try {
                c.close();
            } catch (JMSException e) {
                logger.warn("Exception while closing connection during shutdown.", e);
            }
        }
    }

    @Override
    public void run() {
        String effectiveBrokerUrl = resolveBrokerUrl(brokerUrl);
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(effectiveBrokerUrl);

        int maxDeliveries = readPositiveIntEnvOrDefault(MAX_DELIVERIES_ENV_VAR, 5);

        String selector = MSG_PROP_SOURCE_NODE_IP + " = '" + escapeSelectorValue(currentNodeIp) + "'";
        logger.info(
            "ActiveMQ listener configured. queue='{}' brokerUrl='{}' selector=({})",
            queueName,
            effectiveBrokerUrl,
            selector
        );

        while (running) {
            try {
                Connection connection = connectionFactory.createConnection();
                synchronized (connections) {
                    connections.add(connection);
                }
                connection.start();

                Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
                Destination destination = session.createQueue(queueName);
                MessageConsumer consumer = session.createConsumer(destination, selector);

                logger.info("Listener connected. Waiting for messages from queue '{}'...", queueName);

                while (running) {
                    Message message = consumer.receive(5000);

                    if (!(message instanceof TextMessage textMessage)) {
                        continue;
                    }

                    boolean ok = processMessage(textMessage.getText());

                    if (ok) {
                        message.acknowledge();
                        continue;
                    }

                    int deliveryCount = readDeliveryCountOrDefault(message, 1);
                    if (deliveryCount >= maxDeliveries) {
                        logger.error(
                            "Dropping message after {} deliveries (max={}): {}",
                            deliveryCount,
                            maxDeliveries,
                            safeMessagePreview(textMessage)
                        );
                        message.acknowledge();
                    } else {
                        session.recover();
                        sleep(250);
                    }
                }
            } catch (JMSException e) {
                if (running) {
                    logger.error("JMS connection failed: {}. Retrying in 10 seconds...", e.getMessage());
                    sleep(10000);
                }
            } finally {
            }
        }
        logger.info("ActiveMQ Listener has shut down.");
    }

    private static String escapeSelectorValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private static String resolveBrokerUrl(String configuredBrokerUrl) {
        String envBrokerUrl = System.getenv(BROKER_URL_ENV_VAR);
        if (envBrokerUrl != null && !envBrokerUrl.isBlank()) {
            return envBrokerUrl.trim();
        }

        if (configuredBrokerUrl != null && !configuredBrokerUrl.isBlank()) {
            return configuredBrokerUrl.trim();
        }

        throw new IllegalStateException(
            "Missing ActiveMQ broker URL. Set environment variable '" + BROKER_URL_ENV_VAR + "' or configure 'activemq.broker.url'."
        );
    }

    private boolean processMessage(String messageText) {
        try {
            int bookId = Integer.parseInt(messageText);
            logger.info("Received job: Index book {}", bookId);
            indexingService.indexBook(bookId);
            return true;
        } catch (NumberFormatException e) {
            logger.error("Received an invalid message that was not a book ID: '{}'", messageText);
            return true;
        } catch (IOException e) {
            logger.warn("Indexing failed for message '{}': {}", messageText, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            logger.error("Unexpected error while indexing book from message '{}'", messageText, e);
            return false;
        }
    }

    private static int readPositiveIntEnvOrDefault(String env, int defaultValue) {
        String raw = System.getenv(env);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int readDeliveryCountOrDefault(Message message, int defaultValue) {
        try {
            if (message.propertyExists("JMSXDeliveryCount")) {
                return message.getIntProperty("JMSXDeliveryCount");
            }
        } catch (JMSException ignored) {
        }
        return defaultValue;
    }

    private static String safeMessagePreview(TextMessage msg) {
        try {
            String text = msg.getText();
            if (text == null) {
                return "<null>";
            }
            String trimmed = text.trim();
            return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "...";
        } catch (JMSException e) {
            return "<unavailable>";
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}