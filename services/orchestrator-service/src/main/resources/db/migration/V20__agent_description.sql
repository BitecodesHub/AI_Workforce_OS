-- What an agent does, in one line written about it ("Screens applications and books interviews"),
-- for its card, its page and the chat chips. The instructions are written to the agent in the
-- second person ("You triage...") and read oddly quoted back to the person choosing it.
--
-- Null means none has been written; the console then derives a line from the instructions.
-- Ready-made assistants (AgentTemplates' description) and the General Employee get one when they
-- are created. Agents that
-- already exist get the matching line only while their instructions are still the ready-made
-- ones, so an agent somebody rewrote is not given a description of what it used to do.

ALTER TABLE agents ADD COLUMN description TEXT;
ALTER TABLE agents ADD CONSTRAINT agents_description_length CHECK (char_length(description) <= 200);

UPDATE agents a SET description = d.description
FROM (VALUES
    ('hr', 'You handle people operations%',
     'Checks applications against the role, drafts the welcome email and books interviews in the calendar.'),
    ('engineering-manager', 'You keep an engineering team''s tickets current%',
     'Keeps tickets up to date, sums up open pull requests and writes the daily standup note, so nobody has to.'),
    ('research', 'You compile market and competitor reports%',
     'Pulls together what your own documents already say, points out what is missing and writes the summary into a new document.'),
    ('support', 'You triage support tickets%',
     'Sorts the overnight queue, drafts replies from your support handbook and passes on anything it cannot answer.')
) AS d (key_prefix, prompt_prefix, description)
WHERE a.description IS NULL
  AND NOT a.is_fallback
  AND (a.key = d.key_prefix OR a.key ~ ('^' || d.key_prefix || '-[0-9]+$'))
  AND EXISTS (
      SELECT 1 FROM agent_versions v
      WHERE v.id = a.current_version_id AND v.system_prompt LIKE d.prompt_prefix
  );

UPDATE agents a SET description =
    'Takes any request no specialist covers: questions, explanations, drafting, planning and analysis.'
WHERE a.is_fallback AND a.description IS NULL;
