import { useState, useEffect, useCallback } from 'react'
import { getRepositories, getRepository, searchEndpoints } from '../api/client'
import type { RepositorySummary, RepositoryDetail, SearchResultItem } from '../api/client'
import { EndpointDetails } from '../components/EndpointDetails'

const METHODS = ['', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e', POST: '#6366f1', PUT: '#f59e0b', DELETE: '#ef4444', PATCH: '#8b5cf6',
}

function useDebounce<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(t)
  }, [value, delay])
  return debounced
}

export default function Catalog() {
  // Repo list + expansion
  const [repos, setRepos] = useState<RepositorySummary[]>([])
  const [openDetails, setOpenDetails] = useState<Set<number>>(new Set())
  const toggleEp = (id: number) =>
    setOpenDetails(prev => { const s = new Set(prev); s.has(id) ? s.delete(id) : s.add(id); return s })

  const [loadingRepos, setLoadingRepos] = useState(true)
  const [selected, setSelected] = useState<RepositoryDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  // Search filters
  const [filterPath,      setFilterPath]      = useState('')
  const [filterMethod,    setFilterMethod]    = useState('')
  const [filterFramework, setFilterFramework] = useState('')
  const [filterRepo,      setFilterRepo]      = useState('')

  // Search results
  const [searchResults,  setSearchResults]  = useState<SearchResultItem[] | null>(null)
  const [searching,      setSearching]      = useState(false)
  const [error,          setError]          = useState<string | null>(null)

  const debouncedPath      = useDebounce(filterPath,      350)
  const debouncedFramework = useDebounce(filterFramework, 350)
  const debouncedRepo      = useDebounce(filterRepo,      350)

  const isFiltering = !!(debouncedPath || filterMethod || debouncedFramework || debouncedRepo)

  // Load repos on mount
  useEffect(() => {
    getRepositories()
      .then(setRepos)
      .catch(e => setError(e.message))
      .finally(() => setLoadingRepos(false))
  }, [])

  // Unique framework list for dropdown
  const frameworks = Array.from(new Set(repos.map(r => r.framework))).sort()

  // Run search whenever a debounced/immediate filter changes
  useEffect(() => {
    if (!isFiltering) { setSearchResults(null); return }
    setSearching(true)
    setError(null)
    searchEndpoints({
      path:      debouncedPath      || undefined,
      method:    filterMethod       || undefined,
      framework: debouncedFramework || undefined,
      repo:      debouncedRepo      || undefined,
    })
      .then(setSearchResults)
      .catch(e => setError(e instanceof Error ? e.message : 'Search failed'))
      .finally(() => setSearching(false))
  }, [debouncedPath, filterMethod, debouncedFramework, debouncedRepo, isFiltering])

  const clearFilters = () => {
    setFilterPath(''); setFilterMethod(''); setFilterFramework(''); setFilterRepo('')
    setSearchResults(null)
  }

  const selectRepo = useCallback(async (id: number) => {
    if (selected?.id === id) { setSelected(null); return }
    setDetailLoading(true)
    try {
      setSelected(await getRepository(id))
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load repository')
    } finally {
      setDetailLoading(false)
    }
  }, [selected])

  return (
    <div className="page">
      <h1>API Catalog</h1>
      <p className="subtitle">Browse saved repositories and search across all extracted API endpoints.</p>

      {/* ── Filter bar ─────────────────────────────── */}
      <div className="card search-bar">
        <div className="search-grid">
          <div className="form-group-sm">
            <label>Endpoint path</label>
            <input className="input" placeholder="/users, /api, …" value={filterPath}
              onChange={e => setFilterPath(e.target.value)} />
          </div>
          <div className="form-group-sm">
            <label>Method</label>
            <select className="input" value={filterMethod} onChange={e => setFilterMethod(e.target.value)}>
              {METHODS.map(m => <option key={m} value={m}>{m || 'All'}</option>)}
            </select>
          </div>
          <div className="form-group-sm">
            <label>Framework</label>
            <select className="input" value={filterFramework} onChange={e => setFilterFramework(e.target.value)}>
              <option value="">All</option>
              {frameworks.map(f => <option key={f} value={f}>{f}</option>)}
            </select>
          </div>
          <div className="form-group-sm">
            <label>Repository</label>
            <input className="input" placeholder="repo name…" value={filterRepo}
              onChange={e => setFilterRepo(e.target.value)} />
          </div>
        </div>
        {isFiltering && (
          <button className="btn-xs btn-gray clear-btn" onClick={clearFilters}>Clear filters ✕</button>
        )}
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* ── Search results ──────────────────────────── */}
      {isFiltering ? (
        <div className="card">
          <div className="table-header">
            <h2>{searching ? 'Searching…' : `${searchResults?.length ?? 0} result${searchResults?.length !== 1 ? 's' : ''}`}</h2>
          </div>
          {!searching && searchResults && searchResults.length > 0 && (
            <table className="api-table">
              <thead>
                <tr>
                  <th>Method</th><th>Path</th><th>Repository</th>
                  <th>Framework</th><th>Controller</th><th>Handler</th>
                </tr>
              </thead>
              <tbody>
                {searchResults.map(r => (
                  <tr key={r.endpointId}>
                    <td>
                      <span className="method-badge" style={{ background: METHOD_COLORS[r.method] ?? '#64748b' }}>
                        {r.method}
                      </span>
                    </td>
                    <td className="mono">{r.path}</td>
                    <td>
                      <span className="repo-link" onClick={() => { clearFilters(); selectRepo(r.repositoryId) }}>
                        {r.repositoryName}
                      </span>
                    </td>
                    <td><span className="badge badge-green">{r.framework}</span></td>
                    <td>{r.controller ?? '—'}</td>
                    <td>{r.handler ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {!searching && searchResults?.length === 0 && (
            <p className="empty-text">No endpoints match the current filters.</p>
          )}
        </div>
      ) : (
        /* ── Repo cards ──────────────────────────────── */
        <>
          {loadingRepos && <div className="alert alert-info">Loading repositories…</div>}

          {!loadingRepos && repos.length === 0 && (
            <div className="card placeholder">
              <p>No repositories saved yet. <a href="/review">Submit one →</a></p>
            </div>
          )}

          {repos.map(repo => (
            <div key={repo.id} className="repo-card">
              <div className="repo-card-header" onClick={() => selectRepo(repo.id)}>
                <div className="repo-card-title">
                  <span className="repo-name">{repo.name}</span>
                  <span className="badge badge-green">{repo.framework}</span>
                </div>
                <div className="repo-card-meta">
                  <a href={repo.url} target="_blank" rel="noopener noreferrer"
                    onClick={e => e.stopPropagation()} className="url-link">{repo.url}</a>
                  {repo.hostUrl && (
                    <span className="host-pill" onClick={e => e.stopPropagation()}>
                      API Base URL: <a href={repo.hostUrl} target="_blank" rel="noopener noreferrer">{repo.hostUrl}</a>
                    </span>
                  )}
                  <span className="meta-pill">{repo.endpointCount} endpoint{repo.endpointCount !== 1 ? 's' : ''}</span>
                  <span className="meta-pill">{new Date(repo.createdAt).toLocaleDateString()}</span>
                </div>
              </div>

              {selected?.id === repo.id && (
                <div className="repo-endpoints">
                  {detailLoading ? (
                    <p className="loading-text">Loading endpoints…</p>
                  ) : selected.endpoints.length === 0 ? (
                    <p className="empty-text">No endpoints recorded.</p>
                  ) : (
                    <table className="api-table">
                      <thead>
                        <tr><th></th><th>Method</th><th>Path</th><th>Controller</th><th>Handler</th><th>Description</th></tr>
                      </thead>
                      <tbody>
                        {selected.endpoints.map(ep => (
                          <>
                            <tr key={ep.id} className="endpoint-row" onClick={() => toggleEp(ep.id)} style={{ cursor: 'pointer' }}>
                              <td style={{ width: '1.5rem', color: '#94a3b8', fontSize: '0.75rem' }}>
                                {openDetails.has(ep.id) ? '▾' : '▸'}
                              </td>
                              <td>
                                <span className="method-badge" style={{ background: METHOD_COLORS[ep.method] ?? '#64748b' }}>
                                  {ep.method}
                                </span>
                              </td>
                              <td className="mono">{ep.path}</td>
                              <td>{ep.controller ?? '—'}</td>
                              <td>{ep.handler ?? '—'}</td>
                              <td>{ep.description ?? '—'}</td>
                            </tr>
                            {openDetails.has(ep.id) && (
                              <tr key={`${ep.id}-d`} className="details-row">
                                <td colSpan={6}>
                                  <EndpointDetails
                                    parameters={ep.parameters}
                                    requestBodyType={ep.requestBodyType}
                                    requestBodyFields={ep.requestBodyFields}
                                    responseBodyType={ep.responseBodyType}
                                    responseBodyFields={ep.responseBodyFields}
                                    statusCodes={ep.statusCodes}
                                    tags={ep.tags}
                                    sourceFile={ep.sourceFile}
                                    sourceLine={ep.sourceLine}
                                  />
                                </td>
                              </tr>
                            )}
                          </>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              )}
            </div>
          ))}
        </>
      )}
    </div>
  )
}