/**
 * Copyright (C) 2006-2018 Talend Inc. - www.talend.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.talend.sdk.component.proxy.front;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.talend.sdk.component.proxy.IO.slurp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;

import javax.inject.Inject;
import javax.json.JsonBuilderFactory;
import javax.json.bind.annotation.JsonbProperty;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.junit.After;
import org.junit.jupiter.api.Test;
import org.talend.sdk.component.proxy.api.persistence.OnEdit;
import org.talend.sdk.component.proxy.api.persistence.OnPersist;
import org.talend.sdk.component.proxy.model.EntityRef;
import org.talend.sdk.component.proxy.model.Node;
import org.talend.sdk.component.proxy.model.Nodes;
import org.talend.sdk.component.proxy.model.ProxyErrorPayload;
import org.talend.sdk.component.proxy.model.UiNode;
import org.talend.sdk.component.proxy.service.qualifier.UiSpecProxy;
import org.talend.sdk.component.proxy.test.CdiInject;
import org.talend.sdk.component.proxy.test.InMemoryTestPersistence;
import org.talend.sdk.component.proxy.test.WithServer;

import lombok.Data;

@CdiInject
@WithServer
class ConfigurationResourceTest {

    @Inject
    private InMemoryTestPersistence database;

    @Inject
    @UiSpecProxy
    private JsonBuilderFactory factory;

    @After
    void after() {
        database.clear();
    }

    @Test
    void save(final WebTarget client) {
        final EntityRef ref = client
                .path("configurations/{type}/save")
                .resolveTemplate("type", "test")
                .request(APPLICATION_JSON_TYPE)
                .post(entity(factory
                        .createObjectBuilder()
                        .add("url", "http://")
                        .add("_datasetMetadata", factory.createObjectBuilder().add("name", "New Connection"))
                        .build(), APPLICATION_JSON_TYPE), EntityRef.class);
        assertNotNull(ref.getId());
        assertEquals(1, database.getPersist().size());

        final OnPersist persist = database.getPersist().iterator().next();
        assertNotNull(persist);
        assertEquals(ref.getId(), persist.getId());
        assertEquals("New Connection", persist.getEnrichment(EnrichmentTestModel.class).getMetadata().getName());
    }

    @Test
    void edit(final WebTarget client) {
        final EntityRef ref = client
                .path("configurations/{type}/edit/{id}")
                .resolveTemplate("type", "test")
                .resolveTemplate("id", "fakeId")
                .request(APPLICATION_JSON_TYPE)
                .post(entity(factory
                        .createObjectBuilder()
                        .add("url", "http://")
                        .add("_datasetMetadata", factory.createObjectBuilder().add("name", "Edited Connection"))
                        .build(), APPLICATION_JSON_TYPE), EntityRef.class);
        assertNotNull(ref.getId());
        assertEquals("fakeId", ref.getId());
        assertEquals(1, database.getEdit().size());

        final OnEdit event = database.getEdit().iterator().next();
        assertNotNull(event);
        assertEquals(ref.getId(), event.getId());
        assertEquals("Edited Connection", event.getEnrichment(EnrichmentTestModel.class).getMetadata().getName());
    }

    @Test
    void listRootConfigs(final WebTarget proxyClient) {
        final Nodes roots = proxyClient.path("configurations").request(APPLICATION_JSON_TYPE).get(Nodes.class);
        assertNotNull(roots);
        assertEquals(3, roots.getNodes().size());
        roots.getNodes().forEach((k, c) -> assertNotNull(c.getIcon()));
        assertEquals(asList("Connection-1", "Connection-2", "Connection-3"),
                roots.getNodes().values().stream().map(Node::getLabel).sorted().collect(toList()));
    }

    @Test
    void getConfigDetails(final WebTarget proxyClient) {
        final Node config = proxyClient
                .path("configurations")
                .request(APPLICATION_JSON_TYPE)
                .get(Nodes.class)
                .getNodes()
                .values()
                .stream()
                .sorted(Comparator.comparing(Node::getLabel))
                .iterator()
                .next();
        final UiNode uiNode =
                proxyClient.path("configurations/" + config.getId() + "/form").request(APPLICATION_JSON_TYPE).get(
                        UiNode.class);

        assertNotNull(uiNode);
        assertNotNull(uiNode.getUi());
        assertNotNull(uiNode.getMetadata());
        assertEquals("Connection-1", uiNode.getMetadata().getName());
        assertEquals("myicon", uiNode.getMetadata().getIcon());
    }

    @Test
    void getConfigurationIcon(final WebTarget webTarget) throws IOException {
        final Node config = webTarget
                .path("configurations")
                .request(APPLICATION_JSON_TYPE)
                .get(Nodes.class)
                .getNodes()
                .values()
                .stream()
                .min(Comparator.comparing(Node::getLabel))
                .orElseThrow(() -> new IllegalStateException("test failed, no configuration is found"));

        assertEquals("TheTestFamily2", config.getFamilyLabel());
        final byte[] serverIcon = webTarget
                .path("configurations/" + config.getFamilyId() + "/icon")
                .request(MediaType.APPLICATION_OCTET_STREAM)
                .get(byte[].class);
        assertNotNull(serverIcon);
        byte[] testIcon;
        try (final InputStream customIs = getClass().getClassLoader().getResourceAsStream("icons/myicon_icon32.png")) {
            testIcon = slurp(customIs, customIs.available());
        }
        assertNotNull(testIcon);
        assertTrue(Arrays.equals(testIcon, serverIcon), "Icon are not the same as defined by the component");
    }

    @Test
    void getConfigurationIconWithMissingId(final WebTarget webTarget) {
        final WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> webTarget
                        .path("configurations/x0invalidid/icon")
                        .request(APPLICATION_OCTET_STREAM_TYPE, APPLICATION_JSON_TYPE)
                        .get(byte[].class));
        assertEquals(404, ex.getResponse().getStatus());
        final ProxyErrorPayload proxyErrorPayload = ex.getResponse().readEntity(ProxyErrorPayload.class);
        assertEquals("FAMILY_MISSING", proxyErrorPayload.getCode());
        assertTrue(proxyErrorPayload.getMessage().contains("x0invalidid"));

    }

    @Data
    public static class EnrichmentTestModel {

        @JsonbProperty("_datasetMetadata")
        private MetadataTestModel metadata;
    }

    @Data
    public static class MetadataTestModel {

        private String name;
    }
}