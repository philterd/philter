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
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
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

    protected final UserService userService;
    protected final UserEntity userEntity;
    protected final AuditEventPublisher auditEventPublisher;

    @Override
    public void beforeEnter(BeforeEnterEvent event) {

        final UserEntity currentUser = getCurrentUser();

        if (currentUser == null) {
            LOGGER.warn("Redirecting to /login because user is null.");
            event.forwardTo(LoginView.class);
            return;
        }

        // Force a password change before any other restricted view is accessible.
        if (currentUser.isPasswordChangeRequired()) {
            event.forwardTo(ChangePasswordView.class);
        }

    }

    public AbstractRestrictedView(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {

        this.userService = new UserService(mongoClient, encryptionService, auditEventPublisher);
        this.auditEventPublisher = auditEventPublisher;
        this.userEntity = getCurrentUser();

        final Image logoImage = new Image("/public/philter.png", "Philter");
        logoImage.setWidth("150px");
        logoImage.getStyle().set("cursor", "pointer");
        logoImage.addClickListener(click -> {
            UI.getCurrent().navigate(DashboardView.class);
        });

        final Button logoutButton = new Button("Sign Out", VaadinIcon.SIGN_OUT.create(), click -> {
            logout();
        });

        // An expanding spacer between the logo and Sign Out pushes Sign Out to the far right of the bar
        // so it isn't crowded next to the logo.
        final Div spacer = new Div();

        final HorizontalLayout header = new HorizontalLayout();
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.addClassNames(LumoUtility.Padding.Vertical.NONE, LumoUtility.Padding.Horizontal.MEDIUM);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setPadding(true);
        header.add(logoImage, spacer, logoutButton);
        header.expand(spacer);
        // Separate the top bar from the navigation below it with a subtle divider.
        header.getStyle().set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        addToNavbar(header);

        // Dashboard sits on its own at the top. The top margin gives the navigation breathing room so
        // the first item isn't jammed up against the logo/top bar.
        final SideNav mainNav = new SideNav();
        mainNav.getStyle().set("margin-top", "var(--lumo-space-m)");
        mainNav.addItem(new SideNavItem("Dashboard", DashboardView.class, VaadinIcon.DASHBOARD.create()));
        addToDrawer(mainNav);

        // Everything that configures or records a redaction.
        final SideNav redactionNav = new SideNav("Redaction");
        redactionNav.addItem(new SideNavItem("Redaction Policies", PoliciesView.class, VaadinIcon.FILE_TEXT.create()));
        redactionNav.addItem(new SideNavItem("Custom Lists", CustomListsView.class, VaadinIcon.LIST.create()));
        redactionNav.addItem(new SideNavItem("Always/Never Redact Lists", RedactListsView.class, VaadinIcon.TAGS.create()));
        redactionNav.addItem(new SideNavItem("Contexts", ContextsView.class, VaadinIcon.RECORDS.create()));
        redactionNav.addItem(new SideNavItem("Redaction Ledger", LedgerView.class, VaadinIcon.BOOK.create()));
        addToDrawer(redactionNav);

        // Per-user account and integration settings.
        final SideNav accountNav = new SideNav("Account");
        accountNav.addItem(new SideNavItem("SDKs", SdksView.class, VaadinIcon.CODE.create()));
        accountNav.addItem(new SideNavItem("My Account", AccountView.class, VaadinIcon.USER.create()));
        addToDrawer(accountNav);

        if (isAdmin()) {
            final SideNav adminNav = new SideNav("Administration");
            adminNav.addItem(new SideNavItem("Admin", AdminView.class, VaadinIcon.USER_STAR.create()));
            addToDrawer(adminNav);
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

    /**
     * Whether the signed-in user is an administrator. This is the single source of truth for the
     * admin role check used to gate admin-only UI (e.g. the "All …" tabs and the Admin menu item),
     * so the role string is not duplicated across views.
     */
    public boolean isAdmin() {
        return userEntity != null && "admin".equalsIgnoreCase(userEntity.getRole());
    }

    public HorizontalLayout getTitle(final String name) {

        final HorizontalLayout title = new HorizontalLayout();
        title.setPadding(true);
        title.setAlignItems(FlexComponent.Alignment.END);
        title.add(new H2(name));

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
