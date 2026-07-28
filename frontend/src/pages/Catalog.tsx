import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { getRepositories, deleteRepository, rescanRepository } from '../api/client'
import type { RepositorySummary, SubmitResponse } from '../api/client'
import AddRepoDialog from '../components/AddRepoDialog'

function repoStatus(r: RepositorySummary): { label: string; color: 'green' | 'yellow' | 'gray' } {
  if (r.endpointCount === 0) return { label: 'Empty', color: 'gray' }
  return { label: 'Published', color: 'green' }
}

export default function Catalog() {
  const navigate = useNavigate()
  const [repos, setRepos] = useState<RepositorySummary[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [showAdd, setShowAdd] = useState(false)
  const [actionLoading, setAL] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getRepositories()
      .then(setRepos)
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  const filtered = search.trim()
    ? repos.filter(r =>
        r.name.toLowerCase().includes(search.toLowerCase()) ||
        r.url.toLowerCase().includes(search.toLowerCase()) ||
        (r.framework ?? '').toLowerCase().includes(search.toLowerCase())
      )
    : repos

  const handleDelete = async (r: RepositorySummary, e: React.MouseEvent) => {
    e.stopPropagation()
    if (!window.confirm(`Delete "${r.name}" and all its endpoints?`)) return
    setAL(r.id)
    try { await deleteRepository(r.id); setRepos(prev => prev.filter(x => x.id !== r.id)) }
    catch { setError('Delete failed') }
    finally { setAL(null) }
  }

  const handleRescan = async (r: RepositorySummary, e: React.MouseEvent) => {
    e.stopPropagation()
    setAL(r.id)
    try { await rescanRepository(r.id); setRepos(await getRepositories()) }
    catch { setError('Rescan failed') }
    finally { setAL(null) }
  }

  const onSubmitted = (result: SubmitResponse) => {
    setShowAdd(false)
    navigate('/review', { state: { result } })
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>API Catalog</h1>
        <button className="btn btn-primary" onClick={() => setShowAdd(true)}>Add repository</button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {!loading && (
        <input
          className="search-input"
          placeholder="Search repositories, frameworks…"
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
      )}

      {loading && <div className="alert alert-info">Loading repositories…</div>}

      {!loading && filtered.length === 0 && repos.length === 0 && (
        <div className="card placeholder">
          <p>No repositories yet. Paste a GitHub URL to extract and document its APIs.</p>
          <button className="btn btn-primary" style={{ marginTop: '1rem' }} onClick={() => setShowAdd(true)}>
            Add repository
          </button>
        </div>
      )}

      {!loading && filtered.length === 0 && repos.length > 0 && (
        <div className="card placeholder">
          <p>No repositories match &ldquo;{search}&rdquo;.</p>
        </div>
      )}

      {!loading && filtered.length > 0 && (
        <div className="repo-grid">
          {filtered.map(r => {
            const status = repoStatus(r)
            return (
              <div key={r.id} className="repo-grid-card" onClick={() => navigate(`/repositories/${r.id}`)}>
                <div className="rgc-header">
                  <span className="rgc-name">{r.name}</span>
                  <span className={`chip chip-${status.color}`}>{status.label}</span>
                </div>
                <div className="rgc-url">{r.url}</div>
                <div className="rgc-meta">
                  <span className="meta-pill">{r.framework}</span>
                  <span className="meta-pill">{r.endpointCount} endpoint{r.endpointCount !== 1 ? 's' : ''}</span>
                  <span className="meta-pill">{new Date(r.createdAt).toLocaleDateString()}</span>
                </div>
                <div className="rgc-actions" onClick={e => e.stopPropagation()}>
                  <button className="btn-xs btn-gray" disabled={actionLoading === r.id} onClick={e => handleRescan(r, e)}>
                    {actionLoading === r.id ? '…' : '↺ Rescan'}
                  </button>
                  <button className="btn-xs btn-red" disabled={actionLoading === r.id} onClick={e => handleDelete(r, e)}>
                    Delete
                  </button>
                </div>
              </div>
            )
          })}
        </div>
      )}

      {showAdd && <AddRepoDialog onClose={() => setShowAdd(false)} onSubmitted={onSubmitted} />}
    </div>
  )
}

