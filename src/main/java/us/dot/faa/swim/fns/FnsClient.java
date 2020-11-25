package us.dot.faa.swim.fns;

import java.io.File;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Hashtable;
import java.util.Map;
import java.util.stream.Collectors;

import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;

import us.dot.faa.swim.jms.JmsClient;

public class FnsClient implements ExceptionListener {
	private Logger logger = LoggerFactory.getLogger(FnsClient.class);
	private static FilClient filClient = null;
	private static JmsClient jmsClient = null;
	private static FnsJmsMessageProcessor fnsJmsMessageProcessor = new FnsJmsMessageProcessor();
	private static Hashtable<String, Object> jndiProperties;
	private static String jmsConnectionFactoryName = "";
	private static String jmsQueueName = "";
	private static Config config;
	private static boolean isRunning;

	public static void main(final String[] args) throws InterruptedException {

		config = ConfigFactory.parseFile(new File("fnsClient.conf"));

		jmsConnectionFactoryName = config.getString("jms.connectionFactory");

		final FnsClient fnsClient = new FnsClient();
		filClient = new FilClient(config.getString("fil.sftp.host"), config.getString("fil.sftp.username"),
				config.getString("fil.sftp.certFilePath"));
		
		filClient.setFilFileSavePath("");
		
		try {
			fnsClient.start();
		} catch (InterruptedException e) {
			throw e;
		} finally {
			fnsClient.stop();
		}
	}

	public void start() throws InterruptedException {

		logger.info("Starting FnsClient v1.0");

		jndiProperties = new Hashtable<>();
		for (final Object jndiPropsObject : config.getList("jms.jndiProperties").toArray()) {
			final ConfigList jndiProps = (ConfigList) jndiPropsObject;
			jndiProperties.put(jndiProps.get(0).render().toString().replace("\"", ""),
					jndiProps.get(1).render().toString().replace("\"", ""));
		}

		jmsQueueName = config.getString("jms.destination");

		fnsJmsMessageProcessor
				.setMissedMessageTriggerTime(config.getInt("fnsClient.messageTracker.missedMessageTriggerTime"));

		connectJmsClient();

		initalizeNotamDbFromFil();

		FnsRestApi fnsRestApi = null;
		if (config.getBoolean("restapi.enabled")) {
			logger.info("Starting REST API");
			fnsRestApi = new FnsRestApi();
		}

		isRunning = true;

		Instant lastValidationCheckTime = Instant.now();
		while (isRunning) {
			Thread.sleep(10 * 1000);

			if (NotamDb.isValid()) {
				Map<Long, Instant> missedMessages = fnsJmsMessageProcessor.getMissedMessage();

				if (!missedMessages.isEmpty()) {
					String cachedCorellationIds = missedMessages.entrySet().stream()
							.map(kvp -> kvp.getKey() + ":" + missedMessages.get(kvp.getKey()))
							.collect(Collectors.joining(", ", "{", "}"));

					logger.warn(
							"Missed Message Identified, setting NotamDb to Invalid and ReInitalizing from FNS Initial Load | Missed Messages "
									+ cachedCorellationIds);

					if (NotamDb.isValid()) {
						NotamDb.setInvalid();
						initalizeNotamDbFromFil();
					}

				} else if (Duration.between(fnsJmsMessageProcessor.getLastMessageRecievedTime(), Instant.now())
						.toMinutes() > config.getInt("fnsClient.messageTracker.staleMessageTriggerTime")) {
					logger.warn("Have not recieved a JMS message in "
							+ config.getInt("fnsClient.messageTracker.staleMessageTriggerTime")
							+ " minutes, last message recieved at "
							+ fnsJmsMessageProcessor.getLastMessageRecievedTime()
							+ " Setting NotamDb to Invalid and ReInitalizing from FNS Initial Load");

					fnsJmsMessageProcessor.clearMissedMessages();

					try {
						if (NotamDb.isValid()) {
							NotamDb.setInvalid();
							initalizeNotamDbFromFil();
						}
					} catch (Exception e) {
						logger.error("Failed to ReInitialize NotamDb due to: " + e.getMessage(), e);
					}
				} else if (lastValidationCheckTime.atZone(ZoneId.systemDefault()).getDayOfWeek() != Instant.now()
						.atZone(ZoneId.systemDefault()).getDayOfWeek()) {

					logger.info("Performing database validation check against FIL");
					lastValidationCheckTime = Instant.now();
					try {

						Map<String, Timestamp> missMatchedMap = NotamDb.validateDatabase(filClient.getFnsInitialLoad());
						if (!missMatchedMap.isEmpty()) {
							logger.warn("Validation with Notam Database Failed");

							String missMatches = missMatchedMap.keySet().stream()
									.map(key -> key + ":" + missMatchedMap.get(key))
									.collect(Collectors.joining(", ", "{", "}"));

							logger.debug("Missing NOTAMs: " + missMatches);
						} else {
							logger.info("Database validated against FIL");
						}
					} catch (Exception e) {
						logger.error("Failed to validate database with FIL due to: " + e.getMessage(), e);
					}

					logger.info("Removing old NOTAMS from database");
					try {
						int notamsRemoved = NotamDb.removeOldNotams();
						logger.info("Removed " + notamsRemoved + " Notams");
					} catch (final Exception e) {
						logger.error(
								"Failed to remove old notams from database due to: " + e.getMessage() + ", Closing", e);
					}
				}
			}
		}

		if (jmsClient != null) {
			logger.info("Destroying JmsClient");
			try {

				jmsClient.close();
			} catch (final Exception e) {
				logger.error("Unable to destroy JmsClient due to: " + e.getMessage() + ", Closing", e);
			}
		}

		if (fnsRestApi != null) {
			logger.info("Stopping REST API");
			fnsRestApi.terminate();
		}
	}

	private void connectJmsClient() {

		logger.info("Starting JMS Consumer");
		boolean jmsConsumerStarted = false;

		while (!jmsConsumerStarted) {
			try {
				jmsClient = new JmsClient(jndiProperties);
				jmsClient.connect(jmsConnectionFactoryName, this);				
				jmsClient.createConsumer(jmsQueueName, fnsJmsMessageProcessor, Session.AUTO_ACKNOWLEDGE);				

				jmsConsumerStarted = true;

			} catch (final Exception e) {
				logger.error("JmsClient failed to start due to: " + e.getMessage(), e);
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e1) {
					logger.warn("Thread interupded");
				}
			}
		}
		logger.info("JMS Consumer Started");
	}

	private void initalizeNotamDbFromFil() {
		logger.info("Initalizing Database");
		fnsJmsMessageProcessor.clearMissedMessages();

		boolean successful = false;
		while (!successful) {
			try {
				filClient.connectToFil();
				NotamDb.initalizeNotamDb(filClient.getFnsInitialLoad());				
				successful = true;
			} catch (Exception e) {
				logger.error("Failed to Initialized NotamDb due to: " + e.getMessage(), e);
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e1) {
					logger.warn("Thread interupded");
				}
			} finally {
				filClient.close();
			}
		}
	}

	public void stop() {
		isRunning = false;
	}

	@Override
	public void onException(final JMSException e) {
		logger.error("JmsClient Failure due to : " + e.getMessage() + ". Resarting JmsClient", e);
		try {
			jmsClient.close();
		} catch (final Exception e1) {
			logger.error(
					"Failed to JmsClient JmsClient due to : " + e1.getMessage() + ". Continuing with JmsClient Restart", e1);
		}

		connectJmsClient();
	}

}