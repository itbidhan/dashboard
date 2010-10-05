package nl.topicus.onderwijs.dashboard.web.pages;

import nl.topicus.onderwijs.dashboard.web.components.statustable.StatusTablePanel;

import org.apache.wicket.PageParameters;
import org.apache.wicket.markup.html.WebPage;

public class DashboardPage extends WebPage {

	private static final long serialVersionUID = 1L;

	public DashboardPage(final PageParameters parameters) {
		StatusTablePanel table = new StatusTablePanel("table");
		add(table);
	}
}
