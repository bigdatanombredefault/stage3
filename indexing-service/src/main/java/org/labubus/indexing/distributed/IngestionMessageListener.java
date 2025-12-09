package org.labubus.indexing.distributed;

import javax.jms.*;
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

    // 'volatile' ensures that changes to this flag are visible across threads.
    private volatile boolean running = true;
    private Connection connection; // Keep a reference to close it on shutdown

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
        // Attempt to close the connection to interrupt the blocking 'receive' call
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

                // Inner loop for receiving messages
                while (running) {
                    // We use a timeout so the loop doesn't block forever.
                    // This allows it to check the 'running' flag periodically.
                    Message message = consumer.receive(5000); // 5-second timeout

                    if (message instanceof TextMessage) {
                        TextMessage textMessage = (TextMessage) message;
                        processMessage(textMessage.getText());
                    }
                }
            } catch (JMSException e) {
                if (running) {
                    logger.error("JMS connection failed: {}. Retrying in 10 seconds...", e.getMessage());
                    sleep(10000); // Wait before attempting to reconnect
                }
            } finally {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (JMSException e) {
                        // Ignore errors on close
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
            // This is the trigger that starts the core indexing logic.
            indexingService.indexBook(bookId);
        } catch (NumberFormatException e) {
            logger.error("Received an invalid message that was not a book ID: '{}'", messageText);
        } catch (Exception e) {
            // Catch any exception from the indexing process to prevent the listener from crashing.
            logger.error("An error occurred while trying to index book from message '{}'", messageText, e);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Preserve the interrupted status
        }
    }
}