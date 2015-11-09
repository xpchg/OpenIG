/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */

package org.forgerock.openig.openam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.forgerock.http.protocol.Response.newResponsePromise;
import static org.forgerock.http.protocol.Status.GATEWAY_TIMEOUT;
import static org.forgerock.http.protocol.Status.INTERNAL_SERVER_ERROR;
import static org.forgerock.http.protocol.Status.OK;
import static org.forgerock.http.protocol.Status.UNAUTHORIZED;
import static org.forgerock.json.JsonValue.array;
import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.openig.heap.Keys.CLIENT_HANDLER_HEAP_KEY;
import static org.forgerock.openig.heap.Keys.HTTP_CLIENT_HEAP_KEY;
import static org.forgerock.openig.heap.Keys.LOGSINK_HEAP_KEY;
import static org.forgerock.openig.heap.Keys.TEMPORARY_STORAGE_HEAP_KEY;
import static org.forgerock.openig.openam.PolicyEnforcementFilter.Heaplet.normalizeToJsonEndpoint;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;

import org.forgerock.http.Handler;
import org.forgerock.http.protocol.Request;
import org.forgerock.http.protocol.Response;
import org.forgerock.http.session.Session;
import org.forgerock.http.session.SessionContext;
import org.forgerock.json.JsonValue;
import org.forgerock.json.JsonValueException;
import org.forgerock.openig.el.Expression;
import org.forgerock.openig.handler.ClientHandler;
import org.forgerock.openig.heap.HeapException;
import org.forgerock.openig.heap.HeapImpl;
import org.forgerock.openig.heap.Name;
import org.forgerock.openig.http.HttpClient;
import org.forgerock.openig.io.TemporaryStorage;
import org.forgerock.openig.log.ConsoleLogSink;
import org.forgerock.openig.log.Logger;
import org.forgerock.services.context.AttributesContext;
import org.forgerock.services.context.Context;
import org.forgerock.services.context.RootContext;
import org.mockito.Mock;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

@SuppressWarnings("javadoc")
public class PolicyEnforcementFilterTest {
    private static final URI BASE_URI = URI.create("http://www.example.com:8090/openam/json/");
    private static final String OPENAM_URI = "http://www.example.com:8090/openam/";
    private static final String RESOURCE_CONTENT = "Access granted!";
    private static final String TOKEN = "ARrrg...42*";
    private static final Object POLICY_DECISION = array(object(
                                                            field("advices", object()),
                                                            field("ttl", "9223372036854776000"),
                                                            field("resource", "http://example.com/resource.jpg"),
                                                            field("actions", object(field("POST", false),
                                                                                    field("GET", true))),
                                                            field("attributes", object())));

    private SessionContext sessionContext;
    private AttributesContext attributesContext;

    @Mock
    private Handler terminalHandler;

    @Mock
    private Logger logger;

    @BeforeMethod
    public void setUp() throws Exception {
        initMocks(this);
        sessionContext = new SessionContext(new RootContext(), new SimpleMapSession());
        attributesContext = new AttributesContext(sessionContext);
        attributesContext.getAttributes().put("password", "hifalutin");
        attributesContext.getAttributes().put("ssoTokenSubject", TOKEN);
    }

    @DataProvider
    private static Object[][] validConfigurations() {
        return new Object[][] {
            { json(object(
                    field("openamUrl", OPENAM_URI),
                    field("pepUsername", "jackson"),
                    field("pepPassword", "password"),
                    field("ssoTokenSubject", "${contexts.attributes.attributes.ssoTokenSubject}"),
                    field("application", "myApplication"))) },
            { json(object(
                    field("openamUrl", OPENAM_URI),
                    field("pepUsername", "jackson"),
                    field("pepPassword", "password"),
                    field("jwtSubject", "${contexts.attributes.attributes.jwtSubject}"),
                    field("application", "anotherApplication"))) } };
    }

    @DataProvider
    private static Object[][] invalidConfigurations() {
        return new Object[][] {
            /* Missing url to OpenAM. */
            { json(object(
                    field("pepUsername", "jackson"),
                    field("pepPassword", "password"),
                    field("ssoTokenSubject", "${contexts.attributes.attributes.ssoTokenSubject}"))) },
            /* Missing pepUsername. */
            { json(object(
                    field("openamUrl", OPENAM_URI),
                    field("pepPassword", "password"),
                    field("ssoTokenSubject", "${contexts.attributes.attributes.ssoTokenSubject}"))) },
            /* Missing pepPassword. */
            { json(object(
                    field("openamUrl", OPENAM_URI),
                    field("pepUsername", "jackson"),
                    field("ssoTokenSubject", "${contexts.attributes.attributes.ssoTokenSubject}"))) },
            /* Missing ssoTokenSubject OR jwtSubject. */
            { json(object(
                    field("openamUrl", OPENAM_URI),
                    field("pepUsername", "jackson"),
                    field("pepPassword", "password"))) },
            /* Invalid realm */
            { json(object(
                          field("openamUrl", OPENAM_URI),
                          field("pepUsername", "jackson"),
                          field("pepPassword", "password"),
                          field("realm", "   >>invalid<<    "),
                          field("jwtSubject", "${contexts.attributes.attributes.jwtSubject}"),
                          field("application", "anotherApplication"))) } };
    }

    @DataProvider
    private static Object[][] baseUriBuiltFromOpenamUriAndRealm() throws Exception {
        return new Object[][] {
            // @Checkstyle:off
            // openamUri, realm -> expected
            { "http://www.example.com:8090/openam",       null, "http://www.example.com:8090/openam/json/" },
            { "http://www.example.com:8090/openam",         "", "http://www.example.com:8090/openam/json/" },
            { "http://www.example.com:8090/openam",        "/", "http://www.example.com:8090/openam/json/" },
            { "http://www.example.com:8090/openam/",      null, "http://www.example.com:8090/openam/json/" },
            { "http://www.example.com:8090/openam/",        "", "http://www.example.com:8090/openam/json/" },
            { "http://www.example.com:8090/openam/",       "/", "http://www.example.com:8090/openam/json/" },
            { "http://www.example.com:8090/openam",   "realm/", "http://www.example.com:8090/openam/json/realm/" },
            { "http://www.example.com:8090/openam",   "/realm", "http://www.example.com:8090/openam/json/realm/" },
            { "http://www.example.com:8090/openam/",  "realm/", "http://www.example.com:8090/openam/json/realm/" },
            { "http://www.example.com:8090/openam/",  "/realm", "http://www.example.com:8090/openam/json/realm/" },
            { "http://www.example.com:8090/openam/", "/realm/", "http://www.example.com:8090/openam/json/realm/" } };
            // @Checkstyle:on
    }

    @DataProvider
    private Object[][] invalidParameters() throws Exception {
        return new Object[][] {
            { null, terminalHandler },
            { URI.create(OPENAM_URI), null } };
    }

    @Test(dataProvider = "invalidConfigurations",
          expectedExceptions = { JsonValueException.class, HeapException.class })
    public void shouldFailToCreateHeaplet(final JsonValue invalidConfiguration) throws Exception {
        buildPolicyEnforcementFilter(invalidConfiguration);
    }

    @Test(dataProvider = "validConfigurations")
    public void shouldSucceedToCreateHeaplet(final JsonValue config) throws Exception {
        final PolicyEnforcementFilter filter = buildPolicyEnforcementFilter(config);
        assertThat(filter).isNotNull();
    }

    @Test(dataProvider = "invalidParameters", expectedExceptions = NullPointerException.class)
    public void shouldFailToCreatePolicyEnforcementFilter(final URI baseUri, final Handler policiesHandler)
            throws Exception {
        new PolicyEnforcementFilter(baseUri, policiesHandler);
    }

    @Test
    public void shouldFailToGetAccessToRequestedResource() throws Exception {
        // Given
        final Request request = new Request();
        request.setMethod("GET");
        request.setUri("http://example.com/user.1/edit");

        final PolicyEnforcementFilter filter = buildPolicyEnforcementFilter();

        sessionContext.getSession().put("SSOToken", TOKEN);
        when(terminalHandler.handle(any(Context.class), any(Request.class)))
            .thenReturn(newResponsePromise(policyDecisionAsJsonResponse()));

        // When
        final Response finalResponse = filter.filter(attributesContext,
                                                     request,
                                                     terminalHandler).get();
        // Then
        verify(terminalHandler).handle(any(Context.class), any(Request.class));
        assertThat(finalResponse.getStatus()).isEqualTo(UNAUTHORIZED);
    }

    @Test
    public void shouldSucceedToGetAccessToRequestedResource() throws Exception {
        // Given
        final Request request = new Request();
        request.setMethod("GET");
        request.setUri("http://example.com/resource.jpg");

        final PolicyEnforcementFilter filter = buildPolicyEnforcementFilter();

        sessionContext.getSession().put("SSOToken", TOKEN);
        when(terminalHandler.handle(any(Context.class), any(Request.class)))
            .thenReturn(newResponsePromise(policyDecisionAsJsonResponse()))
            .thenReturn(newResponsePromise(displayResourceResponse()));

        // When
        final Response finalResponse = filter.filter(attributesContext,
                                                     request,
                                                     terminalHandler).get();
        // Then
        verify(terminalHandler, times(2)).handle(any(Context.class), any(Request.class));
        assertThat(finalResponse.getStatus()).isEqualTo(OK);
        assertThat(finalResponse.getEntity().getString()).isEqualTo(RESOURCE_CONTENT);
    }

    @Test
    public void shouldFailDueToInvalidServerResponse() throws Exception {
        // Given
        final Request request = new Request();
        request.setMethod("GET");
        request.setUri("http://example.com/resource.jpg");

        final Response errorResponse = new Response();
        errorResponse.setStatus(GATEWAY_TIMEOUT);
        errorResponse.setEntity(array(json(field("error", "Something has gone wrong"))));

        final PolicyEnforcementFilter filter = buildPolicyEnforcementFilter();
        filter.setLogger(logger);

        sessionContext.getSession().put("SSOToken", TOKEN);
        when(terminalHandler.handle(any(Context.class), any(Request.class)))
            .thenReturn(newResponsePromise(errorResponse));

        // When
        final Response finalResponse = filter.filter(attributesContext,
                                                     request,
                                                     terminalHandler).get();
        // Then
        verify(terminalHandler).handle(any(Context.class), any(Request.class));
        assertThat(finalResponse.getStatus()).isEqualTo(INTERNAL_SERVER_ERROR);
        assertThat(finalResponse.getEntity().getString()).isEmpty();
        verify(logger).debug(any(Exception.class));
    }

    @Test(dataProvider = "baseUriBuiltFromOpenamUriAndRealm")
    public void shouldSucceedToCreateBaseUri(final String openamUri, final String realm, final String expected)
            throws Exception {
        assertThat(normalizeToJsonEndpoint(URI.create(openamUri), realm)).isEqualTo(URI.create(expected));
    }

    private static Response policyDecisionAsJsonResponse() {
        final Response response = new Response();
        response.setStatus(OK);
        response.setEntity(POLICY_DECISION);
        return response;
    }

    private static Response displayResourceResponse() {
        final Response response = new Response();
        response.setStatus(OK);
        response.setEntity(RESOURCE_CONTENT);
        return response;
    }

    private PolicyEnforcementFilter buildPolicyEnforcementFilter() throws Exception {
        final PolicyEnforcementFilter filter = new PolicyEnforcementFilter(BASE_URI, terminalHandler);
        Expression<String> subject = Expression.valueOf("${contexts.attributes.attributes.ssoTokenSubject}",
                                                        String.class);
        filter.setSsoTokenSubject(subject);
        return filter;
    }

    private static PolicyEnforcementFilter buildPolicyEnforcementFilter(final JsonValue config) throws Exception {
        final PolicyEnforcementFilter.Heaplet heaplet = new PolicyEnforcementFilter.Heaplet();
        return (PolicyEnforcementFilter) heaplet.create(Name.of("myAssignmentFilter"),
                                                                config,
                                                                buildDefaultHeap());
    }

    private static class SimpleMapSession extends HashMap<String, Object> implements Session {
        private static final long serialVersionUID = 1L;

        @Override
        public void save(Response response) throws IOException {
            // Nothing to do.
        }
    }

    public static HeapImpl buildDefaultHeap() throws Exception {
        final HeapImpl heap = new HeapImpl(Name.of("myHeap"));
        heap.put(TEMPORARY_STORAGE_HEAP_KEY, new TemporaryStorage());
        heap.put(LOGSINK_HEAP_KEY, new ConsoleLogSink());
        final HttpClient httpClient = new HttpClient();
        heap.put(HTTP_CLIENT_HEAP_KEY, httpClient);
        heap.put(CLIENT_HANDLER_HEAP_KEY, new ClientHandler(httpClient));
        return heap;
    }
}