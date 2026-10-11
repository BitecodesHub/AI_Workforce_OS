// @find: repo picker queries, GitHub repositories for chat, branches, repository tree, attach a repository, GET /api/conversations/context/github/repos
// @what: Read-only queries behind the composer's "Attach a repository" picker.
// @flow: Used by components/chat/ContextPickers (RepoPicker); talks to the orchestrator's GitHubContextController.
import { useQuery } from '@tanstack/react-query'
import { api } from './api'

/** One repository offered by the picker, as the orchestrator describes it. */
export type GitHubRepo = {
  fullName: string
  owner: string
  name: string
  private: boolean
  description: string
  defaultBranch: string
}

/** `connected` false means no live GitHub connection; `message` says why in plain words. */
export type GitHubRepos = { connected: boolean; message: string | null; repos: GitHubRepo[] }

/** `available` false means the connector cannot list folders, and a path is typed instead. */
export type GitHubTree = { available: boolean; entries: { path: string; type: string }[] }

const BASE = '/api/conversations/context/github'

// @find: list GitHub repositories for the repo picker
/**
 * Query listing the repositories the connected GitHub account can see (GET .../context/github/repos). Cached for a minute; `enabled` gates it until the picker opens. When there is no live GitHub connection the answer has connected false and a plain message.
 */
export const useGitHubRepos = (enabled: boolean) =>
  useQuery({
    queryKey: ['chat-context', 'github', 'repos'],
    queryFn: ({ signal }) => api<GitHubRepos>(`${BASE}/repos`, { signal }),
    enabled,
    staleTime: 60_000,
  })

// @find: list branches of a repository for the repo picker
/**
 * Query listing the branches of one repository (GET .../github/branches?repo=). Disabled until a repository is chosen; cached for a minute.
 */
export const useGitHubBranches = (repo: string | null) =>
  useQuery({
    queryKey: ['chat-context', 'github', 'branches', repo],
    queryFn: ({ signal }) =>
      api<{ branches: string[] }>(`${BASE}/branches?repo=${encodeURIComponent(repo ?? '')}`, { signal }),
    enabled: Boolean(repo),
    staleTime: 60_000,
  })

// @find: list folders of a repository for the repo picker
/**
 * Query listing the folders of a repository at a branch and path (GET .../github/tree). Disabled until a repository is chosen. The answer's available flag is false when the connector cannot list folders, and the picker then asks for a typed path.
 */
export const useGitHubTree = (repo: string | null, ref: string | null, path: string | null) =>
  useQuery({
    queryKey: ['chat-context', 'github', 'tree', repo, ref, path],
    queryFn: ({ signal }) => {
      const params = new URLSearchParams({ repo: repo ?? '' })
      if (ref) params.set('ref', ref)
      if (path) params.set('path', path)
      return api<GitHubTree>(`${BASE}/tree?${params.toString()}`, { signal })
    },
    enabled: Boolean(repo),
    staleTime: 60_000,
  })
