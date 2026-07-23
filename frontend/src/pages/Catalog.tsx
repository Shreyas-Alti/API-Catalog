import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getRepositories, searchEndpoints, deleteRepository, rescanRepository } from '../api/client'
import type { RepositorySummary, SearchResultItem } from '../api/client'

const METHODS = ['', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH']
const METHOD_COLORS: Record<string, string> = {
  GET: '#22c55e', POST: '#2563eb', PUT: '#f59e0b', DELETE: '#ef4444', PATCH: '#8b5cf6',
}

function useDebounce<T>(value: T, delay: number): T {
  const [deb, setDeb] = useState(value)
  useEffect(() => { const t = setTimeout(() => setDeb(value), delay); return () => clearTimeout(t) }, [value, delay])
  return deb
}

export default function Catalog() {
  const navigate = useNavigate()

  // Repos (for card grid)
  const [repos, setRepos]       = useState<RepositorySummary[]>([])
  const [loadingRepos, setLR]   = useState(true)
  const [actionLoading, setAL]  = useState<number | null>(null)

  // Search
  const [fp, setFp] = useState('')
  const [fm, setFm] = useState('')
  const [ff, setFf] = useState('')
  const [fr, setFr] = useState('')
  const [searchResults, setSR]    = useState<SearchResultItem[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [error, setError]         = useState<string | null>(null)

  const dbPath = useDebounce(fp, 350)
  const dbFw   = useDebounce(ff, 350)
  const dbRepo = useDebounce(fr, 350)
  const isFiltering = !!(dbPath || fm || dbFw || dbRepo)

  // Load repos
  useEffect(() => {
    getRepositories()
      .then(setRepos)
      .catch(e => setError(e.message))
      .finally(() => setLR(false))
  }, [])

  // Search
  useEffect(() => {
    if (!isFiltering) { setSR(null); return }
    setSearching(true)
    searchEndpoints({
      path: dbPath || undefined,
      method: fm || undefined,
      framework: dbFw || undefined,
      repo: dbRepo || undefined,
    })
      .then(setSR).catch(e => setError(e.message)).finally(() => setSearching(false))
  }, [dbPath, fm, dbFw, dbRepo, isFiltering])

  const clearFilters = () => { setFp(''); setFm(''); setFf(''); setFr('') }

  const frameworks = Array.from(new Set(repos.map(r => r.framework).filter(Boolean))).sort()

  const handleDelete = async (repo: RepositorySummary, e: React.MouseEvent) => {
    e.stopPropagation()
    if (!window.confirm(`Delete "${repo.name}" and all its endpoints?`)) return
    setAL(repo.id)
    try {
      await deleteRepository(repo.id)
      setRepos(prev => prev.filter(r => r.id !== repo.id))
    } catch { setError('Delete failed') }
    finally { setAL(null) }
  }

  const handleRescan = async (repo: RepositorySummary, e: React.MouseEvent) => {
    e.stopPropagation()
    setAL(repo.id)
    try {
      await rescanRepository(repo.id)
      const updated = await getRepositories()
      setRepos(updated)
    } catch { setError('Re-scan failed') }
    finally { setAL(null) }
  }

  return (
    <div className="page">
      <h1>API Catalog</h1>
      <p className="subtitle">Browse repositories or search across all endpoints.</p>

      {/* Search filter bar */}
      <div className="card search-bar">
        <div className="search-grid">
          <div className="form-group-sm">
            <label>Endpoint path</label>
            <input className="input" placeholder="/users, /api, …" value={fp} onChange={e => setFp(e.target.value)} />
          </div>
          <div className="form-group-sm">
            <label>Method</label>
            <select className="input" value={fm} onChange={e => setFm(e.target.value)}>
              {METHODS.map(m => <option key={m} value={m}>{m || 'All'}</option>)}
            </select>
          </div>
          <div className="form-group-sm">
            <label>Framework</label>
            <select className="input" value={ff} onChange={e => setFf(e.target.value)}>
              <option value="">All</option>
              {frameworks.map(f => <option key={f} value={f}>{f}</option>)}
            </select>
          </div>
          <div className="form-group-sm">
            <label>Repository</label>
            <input className="input" placeholder="repo name…" value={fr} onChange={e => setFr(e.target.value)} />
          </div>
        </div>
        {isFiltering && <button className="btn-xs btn-gray clear-btn" onClick={clearFilters}>Clear ✕</button>}
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* ── Search results ─────────────────────────────────────────── */}
      {isFiltering && (
        <div className="card" style={{ marginTop: '1rem' }}>
          {searching ? (
            <p className="loading-text">Searching…</p>
          ) : searchResults && searchResults.length === 0 ? (
            <p className="empty-text">No endpoints match the current filters.</p>
          ) : searchResults ? (
            <table className="api-table">
              <thead>
                <tr><th>Method</th><th>Path / Summary</th><th>Repository</th></tr>
              </thead>
              <tbody>
                {searchResults.map(sr => (
                  <tr key={sr.endpointId} style={{ cursor: 'pointer' }}
                    onClick={() => navigate(`/viewer/${sr.repositoryId}`)}>
                    <td>
                      <span className="method-badge" style={{ background: METHOD_COLORS[sr.method] ?? '#64748b' }}>
                        {sr.method}
                      </span>
                    </td>
                    <td>
                      <span className="mono">{sr.path}</span>
                      {sr.description && <div className="endpoint-label">{sr.description}</div>}
                    </td>
                    <td>
                      <span className="repo-name-inline">{sr.repositoryName}</span>
                      <span className="badge badge-green" style={{ marginLeft: '0.4rem', fontSize: '0.68rem' }}>{sr.framework}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </div>
      )}

      {/* ── Repository cards ───────────────────────────────────────── */}
      {!isFiltering && (
        <>
          {loadingRepos ? (
            <div className="alert alert-info">Loading repositories…</div>
          ) : repos.length === 0 ? (
            <div className="card placeholder">
              <p>No repositories saved yet. <a href="/review">Submit one →</a></p>
            </div>
          ) : (
            <div className="repos-grid">
              {repos.map((repo, idx) => (
                <div key={repo.id} className="repo-card">
                  <div className="repo-card-header">
                    <span className="repo-card-name">{repo.name}</span>
                    {idx === 0 && <span className="badge-latest">Latest</span>}
                  </div>
                  <div className="repo-card-meta">
                    <span className="badge badge-green">{repo.framework}</span>
                    <span className="meta-pill">{repo.endpointCount} endpoint{repo.endpointCount !== 1 ? 's' : ''}</span>
                    <span className="meta-pill">{new Date(repo.createdAt).toLocaleDateString()}</span>
                  </div>
                  {repo.hostUrl && (
                    <div className="host-url-row" onClick={e => e.stopPropagation()}>
                      <span className="host-label">API Base:</span>
                      <a href={repo.hostUrl} target="_blank" rel="noopener noreferrer" className="url-link">{repo.hostUrl}</a>
                    </div>
                  )}
                  <div className="repo-card-actions">
                    <button className="btn btn-primary" onClick={() => navigate(`/viewer/${repo.id}`)}>
                      📖 View Documentation
                    </button>
                    <button className="btn-xs btn-gray" disabled={actionLoading === repo.id}
                      onClick={e => handleRescan(repo, e)} title="Re-scan">
                      {actionLoading === repo.id ? '…' : '↺ Re-scan'}
                    </button>
                    <button className="btn-xs btn-red" disabled={actionLoading === repo.id}
                      onClick={e => handleDelete(repo, e)} title="Delete">
                      🗑
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}
