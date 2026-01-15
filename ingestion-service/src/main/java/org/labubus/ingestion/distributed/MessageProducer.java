package org.labubus.ingestion.distributed;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * A simple helper class to send a message to an ActiveMQ queue.
 * This will be used by the IngestionService to signal that a new book is ready for indexing.
 */
public class MessageProducer {
    private static final Logger logger = LoggerFactory.getLogger(MessageProducer.class);

    private static final String BROKER_URL_ENV_VAR = "BROKER_URL";
    private static final String CURRENT_NODE_IP_ENV_VAR = "CURRENT_NODE_IP";

    /** JMS message property used to route indexing jobs to the node that owns the local datalake partition. */
    public static final String MSG_PROP_SOURCE_NODE_IP = "sourceNodeIp";

    private final String brokerUrl;
    private final String queueName;
    private final String sourceNodeIp;
    private final ConnectionFactory connectionFactory;

    public MessageProducer(String brokerUrl, String queueName, String currentNodeIp) {
        this.brokerUrl = resolveBrokerUrl(brokerUrl);
        this.queueName = queueName;
        this.sourceNodeIp = resolveCurrentNodeIp(currentNodeIp);
        this.connectionFactory = new ActiveMQConnectionFactory(this.brokerUrl);

        logger.info(
            "ActiveMQ producer configured. queue='{}' brokerUrl='{}' {}='{}'",
            this.queueName,
            this.brokerUrl,
            MSG_PROP_SOURCE_NODE_IP,
            this.sourceNodeIp
        );
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

    private static String resolveCurrentNodeIp(String configuredCurrentNodeIp) {
        String envIp = System.getenv(CURRENT_NODE_IP_ENV_VAR);
        if (envIp != null && !envIp.isBlank()) {
            return envIp.trim();
        }

        if (configuredCurrentNodeIp != null && !configuredCurrentNodeIp.isBlank()) {
            return configuredCurrentNodeIp.trim();
        }

        // Keep a sensible default to avoid hard failures in local/dev environments.
        return "localhost";
    }

    /**
     * Sends a message containing the given book ID to the configured queue.
     *
     * @param bookId The ID of the book that has been ingested.
     * @throws JMSException if the connection or message sending fails.
     */
    public void sendMessage(int bookId) throws JMSException {
        try (Connection connection = connectionFactory.createConnection()) {
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            Destination destination = session.createQueue(queueName);

            javax.jms.MessageProducer producer = session.createProducer(destination);
            producer.setDeliveryMode(DeliveryMode.PERSISTENT); // Ensure message survives a broker restart

            TextMessage message = session.createTextMessage(String.valueOf(bookId));
            message.setJMSCorrelationID(String.valueOf(bookId));
            message.setStringProperty(MSG_PROP_SOURCE_NODE_IP, sourceNodeIp);

            producer.send(message);
            logger.debug("Sent message for bookId: {}", bookId);
        }
    }
}