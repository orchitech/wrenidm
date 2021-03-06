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
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2011-2016 ForgeRock AS.
 * Portions Copyright 2020 Wren Security
 */
package org.forgerock.openidm.provisioner.openicf.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.forgerock.json.JsonValue.array;
import static org.forgerock.json.JsonValue.field;
import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.object;
import static org.forgerock.json.resource.Responses.newActionResponse;
import static org.forgerock.json.resource.Router.uriTemplate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FilenameFilter;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.forgerock.json.JsonPointer;
import org.forgerock.json.JsonValue;
import org.forgerock.json.resource.ActionRequest;
import org.forgerock.json.resource.ActionResponse;
import org.forgerock.json.resource.BadRequestException;
import org.forgerock.json.resource.Connection;
import org.forgerock.json.resource.CreateRequest;
import org.forgerock.json.resource.DeleteRequest;
import org.forgerock.json.resource.ForbiddenException;
import org.forgerock.json.resource.InternalServerErrorException;
import org.forgerock.json.resource.MemoryBackend;
import org.forgerock.json.resource.NotFoundException;
import org.forgerock.json.resource.NotSupportedException;
import org.forgerock.json.resource.PatchOperation;
import org.forgerock.json.resource.PatchRequest;
import org.forgerock.json.resource.PermanentException;
import org.forgerock.json.resource.PreconditionFailedException;
import org.forgerock.json.resource.PreconditionRequiredException;
import org.forgerock.json.resource.QueryRequest;
import org.forgerock.json.resource.QueryResourceHandler;
import org.forgerock.json.resource.QueryResponse;
import org.forgerock.json.resource.ReadRequest;
import org.forgerock.json.resource.Request;
import org.forgerock.json.resource.Requests;
import org.forgerock.json.resource.ResourceException;
import org.forgerock.json.resource.ResourceResponse;
import org.forgerock.json.resource.Resources;
import org.forgerock.json.resource.Router;
import org.forgerock.json.resource.ServiceUnavailableException;
import org.forgerock.json.resource.SingletonResourceProvider;
import org.forgerock.json.resource.SortKey;
import org.forgerock.json.resource.UpdateRequest;
import org.forgerock.openicf.framework.ConnectorFrameworkFactory;
import org.forgerock.openidm.audit.util.NullActivityLogger;
import org.forgerock.openidm.config.enhanced.JSONEnhancedConfig;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.core.PropertyAccessor;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.openidm.provisioner.impl.SystemObjectSetService;
import org.forgerock.openidm.provisioner.openicf.commons.ConnectorUtil;
import org.forgerock.openidm.provisioner.openicf.internal.SystemAction;
import org.forgerock.openidm.provisioner.openicf.syncfailure.NullSyncFailureHandler;
import org.forgerock.openidm.provisioner.openicf.syncfailure.SyncFailureHandler;
import org.forgerock.openidm.provisioner.openicf.syncfailure.SyncFailureHandlerFactory;
import org.forgerock.openidm.router.IDMConnectionFactoryWrapper;
import org.forgerock.openidm.router.RouteBuilder;
import org.forgerock.openidm.router.RouteEntry;
import org.forgerock.openidm.router.RouteService;
import org.forgerock.openidm.router.RouterRegistry;
import org.forgerock.openidm.util.FileUtil;
import org.forgerock.services.context.Context;
import org.forgerock.services.context.RootContext;
import org.forgerock.services.context.SecurityContext;
import org.forgerock.services.routing.RouteMatcher;
import org.forgerock.util.promise.Promise;
import org.forgerock.util.query.QueryFilter;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.logging.impl.NoOpLogger;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.SyncToken;
import org.identityconnectors.framework.server.ConnectorServer;
import org.identityconnectors.framework.server.impl.ConnectorServerImpl;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class OpenICFProvisionerServiceTest implements RouterRegistry, SyncFailureHandlerFactory {

    public static final String LAUNCHER_INSTALL_LOCATION = "launcher.install.location";
    public static final String LAUNCHER_INSTALL_URL = "launcher.install.url";
    public static final String LAUNCHER_WORKING_LOCATION = "launcher.working.location";
    public static final String LAUNCHER_WORKING_URL = "launcher.working.url";
    public static final String LAUNCHER_PROJECT_LOCATION = "launcher.project.location";
    public static final String LAUNCHER_PROJECT_URL = "launcher.project.url";

    /* @formatter:off */
    private static final String CONFIGURATION_TEMPLATE =
            "{\n" +
                    "    \"connectorsLocation\" : \"connectors\",\n" +
                    "    \"remoteConnectorServers\" : [\n" +
                    "        {\n" +
                    "            \"name\"          : \"testServer\",\n" +
                    "            \"host\"          : \"127.0.0.1\",\n" +
                    "            \"_port\"         : \"${openicfServerPort}\",\n" +
                    "            \"port\"          : 8759,\n" +
                    "            \"useSSL\"        : false,\n" +
                    "            \"timeout\"       : 0,\n" +
                    "            \"key\"           : \"Passw0rd\",\n" +
                    "            \"heartbeatInterval\" : 5\n" +
                    "        }\n" +
                    "    ]\n" +
                    "}";
    /* @formatter:on */

    /**
     * Setup logging for the {@link OpenICFProvisionerServiceTest}.
     */
    private final static Logger logger = LoggerFactory
            .getLogger(OpenICFProvisionerServiceTest.class);

    private Connection connection = null;

    private ConnectorServer connectorServer = null;

    private Pair<ConnectorInfoProviderService, ComponentContext> provider = null;

    private final List<Pair<OpenICFProvisionerService, ComponentContext>> systems =
            new ArrayList<Pair<OpenICFProvisionerService, ComponentContext>>();

    protected final Router router = new Router();

    final RouteService routeService = new RouteService() {
    };

    public OpenICFProvisionerServiceTest() {
        try {
            IdentityServer.initInstance(new PropertyAccessor() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> T getProperty(String key, T defaultValue, Class<T> expected) {
                    if (String.class.isAssignableFrom(expected)) {
                        try {
                            if (LAUNCHER_INSTALL_LOCATION.equals(key)
                                    || LAUNCHER_PROJECT_LOCATION.equals(key)
                                    || LAUNCHER_WORKING_LOCATION.equals(key)) {
                                return (T) URLDecoder.decode(
                                        OpenICFProvisionerServiceTest.class.getResource("/").getPath(), "utf-8");
                            } else if (LAUNCHER_INSTALL_URL.equals(key)
                                    || LAUNCHER_PROJECT_URL.equals(key)
                                    || LAUNCHER_WORKING_URL.equals(key)) {
                                return (T) OpenICFProvisionerServiceTest.class.getResource("/").toString();
                            }
                        } catch (UnsupportedEncodingException e) {
                            /* ignore */
                        }
                    }
                    return null;
                }
            });
            router.addRoute(uriTemplate("repo/synchronisation/pooledSyncStage"), new MemoryBackend());
            router.addRoute(uriTemplate("audit/activity"), new MemoryBackend());
        } catch (IllegalStateException e) {
            /* ignore */
        }
    }

    // ----- Implementation of RouterRegistry interface

    @Override
    public RouteEntry addRoute(RouteBuilder routeBuilder) {

        final RouteMatcher<Request>[] routes = routeBuilder.register(router);
        return new RouteEntry() {
            @Override
            public boolean removeRoute() {
                return router.removeRoute(routes);
            }
        };
    }

    @DataProvider(name = "dp")
    public Iterator<Object[]> createData() throws Exception {
        List<Object[]> tests = new ArrayList<Object[]>();
        for (Pair<OpenICFProvisionerService, ComponentContext> pair : systems) {
            tests.add(new Object[] { pair.getLeft().getSystemIdentifierName() });
        }
        return tests.iterator();
    }

    @DataProvider(name = "groovy-only")
    public Object[][] createGroovyData() throws Exception {
           return new Object[][]{
                   {"groovy"},
                   {"groovyremote"}
           };
    }

    @BeforeClass
    public void setUp() throws Exception {
        // Start OpenICF Connector Server
        String openicfServerPort =
                IdentityServer.getInstance().getProperty("openicfServerPort", "8759");
        int port = 8759;// Integer.getInteger(openicfServerPort);
        System.setProperty(Log.LOGSPI_PROP, NoOpLogger.class.getName());

        connectorServer = new ConnectorServerImpl();
        connectorServer.setPort(port);

        File root = new File(OpenICFProvisionerService.class.getResource("/").toURI());

        List<URL> bundleURLs = new ArrayList<URL>();

        File[] connectors = (new File(root, "/connectors/")).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });

        assertThat(connectors).isNotNull().overridingErrorMessage("You must copy the connectors first");

        for (File connector : connectors) {
            bundleURLs.add(connector.toURI().toURL());
        }

        // No Connectors were found!
        assertThat(bundleURLs.isEmpty()).isFalse();

        connectorServer.setBundleURLs(bundleURLs);
        connectorServer.setKeyHash("xOS4IeeE6eb/AhMbhxZEC37PgtE=");
        connectorServer.setIfAddress(InetAddress.getByName("127.0.0.1"));
        connectorServer.start();

        // Start ConnectorInfoProvider Service
        Dictionary<String, Object> properties = new Hashtable<String, Object>(3);
        properties.put(JSONEnhancedConfig.JSON_CONFIG_PROPERTY, CONFIGURATION_TEMPLATE);
        // mocking
        ComponentContext context = mock(ComponentContext.class);
        // stubbing
        when(context.getProperties()).thenReturn(properties);

        provider = Pair.of(new ConnectorInfoProviderService(), context);
        provider.getLeft().connectorFrameworkFactory = new ConnectorFrameworkFactory();
        final CryptoService cryptoService = mock(CryptoService.class);
        when(cryptoService.decrypt(any(JsonValue.class))).thenAnswer(
                new Answer<Object>() {
                    @Override
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        return invocation.getArguments()[0];
                    }
                });
        final JSONEnhancedConfig jsonEnhancedConfig = new JSONEnhancedConfig();
        jsonEnhancedConfig.bindCryptoService(cryptoService);
        provider.getLeft().bindEnhancedConfig(jsonEnhancedConfig);
        provider.getLeft().activate(context);

        File[] configJsons = (new File(root, "/config/")).listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith("provisioner.openicf-");
            }
        });

        assertThat(configJsons).isNotNull().overridingErrorMessage("You must copy the configurations first");

        for (final File configJson : configJsons) {
            // Start OpenICFProvisionerService Service
            properties = new Hashtable<String, Object>(3);
            // properties.put(ComponentConstants.COMPONENT_ID, 42);
            // properties.put(ComponentConstants.COMPONENT_NAME,
            // getClass().getCanonicalName());
            properties.put(JSONEnhancedConfig.JSON_CONFIG_PROPERTY, FileUtil.readFile(configJson));

            context = mock(ComponentContext.class);
            // stubbing
            when(context.getProperties()).thenReturn(properties);

            OpenICFProvisionerService service = new OpenICFProvisionerService();

            service.bindConnectorInfoProvider(provider.getLeft());
            service.bindRouterRegistry(this);
            service.bindSyncFailureHandlerFactory(this);
            service.bindEnhancedConfig(jsonEnhancedConfig);
            service.bindConnectionFactory(new IDMConnectionFactoryWrapper(Resources.newInternalConnectionFactory(router)));

            //set as NullActivityLogger to be the mock logger.
            service.setActivityLogger(NullActivityLogger.INSTANCE);

            // Attempt to activate the provisioner service up to 4 times, using ConnectorFacade#test to
            // validate proper initialization.  If the connector info manager is not be initialized, the
            // test fails because the connector cannot connect to the remote server.  In this test, it
            // manifests as a timing issue owing to the flexibility in the provisioner service and the
            // connector info provider supporting the ability for the connector server to come and go, as
            // managed by the health check thread (see ConnectorInfoProviderService#initialiseRemoteManager).
            // The test simply executes too fast for the health check thread to complete setup of the
            // connector info manager.
            for (int count = 0; count < 4; count++)  {
                service.activate(context);
                try {
                    service.getConnectorFacade().test();
                    break;
                } catch (Exception e) {
                    Thread.sleep(1000);
                }
            }

            systems.add(Pair.of(service, context));
        }
        // bind SystemObjectSetService dependencies in closure as the bind methods
        // are protected
        SystemObjectSetService systemObjectSetService =
                new SystemObjectSetService() {{
                    bindConnectionFactory(new IDMConnectionFactoryWrapper(Resources.newInternalConnectionFactory(router)));
                    for (Pair<OpenICFProvisionerService, ComponentContext> pair : systems) {
                            bindProvisionerService(pair.getLeft(), (Map<String, Object>) null);
                    }
                }};

        router.addRoute(uriTemplate("system"), systemObjectSetService);

        connection = Resources.newInternalConnection(router);
    }

    @AfterClass
    public void tearDown() throws Exception {
        for (Pair<OpenICFProvisionerService, ComponentContext> pair : systems) {
            pair.getLeft().deactivate(pair.getRight());
        }
        provider.getLeft().deactivate(provider.getRight());
        connectorServer.stop();
    }

    @Test(dataProvider = "dp")
    public void testReadInstance(String systemName) throws Exception {

    }

    @Test(dataProvider = "dp")
    public void testActionInstance(String systemName) throws Exception {

    }

    /**
     * Creates a user that will be used to test {@link PatchOperation}s on.
     *
     * @return the full resource path of the user created.
     */
    private String setUpUserForPatch() throws ResourceException {
        final String name = "john";
        final String resourceContainer =  "/system/XML/account/";
        final JsonValue object  = json(object(
                field("name", name),
                field("__PASSWORD__", "password"),
                field("lastname", "Doe"),
                field("address", "1234 NE 56th AVE"),
                field("age", 30)));

        final CreateRequest createRequest = Requests.newCreateRequest(resourceContainer, object);
        final JsonValue createdObject = connection
                .create(new SecurityContext(new RootContext(), "system", null ), createRequest).getContent();
        return resourceContainer + createdObject.get("_id").asString();
    }

    private void deleteUser(final String resourceName) throws ResourceException {
        // clean up by deleting this user
        connection.delete(new SecurityContext(new RootContext(), "system", null),
                Requests.newDeleteRequest(resourceName));
    }

    @Test
    public void testPatchInstance() throws Exception {

        final String resourceName = setUpUserForPatch();

        // Test replace operation
        PatchOperation operation = PatchOperation.replace("lastname", "Doe2");
        PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);
        JsonValue patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get("lastname").asString()).isEqualTo("Doe2");

        // Test increment operation
        operation = PatchOperation.increment("age", 10);
        patchRequest = Requests.newPatchRequest(resourceName, operation);
        patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get("age").asInteger()).isEqualTo(40);

        // Test remove operation with no value provided
        operation = PatchOperation.remove("age");
        patchRequest = Requests.newPatchRequest(resourceName, operation);
        patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get("age").isNull()).isEqualTo(true);

        // Test remove operation with value provided that is wrong
        operation = PatchOperation.remove("address", "1234");
        patchRequest = Requests.newPatchRequest(resourceName, operation);
        patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get("address").get(0).getObject()).isEqualTo("1234 NE 56th AVE");

        // Test remove operation with value provided
        operation = PatchOperation.remove("address", "1234 NE 56th AVE");
        patchRequest = Requests.newPatchRequest(resourceName, operation);
        patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get("address").isNull()).isEqualTo(true);

        // Test add operation
        operation = PatchOperation.add("gender", "m");
        patchRequest = Requests.newPatchRequest(resourceName, operation);
        patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get("gender").asString()).isEqualTo("m");

        // clean up by deleting this user
        deleteUser(resourceName);
    }

    @Test
    public void testPatchAddOnMultiValueAttrWithNoValuePresent() throws ResourceException {
        // multi-valued attribute, no value present - should set value
        final String resourceName = setUpUserForPatch();
        try {
            final PatchOperation operation = PatchOperation.add("email", "first.email@test.com");
            final PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);
            final JsonValue patchResult = connection.patch(new RootContext(), patchRequest).getContent();
            assertThat(patchResult.get("email").asList(String.class)).isEqualTo(array("first.email@test.com"));
        } finally {
            deleteUser(resourceName);
        }
    }

    @Test
    public void testPatchAddOnMultiValueAttrWithValuePresent() throws ResourceException {
        // multi-valued attribute, value present - should add additional value
        final String resourceName = setUpUserForPatch();
        try {
            // Patch the user so that it has a multivalue attribute with a value present
            PatchOperation operation = PatchOperation.add("email", "first.email@test.com");
            PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);
            JsonValue patchResult = connection.patch(new RootContext(), patchRequest).getContent();
            assertThat(patchResult.get("email").asList(String.class)).isEqualTo(array("first.email@test.com"));

            // Attempt to add another value to the multi value attribute
            operation = PatchOperation.add("email", "second.email@test.com");
            patchRequest = Requests.newPatchRequest(resourceName, operation);
            patchResult = connection.patch(new RootContext(), patchRequest).getContent();
            assertThat(patchResult.get("email").asList(String.class))
                    .isEqualTo(array("first.email@test.com", "second.email@test.com"));
        } finally {
            deleteUser(resourceName);
        }
    }

    @Test
    public void testPatchAddOnSingleValueAttrWithNoValuePresent() throws ResourceException {
        // single-valued attribute, no value present - should set value
        final String resourceName = setUpUserForPatch();
        try {
            final PatchOperation operation = PatchOperation
                    .add("__DESCRIPTION__", "a description for a single value attribute");
            final PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);
            final JsonValue patchResult = connection.patch(new RootContext(), patchRequest).getContent();
            assertThat(patchResult.get("__DESCRIPTION__").asString())
                    .isEqualTo("a description for a single value attribute");
        } finally {
            deleteUser(resourceName);
        }
    }

    @Test
    public void testPatchAddOnSingleValueAttrWithValuePresent() throws ResourceException {
        // single-valued attribute, value present - should replace value
        final String resourceName = setUpUserForPatch();
        try {
            // Add the description to the user object
            PatchOperation operation = PatchOperation
                    .add("__DESCRIPTION__", "a description for a single value attribute");
            PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);
            JsonValue patchResult = connection.patch(new RootContext(), patchRequest).getContent();
            assertThat(patchResult.get("__DESCRIPTION__").asString())
                    .isEqualTo("a description for a single value attribute");

            operation = PatchOperation.add("__DESCRIPTION__", "replace existing attribute with new description");
            patchRequest = Requests.newPatchRequest(resourceName, operation);
            patchResult = connection.patch(new RootContext(), patchRequest).getContent();
            assertThat(patchResult.get("__DESCRIPTION__").asString())
                    .isEqualTo("replace existing attribute with new description");
        } finally {
            deleteUser(resourceName);
        }
    }

    @Test
    public void testPatchWithAttributeNativeNameDifferentThanOpenIDMAttributeName() throws ResourceException {
        final String resourceName = setUpUserForPatch();
        try {
            PatchOperation operation = PatchOperation.add("disabled", true);
            PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);
            JsonValue patchResult = connection.patch(new RootContext(), patchRequest).getContent();
            assertThat(patchResult.get("disabled").asBoolean()).isEqualTo(Boolean.TRUE);

            operation = PatchOperation.replace("disabled", false);
            patchRequest = Requests.newPatchRequest(resourceName, operation);
            patchResult = connection.patch(new RootContext(), patchRequest).getContent();
            assertThat(patchResult.get("disabled").asBoolean()).isEqualTo(Boolean.FALSE);
        } finally {
            deleteUser(resourceName);
        }
    }

    @Test(expectedExceptions = InternalServerErrorException.class)
    public void testPatchAddOnSingleValuedAttributeWithMultiValue() throws ResourceException {
        final String resourceName = setUpUserForPatch();

        // single-valued attribute, not present, attempt to update with multi-value
        final JsonValue userObject = connection.read(new RootContext(),
                Requests.newReadRequest(resourceName)).getContent();
        assertThat(userObject.get("employee-type").getObject()).isNull();
        final PatchOperation operation = PatchOperation.add("employee-type", array("type1", "type2"));
        final PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);

        // should throw an exception because the value attempting to add to
        // a single valued attribute is multivalued
        try {
            connection.patch(new RootContext(), patchRequest).getContent();
        } finally {
            deleteUser(resourceName);
        }
    }

    @Test
    public void testPatchAddOnArray() throws Exception {
        String name = "jane";
        String resourceContainer = "/system/XML/account/";
        JsonValue object = json(object(
                field("name", name),
                field("__PASSWORD__", "password"),
                field("lastname", "smith"),
                field("email", name + "@example.com"),
                field("age", 29)));

        CreateRequest createRequest = Requests.newCreateRequest(resourceContainer, object);
        JsonValue createdObject = connection.create(
                new SecurityContext(new RootContext(), "system", null), createRequest).getContent();
        String resourceName = resourceContainer + createdObject.get("_id").asString();

        // Add another email into the array
        PatchOperation operation = PatchOperation.add("/email/-", name + "@example2.com");
        PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);
        JsonValue patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get(new JsonPointer("/email")).asList().size()).isEqualTo(2);
        assertThat(patchResult.get(new JsonPointer("/email/1")).asString()).isEqualTo(name + "@example2.com");

        // clean up by deleting this user
        connection.delete(new SecurityContext(new RootContext(), "system", null),
                Requests.newDeleteRequest(resourceContainer, patchResult.get("__UID__").asString()));
    }

    @Test
    public void testPatchRemoveOnArray() throws Exception {
        String name = "jane";
        String resourceContainer = "/system/XML/account/";
        JsonValue object = json(object(
                field("name", name),
                field("__PASSWORD__", "password"),
                field("lastname", "smith"),
                field("email", name + "@example.com"),
                field("age", 29)));

        CreateRequest createRequest = Requests.newCreateRequest(resourceContainer, object);
        JsonValue createdObject = connection.create(
                new SecurityContext(new RootContext(), "system", null), createRequest).getContent();
        String resourceName = resourceContainer + createdObject.get("_id").asString();

        // Add another email into the array
        PatchOperation addOperation = PatchOperation.add("/email/-",
                Arrays.asList(name + "@example2.com", name +"@example3.com"));
        PatchRequest patchRequest = Requests.newPatchRequest(resourceName, addOperation);
        JsonValue patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get(new JsonPointer("/email")).asList().size()).isEqualTo(3);
        assertThat(patchResult.get(new JsonPointer("/email/1")).asString()).isEqualTo(name + "@example2.com");

        // remove a value that doesn't exist in existing value from the array
        PatchOperation removeOperation = PatchOperation.remove("/email", name + "@example4.com");
        patchRequest = Requests.newPatchRequest(resourceName, removeOperation);
        patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        // assert that the value was not changed
        assertThat(patchResult.get(new JsonPointer("/email")).asList().size()).isEqualTo(3);

        // removing from array giving explicit value,
        removeOperation = PatchOperation.remove("/email", name + "@example2.com");
        patchRequest = Requests.newPatchRequest(resourceName, removeOperation);
        patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get(new JsonPointer("/email")).asList().size()).isEqualTo(2);

        // remove using json pointer with no value
        removeOperation = PatchOperation.remove("/email/1");
        patchRequest = Requests.newPatchRequest(resourceName, removeOperation);
        patchResult = connection.patch(new RootContext(), patchRequest).getContent();
        assertThat(patchResult.get(new JsonPointer("/email")).asList().size()).isEqualTo(1);

        // clean up by deleting this user
        connection.delete(new SecurityContext(new RootContext(), "system", null),
                Requests.newDeleteRequest(resourceContainer, patchResult.get("__UID__").asString()));
    }

    // Test remove attribute that doesn't exist in the target system
    @Test(expectedExceptions = BadRequestException.class)
    public void testRemoveUnsupportedAttribute() throws Exception {
        String name = "jane";
        String resourceContainer = "/system/XML/account/";
        JsonValue object = json(object(
                field("name", name),
                field("__PASSWORD__", "password"),
                field("lastname", "smith"),
                field("email", name + "@example.com"),
                field("age", 29)));

        CreateRequest createRequest = Requests.newCreateRequest(resourceContainer, object);
        JsonValue createdObject = connection.create(
                new SecurityContext(new RootContext(), "system", null), createRequest).getContent();
        String resourceName = resourceContainer + createdObject.get("_id").asString();

        PatchOperation removeOperation = PatchOperation.remove("/unsupportedAttribute");
        PatchRequest patchRequest = Requests.newPatchRequest(resourceName, removeOperation);

        try {
            connection.patch(new RootContext(), patchRequest).getContent();
        } finally {
            // clean up by deleting this user
            connection.delete(new SecurityContext(new RootContext(), "system", null),
                    Requests.newDeleteRequest(resourceContainer, createdObject.get("__UID__").asString()));
        }
    }

    // Test to make sure that value types can't mismatch
    @Test(expectedExceptions = InternalServerErrorException.class)
    public void testAttributeTypeValueMismatch() throws Exception {
        String name = "jane";
        String resourceContainer = "/system/XML/account/";
        JsonValue object = json(object(
                field("name", name),
                field("__PASSWORD__", "password"),
                field("lastname", "smith"),
                field("email", name + "@example.com"),
                field("age", 29)));

        CreateRequest createRequest = Requests.newCreateRequest(resourceContainer, object);
        JsonValue createdObject = connection.create(
                new SecurityContext(new RootContext(), "system", null), createRequest).getContent();
        String resourceName = resourceContainer + createdObject.get("_id").asString();

        PatchOperation removeOperation = PatchOperation.replace("age", "twenty-nine");
        PatchRequest patchRequest = Requests.newPatchRequest(resourceName, removeOperation);

        try {
            connection.patch(new RootContext(), patchRequest).getContent();
        } finally {
            // clean up by deleting this user
            connection.delete(new SecurityContext(new RootContext(), "system", null),
                    Requests.newDeleteRequest(resourceContainer, createdObject.get("__UID__").asString()));
        }
    }

    @Test(expectedExceptions = InternalServerErrorException.class)
    public void testRemoveRequiredAttribute() throws Exception {
        String name = "jane";
        String resourceContainer = "/system/XML/account/";
        JsonValue object = json(object(
                field("name", name),
                field("__PASSWORD__", "password"),
                field("lastname", "smith"),
                field("email", name + "@example.com"),
                field("age", 29)));

        CreateRequest createRequest = Requests.newCreateRequest(resourceContainer, object);
        JsonValue createdObject = connection.create(
                new SecurityContext(new RootContext(), "system", null), createRequest).getContent();
        String resourceName = resourceContainer + createdObject.get("_id").asString();

        // Try to remove the lastname that is required
        PatchOperation operation = PatchOperation.remove("lastname", "smith");
        PatchRequest patchRequest = Requests.newPatchRequest(resourceName, operation);

        try {
            connection.patch(new RootContext(), patchRequest).getContent();
        } finally {
            // clean up by deleting this user
            connection.delete(new SecurityContext(new RootContext(), "system", null),
                    Requests.newDeleteRequest(resourceContainer, createdObject.get("__UID__").asString()));
        }
    }

    @Test(dataProvider = "dp")
    public void testUpdateInstance(String systemName) throws Exception {

    }


    private JsonValue getTestConnectorObject(String name) {
        JsonValue createAttributes = new JsonValue(new LinkedHashMap<String, Object>());
        createAttributes.put(Name.NAME, name);
        createAttributes.put("attributeString", name);
        createAttributes.put("attributeLong", (long) name.hashCode());
        return createAttributes;
    }

    private JsonValue getAccountObject(String name) {
        JsonValue createAttributes = new JsonValue(new LinkedHashMap<String, Object>());
        createAttributes.put(Name.NAME, name);
        createAttributes.put("userName", name);
        createAttributes.put("email", name + "@example.com");
        return createAttributes;
    }

    private static class SyncStub implements SingletonResourceProvider {

        final public ArrayList<ActionRequest> requests = new ArrayList<ActionRequest>();

        @Override
        public Promise<ActionResponse, ResourceException> actionInstance(Context context, ActionRequest request) {
            requests.add(request);
            return newActionResponse(new JsonValue(true)).asPromise();
        }

        @Override
        public Promise<ResourceResponse, ResourceException> patchInstance(Context context, PatchRequest request) {
            return new NotSupportedException().asPromise();
        }

        @Override
        public Promise<ResourceResponse, ResourceException> readInstance(Context context, ReadRequest request) {
            return new NotSupportedException().asPromise();
        }

        @Override
        public Promise<ResourceResponse, ResourceException> updateInstance(Context context, UpdateRequest request) {
            return new NotSupportedException().asPromise();
        }
    }

    @Test(dataProvider = "groovy-only", enabled = true)
    public void testSync(String systemName) throws Exception {
        JsonValue stage = new JsonValue(new LinkedHashMap<String, Object>());
        stage.put("connectorData", ConnectorUtil.convertFromSyncToken(new SyncToken(0)));
        CreateRequest createRequest = Requests
                .newCreateRequest("repo/synchronisation/pooledSyncStage",
                        ("system" + systemName + "account").toUpperCase(),
                        stage);
        connection.create(new RootContext(), createRequest);

        SyncStub sync = new SyncStub();
        RouteMatcher<Request> r = router.addRoute(uriTemplate("sync"), sync);


        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName + "/account",
                SystemObjectSetService.SystemAction.liveSync.toString());

        ActionResponse response = connection.action(new RootContext(), actionRequest);
        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(1);
        assertThat(sync.requests.size()).isEqualTo(1);
        ActionRequest delta = sync.requests.remove(0);
        assertThat(delta.getAction()).isEqualTo("notifyCreate");


        response = connection.action(new RootContext(), actionRequest);
        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(2);
        assertThat(sync.requests.size()).isEqualTo( 1);
        delta = sync.requests.remove(0);
        assertThat(delta.getAction()).isEqualTo("notifyUpdate");


        response = connection.action(new RootContext(), actionRequest);
        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(3);
        assertThat(sync.requests.size()).isEqualTo( 1);
        delta = sync.requests.remove(0);
        assertThat(delta.getAction()).isEqualTo("notifyUpdate");


        response = connection.action(new RootContext(), actionRequest);
        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(4);
        assertThat(sync.requests.size()).isEqualTo(1);
        delta = sync.requests.remove(0);
        assertThat(delta.getAction()).isEqualTo("notifyUpdate");
        assertThat(delta.getContent().get("newValue").get("_previous-id").asString()).isEqualTo("001");


        response = connection.action(new RootContext(), actionRequest);
        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(5);
        assertThat(sync.requests.size()).isEqualTo(1);
        delta = sync.requests.remove(0);
        assertThat(delta.getAction()).isEqualTo("notifyDelete");


        response = connection.action(new RootContext(), actionRequest);
        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(10);
        assertThat(sync.requests.isEmpty()).isTrue();

        response = connection.action(new RootContext(), actionRequest);
        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(17);
        assertThat(sync.requests.size()).isEqualTo(4);
        sync.requests.clear();


        stage = new JsonValue(new LinkedHashMap<String, Object>());
        stage.put("connectorData", ConnectorUtil.convertFromSyncToken(new SyncToken(10)));
        createRequest = Requests
                .newCreateRequest("repo/synchronisation/pooledSyncStage",
                        ("system" + systemName + "group").toUpperCase(),
                        stage);
        connection.create(new RootContext(), createRequest);
        actionRequest = Requests.newActionRequest("system/" + systemName + "/group",
                SystemObjectSetService.SystemAction.liveSync.toString());

        response = connection.action(new RootContext(), actionRequest);
        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(16);
        assertThat(sync.requests.size()).isEqualTo(3);

        router.removeRoute(r);
    }


    @Test(dataProvider = "groovy-only", enabled = false)
    public void testPagedSearch(String systemName) throws Exception {

        for (int i = 0; i < 100; i++) {
            JsonValue co = getAccountObject(String.format("TEST%05d", i));
            co.put("sortKey", i);

            CreateRequest request = Requests.newCreateRequest("system/" + systemName + "/account", co);
            connection.create(new SecurityContext(new RootContext(), "system", null ), request);
        }

        QueryRequest queryRequest = Requests.newQueryRequest("system/" + systemName + "/account");
        queryRequest.setPageSize(10);
        queryRequest.addSortKey(SortKey.descendingOrder("sortKey"));
        queryRequest.setQueryFilter(QueryFilter.<JsonPointer>startsWith(new JsonPointer("__NAME__"), "TEST"));

        QueryResponse result = null;

        final Set<ResourceResponse> resultSet = new HashSet<ResourceResponse>();
        int pageIndex = 0;

        try {
            while ((result = connection.query(new RootContext(), queryRequest, new QueryResourceHandler() {

                private int index = 101;

                @Override
                public boolean handleResource(ResourceResponse resource) {
                    Integer idx = resource.getContent().get("sortKey").asInteger();
                    assertThat(idx < index).isTrue();
                    index = idx;
                    return resultSet.add(resource);
                }
            })).getPagedResultsCookie() != null) {

                queryRequest.setPagedResultsCookie(result.getPagedResultsCookie());
                assertThat(resultSet.size()).isEqualTo(10 * ++pageIndex);
            }
        } catch (ResourceException e) {
            fail(e.getMessage());
        }
        assertThat(pageIndex).isEqualTo(9);
        assertThat(resultSet.size()).isEqualTo(100);
    }

    @Test(dataProvider = "dp", enabled = true)
    public void testHelloWorldAction(String systemName) throws Exception {
        if ("Test".equals(systemName)) {

            //Request#1
            ActionRequest actionRequest = Requests.newActionRequest("/system/Test", "script");
            actionRequest.setAdditionalParameter(SystemAction.SCRIPT_ID, "ConnectorScript#1");

            ActionResponse result = connection.action(new RootContext(), actionRequest);
            assertThat(result.getJsonContent().get(new JsonPointer("actions/0/result")).getObject()).isEqualTo(
                    "Arthur Dent");

            //Request#2
            actionRequest = Requests.newActionRequest("/system/Test", "script");
            actionRequest.setAdditionalParameter(SystemAction.SCRIPT_ID, "ConnectorScript#2");
            JsonValue content = new JsonValue(new HashMap<String, Object>());
            content.put("testArgument", "Zaphod Beeblebrox");
            actionRequest.setContent(content);

            result = connection.action(new RootContext(), actionRequest);
            assertThat(result.getJsonContent().get(new JsonPointer("actions/0/result")).getObject()).isEqualTo(
                    "Zaphod Beeblebrox");

            //Request#3
            actionRequest = Requests.newActionRequest("/system/Test", "script");
            actionRequest.setAdditionalParameter(SystemAction.SCRIPT_ID, "ConnectorScript#3");
            content = new JsonValue(new HashMap<String, Object>());
            content.put("testArgument", Arrays.asList("Ford Prefect", "Tricia McMillan"));
            actionRequest.setContent(content);

            result = connection.action(new RootContext(), actionRequest);
            assertThat(result.getJsonContent().get(new JsonPointer("actions/0/result")).getObject()).isEqualTo(2);


            //Request#4
            actionRequest = Requests.newActionRequest("/system/Test", "script");
            actionRequest.setAdditionalParameter(SystemAction.SCRIPT_ID, "ConnectorScript#4");
            result = connection.action(new RootContext(), actionRequest);
            assertThat(result.getJsonContent().get(new JsonPointer("actions/0/error")).getObject()).isEqualTo(
                    "Marvin");
        }
    }

    // AlreadyExistsException -> PreconditionFailedException
    @Test(dataProvider = "groovy-only", expectedExceptions = PreconditionFailedException.class, enabled = true)
    public void testConflictException(String systemName) throws Exception {
        CreateRequest createRequest = Requests.newCreateRequest("system/" + systemName + "/__TEST__", getTestConnectorObject("TEST1"));
        connection.create(new SecurityContext(new RootContext(), "system", null ), createRequest);
    }

    // ConnectorIOException -> ServiceUnavailableException - Will work when new groovy script is updated
    @Test(dataProvider = "groovy-only", expectedExceptions = ServiceUnavailableException.class , enabled = true)
    public void testServiceUnavailableExceptionFromConnectorIOException(String systemName) throws Exception {
        DeleteRequest deleteRequest = Requests.newDeleteRequest("system/" + systemName + "/__TEST__/TESTEX_CIO");
        connection.delete((new SecurityContext(new RootContext(), "system", null)), deleteRequest);
    }

    // OperationTimeoutException -> ServiceUnavailableException
    @Test(dataProvider = "groovy-only", expectedExceptions = ServiceUnavailableException.class , enabled = true)
    public void testServiceUnavailableExceptionFromOperationTimeoutException(String systemName) throws Exception {
        DeleteRequest deleteRequest = Requests.newDeleteRequest("system/" + systemName + "/__TEST__/TESTEX_OT");
        connection.delete((new SecurityContext(new RootContext(), "system", null )), deleteRequest);

    }

    // RetryableException -> ServiceUnavailableException
    @Test(dataProvider = "groovy-only", expectedExceptions = ServiceUnavailableException.class , enabled = true)
    public void testServiceUnavailableExceptionFromRetryableException(String systemName) throws Exception {
        CreateRequest createRequest = Requests.newCreateRequest("system/" + systemName + "/__TEST__", getTestConnectorObject("TEST4"));
        connection.create(new SecurityContext(new RootContext(), "system", null), createRequest);
    }

    // ConfigurationException -> InternalServerErrorException
    @Test(dataProvider = "groovy-only", expectedExceptions = InternalServerErrorException.class, enabled = true)
    public void testInternalServerErrorExceptionFromConfigurationException(String systemName) throws Exception {
        DeleteRequest deleteRequest = Requests.newDeleteRequest("system/" + systemName + "/__TEST__/TESTEX_CE");
        connection.delete(new RootContext(), deleteRequest);
    }

    // ConnectionBrokenException -> ServiceUnavailableException
    @Test(dataProvider = "groovy-only", expectedExceptions = ServiceUnavailableException.class, enabled = true)
    public void testServiceUnavailableExceptionFromConnectionBrokenException(String systemName) throws Exception {
        DeleteRequest deleteRequest = Requests.newDeleteRequest("system/" + systemName + "/__TEST__/TESTEX_CB");
        connection.delete(new RootContext(), deleteRequest);
    }

    // ConnectionFailedException -> ServiceUnavailableException
    @Test(dataProvider = "groovy-only", expectedExceptions = ServiceUnavailableException.class, enabled = true)
    public void testServiceUnavailableExceptionFromConnectionFailedException(String systemName) throws Exception {
        DeleteRequest deleteRequest = Requests.newDeleteRequest("system/" + systemName + "/__TEST__/TESTEX_CF");
        connection.delete(new RootContext(), deleteRequest);
    }

    // ConnectorException -> InternalServerErrorException
    @Test(dataProvider = "groovy-only", expectedExceptions = InternalServerErrorException.class, enabled = true)
    public void testInternalServerErrorExceptionFromConnectorException(String systemName) throws Exception {
        DeleteRequest deleteRequest = Requests.newDeleteRequest("system/" + systemName + "/__TEST__/TESTEX_C");
        connection.delete(new RootContext(), deleteRequest);
    }

    // NullPointerException -> InternalServerErrorException
    @Test(dataProvider = "groovy-only", expectedExceptions = InternalServerErrorException.class, enabled = true)
    public void testInternalServerErrorExceptionFromNullPointerException(String systemName) throws Exception {
        DeleteRequest deleteRequest = Requests.newDeleteRequest("system/" + systemName + "/__TEST__/TESTEX_NPE");
        connection.delete(new RootContext(), deleteRequest);
    }

    // IllegalArgumentException -> InternalServerErrorException
    @Test(dataProvider = "groovy-only", expectedExceptions = InternalServerErrorException.class, enabled = true)
    public void testInternalServerErrorExceptionFromIllegalArgumentException(String systemName) throws Exception {
        CreateRequest createRequest = Requests.newCreateRequest("system/" + systemName + "/__TEST__", getTestConnectorObject("TEST3"));
        connection.create(new SecurityContext(new RootContext(), "system", null), createRequest);
    }

    // ConnectorSecurityException -> InternalServerErrorException
    @Test(dataProvider = "groovy-only", expectedExceptions = InternalServerErrorException.class, enabled = true)
    public void testInternalServerErrorExceptionFromConnectorSecurityException(String systemName) throws Exception {
        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName + "/__TEST__", "authenticate");
        actionRequest.setAdditionalParameter("username", "TEST1");
        actionRequest.setAdditionalParameter("password", "Passw0rd");
        connection.action(new RootContext(), actionRequest);
    }

    // InvalidCredentialException - >  PermanentException (UNAUTHORIZED_ERROR_CODE)
    @Test(dataProvider = "groovy-only", expectedExceptions = PermanentException.class, enabled = true)
    public void testPermanentExceptionFromInvalidCredentialException(String systemName) throws Exception {
        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName + "/__TEST__", "authenticate");
        actionRequest.setAdditionalParameter("username", "TEST2");
        actionRequest.setAdditionalParameter("password", "Passw0rd");
        connection.action(new RootContext(), actionRequest);
    }

    // InvalidPasswordException -> PermanentException (UNAUTHORIZED_ERROR_CODE)
    @Test(dataProvider = "groovy-only", expectedExceptions = PermanentException.class, enabled = true)
    public void testPermanentExceptionFromInvalidPasswordException(String systemName) throws Exception {
        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName + "/__TEST__", "authenticate");
        actionRequest.setAdditionalParameter("username", "TEST3");
        actionRequest.setAdditionalParameter("password", "Passw0rd");
        connection.action(new RootContext(), actionRequest);
    }

    // PermissionDeniedException -> ForbiddenException
    @Test(dataProvider = "groovy-only", expectedExceptions = ForbiddenException.class, enabled = true)
    public void testForbiddenExceptionPermissionDeniedException(String systemName) throws Exception {
        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName + "/__TEST__", "authenticate");
        actionRequest.setAdditionalParameter("username", "TEST4");
        actionRequest.setAdditionalParameter("password", "Passw0rd");
        connection.action(new RootContext(), actionRequest);
    }

    // PasswordExpiredException -> ForbiddenException
    @Test(dataProvider = "groovy-only", expectedExceptions = ForbiddenException.class, enabled = true)
    public void testForbiddenExceptionFromPasswordExpiredException(String systemName) throws Exception {
        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName + "/__TEST__", "authenticate");
        actionRequest.setAdditionalParameter("username", "TEST5");
        actionRequest.setAdditionalParameter("password", "Passw0rd");
        connection.action(new RootContext(), actionRequest);
    }

    // UnknownUidException -> NotFoundException
    @Test(dataProvider = "groovy-only", expectedExceptions = NotFoundException.class, enabled = true)
    public void testNotFoundExceptionFromUnknownException(String systemName) throws Exception {
        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName + "/__SAMPLE__", "authenticate");
        actionRequest.setAdditionalParameter("username", "Unknown-UID");
        actionRequest.setAdditionalParameter("password", "Passw0rd");
        connection.action(new RootContext(), actionRequest);
    }

    // UnsupportedOperationException -> NotFoundException
    @Test(dataProvider = "groovy-only", expectedExceptions = NotFoundException.class, enabled = true)
    public void testNotFoundExceptionFromUnsupportedOperationException(String systemName) throws Exception {
        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName + "/Unsupported-Object", "authenticate");
        actionRequest.setAdditionalParameter("username", "TEST6");
        actionRequest.setAdditionalParameter("password", "Passw0rd");
        connection.action(new RootContext(), actionRequest);
    }

    // InvalidAttributeValueException - > BadRequestException
    @Test(dataProvider = "groovy-only", expectedExceptions = BadRequestException.class, enabled = true)
    public void testBadRequestException(String systemName) throws Exception {
        CreateRequest createRequest = Requests.newCreateRequest("system/" + systemName + "/__TEST__", getTestConnectorObject("TEST2"));
        connection.create(new SecurityContext(new RootContext(), "system", null), createRequest);
    }

    // PreconditionFailedException ->  org.forgerock.json.resource.PreconditionFailedException
    @Test(dataProvider = "groovy-only", expectedExceptions = PreconditionFailedException.class, enabled = true)
    public void testPreconditionFailedException(String systemName) throws Exception {
        final String resourceId = "TEST4";
        UpdateRequest updateRequest = Requests.newUpdateRequest("system/" + systemName + "/__TEST__/",
                resourceId,
                getTestConnectorObject(resourceId));
        connection.update(new SecurityContext(new RootContext(), "system", null), updateRequest);
    }

    // PreconditionRequiredException ->  org.forgerock.json.resource.PreconditionRequiredException
    @Test(dataProvider = "groovy-only", expectedExceptions = PreconditionRequiredException.class, enabled = true)
    public void testPreconditionRequiredException(String systemName) throws Exception {
        final String resourceId = "TEST5";
        UpdateRequest updateRequest = Requests.newUpdateRequest("system/" + systemName + "/__TEST__/",
                resourceId,
                getTestConnectorObject(resourceId));
        connection.update(new SecurityContext(new RootContext(), "system", null ), updateRequest);
    }

    // ResourceException ->  org.forgerock.json.resource.ResourceException
    @Test(dataProvider = "groovy-only", expectedExceptions = ResourceException.class, enabled = true)
    public void testResourceException(String systemName) throws Exception {
        final String resourceId = "TEST6";
        JsonValue user = getTestConnectorObject(resourceId);
        user.put("missingKey", "ignoredValue");
        UpdateRequest updateRequest = Requests.newUpdateRequest("system/" + systemName + "/__TEST__/",
                resourceId,
                user);
        connection.update(new SecurityContext(new RootContext(), "system", null ), updateRequest);
    }

    @Test(dataProvider = "groovy-only", enabled = true)
    public void testSyncWithAllObjectClass(String systemName) throws Exception {

        JsonValue stage = new JsonValue(new LinkedHashMap<String, Object>());
        stage.put("connectorData", ConnectorUtil.convertFromSyncToken(new SyncToken(17)));
        CreateRequest createRequest = Requests
                .newCreateRequest("repo/synchronisation/pooledSyncStage",
                        ("system" + systemName).toUpperCase(),
                        stage);
        connection.create(new RootContext(), createRequest);

        SyncStub sync = new SyncStub();
        RouteMatcher<Request> r = router.addRoute(uriTemplate("sync"), sync);

        ActionRequest actionRequest = Requests.newActionRequest("system/" + systemName,
                SystemObjectSetService.SystemAction.liveSync.toString());

        ActionResponse response = connection.action(new RootContext(), actionRequest);

        assertThat(ConnectorUtil.convertToSyncToken(
                response.getJsonContent().get("connectorData")).getValue()).isEqualTo(17);
        assertThat(sync.requests.size()).isEqualTo(0);

        router.removeRoute(r);
    }

    @Test
    public void testBadNativeTypeConfigError() throws Exception {
        final ActionRequest actionRequest = Requests.newActionRequest("system/errorBadNativeType",
                SystemObjectSetService.SystemAction.test.toString());

        final ActionResponse response = connection.action(new RootContext(), actionRequest);
        assertThat(response.getJsonContent().get("ok").asBoolean()).isFalse();
        assertThat(response.getJsonContent().get("error").asString())
                .isEqualTo("Attribute type 'interface java.util.List' is not supported.");
    }

    @Override
    public SyncFailureHandler create(JsonValue config) throws Exception {
        return NullSyncFailureHandler.INSTANCE;
    }
}
