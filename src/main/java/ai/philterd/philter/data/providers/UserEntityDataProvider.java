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
package ai.philterd.philter.data.providers;

import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.UserService;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.Query;

import java.util.stream.Stream;

/**
 * Used by the grid on the users view for paging users.
 */
public class UserEntityDataProvider extends AbstractBackEndDataProvider<UserEntity, Void> {

    private final UserService userService;

    public UserEntityDataProvider(final UserService userService) {
        this.userService = userService;
    }

    @Override
    protected Stream<UserEntity> fetchFromBackEnd(final Query<UserEntity, Void> query) {
        final int offset = query.getOffset();
        final int limit = query.getLimit();
        return userService.findAll(offset, limit).stream();
    }

    @Override
    protected int sizeInBackEnd(final Query<UserEntity, Void> query) {
        return userService.count();
    }

}
