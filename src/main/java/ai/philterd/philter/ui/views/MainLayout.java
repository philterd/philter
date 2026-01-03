package ai.philterd.philter.ui.views;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.theme.lumo.LumoUtility;

public class MainLayout extends AppLayout {

    public MainLayout() {

        final Image logo = new Image("img/philter-logo-transparent.png", "Philter");
        logo.setHeight("75px");

        final Anchor logoLinkAnchor = new Anchor("/", logo);

        final HorizontalLayout header = new HorizontalLayout(logoLinkAnchor);
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.addClassNames(LumoUtility.Padding.Vertical.NONE, LumoUtility.Padding.Horizontal.MEDIUM);

        addToNavbar(header);

        final SideNav sideNav = new SideNav();
        sideNav.addItem(new SideNavItem("Dashboard", DashboardView.class, VaadinIcon.DASHBOARD.create()));
        sideNav.addItem(new SideNavItem("Filter Text", FilterTextView.class, VaadinIcon.FILTER.create()));
        sideNav.addItem(new SideNavItem("Filter PDF", FilterPdfView.class, VaadinIcon.FILE_TEXT.create()));
        sideNav.addItem(new SideNavItem("Policies", PoliciesView.class, VaadinIcon.FILE_TEXT.create()));

        addToDrawer(sideNav);

    }

}
