package org.labubus.indexing.distributed;

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

    private final String brokerUrl;
    private final String queueName;
    private final IndexingService indexingService;

    private volatile boolean running = true;
    private Connection connection;

    public IngestionMessageListener(String brokerUrl, String queueName, IndexingService indexingService) {
        this.brokerUrl = brokerUrl;
        this.queueName = queueName;
        this.indexingService = indexingService;
    }

    /**
     * Starts the listener in a new background daemon thread.
     */
    public void start() {
        Thread listenerThread = new Thread(this);
        listenerThread.setDaemon(true); // Daemon threads don't prevent the application from exiting.
        listenerThread.setName("ActiveMQ-Listener-Thread");
        listenerThread.start();
    }

    /**
     * Signals the listener to stop processing and shut down gracefully.
     */
    public void stop() {
        this.running = false;
        if (connection != null) {
            try {
                connection.close();
            } catch (JMSException e) {
                logger.warn("Exception while closing connection during shutdown.", e);
            }
        }
    }

    @Override
    public void run() {
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(brokerUrl);

        while (running) {
            try {
                connection = connectionFactory.createConnection();
                connection.start();
                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Destination destination = session.createQueue(queueName);
                MessageConsumer consumer = session.createConsumer(destination);

                logger.info("Listener connected. Waiting for messages from queue '{}'...", queueName);

                while (running) {
                    Message message = consumer.receive(5000);

                    if (message instanceof TextMessage) {
                        TextMessage textMessage = (TextMessage) message;
                        processMessage(textMessage.getText());
                    }
                }
            } catch (JMSException e) {
                if (running) {
                    logger.error("JMS connection failed: {}. Retrying in 10 seconds...", e.getMessage());
                    sleep(10000);
                }
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (JMSException e) {
                    }
                }
            }
        }
        logger.info("ActiveMQ Listener has shut down.");
    }

    private void processMessage(String messageText) {
        try {
            int bookId = Integer.parseInt(messageText);
            logger.info("Received job: Index book {}", bookId);
            indexingService.indexBook(bookId);
        } catch (NumberFormatException e) {
            logger.error("Received an invalid message that was not a book ID: '{}'", messageText);
        } catch (Exception e) {
            logger.error("An error occurred while trying to index book from message '{}'", messageText, e);
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