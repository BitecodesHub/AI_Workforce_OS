-- @find: browser grant search_site, existing browser grants, new browser read tool
-- @what: Gives every existing web browser grant that can open pages the read-only search_site tool.
-- @flow: Runs once at orchestrator start (Flyway); grants created later get it from the connect dialog
--
-- search_site arrived after the first browser grants were made, and a grant lists its tools one by
-- one, so an agent given the browser before then could not use it: asked to search a site, it typed
-- into the box and then needed an approval to submit the form. search_site only types the words
-- into the site's own search box and runs the search, a read like open_page, so every grant that
-- may open pages gets it too.
UPDATE agent_tool_grants
SET allowed_tools = array_append(allowed_tools, 'search_site'),
    updated_at = now()
WHERE server = 'browser'
  AND 'open_page' = ANY (allowed_tools)
  AND NOT ('search_site' = ANY (allowed_tools));
