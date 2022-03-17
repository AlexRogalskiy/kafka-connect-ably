/**
 * Copyright © 2021 Ably Real-time Ltd. (support@ably.com)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ably.kafka.connect;

import com.github.jcustenborder.kafka.connect.utils.VersionUtil;
import io.ably.lib.realtime.AblyRealtime;
import io.ably.lib.realtime.Channel;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.Message;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;

public class ChannelSinkTask extends SinkTask {
    private static final Logger logger = LoggerFactory.getLogger(ChannelSinkTask.class);
    private static final String[] severities = new String[]{"", "", "VERBOSE", "DEBUG", "INFO", "WARN", "ERROR", "ASSERT"};

    private AblyRealtime ably;

    private ChannelSinkMapping channelSinkMapping;
    private MessageSinkMapping messageSinkMapping;

    @Override
    public void start(Map<String, String> settings) {
        logger.info("Starting Ably channel Sink task");

        final ChannelSinkConnectorConfig config = new ChannelSinkConnectorConfig(settings);
        final ConfigValueEvaluator configValueEvaluator = new ConfigValueEvaluator();
        channelSinkMapping = new DefaultChannelSinkMapping(config, configValueEvaluator);
        messageSinkMapping = new MessageSinkMappingImpl(config, configValueEvaluator);

        if (config.clientOptions == null) {
            logger.error("Ably client options were not initialized due to invalid configuration.");
            return;
        }

        config.clientOptions.logHandler = (severity, tag, msg, tr) -> {
            if (severity < 0 || severity >= severities.length) {
                severity = 3;
            }
            switch (severities[severity]) {
                case "VERBOSE":
                    logger.trace(msg, tr);
                    break;
                case "DEBUG":
                    logger.debug(msg, tr);
                    break;
                case "INFO":
                    logger.info(msg, tr);
                    break;
                case "WARN":
                    logger.warn(msg, tr);
                    break;
                case "ERROR":
                    logger.error(msg, tr);
                    break;
                case "default":
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                                String.format(
                                        "severity: %d, tag: %s, msg: %s, err",
                                        severity, tag, msg, (tr != null) ? tr.getMessage() : "null"
                                )
                        );
                    }
            }
        };

        try {
            ably = new AblyRealtime(config.clientOptions);
        } catch (AblyException e) {
            logger.error("error initializing ably client", e);
        }
    }

    @Override
    public void put(Collection<SinkRecord> records) {
        if (ably == null) {
            // Put is not retryable, throwing error will indicate this
            throw new ConnectException("ably client is uninitialized");
        }

        for (final SinkRecord record : records) {
            // TODO: add configuration to change the event name
            try {
                final Channel channel = channelSinkMapping.getChannel(record, ably);
                final Message message = messageSinkMapping.getMessage(record);
                channel.publish(message);
            } catch (AblyException e) {
                if (ably.options.queueMessages) {
                    logger.error("Failed to publish message", e);
                } else {
                    throw new RetriableException("Failed to publish to Ably when queueMessages is disabled.", e);
                }
            } catch (ChannelSinkConnectorConfig.ConfigException e) {
                throw new ConnectException("Configuration error", e);
            }
        }
    }

    @Override
    public void flush(Map<TopicPartition, OffsetAndMetadata> map) {
        // Currently irrelevant because the put call is synchronous
        return;
    }

    @Override
    public void stop() {
        logger.info("Stopping Ably channel Sink task");

        if (ably != null) {
            ably.close();
            ably = null;
        }
    }

    @Override
    public String version() {
        return VersionUtil.version(this.getClass());
    }
}
