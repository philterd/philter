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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.theme.lumo.LumoUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;

public abstract class AbstractRestrictedView extends AppLayout implements BeforeEnterObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRestrictedView.class);

    public abstract String getHelpMarkdownText();

    protected final VerticalLayout helpWindowVerticalLayout = new VerticalLayout();
    protected final UserService userService;
    protected final UserEntity userEntity;

    @Override
    public void beforeEnter(BeforeEnterEvent event) {

        if (getCurrentUser() == null) {
            LOGGER.warn("Redirecting to /login because user is null.");
            event.forwardTo(LoginView.class);
        }

    }

    public AbstractRestrictedView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher, final boolean showHelpPanel) {

        this.userService = new UserService(mongoClient, encryptionService, auditEventPublisher);
        this.userEntity = getCurrentUser();

        final Image logoImage = new Image("/images/philter.png", "Philter");
        logoImage.setWidth("35px");
        logoImage.getStyle().set("cursor", "pointer");
        logoImage.addClickListener(click -> {
            UI.getCurrent().navigate(DashboardView.class);
        });

        final Button logoutButton = new Button("Sign Out", VaadinIcon.SIGN_OUT.create(), click -> {
            logout();
        });

        final HorizontalLayout header = new HorizontalLayout();
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.addClassNames(LumoUtility.Padding.Vertical.NONE, LumoUtility.Padding.Horizontal.MEDIUM);
        header.add(logoImage);
        header.add(logoutButton);

        addToNavbar(header);

        final SideNav sideNav = new SideNav();
        sideNav.addItem(new SideNavItem("Dashboard", DashboardView.class, VaadinIcon.DASHBOARD.create()));
        sideNav.addItem(new SideNavItem("Policies", PoliciesView.class, VaadinIcon.FILE_TEXT.create()));
        sideNav.addItem(new SideNavItem("Contexts", ContextsView.class, VaadinIcon.DOCTOR.create()));
        sideNav.addItem(new SideNavItem("Custom Lists", CustomListsView.class, VaadinIcon.LIST.create()));
        sideNav.addItem(new SideNavItem("API and SDKs", ApiKeysAndSDKsView.class, VaadinIcon.COG.create()));
        sideNav.addItem(new SideNavItem("Metrics", MetricsView.class, VaadinIcon.CHART_LINE.create()));

        if ("admin".equalsIgnoreCase(userEntity.getRole())) {
            sideNav.addItem(new SideNavItem("Users", UsersView.class, VaadinIcon.USERS.create()));
            sideNav.addItem(new SideNavItem("Settings", SettingsView.class, VaadinIcon.COG.create()));
        }

        addToDrawer(sideNav);

        if (showHelpPanel) {

            // Add side the panel for the help.
            final Markdown helpMarkdown = new Markdown();
            helpMarkdown.setContent(getHelpMarkdownText());
            //helpMarkdown.setSizeFull();
            helpMarkdown.getElement().getStyle().set("font-size", "var(--lumo-font-size-s)");

            final HorizontalLayout moreHelpHorizontalLayout = new HorizontalLayout();
            //moreHelpHorizontalLayout.add(closeHelpButton);
            // moreHelpHorizontalLayout.add(moreHelpButton);

            helpWindowVerticalLayout.setWidth("800px");
            helpWindowVerticalLayout.getStyle().set("background-color", "#f0f3fa");
            helpWindowVerticalLayout.add(helpMarkdown);

            helpWindowVerticalLayout.add(moreHelpHorizontalLayout);
            //helpWindowVerticalLayout.setVisible(userEntity.isHelpPanelOpen());
            //   helpWindowVerticalLayout.setHeightFull();

        }

    }

    public void logout() {

        final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
        logoutHandler.logout(VaadinServletRequest.getCurrent().getHttpServletRequest(), null, null);

        UI.getCurrent().navigate(LoginView.class);

    }

    public UserEntity getCurrentUser() {

        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        final String email = authentication.getName();

        return userService.findByEmail(email);

    }

    public HorizontalLayout getTitle(final String name) {

        final HorizontalLayout title = new HorizontalLayout();
        title.setPadding(true);
        title.setAlignItems(FlexComponent.Alignment.END);
        title.add(new H2(name));

        helpWindowVerticalLayout.setVisible(true);

        return title;

    }

    public String getClientIpAddress() {
        return VaadinService.getCurrentRequest().getHeader("X-Forwarded-For");
    }

    public void showSuccessNotification(final String message) {

        Notification notification = new Notification(message, 5000);
        notification.setPosition(Notification.Position.BOTTOM_START);
        notification.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        notification.open();

    }

    public void showFailureNotification(final String message) {

        Notification notification = new Notification(message, 5000);
        notification.setPosition(Notification.Position.BOTTOM_START);
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        notification.open();

    }

    public void showWarningNotification(final String message) {

        Notification notification = new Notification(message, 5000);
        notification.setPosition(Notification.Position.BOTTOM_START);
        notification.addThemeVariants(NotificationVariant.LUMO_WARNING);
        notification.open();

    }

}
