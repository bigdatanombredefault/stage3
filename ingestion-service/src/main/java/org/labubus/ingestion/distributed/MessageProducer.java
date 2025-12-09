package org.labubus.ingestion.distributed;

import javax.jms.*;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * A simple helper class to send a message to an ActiveMQ queue.
 * This will be used by the IngestionService to signal that a new book is ready for indexing.
 */
public class MessageProducer {
    private static final Logger logger = LoggerFactory.getLogger(MessageProducer.class);

    private final String brokerUrl;
    private final String queueName;
    private final ConnectionFactory connectionFactory;

    public MessageProducer(String brokerUrl, String queueName) {
        this.brokerUrl = brokerUrl;
        this.queueName = queueName;
        this.connectionFactory = new ActiveMQConnectionFactory(brokerUrl);
    }

    /**
     * Sends a message containing the given book ID to the configured queue.
     *
     * @param bookId The ID of the book that has been ingested.
     * @throws JMSException if the connection or message sending fails.
     */
    public void sendMessage(int bookId) throws JMSException {
        // 'try-with-resources' ensures that the connection is automatically closed
        // even if an error occurs. This is the recommended way to handle JMS resources.
        try (Connection connection = connectionFactory.createConnection()) {
            connection.start();

            // Create a non-transactional session with automatic acknowledgement.
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            // Get the queue destination.
            Destination destination = session.createQueue(queueName);

            // Create a producer to send messages to the queue.
            javax.jms.MessageProducer producer = session.createProducer(destination);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT); // Ensure message survives a broker restart

            // Create a text message with the book ID.
            TextMessage message = session.createTextMessage(String.valueOf(bookId));

            // Send the message.
            producer.send(message);
            logger.debug("Sent message for bookId: {}", bookId);
        }
    }
}