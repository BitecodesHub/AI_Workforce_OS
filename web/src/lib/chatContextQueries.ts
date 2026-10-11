// @find: repo picker queries, GitHub repositories for chat, branches, repository tree, attach a repository, GET /api/conversations/context/github/repos
// @what: Read-only queries behind the composer's "Attach a repository" picker.
// @flow: Used by components/chat/ContextPickers (RepoPicker); talks to the orchestrator's GitHubContextController.
import { useQuery } from '@tanstack/react-query'
import { api } from './api'

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
export const useGitHubRepos = (enabled: boolean) =>
  useQuery({
    queryKey: ['chat-context', 'github', 'repos'],
    queryFn: ({ signal }) => api<GitHubRepos>(`${BASE}/repos`, { signal }),
    enabled,
    staleTime: 60_000,
  })

// @find: list branches of a repository for the repo picker
export const useGitHubBranches = (repo: string | null) =>
  useQuery({
    queryKey: ['chat-context', 'github', 'branches', repo],
    queryFn: ({ signal }) =>
      api<{ branches: string[] }>(`${BASE}/branches?repo=${encodeURIComponent(repo ?? '')}`, { signal }),
    enabled: Boolean(repo),
    staleTime: 60_000,
  })

// @find: list folders of a repository for the repo picker
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
