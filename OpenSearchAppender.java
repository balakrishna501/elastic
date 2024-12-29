package com.example.logagent;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.bulk.BulkRequest;
import org.opensearch.client.opensearch.core.bulk.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;

@Plugin(name = "OpenSearchAppender", category = "Core", elementType = "appender", printObject = true)
public class OpenSearchAppender extends AbstractAppender {

    private static final ConcurrentLinkedQueue<LogEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private static OpenSearchClient openSearchClient;
    private final ExecutorService executorService;

    @Autowired
    public OpenSearchAppender(String name, PatternLayout layout) {
        super(name, null, layout, false);
        this.executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);  // Increase thread pool size for higher throughput
        for (int i = 0; i < Runtime.getRuntime().availableProcessors() * 4; i++) {
            this.executorService.submit(this::processLogs);
        }
    }

    @PluginFactory
    public static OpenSearchAppender createAppender(@PluginAttribute("name") String name, @PluginElement("Layout") PatternLayout layout) {
        return new OpenSearchAppender(name, layout);
    }

    @Override
    public void append(LogEvent event) {
        if (openSearchClient == null) {
            openSearchClient = SpringApplicationContext.getBean(OpenSearchClient.class);
        }
        eventQueue.offer(event);
    }

    private void processLogs() {
        List<LogEvent> eventBatch = new ArrayList<>();
        int batchSize = 1000;  // Adjust batch size as needed

        while (true) {
            try {
                while (eventBatch.size() < batchSize) {
                    LogEvent event = eventQueue.poll();
                    if (event != null) {
                        eventBatch.add(event);
                    } else {
                        break;
                    }
                }

                if (!eventBatch.isEmpty()) {
                    List<BulkOperation> bulkOperations = eventBatch.stream()
                            .map(e -> BulkOperation.of(b -> b
                                .index(idx -> idx
                                    .index("logs-index")
                                    .document(new String(getLayout().toByteArray(e)))
                                )
                            )).collect(Collectors.toList());

                    BulkRequest request = new BulkRequest.Builder().operations(bulkOperations).build();
                    BulkResponse bulkResponse = openSearchClient.bulk(request);

                    if (bulkResponse.errors()) {
                        System.err.println("Errors occurred while indexing logs: " + bulkResponse.toString());
                        // Implement retry logic for failed operations if necessary
                    }

                    eventBatch.clear();
                } else {
                    TimeUnit.MILLISECONDS.sleep(10);  // Avoid busy-waiting
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
