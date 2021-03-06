package pl.allegro.tech.hermes.frontend.buffer;

import com.codahale.metrics.Timer;
import com.google.common.io.Files;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import pl.allegro.tech.hermes.api.Topic;
import pl.allegro.tech.hermes.api.TopicName;
import pl.allegro.tech.hermes.common.config.ConfigFactory;
import pl.allegro.tech.hermes.common.config.Configs;
import pl.allegro.tech.hermes.common.metric.HermesMetrics;
import pl.allegro.tech.hermes.frontend.buffer.chronicle.ChronicleMapMessageRepository;
import pl.allegro.tech.hermes.frontend.cache.topic.TopicsCache;
import pl.allegro.tech.hermes.frontend.listeners.BrokerListeners;
import pl.allegro.tech.hermes.frontend.producer.BrokerMessageProducer;
import pl.allegro.tech.hermes.frontend.publishing.PublishingCallback;
import pl.allegro.tech.hermes.frontend.publishing.message.JsonMessage;
import pl.allegro.tech.hermes.frontend.publishing.message.Message;
import pl.allegro.tech.hermes.test.helper.builder.TopicBuilder;
import pl.allegro.tech.hermes.tracker.frontend.NoOperationPublishingTracker;
import pl.allegro.tech.hermes.tracker.frontend.Trackers;

import java.io.File;
import java.util.Optional;
import java.util.UUID;

import static java.time.LocalDateTime.now;
import static java.time.ZoneOffset.UTC;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BackupMessagesLoaderTest {

    private BrokerMessageProducer producer = mock(BrokerMessageProducer.class);

    private HermesMetrics metrics = mock(HermesMetrics.class);

    private BrokerListeners listeners = mock(BrokerListeners.class);

    private TopicsCache topicsCache = mock(TopicsCache.class);

    private Trackers trackers = mock(Trackers.class);

    private ConfigFactory configFactory = mock(ConfigFactory.class);

    private File tempDir;

    private final TopicName topicName = TopicName.fromQualifiedName("pl.allegro.tech.hermes.test");
    private final Topic topic = TopicBuilder.topic(topicName).build();

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDir();
        when(topicsCache.getTopic(topicName.qualifiedName())).thenReturn(Optional.of(topic));
        when(metrics.timer(anyString())).thenReturn(new Timer());
        when(metrics.timer(anyString(), eq(topicName))).thenReturn(new Timer());
        when(producer.isTopicAvailable(topic)).thenReturn(true);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteDirectory(tempDir);
    }

    @Test
    public void shouldNotSendOldMessages() {
        //given
        when(configFactory.getIntProperty(Configs.MESSAGES_LOADING_WAIT_FOR_TOPICS_CACHE)).thenReturn(5);
        when(configFactory.getIntProperty(Configs.MESSAGES_LOCAL_STORAGE_MAX_AGE_HOURS)).thenReturn(8);
        when(configFactory.getIntProperty(Configs.MESSAGES_LOCAL_STORAGE_MAX_RESEND_RETRIES)).thenReturn(2);

        MessageRepository messageRepository = new ChronicleMapMessageRepository(new File(tempDir.getAbsoluteFile(), "messages.dat"));
        BackupMessagesLoader backupMessagesLoader = new BackupMessagesLoader(producer, metrics, listeners, topicsCache, trackers, configFactory);

        messageRepository.save(messageOfAge(1), topic);
        messageRepository.save(messageOfAge(10), topic);
        messageRepository.save(messageOfAge(10), topic);

        //when
        backupMessagesLoader.loadMessages(messageRepository);

        //then
        verify(producer, times(1)).send(any(JsonMessage.class), eq(topic), any(PublishingCallback.class));
    }

    @Test
    public void shouldSendAndResendMessages() {
        //given
        int noOfSentCalls = 2;
        when(configFactory.getIntProperty(Configs.MESSAGES_LOADING_WAIT_FOR_TOPICS_CACHE)).thenReturn(5);
        when(configFactory.getIntProperty(Configs.MESSAGES_LOCAL_STORAGE_MAX_AGE_HOURS)).thenReturn(8);
        when(configFactory.getIntProperty(Configs.MESSAGES_LOCAL_STORAGE_MAX_RESEND_RETRIES)).thenReturn(noOfSentCalls - 1);
        when(trackers.get(eq(topic))).thenReturn(new NoOperationPublishingTracker());

        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ((PublishingCallback) args[2]).onUnpublished((Message) args[0], (Topic) args[1], new Exception("test"));
            return "";
        }).when(producer).send(any(JsonMessage.class), eq(topic), any(PublishingCallback.class));


        MessageRepository messageRepository = new ChronicleMapMessageRepository(new File(tempDir.getAbsoluteFile(), "messages.dat"));
        BackupMessagesLoader backupMessagesLoader = new BackupMessagesLoader(producer, metrics, listeners, topicsCache, trackers, configFactory);

        messageRepository.save(messageOfAge(1), topic);

        //when
        backupMessagesLoader.loadMessages(messageRepository);

        //then
        verify(producer, times(noOfSentCalls)).send(any(JsonMessage.class), eq(topic), any(PublishingCallback.class));
        verify(listeners, times(noOfSentCalls)).onError(any(JsonMessage.class), eq(topic), any(Exception.class));
    }

    @Test
    public void shouldSendOnlyWhenBrokerTopicIsAvailable() {
        // given
        when(configFactory.getIntProperty(Configs.MESSAGES_LOADING_WAIT_FOR_TOPICS_CACHE)).thenReturn(1);
        when(configFactory.getIntProperty(Configs.MESSAGES_LOCAL_STORAGE_MAX_AGE_HOURS)).thenReturn(10);

        when(producer.isTopicAvailable(topic)).thenReturn(false).thenReturn(false).thenReturn(true);

        BackupMessagesLoader backupMessagesLoader = new BackupMessagesLoader(producer, metrics, listeners, topicsCache, trackers, configFactory);
        MessageRepository messageRepository = new ChronicleMapMessageRepository(new File(tempDir.getAbsoluteFile(), "messages.dat"));
        messageRepository.save(messageOfAge(1), topic);

        // when
        backupMessagesLoader.loadMessages(messageRepository);

        // then
        verify(producer, times(3)).isTopicAvailable(topic);
        verify(producer, times(1)).send(any(JsonMessage.class), eq(topic), any(PublishingCallback.class));
    }

    private Message messageOfAge(int ageHours) {
        return new JsonMessage(UUID.randomUUID().toString(), "{'a':'b'}".getBytes(), now().minusHours(ageHours).toInstant(UTC).toEpochMilli());
    }
}