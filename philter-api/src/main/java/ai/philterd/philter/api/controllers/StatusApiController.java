package ai.philterd.philter.api.controllers;

import ai.philterd.phileas.model.configuration.PhileasConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.util.Properties;

@Controller
public class StatusApiController extends AbstractController {

  private static final Logger LOGGER = LogManager.getLogger(StatusApiController.class);

  private static final String NER_UNHEALTHY_OR_INITIALIZING = "Philter is currently initializing or unhealthy if status persists.";

  private final PhileasConfiguration phileasConfiguration;

  @Autowired
  public StatusApiController(PhileasConfiguration phileasConfiguration) {
    this.phileasConfiguration = phileasConfiguration;
  }

  @RequestMapping(value="/api/status", method=RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> status() throws IOException {

    return new ResponseEntity<>("healthy: " + getVersion(), HttpStatus.OK);


  }

  private String getVersion() throws IOException {

    final Properties properties = new Properties();
    properties.load(StatusApiController.this.getClass().getClassLoader().getResourceAsStream("internal.properties"));
    return properties.getProperty("build.version");

  }

}
