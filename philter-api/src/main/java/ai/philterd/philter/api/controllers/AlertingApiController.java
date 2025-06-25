/*
 *     Copyright 2025 Philterd, LLC @ https://www.philterd.ai
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
package ai.philterd.philter.api.controllers;

import ai.philterd.phileas.model.exceptions.api.BadRequestException;
import ai.philterd.phileas.model.objects.Alert;
import ai.philterd.phileas.model.services.FilterService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
public class AlertingApiController extends AbstractController {

    private final FilterService filterService;

    @Autowired
    public AlertingApiController(FilterService filterService) {
        this.filterService = filterService;
    }

    @RequestMapping(value = "/api/alerts", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<List<Alert>> getAlerts() {

        final List<Alert> alerts = filterService.getAlertService().getAlerts();

        return ResponseEntity.status(HttpStatus.OK)
                .body(alerts);

    }

    @RequestMapping(value = "/api/alerts/{alertId}", method = RequestMethod.DELETE)
    public @ResponseBody ResponseEntity deleteAlert(@PathVariable(name="alertId") String alertId) {

        if (StringUtils.isEmpty(alertId)) {
            throw new BadRequestException("The alert ID is missing.");
        }

        filterService.getAlertService().delete(alertId);

        return ResponseEntity.status(HttpStatus.OK).build();

    }

}