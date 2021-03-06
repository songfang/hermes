package pl.allegro.tech.hermes.consumers.supervisor.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.allegro.tech.hermes.api.SubscriptionName;
import pl.allegro.tech.hermes.common.config.ConfigFactory;
import pl.allegro.tech.hermes.common.exception.RetransmissionException;
import pl.allegro.tech.hermes.common.kafka.offset.PartitionOffset;
import pl.allegro.tech.hermes.common.kafka.offset.PartitionOffsets;
import pl.allegro.tech.hermes.common.kafka.offset.SubscriptionOffsetChangeIndicator;
import pl.allegro.tech.hermes.consumers.consumer.offset.OffsetsStorage;
import pl.allegro.tech.hermes.consumers.consumer.offset.SubscriptionPartition;
import pl.allegro.tech.hermes.consumers.consumer.offset.SubscriptionPartitionOffset;

import javax.inject.Inject;
import java.util.List;

import static pl.allegro.tech.hermes.common.config.Configs.KAFKA_CLUSTER_NAME;

public class Retransmitter {

    private static final Logger logger = LoggerFactory.getLogger(Retransmitter.class);

    private SubscriptionOffsetChangeIndicator subscriptionOffsetChangeIndicator;
    private List<OffsetsStorage> offsetsStorages;
    private String brokersClusterName;

    @Inject
    public Retransmitter(SubscriptionOffsetChangeIndicator subscriptionOffsetChangeIndicator,
                         List<OffsetsStorage> offsetsStorages,
                         ConfigFactory configs) {
        this.subscriptionOffsetChangeIndicator = subscriptionOffsetChangeIndicator;
        this.offsetsStorages = offsetsStorages;
        this.brokersClusterName = configs.getStringProperty(KAFKA_CLUSTER_NAME);
    }

    public void reloadOffsets(SubscriptionName subscriptionName) {
        logger.info("Reloading offsets for {}", subscriptionName);
        try {
            PartitionOffsets offsets = subscriptionOffsetChangeIndicator.getSubscriptionOffsets(
                    subscriptionName.getTopicName(), subscriptionName.getName(), brokersClusterName);

            for (PartitionOffset partitionOffset : offsets) {
                SubscriptionPartitionOffset subscriptionPartitionOffset = new SubscriptionPartitionOffset(
                        new SubscriptionPartition(partitionOffset.getTopic(), subscriptionName, partitionOffset.getPartition()),
                        partitionOffset.getOffset()
                );

                for (OffsetsStorage s: offsetsStorages) {
                    s.moveSubscriptionOffset(subscriptionPartitionOffset);
                }
            }
        } catch (Exception ex) {
            throw new RetransmissionException(ex);
        }
    }
}
