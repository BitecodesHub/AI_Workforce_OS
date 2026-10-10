// @find: schedule time zone, workspace timezone lookup, ScheduleZoneLookup, which timezone for schedule
// @what: Looks up the workspace time zone used to read schedule text.
// @flow: Used by ScheduleService.resolveZone
package os.aiworkforce.orchestrator.schedule;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import os.aiworkforce.orchestrator.chat.WorkspaceZoneLookup;
import os.aiworkforce.orchestrator.service.InternalTokenProvider;
import os.aiworkforce.platform.config.PlatformProperties;

/**
 * The timezone a workspace schedules in.
 *
 * <p>The same lookup the chat coordinator and the agent runner use - {@link WorkspaceZoneLookup} -
 * under the name this package has always called it by, and the one registered as a bean, so
 * schedules, chat and the date an agent is told cannot disagree about where a workspace is. It
 * falls back to UTC when the organisation service cannot be reached.
 */
@Component
public class ScheduleZoneLookup extends WorkspaceZoneLookup {

    public ScheduleZoneLookup(WebClient.Builder builder, PlatformProperties properties, InternalTokenProvider tokens) {
        super(builder, properties, tokens);
    }
}
