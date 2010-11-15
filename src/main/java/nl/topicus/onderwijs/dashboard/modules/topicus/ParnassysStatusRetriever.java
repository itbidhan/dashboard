package nl.topicus.onderwijs.dashboard.modules.topicus;

import static nl.topicus.onderwijs.dashboard.modules.topicus.RetrieverUtils.getStatuspage;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.Source;
import nl.topicus.onderwijs.dashboard.datasources.ApplicationVersion;
import nl.topicus.onderwijs.dashboard.datasources.AverageRequestTime;
import nl.topicus.onderwijs.dashboard.datasources.NumberOfServers;
import nl.topicus.onderwijs.dashboard.datasources.NumberOfServersOffline;
import nl.topicus.onderwijs.dashboard.datasources.NumberOfUsers;
import nl.topicus.onderwijs.dashboard.datasources.RequestsPerMinute;
import nl.topicus.onderwijs.dashboard.datasources.ServerAlerts;
import nl.topicus.onderwijs.dashboard.datasources.ServerStatus;
import nl.topicus.onderwijs.dashboard.datasources.Uptime;
import nl.topicus.onderwijs.dashboard.datatypes.Alert;
import nl.topicus.onderwijs.dashboard.datatypes.DotColor;
import nl.topicus.onderwijs.dashboard.keys.Key;
import nl.topicus.onderwijs.dashboard.modules.Repository;
import nl.topicus.onderwijs.dashboard.modules.Settings;

import org.apache.wicket.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ParnassysStatusRetriever implements Retriever,
		TopicusApplicationStatusProvider {
	private static final Logger log = LoggerFactory
			.getLogger(ParnassysStatusRetriever.class);
	private HashMap<Key, TopicusApplicationStatus> statusses = new HashMap<Key, TopicusApplicationStatus>();

	private Map<String, Alert> oldAlerts = new HashMap<String, Alert>();

	public ParnassysStatusRetriever() {
	}

	@Override
	public void onConfigure(Repository repository) {
		Map<Key, Map<String, ?>> serviceSettings = Settings.get()
				.getServiceSettings(ParnassysStatusRetriever.class);
		for (Key project : serviceSettings.keySet()) {
			statusses.put(project, new TopicusApplicationStatus());
			repository.addDataSource(project, RequestsPerMinute.class,
					new RequestsPerMinuteImpl(project, this));
			repository.addDataSource(project, AverageRequestTime.class,
					new AverageRequestTimeImpl(project, this));
			repository.addDataSource(project, NumberOfUsers.class,
					new NumberOfUsersImpl(project, this));
			repository.addDataSource(project, NumberOfServers.class,
					new NumberOfServersImpl(project, this));
			repository.addDataSource(project, NumberOfServersOffline.class,
					new NumberOfServersOfflineImpl(project, this));
			repository.addDataSource(project, Uptime.class, new UptimeImpl(
					project, this));
			repository.addDataSource(project, ApplicationVersion.class,
					new ApplicationVersionImpl(project, this));
			repository.addDataSource(project, ServerStatus.class,
					new ServerStatusImpl(project, this));
			repository.addDataSource(project, ServerAlerts.class,
					new AlertsImpl(project, this));
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void refreshData() {
		HashMap<Key, TopicusApplicationStatus> newStatusses = new HashMap<Key, TopicusApplicationStatus>();
		Map<Key, Map<String, ?>> serviceSettings = Settings.get()
				.getServiceSettings(ParnassysStatusRetriever.class);

		for (Map.Entry<Key, Map<String, ?>> configEntry : serviceSettings
				.entrySet()) {
			Key project = configEntry.getKey();
			List<String> urls = (List<String>) configEntry.getValue().get(
					"urls");
			TopicusApplicationStatus status = getProjectData(project, urls);
			newStatusses.put(project, status);
		}
		statusses = newStatusses;
	}

	private TopicusApplicationStatus getProjectData(Key project,
			List<String> urls) {
		TopicusApplicationStatus status = new TopicusApplicationStatus();
		if (urls == null || urls.isEmpty()) {
			return status;
		}
		int serverIndex = 0;
		List<Alert> alerts = new ArrayList<Alert>();
		for (String statusUrl : urls) {
			TopicusServerStatus server = new TopicusServerStatus(statusUrl);
			status.addServer(server);
			serverIndex++;
			Alert oldAlert = oldAlerts.get(statusUrl);
			try {
				StatusPageResponse statuspage = getStatuspage(statusUrl);
				if (statuspage.isOffline()) {
					server.setServerStatus(DotColor.RED);
					Alert alert = new Alert(oldAlert, DotColor.RED, project,
							"Server " + serverIndex + " offline");
					oldAlerts.put(statusUrl, alert);
					alerts.add(alert);
					continue;
				}
				String page = statuspage.getPageContent();

				Source source = new Source(page);

				source.fullSequentialParse();

				List<Element> tableHeaders = source.getAllElements("class",
						"main_label", true);
				for (Element tableHeader : tableHeaders) {
					String contents = tableHeader.getContent().toString();
					if ("Actieve sessies:".equals(contents)) {
						fetchNumberOfUsers(server, tableHeader);
					} else if ("Gestart op:".equals(contents)) {
						fetchStartTijd(server, tableHeader);
					} else if ("Gemiddelde requesttijd:".equals(contents)) {
						fetchAvgRequestTime(server, tableHeader);
					} else if ("Requests per minuut:".equals(contents)) {
						fetchRequestsPerMinute(server, tableHeader);
					}
				}
				server.setServerStatus(DotColor.GREEN);
				oldAlerts.put(statusUrl, null);
			} catch (Exception e) {
				server.setServerStatus(DotColor.YELLOW);
				Alert alert = new Alert(oldAlert, DotColor.YELLOW, project, e
						.getMessage());
				oldAlerts.put(statusUrl, alert);
				alerts.add(alert);
				log.warn("Could not retrieve status for '" + statusUrl + "': "
						+ e.getClass().getSimpleName() + " - "
						+ e.getLocalizedMessage());
			}
		}
		status.setAlerts(alerts);
		log.info("Application status: {}->{}", project, status);
		return status;
	}

	private void fetchNumberOfUsers(TopicusServerStatus server,
			Element tableHeader) {
		Element sessiesCell = tableHeader.getParentElement().getContent()
				.getChildElements().get(1);

		String tdContents = sessiesCell.getTextExtractor().toString();
		Integer numberOfUsersOnServer = Integer.valueOf(tdContents);
		server.setNumberOfUsers(numberOfUsersOnServer);
	}

	private void fetchStartTijd(TopicusServerStatus server, Element tableHeader) {
		Element starttijdCell = tableHeader.getParentElement().getContent()
				.getChildElements().get(1);
		String starttijdText = starttijdCell.getTextExtractor().toString();

		SimpleDateFormat sdf = new SimpleDateFormat("E MMM dd HH:mm:ss Z yyyy");
		try {
			Date starttime = sdf.parse(starttijdText);
			Date now = new Date();
			server.setUptime(Duration.milliseconds(
					now.getTime() - starttime.getTime()).getMilliseconds());
		} catch (ParseException e) {
			log.error("Unable to parse starttime " + starttijdText
					+ " according to format dd MMMM yyyy, hh:mm", e);
		}
	}

	private void fetchAvgRequestTime(TopicusServerStatus server,
			Element tableHeader) {
		Element sessiesCell = tableHeader.getParentElement().getContent()
				.getChildElements().get(1);

		String tdContents = sessiesCell.getTextExtractor().toString();
		int space = tdContents.indexOf(' ');
		if (space > -1) {
			tdContents = tdContents.substring(0, space);
		}
		int avgRequestTime = Integer.parseInt(tdContents);
		server.setAverageRequestDuration(avgRequestTime);
	}

	private void fetchRequestsPerMinute(TopicusServerStatus server,
			Element tableHeader) {
		Element sessiesCell = tableHeader.getParentElement().getContent()
				.getChildElements().get(1);

		String tdContents = sessiesCell.getTextExtractor().toString();
		Integer requestsPerMinute = Integer.valueOf(tdContents);
		server.setRequestsPerMinute(requestsPerMinute);
	}

	@Override
	public TopicusApplicationStatus getStatus(Key project) {
		return statusses.get(project);
	}
}
