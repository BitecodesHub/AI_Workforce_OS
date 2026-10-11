// @find: skills, playbooks, reusable instructions, skills page, new skill, edit skill, duplicate skill, delete skill, ready-made skill, use in chat, /skills, Skills page
// @what: The Skills page: the workspace's reusable playbooks, searchable, with create, edit, duplicate, delete and a link to use one in chat.
// @flow: Routed from App.tsx at /skills; uses lib/skillQueries and components/skills/SkillEditorDialog; "Use in chat" opens /chat?skill=<id>.
import { useState } from 'react'
import { Button, Card, ConfirmDialog, EmptyState, PageHeader, Tag } from '../components/ui'
import { FilterBar, FilterEmpty } from '../components/ui/FilterBar'
import { EmptyIcon, QueryState } from '../components/ui/QueryState'
import { SkillEditorDialog } from '../components/skills/SkillEditorDialog'
import { describeApiError } from '../lib/api'
import { useIntegrations } from '../lib/queries'
import { can } from '../lib/session'
import { canManageSkills, useDeleteSkill, useDuplicateSkill, useSkills } from '../lib/skillQueries'
import type { Skill } from '../lib/skillQueries'
import { useToast } from '../lib/toast'
import { useListFilter } from '../lib/useListFilter'

/*
 * Skills are written once and applied to any chat message from the composer's + menu. Everyone
 * who can chat sees the list and can open one in chat; only someone who can change agents may
 * add, change, copy or remove one, and for everyone else the page is read-only.
 */

/** Everything a search should find a skill by. */
function skillSearchText(skill: Skill, connectorName: (id: string) => string): string {
  return [
    skill.name,
    skill.description,
    skill.defaultAgentName ?? '',
    ...skill.requiredConnectors.map(connectorName),
    skill.builtIn ? 'ready-made' : '',
  ].join(' ')
}

// @find: skill card, one skill, skill actions
/**
 * One skill as a card: name, a Ready-made tag for built-in skills, its description, the agent it defaults to ("Routed as usual" when none), and the connectors it needs. Everyone gets Use in chat, a link to /chat?skill=<id> that attaches it to the composer. People who can manage skills also get Edit, Duplicate (shows a busy state while copying) and Delete.
 */
function SkillCard({
  skill,
  canManage,
  connectorName,
  onEdit,
  onDuplicate,
  onDelete,
  duplicating,
}: {
  skill: Skill
  canManage: boolean
  connectorName: (id: string) => string
  onEdit: () => void
  onDuplicate: () => void
  onDelete: () => void
  duplicating: boolean
}) {
  return (
    <Card as="article" className="skill-card">
      <div className="skill-card-head">
        <h2 className="skill-card-title">
          {skill.name}
        </h2>
        {skill.builtIn && <Tag tone="blue">Ready-made</Tag>}
      </div>
      {skill.description ? (
        <p className="skill-card-description">{skill.description}</p>
      ) : (
        <p className="skill-card-description muted">No description.</p>
      )}
      <dl className="skill-card-facts">
        <div>
          <dt className="caption muted">Goes to</dt>
          <dd>{skill.defaultAgentName ?? 'Routed as usual'}</dd>
        </div>
        <div>
          <dt className="caption muted">Needs</dt>
          <dd>
            {skill.requiredConnectors.length === 0 ? (
              <span className="muted">No connectors</span>
            ) : (
              <span className="skill-card-tags">
                {skill.requiredConnectors.map((id) => (
                  <Tag key={id}>{connectorName(id)}</Tag>
                ))}
              </span>
            )}
          </dd>
        </div>
      </dl>
      <div className="skill-card-actions">
        <a className="button button-primary button-sm" href={`/chat?skill=${encodeURIComponent(skill.id)}`} aria-label={`Use ${skill.name} in chat`}>
          Use in chat
        </a>
        {canManage && (
          <>
            <Button variant="outline" className="button-sm" aria-label={`Edit ${skill.name}`} onClick={onEdit}>
              Edit
            </Button>
            <Button
              variant="outline"
              className="button-sm"
              aria-label={`Duplicate ${skill.name}`}
              onClick={onDuplicate}
              loading={duplicating}
            >
              Duplicate
            </Button>
            <Button variant="danger" className="button-sm" aria-label={`Delete ${skill.name}`} onClick={onDelete}>
              Delete
            </Button>
          </>
        )}
      </div>
    </Card>
  )
}

// @find: Skills component, skills page, /skills
/**
 * The Skills page (route /skills, permission chat:use): reusable playbooks applied to chat messages from the composer's + menu.
 * Reads the skills (useSkills) and, with integration:read, connector names for the required-connector tags. Search runs over name, description, default agent, required connectors and the ready-made flag. Anyone who can chat sees the list; managing (New skill, Edit, Duplicate, Delete) needs the permission checked by canManageSkills. Duplicate creates a copy through useDuplicateSkill, toasts, and opens it in the editor; Delete asks for confirmation, calls useDeleteSkill and toasts, with failures shown in the dialog or as a toast. The empty state differs for people who cannot create skills.
 */
export function Skills() {
  const canManage = canManageSkills(can)
  const toast = useToast()
  const query = useSkills()
  const integrations = useIntegrations({ enabled: can('integration:read') })
  const duplicate = useDuplicateSkill()
  const deleteSkill = useDeleteSkill()

  const [editorTarget, setEditorTarget] = useState<'create' | Skill | null>(null)
  const [deleting, setDeleting] = useState<Skill | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const names = new Map((integrations.data ?? []).map((item) => [item.server, item.displayName || item.server]))
  const connectorName = (id: string) => names.get(id) ?? id

  const filter = useListFilter({ rows: query.data, text: (skill) => skillSearchText(skill, connectorName) })

  const handleDuplicate = async (skill: Skill) => {
    try {
      const copy = await duplicate.mutateAsync(skill.id)
      toast.success(`${copy.name} was created from ${skill.name}`)
      setEditorTarget(copy)
    } catch (err) {
      toast.error(describeApiError(err))
    }
  }

  const closeDelete = () => {
    setDeleting(null)
    setDeleteError(null)
  }

  const handleDelete = async () => {
    if (!deleting) return
    setDeleteError(null)
    try {
      await deleteSkill.mutateAsync(deleting.id)
      toast.success(`${deleting.name} was deleted`)
      closeDelete()
    } catch (err) {
      setDeleteError(describeApiError(err))
    }
  }

  return (
    <div className="page">
      <PageHeader
        eyebrow="Reusable playbooks"
        title="Skills"
        description="Reusable playbooks you can apply to any chat message from the + menu. Write the steps once, and every agent follows them the same way."
        action={canManage ? <Button onClick={() => setEditorTarget('create')}>New skill</Button> : undefined}
      />

      <QueryState
        query={query}
        permission="chat:use"
        what="the skills list"
        isEmpty={(rows) => rows.length === 0}
        empty={
          <Card as="section">
            <EmptyState
              icon={<EmptyIcon kind="document" />}
              title="No skills yet"
              body={
                canManage
                  ? 'Write your first skill: a set of instructions anyone can apply to a chat message.'
                  : 'Nobody has written a skill for this workspace yet.'
              }
              action={canManage ? <Button onClick={() => setEditorTarget('create')}>New skill</Button> : undefined}
            />
          </Card>
        }
        rows={4}
      >
        {(skills) => (
          <div className="skills-body">
            <FilterBar
              searchLabel="Search skills"
              query={filter.query}
              onQueryChange={filter.setQuery}
              placeholder="Name, description, agent or connector"
              shown={filter.filtered.length}
              total={skills.length}
              active={filter.active}
              onClear={filter.clear}
            />
            {!canManage && (
              <p className="caption muted">Your role can use these skills in chat but not change them.</p>
            )}
            {filter.filtered.length === 0 ? (
              <Card>
                <FilterEmpty onClear={filter.clear} what="skills" />
              </Card>
            ) : (
              <div className="skills-grid">
                {filter.filtered.map((skill) => (
                  <SkillCard
                    key={skill.id}
                    skill={skill}
                    canManage={canManage}
                    connectorName={connectorName}
                    onEdit={() => setEditorTarget(skill)}
                    onDuplicate={() => void handleDuplicate(skill)}
                    onDelete={() => {
                      setDeleting(skill)
                      setDeleteError(null)
                    }}
                    duplicating={duplicate.isPending && duplicate.variables === skill.id}
                  />
                ))}
              </div>
            )}
          </div>
        )}
      </QueryState>

      {canManage && (
        <SkillEditorDialog
          open={editorTarget !== null}
          onClose={() => setEditorTarget(null)}
          skill={editorTarget === 'create' ? null : editorTarget}
        />
      )}

      <ConfirmDialog
        open={deleting !== null}
        onClose={closeDelete}
        onConfirm={handleDelete}
        eyebrow="Delete skill"
        title={`Delete “${deleting?.name ?? ''}”?`}
        description="It disappears from the + menu for everyone. Messages it was already applied to are not affected."
        confirmLabel="Delete skill"
        cancelLabel="Keep it"
        tone="danger"
        loading={deleteSkill.isPending}
        error={deleteError}
      />
    </div>
  )
}
