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

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.theme.lumo.LumoUtility;

public abstract class AbstractView extends AppLayout {

    public abstract String getHelpMarkdownText();

    protected final VerticalLayout helpWindowVerticalLayout = new VerticalLayout();

    public AbstractView(final boolean showHelpPanel) {

        final Image logo = new Image("public/philter.png", "Philter");
        logo.setHeight("75px");

        final Anchor logoLinkAnchor = new Anchor("/", logo);

        final HorizontalLayout header = new HorizontalLayout(logoLinkAnchor);
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.addClassNames(LumoUtility.Padding.Vertical.NONE, LumoUtility.Padding.Horizontal.MEDIUM);

        addToNavbar(header);

        final SideNav sideNav = new SideNav();
        sideNav.addItem(new SideNavItem("Dashboard", DashboardView.class, VaadinIcon.DASHBOARD.create()));
        sideNav.addItem(new SideNavItem("Policies", PoliciesView.class, VaadinIcon.FILE_TEXT.create()));
        sideNav.addItem(new SideNavItem("Contexts", ContextsView.class, VaadinIcon.DOCTOR.create()));
        sideNav.addItem(new SideNavItem("Custom Lists", CustomListsView.class, VaadinIcon.LIST.create()));
        sideNav.addItem(new SideNavItem("API", ApiView.class, VaadinIcon.COG.create()));
        sideNav.addItem(new SideNavItem("Metrics", MetricsView.class, VaadinIcon.CHART_LINE.create()));
        sideNav.addItem(new SideNavItem("Client SDKs", SdksView.class, VaadinIcon.WRENCH.create()));

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

    protected VerticalLayout getFooter() {

        final VerticalLayout footer = new VerticalLayout();
        footer.setWidthFull();
        footer.setAlignItems(FlexComponent.Alignment.CENTER);
        footer.setPadding(true);

        final Image logo = new Image("philterd.png", "Philterd");

        final Anchor link = new Anchor("https://www.philterd.ai", logo);
        link.setTarget("_blank");

        final Span copyright = new Span("Copyright 2026 © Philterd, LLC. \"Philter\" is a registered trademark of Philterd, LLC.");
        copyright.getStyle().set("font-size", "0.8rem");
        copyright.getStyle().set("color", "#858796");

        footer.add(link, copyright);

        return footer;

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
