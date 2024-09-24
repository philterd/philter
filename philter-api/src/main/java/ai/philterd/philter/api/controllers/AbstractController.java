package ai.philterd.philter.api.controllers;

import ai.philterd.phileas.model.exceptions.api.ServiceUnavailableException;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URL;

public abstract class AbstractController {

    private static final Logger LOGGER = LogManager.getLogger(AbstractController.class);

    protected String getPythonRESTServiceStatus(String philterNerEndpoint) {

        LOGGER.trace("Retrieving ph-eye status.");

        final HttpClient client = HttpClientBuilder.create().build();

        try {

            final URL url = new URL(philterNerEndpoint);
            final String protocol = url.getProtocol();
            final String host = url.getHost();
            final int port = url.getPort();

            final HttpHost target = new HttpHost(host, port, protocol);

            // specify the get request
            final HttpGet getRequest = new HttpGet("/status");

            final HttpResponse httpResponse = client.execute(target, getRequest);
            final HttpEntity entity = httpResponse.getEntity();

            if (entity != null) {

                final String response = EntityUtils.toString(entity);
                LOGGER.debug("ph-eye status response: {}", response);
                return response;

            } else {

                LOGGER.debug("Received empty response from ph-eye status.");

            }

        } catch (Exception ex) {

            LOGGER.warn("Unable to successfully determine status: {}", ex.getMessage());
            throw new ServiceUnavailableException("Philter is still initializing. If message persists refer to Philter's log file.");

        }

        return "Unhealthy";

    }

}