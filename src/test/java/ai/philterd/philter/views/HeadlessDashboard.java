/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.views;

import ai.philterd.philter.data.entities.UserEntity;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.router.Router;
import com.vaadin.flow.server.Constants;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Enough of Vaadin to build a dashboard view in a plain unit test.
 *
 * <p>The views were untested because constructing one needs a {@link VaadinService}: the side
 * navigation resolves each destination through the router, so a bare {@code new SomeView(...)} throws
 * before any assertion can run. That is a fixture problem, not a reason to leave the dashboard
 * unverified, and this is the fixture. Three things are needed and nothing else:
 *
 * <ul>
 *   <li>a route registry that knows the views, so {@code SideNavItem} can resolve a URL;</li>
 *   <li>the default safe URL schemes, since a mocked configuration reports none and the footer's
 *       {@code https://} support link is then rejected;</li>
 *   <li>an authenticated principal, because every restricted view resolves its user in the
 *       constructor.</li>
 * </ul>
 *
 * <p>Close it to release the static mock and clear the security context:
 *
 * <pre>try (final HeadlessDashboard dashboard = HeadlessDashboard.signedInAs("a@example.com")) { ... }</pre>
 */
final class HeadlessDashboard implements AutoCloseable {

    /** Registered once: the registry is keyed on the servlet context and rejects a duplicate route. */
    private static final ApplicationRouteRegistry REGISTRY = routeRegistry();

    private final MockedStatic<VaadinService> vaadinService;

    private HeadlessDashboard(final String username) {

        final VaadinService service = mock(VaadinService.class, RETURNS_DEEP_STUBS);
        when(service.getRouter()).thenReturn(new Router(REGISTRY));
        when(service.getDeploymentConfiguration().getUrlSafeSchemes())
                .thenReturn(Constants.DEFAULT_URL_SAFE_SCHEMES);

        vaadinService = mockStatic(VaadinService.class);
        vaadinService.when(VaadinService::getCurrent).thenReturn(service);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "password"));

        // A current UI, which several pages reach for while building.
        UI.setCurrent(new UI());

    }

    /** A dashboard whose signed-in principal is the given username. */
    static HeadlessDashboard signedInAs(final String username) {
        return new HeadlessDashboard(username);
    }

    @Override
    public void close() {
        UI.setCurrent(null);
        vaadinService.close();
        SecurityContextHolder.clearContext();
    }

    private static ApplicationRouteRegistry routeRegistry() {

        final ApplicationRouteRegistry registry =
                ApplicationRouteRegistry.getInstance(new VaadinServletContext(new MockServletContext()));
        final RouteConfiguration configuration = RouteConfiguration.forRegistry(registry);

        final List<Class<? extends Component>> views = List.of(
                DashboardView.class, PoliciesView.class, CustomListsView.class, RedactListsView.class,
                ContextsView.class, LedgerView.class, HoldsView.class, AccountView.class,
                AdminView.class, LoginView.class, ChangePasswordView.class, MfaChallengeView.class);

        for (final Class<? extends Component> view : views) {
            configuration.setAnnotatedRoute(view);
        }

        return registry;

    }

    /** A Mongo client answering mocks, so a view's data services can be constructed. */
    static MongoClient mongoClient() {
        final MongoClient client = mock(MongoClient.class, RETURNS_DEEP_STUBS);
        ai.philterd.philter.testutil.MongoSchemaMocks.configure(client.getDatabase("philter").getCollection("users"));
        return client;
    }

    /**
     * A Mongo client that answers the signed-in user. Every restricted view resolves its user through
     * {@code UserService} in the constructor and then scopes its queries by that id, so a client that
     * answers nothing gives every view a null user and an immediate failure.
     */
    static MongoClient mongoClientReturning(final UserEntity user) {

        final MongoClient client = mock(MongoClient.class, RETURNS_DEEP_STUBS);

        final Document document = new Document()
                .append("_id", user.getId())
                .append("username", user.getUsername())
                .append("email", user.getEmail())
                .append("role", user.getRole())
                .append("password_change_required", user.isPasswordChangeRequired());

        ai.philterd.philter.testutil.MongoSchemaMocks.configure(client.getDatabase(anyString()).getCollection(anyString()));
        when(client.getDatabase(anyString()).getCollection(anyString()).find(any(Bson.class)).first())
                .thenReturn(document);

        return client;

    }

    /** A user as the dashboard sees one. */
    static UserEntity user(final String username, final String role) {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setUsername(username);
        user.setEmail(username);
        user.setRole(role);
        return user;
    }

}
