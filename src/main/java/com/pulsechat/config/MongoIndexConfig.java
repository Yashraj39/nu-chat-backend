package com.pulsechat.config;

import com.pulsechat.model.ActiveName;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class MongoIndexConfig {
    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureActiveNameIndexes() {
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
}
