package com.example.logagent;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.spi.DeferredProcessingAware;
import ch.qos.logback.classic.spi.ILoggingEvent;
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

@Component
public class OpenSearchAppender extends AppenderBase<ILoggingEvent> implements DeferredProcessingAware {

    private static final ConcurrentLinkedQueue<ILoggingEvent> eventQueue = new ConcurrentLinkedQueue<>();
    private static OpenSearchClient openSearchClient;
    private final ExecutorService executorService;

    @Autowired
    public OpenSearchAppender() {
        this.executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 4);  // Increase thread pool size for higher throughput
        for (int i = 0; i < Runtime.getRuntime().availableProcessors() * 4; i++) {
            this.executorService.submit(this::processLogs);
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (openSearchClient == null) {
            openSearchClient = SpringApplicationContext.getBean(OpenSearchClient.class);
        }
        eventQueue.offer(event);
    }

    private void processLogs() {
        List<ILoggingEvent> eventBatch = new ArrayList<>();
        int batchSize = 1000;  // Adjust batch size as needed

        while (true) {
            try {
                while (eventBatch.size() < batchSize) {
                    ILoggingEvent event = eventQueue.poll();
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
                                    .document(layout.doLayout(e))
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
