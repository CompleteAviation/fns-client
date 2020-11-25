package us.dot.faa.swim.fns;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.jms.BytesMessage;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import us.dot.faa.swim.fns.FnsMessage.NotamStatus;

public class FnsJmsMessageProcessor implements MessageListener {
	private static final Logger logger = LoggerFactory.getLogger(FnsJmsMessageProcessor.class);

	private int missedMessageTriggerTimeInMinutes = 5;
	private long lastRecievedCorrelationId = -1;
	private Instant lastMessageRecievedTime = Instant.now();
	private ConcurrentHashMap<Long, Instant> missedMessagesHashMap = new ConcurrentHashMap<Long, Instant>();

	public void setMissedMessageTriggerTime(int timeInMinutes) {
		missedMessageTriggerTimeInMinutes = timeInMinutes;
	}

	public Map<Long, Instant> getMissedMessage() {
		return missedMessagesHashMap.entrySet().stream().filter(
				m -> Duration.between(m.getValue(), Instant.now()).toMinutes() >= missedMessageTriggerTimeInMinutes)
				.collect(Collectors.toMap(m -> m.getKey(), m -> m.getValue()));
	}

	public void clearMissedMessages() {
		missedMessagesHashMap.entrySet().removeIf(
				m -> Duration.between(m.getValue(), Instant.now()).toMinutes() >= missedMessageTriggerTimeInMinutes);
	}

	public Instant getLastMessageRecievedTime() {
		return lastMessageRecievedTime;
	}

	@Override
	public void onMessage(Message jmsMessage) {
		try {

			this.lastMessageRecievedTime = Instant.now();

			FnsMessage fnsMessage = parseFnsJmsMessage(jmsMessage);

			if (lastRecievedCorrelationId == -1) {
				lastRecievedCorrelationId = fnsMessage.getCorrelationId();
			} else if (fnsMessage.getCorrelationId() != -1) {

				missedMessagesHashMap.remove(fnsMessage.getCorrelationId());

				if (fnsMessage.getCorrelationId() - lastRecievedCorrelationId == 1) {
					lastRecievedCorrelationId = fnsMessage.getCorrelationId();
				} else if (fnsMessage.getCorrelationId() - lastRecievedCorrelationId > 1) {
					long countOfMissedMessages = fnsMessage.getCorrelationId() - lastRecievedCorrelationId - 1;

					for (int i = 0; i < countOfMissedMessages; i++) {
						Long missedMessageId = lastRecievedCorrelationId + i + 1;
						missedMessagesHashMap.put(missedMessageId, lastMessageRecievedTime);
					}
					lastRecievedCorrelationId = fnsMessage.getCorrelationId();
				}

				// FnsMissedMessageTracker.addProcessedCorrelationIdToCache(fnsMessage.getCorrelationId());
				logger.debug("Recieved JMS FNS Message " + fnsMessage.getFNS_ID() + " with CorrelationIds: "
						+ fnsMessage.getCorrelationId() + " | Latency (ms): "
						+ (Instant.now().toEpochMilli() - jmsMessage.getJMSTimestamp()));
			} else {
				logger.debug("Recieved JMS FNS Message with no CorrelationId");
			}

			processFnsMessage(fnsMessage);

		} catch (Exception e) {
			logger.error("Failed to processed JMS Text Message due to: " + e.getMessage());
		}
	}

	private FnsMessage parseFnsJmsMessage(Message message) throws Exception {

		FnsMessage fnsMessage;
		long correlationId = -1;
		if (message.propertyExists("us_gov_dot_faa_aim_fns_nds_CorrelationID")) {
			correlationId = Long.parseLong(message.getStringProperty("us_gov_dot_faa_aim_fns_nds_CorrelationID"));
		}

		String messageBody = "";
		if (message instanceof BytesMessage) {
			BytesMessage byteMessage = (BytesMessage) message;
			byte[] messageBytes = new byte[(int) byteMessage.getBodyLength()];
			byteMessage.readBytes(messageBytes);
			messageBody = new String(messageBytes);
		} else if (message instanceof TextMessage) {
			messageBody = ((TextMessage) message).getText();
		}

		fnsMessage = new FnsMessage(correlationId, messageBody);
		fnsMessage.setStatus(NotamStatus.valueOf(message.getStringProperty("us_gov_dot_faa_aim_fns_nds_NOTAMStatus")));

		return fnsMessage;

	}

	private void processFnsMessage(final FnsMessage fnsMessage) {
		if (!NotamDb.isValid()) {
			logger.debug("Pending " + fnsMessage.getStatus() + " NOTAM with FNS_ID:" + fnsMessage.getFNS_ID()
					+ " and CorrelationId: " + fnsMessage.getCorrelationId() + " due to invalid database.");
			NotamDb.pendingMessages.add(fnsMessage);
		} else {

			try {
				NotamDb.putNotam(fnsMessage);
			} catch (SQLException e) {
				logger.warn("Failed to insert Notam into Database, setting NotamDb to inValid", e);
				NotamDb.setInvalid();
			}
		}
	}

}