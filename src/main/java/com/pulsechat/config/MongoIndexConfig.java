package com.pulsechat.config;

import com.pulsechat.model.ActiveName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MongoIndexConfig {
    private static final Logger log = LoggerFactory.getLogger(MongoIndexConfig.class);

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureActiveNameIndexes() {
        cleanupActiveNameDuplicates();

        mongoTemplate.indexOps(ActiveName.class)
                .ensureIndex(new Index()
                        .on("nameKey", Sort.Direction.ASC)
                        .unique()
                        .named("ux_active_names_name_key"));

        mongoTemplate.indexOps(ActiveName.class)
                .ensureIndex(new Index()
                        .on("expiresAt", Sort.Direction.ASC)
                        .expire(0L)
                        .named("ttl_active_names_expires_at"));
    }

    private void cleanupActiveNameDuplicates() {
        List<ActiveName> activeNames = mongoTemplate.findAll(ActiveName.class);
        if (activeNames.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        Map<String, List<ActiveName>> byName = activeNames.stream()
                .collect(Collectors.groupingBy(ActiveName::getNameKey));

        int removed = 0;

        for (List<ActiveName> reservations : byName.values()) {
            List<ActiveName> ordered = reservations.stream()
                    .sorted(Comparator.comparing(
                            ActiveName::getExpiresAt,
                            Comparator.nullsFirst(Comparator.naturalOrder())
                    ).reversed())
                    .toList();

            ActiveName keeper = ordered.get(0);

            for (int i = 1; i < ordered.size(); i++) {
                mongoTemplate.remove(ordered.get(i));
                removed++;
            }

            // Expired reservations should not block a name when the unique index is created.
            if (keeper.getExpiresAt() == null || !keeper.getExpiresAt().isAfter(now)) {
                mongoTemplate.remove(keeper);
                removed++;
            }
        }

        if (removed > 0) {
            log.warn("Cleaned up {} stale/duplicate active-name reservations before creating indexes.", removed);
        }
    }
}
